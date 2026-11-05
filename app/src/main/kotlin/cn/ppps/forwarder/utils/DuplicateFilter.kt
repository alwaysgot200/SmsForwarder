package cn.ppps.forwarder.utils

import com.google.gson.Gson

/**
 * 重复消息/指令过滤：统一的"窗口内是否已出现过"判定
 *
 * 替代旧版"每条消息/指令一个 md5 存储键"的实现——那会让转发历史存储随消息量无限膨胀
 * （SharedPreferences 全量载入内存，长期使用拖慢启动）。现改为单键滚动记录（最近 N 条：
 * key + 最近出现时间），随写入淘汰超量条目，存储有界。
 *
 * SendWorker（重复消息过滤）与 SmsCommandUtils（重复指令过滤）共用本工具，消除重复实现。
 * 去重判定为纯逻辑 [isDuplicateWindow]，便于单元测试。
 *
 * @since 2026年11月5日
 */
object DuplicateFilter {

    private val TAG = DuplicateFilter::class.java.simpleName

    //单键滚动记录：key + 最近出现时间
    private const val RECORDS_KEY = "duplicate_filter_records"
    private const val RECORDS_LIMIT = 200

    //旧版遗留去重存储键：xch_* 跨通道键（≤3.5.2）、每消息 md5 键（旧重复过滤），一次性清理
    private const val LEGACY_MIGRATED_KEY = "duplicate_filter_legacy_migrated"
    private val MD5_KEY_REGEX = Regex("[0-9a-f]{32}")

    private val lock = Any()

    internal data class Record(val key: String, val time: Long)

    /**
     * 判定 key 是否为窗口内重复：是则返回 true（并刷新最近出现时间，使持续重复持续被拦），
     * 否则记录当前时间并返回 false。windowMs <= 0 时直接放行（不过滤）。
     */
    fun isDuplicate(key: String, windowMs: Long): Boolean {
        if (windowMs <= 0L) return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val records = loadRecords()
            val lastTime = records.lastOrNull { it.key == key }?.time
            //无论命中与否都刷新最近出现时间，保持"持续重复持续被拦"的滑动语义
            records.removeAll { it.key == key }
            records.add(Record(key, now))
            saveRecords(records)
            return isDuplicateWindow(lastTime, now, windowMs)
        }
    }

    //去重判定（纯逻辑）：已有记录且距今不超过窗口 → 重复；窗口 <= 0 表示关闭过滤
    internal fun isDuplicateWindow(lastTime: Long?, now: Long, windowMs: Long): Boolean =
        windowMs > 0L && lastTime != null && now - lastTime <= windowMs

    //加载滚动记录；解析失败重建空记录
    private fun loadRecords(): MutableList<Record> {
        var raw: String by HistoryUtils(RECORDS_KEY, "")
        return try {
            if (raw.isEmpty()) mutableListOf() else Gson().fromJson(raw, Array<Record>::class.java).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "重复过滤记录解析失败，重建记录: ${e.message}")
            mutableListOf()
        }
    }

    //保存滚动记录（单键），只保留最近 N 条，存储有界
    private fun saveRecords(records: MutableList<Record>) {
        var raw: String by HistoryUtils(RECORDS_KEY, "")
        raw = Gson().toJson(records.takeLast(RECORDS_LIMIT))
    }

    /**
     * 一次性清理旧版本遗留的去重存储键（xch_* 跨通道键、每消息 md5 键），
     * 它们随消息量无限膨胀，现已由单键滚动记录取代。只在升级后首次启动执行一次。
     */
    fun migrateLegacy() {
        var migrated: Boolean by HistoryUtils(LEGACY_MIGRATED_KEY, false)
        if (migrated) return
        val removed = HistoryUtils.removeKeysIf { it.startsWith("xch_") || it.matches(MD5_KEY_REGEX) }
        migrated = true
        if (removed > 0) Log.i(TAG, "清理旧版遗留去重存储键 $removed 个")
    }

}
