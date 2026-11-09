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
 * 短信库（content://sms）变更监听：无广播补漏
 *
 * 系统"智能信息"等通道写入短信库的消息可能不触发 SMS_RECEIVED 广播，
 * 本监听捕获新入库短信，经 [SmsUtils.dispatch] 统一去重后补发，与广播通道不重不漏。
 * 长短信按（发件人+时间）归并分段后再补发。
 *
 * 水位线只前瞻、永不回扫（杜绝首装后历史短信全量转发）：
 * - 未初始化（0）：首次扫描吞掉历史存量，仅保护最近窗口内新入库的短信（即触发本次扫描的新短信）；
 * - 已初始化（> 0）：只处理水位线之后的新行；
 * - 总开关关闭期间入库的短信吞掉不补发（开关只影响此后新短信）。
 *
 * @since 2026年11月3日
 */

//初始化保护窗口：首次扫描只保护最近 N 毫秒内入库的行（短信库 date 为短信中心时间戳，容忍 60 秒偏差），
//既保证触发首次扫描的新短信不被吞掉，又杜绝历史短信回扫
internal const val SMS_DB_INIT_PROTECT_WINDOW_MS = 60_000L

//扫描决策结果：水位线新值、需查询的行范围、是否分发
internal data class SmsDbScanPlan(val newWatermark: Long, val queryAfterId: Long, val queryMinDate: Long, val dispatch: Boolean)

//水位线扫描决策（纯逻辑，便于单元测试）：未初始化只前瞻并保护最近入库行；开关关闭吞存量；正常只扫新行
internal fun planSmsDbScan(lastId: Long, maxId: Long, enableSms: Boolean, now: Long): SmsDbScanPlan = when {
    !enableSms -> SmsDbScanPlan(maxId, 0L, 0L, false)
    lastId <= 0L -> SmsDbScanPlan(maxId, 0L, now - SMS_DB_INIT_PROTECT_WINDOW_MS, true)
    else -> SmsDbScanPlan(maxId, lastId, 0L, true)
}

class SmsDbObserver(private val context: Context) : ContentObserver(handler) {

    companion object {
        private val TAG = SmsDbObserver::class.java.simpleName

        private val handlerThread = HandlerThread("sms-db-observer").apply { start() }
        private val handler = Handler(handlerThread.looper)

        //水位线：0 = 未初始化；> 0 = 只处理其后新入库的短信（持久化，进程重启后继续生效，绝不回写 0）
        private const val WATERMARK_KEY = "sms_db_observer_last_id"

        //长短信分段连续入库会触发多次变更，延迟合并统一处理
        private const val DEBOUNCE_MS = 2000L

        private val SMS_URI = Uri.parse("content://sms/")
    }

    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long, val subId: Int)

    private val scanRunnable = Runnable { scan() }

    fun register() {
        context.contentResolver.registerContentObserver(SMS_URI, true, this)
        Log.i(TAG, "短信库监听已注册（水位线在首次扫描时初始化，只前瞻不回扫）")
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        //熄屏 Doze 下 CPU 休眠会把防抖与扫描推迟到维护窗口，持短唤醒锁保证补漏链路及时执行
        //（只需覆盖 2 秒防抖 + 毫秒级扫描；随后的转发由 SendUtils.sendMsg 持自己的唤醒锁。
        //发出短信写库同样会触发 onChange，锁时长须克制，避免每次发短信让 CPU 多醒一分钟）
        ForwardWakeLock.acquire("sms_db_scan", 15_000L)
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, DEBOUNCE_MS)
    }

    private fun scan() {
        try {
            var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)

            val maxId = queryMaxId()
            if (maxId <= 0L) {
                Log.w(TAG, "短信库不可读（READ_SMS 权限未授予？），保持水位线 lastId = $lastId，等待下次扫描重试")
                return
            }

            val plan = planSmsDbScan(lastId, maxId, SettingUtils.enableSms, System.currentTimeMillis())
            if (!plan.dispatch) {
                lastId = plan.newWatermark
                Log.i(TAG, "转发开关关闭：吞掉期间新入库短信不补发，水位线推进到 lastId = $lastId")
                return
            }
            if (plan.queryMinDate > 0L) {
                Log.i(TAG, "水位线首次初始化：吞掉历史存量不补发，仅处理最近 ${SMS_DB_INIT_PROTECT_WINDOW_MS / 1000} 秒内入库的短信")
            }

            val rows = queryNewRows(plan.queryAfterId, plan.queryMinDate)
            dispatchRows(rows)
            lastId = if (rows.isEmpty()) plan.newWatermark else maxOf(plan.newWatermark, rows.maxOf { it.id })
            Log.i(TAG, "短信库补漏扫描完成：补发 ${rows.size} 条，水位线 lastId = $lastId")
        } catch (e: Exception) {
            Log.e(TAG, "SmsDbObserver scan failed: ${e.message}")
        }
    }

    //长短信分段按（发件人+时间）归并后统一去重分发
    private fun dispatchRows(rows: List<SmsRow>) {
        val grouped = rows.groupBy { "${it.address}|${it.date}" }
        for (group in grouped.values) {
            val first = group.first()
            val body = group.sortedBy { it.id }.joinToString("") { it.body }
            val msgInfo = MsgInfo("sms", first.address.trim(), body, Date(first.date), "", -1, first.subId)
            Log.d(TAG, "短信库补漏: msgInfo = $msgInfo")
            SmsUtils.dispatch(context, msgInfo, SmsUtils.CHANNEL_DB)
        }
    }

    private fun queryNewRows(afterId: Long, minDate: Long = 0L): List<SmsRow> {
        val rows = mutableListOf<SmsRow>()
        try {
            val selection = if (minDate > 0L) "_id > ? AND type = 1 AND date >= ?" else "_id > ? AND type = 1"
            val selectionArgs = if (minDate > 0L) arrayOf(afterId.toString(), minDate.toString()) else arrayOf(afterId.toString())
            val cursor = context.contentResolver.query(
                SMS_URI, arrayOf("_id", "address", "body", "date", "sub_id"),
                selection, selectionArgs, "_id asc"
            ) ?: return rows
            cursor.use {
                val idxId = it.getColumnIndex("_id")
                val idxAddress = it.getColumnIndex("address")
                val idxBody = it.getColumnIndex("body")
                val idxDate = it.getColumnIndex("date")
                val idxSubId = it.getColumnIndex("sub_id")
                while (it.moveToNext()) {
                    rows.add(
                        SmsRow(
                            it.getLong(idxId),
                            it.getString(idxAddress) ?: "",
                            it.getString(idxBody) ?: "",
                            it.getLong(idxDate),
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
        try {
            val cursor = context.contentResolver.query(
                SMS_URI, arrayOf("_id"), null, null, "_id desc"
            ) ?: return 0L
            cursor.use {
                return if (it.moveToFirst()) it.getLong(0) else 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryMaxId failed: ${e.message}")
            return 0L
        }
    }

}
