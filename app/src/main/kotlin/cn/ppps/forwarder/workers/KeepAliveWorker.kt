package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.ppps.forwarder.receiver.KeepAliveReceiver
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils

/**
 * 保活看门狗（WorkManager 周期任务）：与闹钟看门狗（[cn.ppps.forwarder.receiver.KeepAliveReceiver]）互为冗余。
 *
 * WorkManager 周期任务持久化于系统侧，重启、应用覆盖更新后自动恢复，且不受精确闹钟
 * 权限影响——在厂商限制闹钟、或用户拒绝"闹钟和提醒"特殊权限时仍能周期检查并拉起服务。
 * 即便本任务重启前台服务被 Android 12+ 后台启动限制拦截，进程也会随任务启动，
 * App.onCreate 会重新接上闹钟看门狗心跳链。
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private val TAG = KeepAliveWorker::class.java.simpleName
        const val UNIQUE_WORK_NAME = "keep_alive_watchdog"
    }

    override suspend fun doWork(): Result {
        //纯客户端模式不保活
        if (SettingUtils.enablePureClientMode) return Result.success()

        if (!ForegroundService.isRunning) {
            Log.w(TAG, "WM看门狗：前台服务未运行，重新拉起")
            KeepAliveReceiver.restartForegroundService(applicationContext)
        } else {
            Log.d(TAG, "WM看门狗：前台服务运行中")
        }

        return Result.success()
    }

}
