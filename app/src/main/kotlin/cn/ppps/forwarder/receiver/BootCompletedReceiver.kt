package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils

@Suppress("PrivatePropertyName")
class BootCompletedReceiver : BroadcastReceiver() {

    private val TAG = BootCompletedReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent?) {

        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                //开机广播属于系统豁免场景，直接拉起前台服务保活
                //（Android 10+ 禁止后台启动 Activity，不再尝试拉起界面）
                if (!SettingUtils.enablePureClientMode) {
                    KeepAliveReceiver.restartForegroundService(context)
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                //应用覆盖更新后闹钟与延迟任务被系统清空：直接拉起前台服务并重建看门狗心跳
                //（不弹启动界面，更新后静默恢复）
                if (!SettingUtils.enablePureClientMode) {
                    KeepAliveReceiver.restartForegroundService(context)
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                //解锁自愈：厂商系统查杀进程后，用户解锁时第一时间恢复前台服务（后台启动受限时静默，由看门狗兜底）
                if (!SettingUtils.enablePureClientMode && !ForegroundService.isRunning) {
                    Log.w(TAG, "解锁自愈：前台服务未运行，重新拉起")
                    KeepAliveReceiver.restartForegroundService(context)
                }
            }
        }

    }
}
