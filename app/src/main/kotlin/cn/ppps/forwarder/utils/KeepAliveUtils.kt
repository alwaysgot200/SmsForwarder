package cn.ppps.forwarder.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import cn.ppps.forwarder.R
import cn.ppps.forwarder.receiver.KeepAliveReceiver

@Suppress("DEPRECATION")
class KeepAliveUtils private constructor() {

    companion object {
        private const val TAG = "KeepAliveUtils"

        //看门狗心跳周期：厂商系统查杀后最迟一个周期内自愈；allow-while-idle 闹钟在 Doze 下仍触发
        const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        //灭屏期间的心跳周期：厂商熄屏查杀不会回调 onDestroy，只能等下轮心跳自愈，
        //缩短周期以减小转发中断空窗（深度 Doze 下系统限制每应用约 9 分钟触发一次，
        //请求 5 分钟会被系统自动节流到允许时机，不丢闹钟、不额外耗电）
        const val WATCHDOG_INTERVAL_SCREEN_OFF_MS = 5 * 60 * 1000L

        fun isIgnoreBatteryOptimization(activity: Activity): Boolean {
            //安卓6.0以下没有忽略电池优化
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                true
            } else try {
                val powerManager: PowerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(activity.packageName)
            } catch (e: Exception) {
                XToastUtils.error(R.string.unsupport)
                false
            }
        }

        @RequiresApi(api = Build.VERSION_CODES.M)
        fun ignoreBatteryOptimization(activity: Activity) {
            try {
                if (isIgnoreBatteryOptimization(activity)) {
                    return
                }
                @SuppressLint("BatteryLife") val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:" + activity.packageName)
                val resolveInfo: ResolveInfo? = activity.packageManager.resolveActivity(intent, 0)
                if (resolveInfo != null) {
                    activity.startActivity(intent)
                } else {
                    XToastUtils.error(R.string.unsupport)
                }
            } catch (e: Exception) {
                XToastUtils.error(R.string.unsupport)
            }
        }

        /**
         * 安排保活看门狗心跳：系统侧闹钟不随进程死亡而取消，由 [KeepAliveReceiver] 周期
         * 检查前台服务存活并在被厂商系统查杀后重新拉起。
         *
         * 同一 PendingIntent 重复安排为覆盖式替换，不会累积多个闹钟。
         * 优先精确闹钟（Doze 下准点触发，且 Android 12+ 豁免前台服务后台启动限制）；
         * 未授予精确闹钟权限时退化为 allow-while-idle 不精确闹钟（触发时机可能延后但依然有效）。
         */
        fun scheduleServiceWatchdog(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_KEEPALIVE_CHECK)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            val triggerAt = System.currentTimeMillis() + delayMs
            try {
                val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    true
                }
                when {
                    //API 23+：优先精确闹钟（Doze 下准点触发，且 Android 12+ 豁免前台服务后台启动限制）
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && canExact ->
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)

                    //API 23+：未授予精确闹钟权限时退化为 allow-while-idle 不精确闹钟（触发可能延后但依然有效）
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)

                    //API 19-22：无 allow-while-idle，用普通闹钟兜底
                    else -> alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                Log.d(TAG, "看门狗心跳已安排：${delayMs / 1000}秒后触发")
            } catch (e: SecurityException) {
                Log.e(TAG, "安排看门狗心跳失败: ${e.message}")
            }
        }

        /**
         * 按亮灭屏自适应安排看门狗心跳：灭屏期间厂商查杀风险高且短信转发延迟代价大，
         * 用短周期；亮屏恢复常规周期。用于周期性重排（心跳触发后、应用启动时）。
         */
        fun scheduleAdaptiveWatchdog(context: Context) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val interval = if (powerManager?.isInteractive == false) WATCHDOG_INTERVAL_SCREEN_OFF_MS else WATCHDOG_INTERVAL_MS
            scheduleServiceWatchdog(context, interval)
        }

    }
}
