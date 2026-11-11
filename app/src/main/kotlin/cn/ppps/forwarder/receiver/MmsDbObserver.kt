package cn.ppps.forwarder.receiver

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.ForwardWakeLock
import cn.ppps.forwarder.utils.HistoryUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SmsUtils
import java.util.Date

/**
 * 彩信库（content://mms）变更监听：转发彩信的文字部分（用户需求：只要文字，不要图片/多媒体）
 *
 * 彩信到达分两步：先入库通知行（无 parts），系统信息应用再异步下载内容并写入 parts 表。
 * 扫描时按下载状态分流：
 * - 已有 parts：提取文本类（text/ 开头）部件的正文 + 主题（主题列需 ISO-8859-1→UTF-8 转码恢复）→
 *   构造 type=sms 的 [MsgInfo] 经 [SmsUtils.dispatch] 统一去重分发（复用短信规则与全部通道）；
 * - 无 parts 且行龄 < 10 分钟：视为下载中，水位线不推进、该行记入已处理表防重复，5 秒后自动重扫；
 * - 无 parts 且行龄超时：下载失败，跳过；
 * - 有 parts 但无文本类部件：纯图片/多媒体彩信，按用户需求忽略。
 * 水位线语义与短信库监听一致（[planSmsDbScan]）：只前瞻不回扫，首装吞存量、只保护最近 60 秒内入库行。
 *
 * 历史遗留的 WAP-Push 广播解析（SmsReceiver.handleMmsData，反射调用已废弃的 GSM SmsMessage
 * 解析 MMS PDU）实际不工作，已移除；本监听基于彩信库，与谁下载内容无关，是可靠路径。
 *
 * @since 2026年11月10日
 */
class MmsDbObserver(private val context: Context) : ContentObserver(handler) {

    companion object {
        private val TAG = MmsDbObserver::class.java.simpleName

        private val handlerThread = HandlerThread("mms-db-observer").apply { start() }
        private val handler = Handler(handlerThread.looper)

        //水位线：已处理的最高彩信 _id（持久化，进程重启后继续生效）；挂起的下载行会挡住水位线
        private const val WATERMARK_KEY = "mms_db_observer_last_id"

        //已处理彩信 id 滚动记录：水位线之上的行防重复分发（下载中行挡住水位线期间多次扫描）
        private const val PROCESSED_KEY = "mms_db_processed_ids"
        private const val PROCESSED_LIMIT = 100

        //彩信入库行先于内容下载；下载中的行最多等待 10 分钟，超时按失败跳过
        private const val DEBOUNCE_MS = 2500L
        private const val RESCAN_DELAY_MS = 5000L
        private const val DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L

        //彩信正文上限：保护转发通道（MMS 理论上限约 1MB 文本）
        private const val CONTENT_MAX_CHARS = 10_000

        private val MMS_URI = Uri.parse("content://mms")
        private val PART_URI = Uri.parse("content://mms/part")
    }

    private data class MmsRow(val id: Long, val dateSec: Long, val subject: String, val subId: Int)
    private data class MmsContent(val text: String, val hasParts: Boolean)

    private val scanRunnable = Runnable { scan() }

