package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.ACTION_KEEPALIVE_CHECK
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.KeepAliveUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils

/**
 * 保活看门狗：由 AlarmManager 周期唤醒（系统侧闹钟不随进程死亡而取消，
 * allow-while-idle 闹钟在 Doze 下仍触发），发现前台服务未运行时重新拉起。
 *
 * 厂商系统（如 OPPO ColorOS）熄屏后会查杀后台应用，杀掉进程后广播、
 * JobScheduler、应用内定时器均随进程失效，只有系统侧闹钟还能唤醒本应用：
 * 进程重启 → App.onCreate → 前台服务、各监听与短信库观察者全部恢复。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_KEEPALIVE_CHECK) return

        //纯客户端模式不保活
        if (SettingUtils.enablePureClientMode) return

        if (!ForegroundService.isRunning) {
            Log.w(TAG, "看门狗：前台服务未运行，重新拉起")
            restartForegroundService(context)
        } else {
            Log.d(TAG, "看门狗：前台服务运行中")
        }

        //重排下一次心跳（灭屏短周期/亮屏常规周期自适应）
        KeepAliveUtils.scheduleAdaptiveWatchdog(context)
    }

    companion object {

        private val TAG = KeepAliveReceiver::class.java.simpleName

        //拉起前台服务；Android 12+ 后台启动前台服务有豁免条件（精确闹钟、开机广播等），失败时静默待下次心跳重试
        fun restartForegroundService(context: Context) {
            try {
                val intent = Intent(context, ForegroundService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉起前台服务失败: ${e.message}")
            }
        }
    }

}
