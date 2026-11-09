package cn.ppps.forwarder.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.xuexiang.xutil.security.CipherUtils
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.entity.MsgInfo

/**
 * 短信数据源统一处理：发件人解析（取号回退链）、短信库回查、多入口统一去重分发
 *
 * 背景：系统"智能信息"开启后，部分短信的发件人号码被替换为名称（如"丰巢"）或为空，
 * 且部分消息不触发 SMS_RECEIVED 广播、由系统直接写入短信库，导致漏转发。
 *
 * 统一去重：同一条物理短信可能经广播（SMS_RECEIVED / SMS_DELIVER / 智能信息等多动作）
 * 与短信库监听多个入口重复到达，所有入口经同一消息指纹去重，窗口内只处理一次。
 *
 * @since 2026年11月3日
 */
object SmsUtils {

    private val TAG = SmsUtils::class.java.simpleName

    //多入口分发标记：广播通道 / 短信库监听通道
    const val CHANNEL_BROADCAST = "broadcast"
    const val CHANNEL_DB = "db"

    //统一去重窗口：实时捕捉的短信副本只会在前后几秒内重复到达，窗口内同指纹只处理一次
    private const val DEDUP_WINDOW_MS = 6_000L

    //单键滚动记录容量：最近 50 条（指纹、from、时间、通道），写入时自动淘汰过期条目
    private const val DEDUP_RECORDS_KEY = "sms_dispatch_dedup_records"
    private const val DEDUP_RECORDS_LIMIT = 50

    //短信库回查时间窗口（毫秒）：短信库 date 为短信中心时间戳，与本机接收时间可能有偏差
    private const val DB_LOOKUP_WINDOW_MS = 10 * 60 * 1000L

    //号码：可含 +、数字、空格、-、()
    private val PHONE_REGEX = Regex("^[+\\d][\\d\\s\\-()]{2,}$")

    //空白归一化：折叠连续空白（含全角空格），消除广播体/库存体细微空白差异
    private val WHITESPACE_REGEX = Regex("[\\s\\u3000]+")

    private val dedupLock = Any()

    internal data class DedupRecord(val fingerprint: String, val from: String, val time: Long, val channel: String)

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
     * 多入口统一去重分发：同一物理消息经广播多动作/短信库重复到达时，窗口内只处理一次
     *
     * 丢弃条件：去重窗口内已有同指纹记录，且两者 from 相同、或任一 from 为空（同一消息的重复副本）
     * 不丢弃：同指纹但 from 明确不同（不同发件人恰好同文案，宁重不漏）
     */
    fun dispatch(context: Context, msgInfo: MsgInfo, channel: String) {
        if (msgInfo.from.isEmpty() && msgInfo.content.isEmpty()) {
            Log.w(TAG, "发件人与内容均为空，跳过转发")
            return
        }

        val now = System.currentTimeMillis()
        val fingerprint = messageFingerprint(msgInfo.type, msgInfo.content)

        synchronized(dedupLock) {
            val records = loadDedupRecords(now)
            val duplicate = records.firstOrNull { it.fingerprint == fingerprint && isDuplicateSender(it.from, msgInfo.from) }
            if (duplicate != null) {
                Log.i(TAG, "统一去重：${duplicate.channel} 通道已处理相同消息，跳过 channel = $channel，from = ${msgInfo.from}")
                return
            }
            records.add(DedupRecord(fingerprint, msgInfo.from, now, channel))
            saveDedupRecords(records)
        }

        //统一经 SendUtils.sendMsg 立即转发（WorkManager 直接入队在 Doze 下会被推迟到维护窗口，导致熄屏不转发）
        SendUtils.sendMsg(context, msgInfo)
    }

    //消息指纹：类型 + 归一化内容
    internal fun messageFingerprint(type: String, content: String): String = CipherUtils.md5(type + normalizeContent(content))

    //内容归一化：去除首尾空白并折叠连续空白（含全角空格）
    internal fun normalizeContent(content: String): String = content.trim().replace(WHITESPACE_REGEX, " ")

    //from 去重判定：相同或任一为空 → 同一物理消息的重复副本；明确不同 → 两个发件人恰好同文案，都转发
    internal fun isDuplicateSender(existingFrom: String, incomingFrom: String): Boolean =
        existingFrom == incomingFrom || existingFrom.isEmpty() || incomingFrom.isEmpty()

    //加载滚动记录并淘汰过期条目（窗口外的记录无去重价值）
    private fun loadDedupRecords(now: Long): MutableList<DedupRecord> {
        var raw: String by HistoryUtils(DEDUP_RECORDS_KEY, "")
        val records: MutableList<DedupRecord> = try {
            if (raw.isEmpty()) mutableListOf() else Gson().fromJson(raw, Array<DedupRecord>::class.java).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "去重记录解析失败，重建记录: ${e.message}")
            mutableListOf()
        }
        records.removeAll { now - it.time > DEDUP_WINDOW_MS }
        return records
    }

    //保存滚动记录（单键），只保留最近 N 条
    private fun saveDedupRecords(records: MutableList<DedupRecord>) {
        var raw: String by HistoryUtils(DEDUP_RECORDS_KEY, "")
        raw = Gson().toJson(records.takeLast(DEDUP_RECORDS_LIMIT))
    }

}
