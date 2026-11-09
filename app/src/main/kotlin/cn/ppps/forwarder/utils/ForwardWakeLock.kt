package cn.ppps.forwarder.utils

import android.content.Context
import android.os.PowerManager
import cn.ppps.forwarder.App

/**
 * 转发用临时唤醒锁
 *
 * 熄屏后设备进入 Doze，CPU 休眠会把异步链路（WorkManager 任务、Handler 延迟任务）
 * 推迟到维护窗口才执行，导致熄屏期间消息不转发、解锁后才补发。
 * 持有 PARTIAL_WAKE_LOCK 保证"消息到达 → 规则匹配 → 网络请求发出"全程不睡。
 *
 * 发送器内部为异步 IO（无统一完成回调可挂），故每次持锁均带超时自动释放，不存在泄漏；
 * 多次 acquire 为相互独立的锁对象，不会相互干扰。
 */
object ForwardWakeLock {

    private val TAG = ForwardWakeLock::class.java.simpleName

    fun acquire(reason: String, timeoutMs: Long) {
        try {
            val powerManager = App.context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "forwarder:$reason")
            wakeLock.setReferenceCounted(false)
            wakeLock.acquire(timeoutMs)
            Log.d(TAG, "持有唤醒锁 $reason（${timeoutMs / 1000}秒后自动释放）")
        } catch (e: Exception) {
            Log.e(TAG, "acquire failed: ${e.message}")
        }
    }

}
