package cn.ppps.forwarder.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一去重纯逻辑测试：内容归一化、消息指纹、from 去重判定
 */
class SmsUtilsTest {

    @Test
    fun normalizeContent_collapsesWhitespace() {
        assertEquals("a b c", SmsUtils.normalizeContent("  a  b\t\nc  "))
    }

    @Test
    fun normalizeContent_collapsesFullWidthSpace() {
        assertEquals("丰巢 取件", SmsUtils.normalizeContent("丰巢 　取件"))
    }

    @Test
    fun normalizeContent_keepsChineseText() {
        assertEquals("验证码 1234，5 分钟内有效", SmsUtils.normalizeContent("验证码 1234，5 分钟内有效"))
    }

    @Test
    fun fingerprint_ignoresWhitespaceDifferences() {
        //广播体与短信库存储体的细微空白差异不改变指纹（跨入口去重不失效）
        assertEquals(
            SmsUtils.messageFingerprint("sms", "验证码 1234"),
            SmsUtils.messageFingerprint("sms", "  验证码  1234 ")
        )
    }

    @Test
    fun fingerprint_differsOnContent() {
        assertNotEquals(
            SmsUtils.messageFingerprint("sms", "验证码 1234"),
            SmsUtils.messageFingerprint("sms", "验证码 5678")
        )
    }

    @Test
    fun fingerprint_differsOnType() {
        assertNotEquals(
            SmsUtils.messageFingerprint("sms", "hello"),
            SmsUtils.messageFingerprint("notification", "hello")
        )
    }

    @Test
    fun duplicateSender_sameOrEmpty_isDuplicate() {
        //相同 from、或任一 from 为空 → 同一物理消息的重复副本，丢弃
        assertTrue(SmsUtils.isDuplicateSender("10086", "10086"))
        assertTrue(SmsUtils.isDuplicateSender("", "10086"))
        assertTrue(SmsUtils.isDuplicateSender("10086", ""))
        assertTrue(SmsUtils.isDuplicateSender("", ""))
    }

    @Test
    fun duplicateSender_different_isNotDuplicate() {
        //明确不同的 from → 两个发件人恰好同文案，都转发（宁重不漏）
        assertFalse(SmsUtils.isDuplicateSender("10086", "10010"))
        assertFalse(SmsUtils.isDuplicateSender("丰巢", "10086"))
    }
}
