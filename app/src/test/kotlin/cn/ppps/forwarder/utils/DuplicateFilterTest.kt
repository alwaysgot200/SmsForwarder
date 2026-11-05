package cn.ppps.forwarder.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复过滤纯逻辑测试：窗口内是否已出现过
 */
class DuplicateFilterTest {

    private val now = 1_700_000_000_000L

    @Test
    fun neverSeen_isNotDuplicate() {
        //无历史记录：首次出现，不判为重复
        assertFalse(DuplicateFilter.isDuplicateWindow(null, now, 6000L))
    }

    @Test
    fun seenWithinWindow_isDuplicate() {
        //窗口内已出现过：判为重复（含恰好同毫秒）
        assertTrue(DuplicateFilter.isDuplicateWindow(now, now, 6000L))
        assertTrue(DuplicateFilter.isDuplicateWindow(now - 5999L, now, 6000L))
    }

    @Test
    fun seenBeyondWindow_isNotDuplicate() {
        //超出窗口：判为新消息
        assertFalse(DuplicateFilter.isDuplicateWindow(now - 6001L, now, 6000L))
    }

    @Test
    fun zeroWindow_neverDuplicates() {
        //窗口为 0：关闭过滤，任何情况都不判为重复
        assertFalse(DuplicateFilter.isDuplicateWindow(now, now, 0L))
    }
}
