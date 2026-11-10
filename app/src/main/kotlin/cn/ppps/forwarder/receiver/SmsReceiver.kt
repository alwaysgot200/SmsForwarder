package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SmsCommandUtils
import cn.ppps.forwarder.utils.SmsUtils
import com.xuexiang.xrouter.utils.TextUtils
import java.util.Date

//短信广播
@Suppress("PrivatePropertyName", "UNUSED_PARAMETER")
class SmsReceiver : BroadcastReceiver() {

    private var TAG = SmsReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        try {
            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //过滤广播（彩信改由彩信库监听 MmsDbObserver 处理，不走 WAP-Push 广播）
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
                && intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
            ) return

            var msg = ""
            var displayAddress = ""
            var rawAddress = ""
            val smsMessages: List<SmsMessage> = try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent)?.toList() ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getMessagesFromIntent failed: ${e.message}")
                emptyList()
            }
            for (smsMessage in smsMessages) {
                //逐字段取值：单个字段解析失败（如智能短信 PDU 地址/内容为空）不丢整条短信
                try {
                    if (displayAddress.isEmpty()) displayAddress = smsMessage.displayOriginatingAddress ?: ""
                } catch (e: Exception) {
                    Log.e(TAG, "displayOriginatingAddress failed: ${e.message}")
                }
                try {
                    if (rawAddress.isEmpty()) rawAddress = smsMessage.originatingAddress ?: ""
                } catch (e: Exception) {
                    Log.e(TAG, "originatingAddress failed: ${e.message}")
                }
                try {
                    msg += smsMessage.messageBody ?: ""
                } catch (e: Exception) {
                    Log.e(TAG, "messageBody failed: ${e.message}")
                }
            }

            //取号回退链：智能信息把发件人替换为名称/空时，依次从原始地址、短信库恢复真实号码
            val from = SmsUtils.resolveSender(displayAddress, rawAddress, msg, System.currentTimeMillis())
            Log.d(TAG, "displayAddress = $displayAddress, rawAddress = $rawAddress, from = $from, msg = $msg")

            //短信指令
            if (SettingUtils.enableSmsCommand && msg.startsWith("smsf#")) {
                doSmsCommand(context, from, msg)
                return
            }

            //总开关
            if (!SettingUtils.enableSms) return

            //TODO：准确获取卡槽信息，目前测试结果只有 subscription 相对靠谱
            val slot = intent.extras?.getInt("slot") ?: -1
            val simId = intent.extras?.getInt("simId") ?: slot
            val subscription = intent.extras?.getInt("subscription") ?: simId
            Log.d(TAG, "slot = $slot, simId = $simId, subscription = $subscription")

            //卡槽id：-1=获取失败、0=卡槽1、1=卡槽2
            var simSlot = -1
            //以自定义卡槽信息优先
            if (SettingUtils.subidSim1 > 0 || SettingUtils.subidSim2 > 0) {
                simSlot = if (subscription == SettingUtils.subidSim1) 0 else 1
            } else {
                //获取卡槽信息
                if (App.SimInfoList.isEmpty()) {
                    App.SimInfoList = PhoneUtils.getSimMultiInfo()
                }
                Log.d(TAG, "SimInfoList = " + App.SimInfoList.toString())

                if (App.SimInfoList.isNotEmpty()) {
                    for (simInfo in App.SimInfoList.values) {
                        if (simInfo.mSubscriptionId == subscription) {
                            simSlot = simInfo.mSimSlotIndex
                            break
                        }
                    }
                }
            }

            //获取卡槽信息
            val simInfo = when (simSlot) {
                0 -> "SIM1_" + SettingUtils.extraSim1
                1 -> "SIM2_" + SettingUtils.extraSim2
                else -> ""
            }

            val msgInfo = MsgInfo("sms", from, msg, Date(), simInfo, simSlot, subscription)
            Log.d(TAG, "msgInfo = $msgInfo")

            SmsUtils.dispatch(context, msgInfo, SmsUtils.CHANNEL_BROADCAST)

        } catch (e: Exception) {
            Log.e(TAG, "Parsing SMS failed: " + e.message.toString())
        }
    }

    //处理短信指令
    private fun doSmsCommand(context: Context, from: String, message: String) {
        var safePhone = SettingUtils.smsCommandSafePhone
        Log.d(TAG, "safePhone = $safePhone")

        if (!TextUtils.isEmpty(safePhone)) {
            var isSafePhone = false
            safePhone = safePhone.replace(";", ",").replace("；", ",").replace("，", ",").trim()
            for (phone in safePhone.split(",")) {
                if (!TextUtils.isEmpty(phone.trim()) && from.endsWith(phone.trim())) {
                    isSafePhone = true
                    break
                }
            }
            if (!isSafePhone) {
                Log.d(TAG, "from = $from is not safePhone = $safePhone")
                return
            }
        }

        val smsCommand = message.substring(5)
        SmsCommandUtils.execute(context, smsCommand)
    }



}