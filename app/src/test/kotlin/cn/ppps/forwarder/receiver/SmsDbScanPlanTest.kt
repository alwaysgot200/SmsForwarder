package cn.ppps.forwarder.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 水位线扫描决策测试：只前瞻不回扫（杜绝首装后历史短信全量转发）
 */
class SmsDbScanPlanTest {

    private val now = 1_700_000_000_000L

    @Test
    fun uninitialized_protectOnlyRecentRows() {
        //未初始化：吞掉历史存量，仅保护最近窗口内新入库的短信（触发首次扫描的新短信不丢）
        val plan = planSmsDbScan(lastId = 0L, maxId = 100L, enableSms = true, now = now)
        assertEquals(100L, plan.newWatermark)
        assertEquals(0L, plan.queryAfterId)
        assertEquals(now - SMS_DB_INIT_PROTECT_WINDOW_MS, plan.queryMinDate)
        assertTrue(plan.dispatch)
    }

    @Test
    fun switchOff_swallowAllWithoutBackfill() {
        //开关关闭：吞掉期间新入库短信不补发（未初始化时同样吞掉并完成初始化）
        val uninitialized = planSmsDbScan(lastId = 0L, maxId = 100L, enableSms = false, now = now)
        assertEquals(100L, uninitialized.newWatermark)
        assertFalse(uninitialized.dispatch)

        val initialized = planSmsDbScan(lastId = 50L, maxId = 100L, enableSms = false, now = now)
        assertEquals(100L, initialized.newWatermark)
        assertFalse(initialized.dispatch)
    }

    @Test
    fun initialized_scanNewRowsOnly() {
        //已初始化且开关开启：只扫水位线之后的新行，水位线只前瞻
        val plan = planSmsDbScan(lastId = 50L, maxId = 100L, enableSms = true, now = now)
        assertEquals(100L, plan.newWatermark)
        assertEquals(50L, plan.queryAfterId)
        assertEquals(0L, plan.queryMinDate)
        assertTrue(plan.dispatch)
    }

    @Test
    fun watermarkNeverRewinds() {
        //任何决策下水位线新值都不低于当前最大 id，绝不回扫
        val plans = listOf(
            planSmsDbScan(0L, 100L, true, now),
            planSmsDbScan(0L, 100L, false, now),
            planSmsDbScan(50L, 100L, true, now),
            planSmsDbScan(50L, 100L, false, now)
        )
        plans.forEach { assertEquals(100L, it.newWatermark) }
    }
}