    fun register() {
        context.contentResolver.registerContentObserver(MMS_URI, true, this)
        Log.i(TAG, "彩信库监听已注册（只转发文字部分；水位线首扫初始化，只前瞻不回扫）")
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        //入库与下载是两次独立写库，防抖合并；持短唤醒锁保证熄屏链路及时执行
        ForwardWakeLock.acquire("mms_db_scan", 15_000L)
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, DEBOUNCE_MS)
    }

    private fun scan() {
        try {
            var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)

            val maxId = queryMaxId()
            if (maxId <= 0L) {
                Log.w(TAG, "彩信库不可读（READ_SMS 权限未授予？），保持水位线 lastId = $lastId，等待下次扫描重试")
                return
            }

            val plan = planSmsDbScan(lastId, maxId, SettingUtils.enableSms, System.currentTimeMillis())
            if (!plan.dispatch) {
                lastId = plan.newWatermark
                Log.i(TAG, "转发开关关闭：吞掉期间新入库彩信不补发，水位线推进到 lastId = $lastId")
                return
            }

            //彩信库 date 为秒级时间戳
            val minDateSec = if (plan.queryMinDate > 0L) plan.queryMinDate / 1000L else 0L
            val rows = queryNewRows(plan.queryAfterId, minDateSec)
            if (rows.isEmpty()) {
                lastId = plan.newWatermark
                Log.i(TAG, "彩信补漏扫描完成：无新增，水位线推进到 lastId = $lastId")
                return
            }

            val processed = loadProcessed()
            var handledMax = lastId
            //下载中行的最小 _id：水位线必须停在它之下，否则后续行处理后水位线越过它会永久漏转发
            var pendingMinId = Long.MAX_VALUE
            var dispatched = 0

            for (row in rows) {
                if (row.id !in processed) {
                    val content = extractText(row.id)

                    if (!content.hasParts) {
                        if (System.currentTimeMillis() - row.dateSec * 1000L < DOWNLOAD_TIMEOUT_MS) {
                            //通知行已入库但内容尚未下载：挡住水位线，稍后自动重扫
                            pendingMinId = minOf(pendingMinId, row.id)
                            continue
                        }
                        Log.i(TAG, "彩信 ${row.id} 超时未下载，跳过")
                    } else if (content.text.isBlank()) {
                        Log.i(TAG, "彩信 ${row.id} 无文字部分（纯图片/多媒体），按需忽略")
                    } else {
                        val sender = querySender(row.id)
                        val msgInfo = MsgInfo("sms", sender, content.text.take(CONTENT_MAX_CHARS), Date(row.dateSec * 1000L), "", -1, row.subId)
                        Log.d(TAG, "彩信库补漏: msgInfo = $msgInfo")
                        SmsUtils.dispatch(context, msgInfo, SmsUtils.CHANNEL_DB)
                        dispatched++
                    }

                    processed += row.id
                }
                //已处理行（含此前已入库的）同样计入水位线推进量，最终水位线再按下载中行钳制
                handledMax = maxOf(handledMax, row.id)
            }

            val pending = pendingMinId != Long.MAX_VALUE
            lastId = if (pending) minOf(handledMax, pendingMinId - 1) else handledMax
            saveProcessed(processed)
            Log.i(TAG, "彩信补漏扫描完成：转发 $dispatched 条，水位线 lastId = $lastId" + (if (pending) "，存在下载中的彩信待重扫" else ""))

            if (pending) {
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, RESCAN_DELAY_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MmsDbObserver scan failed: ${e.message}")
        }
    }

    /**
     * 提取彩信的文字内容（主题 + 文本类部件的正文），已下载判定依据：parts 表是否已有该彩信的部件
     */
    private fun extractText(id: Long): MmsContent {
        var hasParts = false
        val textParts = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(
                PART_URI, arrayOf("ct", "text"), "mid = ?", arrayOf(id.toString()), "_id asc"
            ) ?: return MmsContent("", false)
            cursor.use {
                val idxCt = it.getColumnIndex("ct")
                val idxText = it.getColumnIndex("text")
                while (it.moveToNext()) {
                    val ct = it.getString(idxCt) ?: continue
                    hasParts = true
                    if (!ct.startsWith("text/")) continue
                    val text = it.getString(idxText) ?: continue
                    if (text.isNotBlank()) textParts.add(text)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractText($id) failed: ${e.message}")
        }
        val subject = querySubject(id)
        val body = textParts.joinToString("\n")
        val composed = listOf(subject, body).filter { it.isNotBlank() }.joinToString("\n")
        return MmsContent(composed, hasParts)
    }

    //主题列在部分设备上按 ISO-8859-1 错读 UTF-8（如"æ±è…"），检测到拉丁扩展字符时转码恢复
    private fun querySubject(id: Long): String {
        val raw = queryMmsColumn(id, "sub")
        if (raw.isBlank()) return ""
        if (raw.none { it.code in 0xC0..0xFF }) return raw
        return try {
            String(raw.toByteArray(charset("ISO-8859-1")), charset("UTF-8"))
        } catch (e: Exception) {
            raw
        }
    }

    private fun queryMmsColumn(id: Long, column: String): String {
        return try {
            val cursor = context.contentResolver.query(MMS_URI, arrayOf(column), "_id = ?", arrayOf(id.toString()), null) ?: return ""
            cursor.use {
                if (it.moveToFirst()) it.getString(0) ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryMmsColumn($id, $column) failed: ${e.message}")
            ""
        }
    }

    //发件人地址：addr 表 msg_type=137 为 FROM
    private fun querySender(id: Long): String {
        return try {
            val cursor = context.contentResolver.query(
                Uri.parse("$MMS_URI/$id/addr"), arrayOf("address"), "msg_type = 137", null, null
            ) ?: return ""
            cursor.use {
                if (it.moveToFirst()) it.getString(0) ?: "" else ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "querySender($id) failed: ${e.message}")
            ""
        }
    }

    private fun queryNewRows(afterId: Long, minDateSec: Long): List<MmsRow> {
        val rows = mutableListOf<MmsRow>()
        try {
            val selection = if (minDateSec > 0L) "_id > ? AND msg_box = 1 AND m_type = 132 AND date >= ?" else "_id > ? AND msg_box = 1 AND m_type = 132"
            val selectionArgs = if (minDateSec > 0L) arrayOf(afterId.toString(), minDateSec.toString()) else arrayOf(afterId.toString())
            val cursor = context.contentResolver.query(
                MMS_URI, arrayOf("_id", "date", "sub", "sub_id"),
                selection, selectionArgs, "_id asc"
            ) ?: return rows
            cursor.use {
                val idxId = it.getColumnIndex("_id")
                val idxDate = it.getColumnIndex("date")
                val idxSub = it.getColumnIndex("sub")
                val idxSubId = it.getColumnIndex("sub_id")
                while (it.moveToNext()) {
                    rows.add(
                        MmsRow(
                            it.getLong(idxId),
                            it.getLong(idxDate),
                            it.getString(idxSub) ?: "",
                            if (idxSubId >= 0) it.getInt(idxSubId) else 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryNewRows failed: ${e.message}")
        }
        return rows
    }

    private fun queryMaxId(): Long {
        return try {
            val cursor = context.contentResolver.query(MMS_URI, arrayOf("_id"), null, null, "_id desc") ?: return 0L
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryMaxId failed: ${e.message}")
            0L
        }
    }

    //已处理 id 滚动记录：按水位线修剪 + 容量上限
    private fun loadProcessed(): MutableSet<Long> {
        var raw: String by HistoryUtils(PROCESSED_KEY, "")
        val set = raw.split(",").mapNotNull { it.toLongOrNull() }.toMutableSet()
        var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)
        set.removeAll { it <= lastId }
        return set
    }

    private fun saveProcessed(processed: MutableSet<Long>) {
        var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)
        processed.removeAll { it <= lastId }
        var raw: String by HistoryUtils(PROCESSED_KEY, "")
        raw = processed.sorted().takeLast(PROCESSED_LIMIT).joinToString(",")
    }

}
