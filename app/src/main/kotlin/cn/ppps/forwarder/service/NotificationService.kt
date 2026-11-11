package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.entity.Rule
import cn.ppps.forwarder.entity.MsgInfo
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PACKAGE_NAME
import cn.ppps.forwarder.utils.SendUtils
import cn.ppps.forwarder.utils.SettingUtils
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xutil.display.ScreenUtils
import java.util.Date


@Suppress("PrivatePropertyName", "DEPRECATION")
class NotificationService : NotificationListenerService() {

    private val TAG: String = NotificationService::class.java.simpleName

    override fun onListenerConnected() {
        Log.d(TAG, "onListenerConnected")
    }

    override fun onListenerDisconnected() {
        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return

        //总开关
        if (!SettingUtils.enableAppNotify) return

        Log.d(TAG, "通知侦听器断开连接 - 请求重新绑定")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //必须传本服务的组件名（传基类框架组件名无法匹配清单声明，重绑定请求会被静默忽略）
            requestRebind(ComponentName(this, javaClass))
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //异常通知跳过
            val notification = sbn?.notification ?: return
            val extras = notification.extras ?: return

            //自动消除额外APP通知
            SettingUtils.cancelExtraAppNotify
                .takeIf { it.isNotEmpty() }
                ?.split("\n")
                ?.forEach { app ->
                    if (sbn.packageName == app.trim()) {
                        Log.d(TAG, "自动消除额外APP通知：$app")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cancelNotification(sbn.key)
                        } else {
                            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                        }
                        return@forEach
                    }
                }


            //总开关
            if (!SettingUtils.enableAppNotify) return

            //仅锁屏状态转发APP通知
            if (SettingUtils.enableNotUserPresent && !ScreenUtils.isScreenLock()) return

            val from = sbn.packageName
            //自身通知跳过
            if (PACKAGE_NAME == sbn.packageName) return
            // 标题
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            // 通知内容
            var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
                if (bigText.isNotEmpty()) {
                    text = bigText
                }
            }
            if (text.isEmpty() && notification.tickerText != null) {
                text = notification.tickerText.toString()
            }

            //不处理空消息（标题跟内容都为空）
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return

            //关键词黑名单：一行一个，支持正则，命中标题或内容则不转发
            if (isInBlacklist(title, text)) {
                Log.d(TAG, "命中APP通知关键词黑名单，跳过转发。title=$title, text=$text")
                return
            }

            val msgInfo = MsgInfo("app", from, text, Date(), title, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "消息的UID====>" + sbn.uid)
                msgInfo.uid = sbn.uid
            }
            //TODO：自动消除通知（临时方案，重复查询换取准确性）
            if (SettingUtils.enableCancelAppNotify) {
                val ruleList: List<Rule> = Core.rule.getRuleList(msgInfo.type, 1, "SIM0")
                for (rule in ruleList) {
                    if (rule.checkMsg(msgInfo)) {
                        Log.d(TAG, "自动消除通知")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            cancelNotification(sbn.key)
                        } else {
                            cancelNotification(sbn.packageName, sbn.tag, sbn.id)
                        }
                        break
                    }
                }
            }

            //统一经 SendUtils.sendMsg 立即转发，避免熄屏 Doze 下 WorkManager 被推迟导致补发延迟
            SendUtils.sendMsg(applicationContext, msgInfo)

        } catch (e: Exception) {
            Log.e(TAG, "Parsing Notification failed: " + e.message.toString())
        }

    }

    //关键词黑名单匹配：一行一个关键词，支持正则表达式，命中通知标题或内容任意一项即返回 true
    private fun isInBlacklist(title: String, text: String): Boolean {
        val blacklist = SettingUtils.appNotifyBlacklist
        if (TextUtils.isEmpty(blacklist)) return false

        for (line in blacklist.split("\n")) {
            val keyword = line.trim()
            if (keyword.isEmpty()) continue
            try {
                val regex = Regex(keyword)
                if (regex.containsMatchIn(title) || regex.containsMatchIn(text)) {
                    return true
                }
            } catch (e: Exception) {
                //正则表达式非法时，降级为普通包含匹配
                Log.w(TAG, "黑名单关键词正则非法，降级为包含匹配：$keyword, ${e.message}")
                if (title.contains(keyword) || text.contains(keyword)) {
                    return true
                }
            }
        }
        return false
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Log.d(TAG, "Removed Package Name : ${sbn?.packageName}")
    }

}