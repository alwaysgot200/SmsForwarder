package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.ppps.forwarder.utils.SettingUtils

/**
 * 开机/覆盖更新自愈：两者均为系统豁免场景，直接拉起前台服务恢复转发。
 *
 * 注意：解锁自愈（ACTION_USER_PRESENT）只能动态注册接收（系统不向 manifest
 * 声明的接收器投递），由 App 中动态注册的 [LockScreenReceiver] 负责。
 */
class BootCompletedReceiver : BroadcastReceiver() {

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
        }

    }
}
