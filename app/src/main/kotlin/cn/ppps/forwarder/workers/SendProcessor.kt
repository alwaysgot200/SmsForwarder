package cn.ppps.forwarder.workers

import android.annotation.SuppressLint
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Logs
import cn.ppps.forwarder.database.entity.Msg
import cn.ppps.forwarder.database.entity.Rule
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.entity.TaskSetting
import cn.ppps.forwarder.utils.CHECK_SIM_SLOT_ALL
import cn.ppps.forwarder.utils.DataProvider
import cn.ppps.forwarder.utils.DuplicateFilter
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SendUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.TASK_CONDITION_APP
import cn.ppps.forwarder.utils.TASK_CONDITION_CALL
import cn.ppps.forwarder.utils.TASK_CONDITION_SMS
import cn.ppps.forwarder.utils.TaskWorker
import cn.ppps.forwarder.utils.task.ConditionUtils
import com.google.gson.Gson
import com.xuexiang.xutil.resource.ResUtils
import com.xuexiang.xutil.security.CipherUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 转发核心处理：自动任务、消息入库、规则匹配、逐通道分发
 *
 * 从 SendWorker 抽取，供两条执行路径共用：
 * - 立即执行路径（SendUtils.sendMsg 持唤醒锁内联调用，不受 Doze 推迟影响）
 * - WorkManager 兜底路径（立即路径抛异常或进程中途被杀后补发）
 *
 * 未命中规则、免打扰、重复过滤等"正常不转发"场景不抛异常直接返回；
 * 抛出异常仅表示处理中途失败（如数据库异常），调用方可安排补发。
 */
@Suppress("PrivatePropertyName", "DEPRECATION")
object SendProcessor {

    private val TAG = SendProcessor::class.java.simpleName

    @SuppressLint("SimpleDateFormat")
    suspend fun process(msgInfo: MsgInfo) = withContext(Dispatchers.IO) {
        val msgInfoJson = Gson().toJson(msgInfo)
        //【注意】卡槽id：-1=获取失败、0=卡槽1、1=卡槽2，但是 Rule 表里存的是 SIM1/SIM2
        val simSlot = "SIM" + (msgInfo.simSlot + 1)

        //自动任务处理逻辑
        autoTaskProcess(msgInfo, msgInfoJson, simSlot)

        // 免打扰(禁用转发)时间段
        var isSilentPeriod = false
        Log.d(TAG, "silentPeriodStart = ${SettingUtils.silentPeriodStart}, silentPeriodEnd = ${SettingUtils.silentPeriodEnd}")
        if (SettingUtils.silentPeriodStart != SettingUtils.silentPeriodEnd) {
            isSilentPeriod = DataProvider.isCurrentTimeInPeriod(SettingUtils.silentPeriodStart, SettingUtils.silentPeriodEnd)
            Log.d(TAG, "isSilentPeriod = $isSilentPeriod, enableSilentPeriodLogs = ${SettingUtils.enableSilentPeriodLogs}")
            if (isSilentPeriod && !SettingUtils.enableSilentPeriodLogs) {
                Log.e(TAG, "免打扰(禁用转发)时间段")
                return@withContext
            }
        }

        // 过滤重复消息机制
        val duplicateMessagesLimits = SettingUtils.duplicateMessagesLimits * 1000L
        if (duplicateMessagesLimits > 0L) {
            val key = CipherUtils.md5(msgInfo.type + msgInfo.from + msgInfo.content)
            if (DuplicateFilter.isDuplicate(key, duplicateMessagesLimits)) {
                Log.e(TAG, "过滤重复消息机制 msgInfo=$msgInfo")
                return@withContext
            }
        }

        //无论是否命中规则，消息一律入库，避免漏转发无痕可查
        val msg = Msg(0, msgInfo.type, msgInfo.from, msgInfo.content, msgInfo.simSlot, msgInfo.simInfo, msgInfo.subId, msgInfo.callType)
        val msgId = Core.msg.insert(msg)

        val ruleList: List<Rule> = Core.rule.getRuleList(msgInfo.type, 1, simSlot)
        if (ruleList.isEmpty()) {
            Log.w(TAG, "无可用规则，消息仅入库未转发 msgId = $msgId")
            return@withContext
        }

        val ruleListMatched: MutableList<Rule> = mutableListOf()
        for (rule in ruleList) {
            Log.d(TAG, rule.toString())
            if (rule.checkMsg(msgInfo)) ruleListMatched.add(rule)
        }
        if (ruleListMatched.isEmpty()) {
            Log.w(TAG, "未命中任何规则，消息仅入库未转发 msgId = $msgId")
            return@withContext
        }

        for (rule in ruleListMatched) {
            val sender = rule.senderList[0]
            if (isSilentPeriod) {
                val log = Logs(0, msgInfo.type, msgId, rule.id, sender.id, 0, ResUtils.getString(R.string.silent_time_period))
                Core.logs.insert(log)
                continue
            }
            val log = Logs(0, msgInfo.type, msgId, rule.id, sender.id)
            val logId = Core.logs.insert(log)
            SendUtils.sendMsgSender(msgInfo, rule, 0, logId, msgId)
        }
    }

    private fun autoTaskProcess(msgInfo: MsgInfo, msgInfoJson: String, simSlot: String) {
        val conditionType = when (msgInfo.type) {
            "app" -> TASK_CONDITION_APP
            "call" -> TASK_CONDITION_CALL
            else -> TASK_CONDITION_SMS
        }

        val taskList = Core.task.getByType(conditionType)
        for (task in taskList) {
            Log.d(TAG, "task = $task")

            // 根据任务信息执行相应操作
            val conditionList = Gson().fromJson(task.conditions, Array<TaskSetting>::class.java).toMutableList()
            if (conditionList.isEmpty()) {
                Log.d(TAG, "TASK-${task.id}：conditionList is empty")
                continue
            }
            val firstCondition = conditionList.firstOrNull()
            if (firstCondition == null) {
                Log.d(TAG, "TASK-${task.id}：firstCondition is null")
                continue
            }

            val ruleSetting = Gson().fromJson(firstCondition.setting, Rule::class.java)
            if (ruleSetting == null) {
                Log.d(TAG, "TASK-${task.id}：ruleSetting is null")
                continue
            }

            if (ruleSetting.simSlot != CHECK_SIM_SLOT_ALL && simSlot != ruleSetting.simSlot) {
                Log.d(TAG, "TASK-${task.id}：simSlot is not matched, simSlot = $simSlot, ruleSetting = $ruleSetting")
                continue
            }

            if (!ruleSetting.checkMsg(msgInfo)) {
                Log.d(TAG, "TASK-${task.id}：ruleSetting is not matched, msgInfo = $msgInfo, ruleSetting = $ruleSetting")
                continue
            }

            //TODO：判断其他条件是否满足
            if (!ConditionUtils.checkCondition(task.id, conditionList)) {
                Log.d(TAG, "TASK-${task.id}：other condition is not satisfied")
                continue
            }

            //TODO: 组装消息体 && 执行具体任务
            val actionData = Data.Builder()
                .putLong(TaskWorker.TASK_ID, task.id)
                .putString(TaskWorker.TASK_ACTIONS, task.actions)
                .putString(TaskWorker.MSG_INFO, msgInfoJson)
                .build()
            val actionRequest = OneTimeWorkRequestBuilder<ActionWorker>().setInputData(actionData).build()
            WorkManager.getInstance().enqueue(actionRequest)
        }
    }

}
