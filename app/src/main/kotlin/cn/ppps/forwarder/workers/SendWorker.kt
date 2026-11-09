package cn.ppps.forwarder.workers

import android.annotation.SuppressLint
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.Worker
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager 兜底转发任务：核心处理逻辑在 [SendProcessor]，
 * 与立即执行路径（[cn.ppps.forwarder.utils.SendUtils.sendMsg]）共用。
 */
@Suppress("PrivatePropertyName", "DEPRECATION")
class SendWorker(context: android.content.Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val TAG: String = SendWorker::class.java.simpleName

    @SuppressLint("SimpleDateFormat")
    override suspend fun doWork(): Result {

        return withContext(Dispatchers.IO) {
            val msgInfoJson = inputData.getString(Worker.SEND_MSG_INFO)
            if (msgInfoJson.isNullOrBlank()) {
                return@withContext Result.failure(workDataOf("send" to "msgInfoJson is null"))
            }

            try {
                val msgInfo = Gson().fromJson(msgInfoJson, MsgInfo::class.java)
                SendProcessor.process(msgInfo)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "SendWorker error: ${e.message}")
                return@withContext Result.failure(workDataOf("send" to e.message.toString()))
            }

            return@withContext Result.success()
        }
    }

}
