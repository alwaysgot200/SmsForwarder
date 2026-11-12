package cn.ppps.forwarder.utils

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity

/**
 * MIUI/HyperOS 短信广播拦截检测与告警。
 *
 * MIUI 私有 appops（MIUIOP 10001~10045）非 allow 时，系统按 "MIUI Permission Skip"
 * 策略在广播入队时直接跳过本应用的 SmsReceiver（REDMI 真机实测：权限编辑器与自启动
 * 均显示正常仍被拦），短信转发静默失效。放开这些开关为一次性系统设置，之后转发全程
 * 自动；仅在卸载重装/系统还原后被复位。
 *
 * 由保活看门狗每次心跳（[cn.ppps.forwarder.receiver.KeepAliveReceiver]）调用 [warnIfNeeded]：
 * 平时静默，发现被拦时发通知引导恢复一次，不参与转发链路、不要求任何日常点击。
 *
 * @since 2026年11月12日
 */
object MiuiBroadcastGuard {

    private val TAG = MiuiBroadcastGuard::class.java.simpleName

    //真机实测导致 "MIUI Permission Skip" 的私有 appops：ignore/ask 状态下短信广播被直接跳过
    private val WATCH_OPS = intArrayOf(10001, 10002, 10004, 10017, 10018, 10020, 10021, 10022, 10045)

    //告警节流：看门狗心跳每 5~15 分钟一次，被拦期间提醒通知按此间隔重发
    private const val WARN_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private const val SP_LAST_WARN = "miui_broadcast_guard_warn_ts"

    /**
     * 读取单个 MIUIOP 的模式。MIUI 私有 op 没有公共字符串名（strOpToOp 不识别
     * "MIUIOP(10004)" 等写法，真机实测），公共 checkOpNoThrow(String,...) 无法寻址，
     * 只能反射 @hide 的 int 版本；反射被隐藏 API 限制拦截时返回 null（检测不可用，不误报）。
     */
    private fun checkMode(context: Context, op: Int): Int? {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(appOps, op, context.applicationInfo.uid, context.packageName) as Int
        } catch (e: Exception) {
            Log.w(TAG, "MIUIOP $op 状态读取失败（隐藏 API 受限？）: ${e.message}")
            null
        }
    }

    /**
     * 是否处于短信广播拦截状态：仅小米系检测，非小米系/检测异常一律视为正常（不误报）。
     * 任一监视的 MIUIOP 非 MODE_ALLOWED（含 ask/ignore）即视为被拦。
     */
    fun isBlocked(context: Context): Boolean {
        if (!CommonUtils.isXiaomiFamily()) return false
        return WATCH_OPS.any { op ->
            val mode = checkMode(context, op)
            mode != null && mode != AppOpsManager.MODE_ALLOWED
        }
    }

    /** ADB 一键放行命令（MIUI 权限界面无对应开关时的确定性修复路径，真机实测有效） */
    fun adbFixCommand(context: Context): String =
        "adb shell \"for op in ${WATCH_OPS.joinToString(" ")}; do cmd appops set ${context.packageName} \$op allow; done\""

    /**
     * 被拦时发提醒通知（复用保活审计通知渠道与 ID，单槽位不叠加），并重置主窗口
     * 审计节流：点开通知进入主窗口立即弹出恢复引导。未被拦时静默返回。
     */
    fun warnIfNeeded(context: Context) {
        if (!isBlocked(context)) return

        var lastWarn: Long by SharedPreference(SP_LAST_WARN, 0L)
        val now = System.currentTimeMillis()
        if (now - lastWarn < WARN_INTERVAL_MS) return
        lastWarn = now

        //点开通知进入主窗口立即弹出引导（不等 24h 审计节流）
        KeepAliveSetupWizard.resetUiAuditThrottle()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(KeepAliveSetupWizard.AUDIT_CHANNEL_ID, context.getString(R.string.keepalive_audit_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, KeepAliveSetupWizard.AUDIT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentTitle(context.getString(R.string.keepalive_audit_notify_title))
            .setContentText(context.getString(R.string.keepalive_audit_notify_content, 1, context.getString(R.string.setup_wizard_miui_broadcast_title)))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(KeepAliveSetupWizard.AUDIT_NOTIFY_ID, notification)
        Log.w(TAG, "MIUI 短信广播处于拦截状态，已发提醒通知等待放行")
    }

}
