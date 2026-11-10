package cn.ppps.forwarder.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.ppps.forwarder.R
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xutil.resource.ResUtils.getString

/**
 * 短信发送反诈确认自动点击（无障碍服务）
 *
 * 小米/OPPO/vivo/华为/荣耀等国产系统对第三方应用发送"非验证码"短信有反诈人工确认弹窗
 * （如小米 com.miui.securitycenter 的 SendSmsVerificationActivity），系统级拦截，
 * 无法编程跳过。本服务在用户主动开启后，监听这些安全组件的确认弹窗，
 * 自动点击"确认发送/允许"，替代用户逐条手工确认——弹窗仍会一闪而过，
 * 发送行为对用户完全可见，只是无需人工介入。
 *
 * 作用域刻意收窄：只响应安全组件包名的弹窗，且仅当按钮文案命中"确认发送"，
 * 或（弹窗内容包含"发送短信"字样时的"允许"），绝不点击其他任何系统按钮。
 */
class SmsConfirmAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SmsConfirmA11y"

        //国产系统承载"发送短信确认"弹窗的安全组件包名前缀
        private val SECURITY_PKG_PREFIXES = listOf(
            "com.miui.securitycenter", "com.miui.guardprovider",
            "com.coloros.", "com.oplus.",
            "com.vivo.", "com.iqoo.", "com.lbe.security",
            "com.huawei.systemmanager", "com.hihonor.systemmanager",
            "com.meizu.safe"
        )

        //语义唯一、可直接点击的按钮文案
        private const val PHRASE_CONFIRM_SEND = "确认发送"
        private const val PHRASE_CONFIRM_SEND_EN = "Confirm send"

        //宽义按钮文案：仅当弹窗内容明确出现"发送短信"字样时才允许点击
        private val PHRASES_GENERIC = listOf("允许", "Allow")

        //防重复点击：同一弹窗的窗口刷新事件会在短时间内连发
        private const val CLICK_DEBOUNCE_MS = 2_000L

        @Volatile
        private var lastClickTs = 0L

        /** 服务是否已在系统无障碍设置中开启（可编程检测，供保活向导/审计自动跳过与复检） */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, SmsConfirmAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabled.isNullOrEmpty()) return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        //应用内开关关闭时不动作（系统无障碍服务虽在运行，但用户已停用该功能）
        if (!SettingUtils.enableAutoConfirmSend) return
        val pkg = event.packageName?.toString() ?: return
        if (SECURITY_PKG_PREFIXES.none { pkg.startsWith(it) }) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = System.currentTimeMillis()
        if (now - lastClickTs < CLICK_DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        if (root.packageName != pkg) return

        val confirmNode = findConfirmNode(root) ?: return
        lastClickTs = now
        val clicked = confirmNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "自动确认发送：clicked = $clicked, pkg = $pkg")
        if (clicked) {
            XToastUtils.success(getString(R.string.auto_confirm_sent_toast))
        }
    }

    /**
     * 查找确认按钮：文案命中"确认发送"直接点击；宽义文案（允许）必须以
     * "弹窗内容含'发送短信'"为前置条件，避免误点其他安全弹窗
     */
    private fun findConfirmNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (phrase in listOf(PHRASE_CONFIRM_SEND, PHRASE_CONFIRM_SEND_EN)) {
            findClickableByText(root, phrase)?.let { return it }
        }
        val hasSmsContext = root.findAccessibilityNodeInfosByText("发送短信").isNotEmpty() ||
            root.findAccessibilityNodeInfosByText("发送彩信").isNotEmpty()
        if (!hasSmsContext) return null
        for (phrase in PHRASES_GENERIC) {
            findClickableByText(root, phrase)?.let { return it }
        }
        return null
    }

    /** 按文案查找可点击节点：节点自身不可点击时上溯最近的可点击祖先 */
    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        for (node in root.findAccessibilityNodeInfosByText(text)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                target = target.parent
            }
            target?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {
        //无障碍中断无补救动作
    }
}
