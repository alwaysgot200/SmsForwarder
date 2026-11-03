package cn.ppps.forwarder.receiver

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.HistoryUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SmsUtils
import java.util.Date

/**
 * 短信库（content://sms）变更监听：无广播补漏
 *
 * 系统"智能信息"等通道写入短信库的消息可能不触发 SMS_RECEIVED 广播，
 * 本监听捕获新入库短信，经 [SmsUtils.dispatch] 双通道去重后补发，与广播通道不重不漏。
 * 长短信按（发件人+时间）归并分段后再补发。
 *
 * @since 2026年11月3日
 */
class SmsDbObserver(private val context: Context) : ContentObserver(handler) {

    companion object {
        private val TAG = SmsDbObserver::class.java.simpleName

        private val handlerThread = HandlerThread("sms-db-observer").apply { start() }
        private val handler = Handler(handlerThread.looper)

        //水位线：仅处理首次注册之后新入库的短信（持久化，进程重启后继续生效）
        private const val WATERMARK_KEY = "sms_db_observer_last_id"

        //长短信分段连续入库会触发多次变更，延迟合并统一处理
        private const val DEBOUNCE_MS = 2000L

        private val SMS_URI = Uri.parse("content://sms/")
    }

    private data class SmsRow(val id: Long, val address: String, val body: String, val date: Long, val subId: Int)

    private val scanRunnable = Runnable { scan() }

    fun register() {
        var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)
        if (lastId <= 0L) {
            lastId = queryMaxId()
        }
        context.contentResolver.registerContentObserver(SMS_URI, true, this)
        Log.i(TAG, "短信库监听已注册，水位线 lastId = $lastId")
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, DEBOUNCE_MS)
    }

    private fun scan() {
        try {
            if (!SettingUtils.enableSms) return

            var lastId: Long by HistoryUtils(WATERMARK_KEY, 0L)
            val rows = queryNewRows(lastId)
            if (rows.isEmpty()) return

            //长短信分段按（发件人+时间）归并
            val grouped = rows.groupBy { "${it.address}|${it.date}" }
            for (group in grouped.values) {
                val first = group.first()
                val body = group.sortedBy { it.id }.joinToString("") { it.body }
                val msgInfo = MsgInfo("sms", first.address.trim(), body, Date(first.date), "", -1, first.subId)
                Log.d(TAG, "短信库补漏: msgInfo = $msgInfo")
                SmsUtils.dispatch(context, msgInfo, SmsUtils.CHANNEL_DB)
            }

            lastId = rows.maxOf { it.id }
            Log.i(TAG, "短信库补漏扫描完成：补发 ${grouped.size} 条，水位线 lastId = $lastId")
        } catch (e: Exception) {
            Log.e(TAG, "SmsDbObserver scan failed: ${e.message}")
        }
    }

    private fun queryNewRows(afterId: Long): List<SmsRow> {
        val rows = mutableListOf<SmsRow>()
        try {
            val cursor = context.contentResolver.query(
                SMS_URI, arrayOf("_id", "address", "body", "date", "sub_id"),
                "_id > ? AND type = 1", arrayOf(afterId.toString()), "_id asc"
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
                SMS_URI, arrayOf("_id"), null, null, "_id desc limit 1"
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
