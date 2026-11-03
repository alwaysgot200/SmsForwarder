package cn.ppps.forwarder.utils

import android.content.Context
import android.net.Uri
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.gson.Gson
import com.xuexiang.xutil.security.CipherUtils
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.workers.SendWorker

/**
 * 短信数据源统一处理：发件人解析（取号回退链）、短信库回查、双通道（广播/短信库）互斥分发
 *
 * 背景：系统"智能信息"开启后，部分短信的发件人号码被替换为名称（如"丰巢"）或为空，
 * 且部分消息不触发 SMS_RECEIVED 广播、由系统直接写入短信库，导致漏转发。
 *
 * @since 2026年11月3日
 */
object SmsUtils {

    private val TAG = SmsUtils::class.java.simpleName

    //双通道分发标记：广播通道 / 短信库监听通道
    const val CHANNEL_BROADCAST = "broadcast"
    const val CHANNEL_DB = "db"

    //跨通道去重窗口：同一物理消息经两通道各到达一次，窗口内只处理一次
    private const val CROSS_CHANNEL_DEDUP_MS = 60_000L

    //短信库回查时间窗口（毫秒）：短信库 date 为短信中心时间戳，与本机接收时间可能有偏差
    private const val DB_LOOKUP_WINDOW_MS = 10 * 60 * 1000L

    //号码：可含 +、数字、空格、-、()
    private val PHONE_REGEX = Regex("^[+\\d][\\d\\s\\-()]{2,}$")

    fun isPhoneNumber(value: String): Boolean = PHONE_REGEX.matches(value.trim())

    /**
     * 取号回退链：displayOriginatingAddress → originatingAddress → 短信库 address 列
     * 均无号码时保留发件人名称（如"丰巢"），彻底为空才返回空串
     */
    fun resolveSender(displayAddress: String?, rawAddress: String?, body: String, timeMs: Long): String {
        val candidates = listOf(displayAddress?.trim() ?: "", rawAddress?.trim() ?: "")
        candidates.firstOrNull { isPhoneNumber(it) }?.let { return it }

        val dbAddress = querySmsAddress(body, timeMs)?.trim() ?: ""
        if (isPhoneNumber(dbAddress)) return dbAddress

        return candidates.firstOrNull { it.isNotEmpty() } ?: dbAddress
    }

    //按内容+时间窗口回查短信库的发件人地址（长短信按分段存储，整段精确匹配不到时按前缀回查）
    private fun querySmsAddress(body: String, timeMs: Long): String? {
        if (body.isBlank()) return null
        try {
            val windowStart = (timeMs - DB_LOOKUP_WINDOW_MS).toString()
            val windowEnd = (timeMs + DB_LOOKUP_WINDOW_MS).toString()

            queryAddress("type = 1 AND body = ? AND date BETWEEN ? AND ?", arrayOf(body, windowStart, windowEnd))?.let {
                return it
            }

            val prefix = body.take(30)
            if (prefix.length < body.length) {
                return queryAddress("type = 1 AND body LIKE ? AND date BETWEEN ? AND ?", arrayOf("$prefix%", windowStart, windowEnd))
            }
        } catch (e: Exception) {
            Log.e(TAG, "querySmsAddress failed: ${e.message}")
        }
        return null
    }

    private fun queryAddress(selection: String, selectionArgs: Array<String>): String? {
        val cursor = Core.app.contentResolver.query(
            Uri.parse("content://sms/"), arrayOf("address"), selection, selectionArgs, "date desc"
        ) ?: return null
        cursor.use {
            return if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * 双通道互斥分发：同一物理消息只会经广播或短信库之一先到达，去重窗口内另一通道的重复投递直接丢弃
     */
    fun dispatch(context: Context, msgInfo: MsgInfo, channel: String) {
        if (msgInfo.from.isEmpty() && msgInfo.content.isEmpty()) {
            Log.w(TAG, "发件人与内容均为空，跳过转发")
            return
        }

        val now = System.currentTimeMillis()
        val md5 = CipherUtils.md5(msgInfo.type + msgInfo.content)
        val otherChannel = if (channel == CHANNEL_BROADCAST) CHANNEL_DB else CHANNEL_BROADCAST
        var otherTime: Long by HistoryUtils("xch_${otherChannel}_$md5", 0L)
        if (otherTime > 0 && now - otherTime <= CROSS_CHANNEL_DEDUP_MS) {
            Log.i(TAG, "跨通道去重：$otherChannel 通道已处理相同消息，跳过 channel = $channel")
            return
        }
        var selfTime: Long by HistoryUtils("xch_${channel}_$md5", 0L)
        selfTime = now

        val request = OneTimeWorkRequestBuilder<SendWorker>().setInputData(
            workDataOf(
                Worker.SEND_MSG_INFO to Gson().toJson(msgInfo)
            )
        ).build()
        WorkManager.getInstance(context).enqueue(request)
    }

}
