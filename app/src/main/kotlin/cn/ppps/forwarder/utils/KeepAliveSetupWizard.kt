package cn.ppps.forwarder.utils

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.service.SmsConfirmAccessibilityService
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog

/**
 * 首次启动保活设置向导：保活高于一切。
 *
 * 能自动授权的（精确闹钟 13+ 随安装自动授予、WAKE_LOCK 等 install-time 权限）
 * 在安装时已自动生效；此向导只承载剩余必须人工操作的设置，
 * 已完成的项目自动检测跳过，每步"一键直达"对应设置页，总步骤最少。
 *
 * 步骤按保活权重排序：忽略电池优化 → 精确闹钟 → 厂商自启动 → 厂商省电策略 →
 * 通知使用权（系统强绑定锚点）→ 小米反诈确认自动点击（仅小米系，可检测）→
 * 验证码安全保护关闭（国产系，"去过即算"）→ 最近任务加锁说明。
 * 安装首启强制逐步引导：对话框不可取消，每步必须逐一点击"去设置/跳过此步"才能推进，
 * 无法一键跳过整个向导；从设置页返回后自动进入下一步（[onResumed] 驱动）。
 * 向导完成后由 [auditInBackground] 每 24 小时自动复查全部设置（无需打开主窗口），
 * 可编程判定项（电池优化/精确闹钟/通知使用权/小米反诈点击）回归缺省时发通知引导补充；
 * 可检测项在主窗口审计（[auditIfNeeded]）中同样按实时状态重新出现。
 * 后续若仍发生"被杀未复苏"，由 [cn.ppps.forwarder.activity.MainActivity] 的心跳检测引导兜底。
 */
object KeepAliveSetupWizard {

    //步骤常量（持久化用，不可变更）
    private const val STEP_BATTERY = 1
    private const val STEP_EXACT_ALARM = 2
    private const val STEP_AUTOSTART = 3
    private const val STEP_POWER_SAVE = 4
    private const val STEP_NOTIFY_LISTEN = 5
    private const val STEP_LOCK_TASK = 6
    private const val STEP_SMS_CTA = 7
    private const val STEP_SMS_CODE_PROTECT = 8

    private const val SP_SETUP_DONE = "keepalive_setup_wizard_done"
    private const val SP_SETUP_VISITED = "keepalive_setup_wizard_visited"
    private const val SP_LAST_AUDIT = "keepalive_audit_ts"

    //后台自动审计时间戳（[auditInBackground] 用，与主窗口审计分开节流）
    private const val SP_LAST_BG_AUDIT = "keepalive_bg_audit_ts"

    //缺项审计周期：向导完成后每隔该时长复查一次（打开主窗口 [auditIfNeeded] /
    //后台 [auditInBackground] 两条路径触发），发现缺项立即提醒
    private const val AUDIT_INTERVAL_MS = 24 * 60 * 60 * 1000L

    //后台审计缺项提醒通知
    private const val AUDIT_NOTIFY_ID = 0x1012
    private const val AUDIT_CHANNEL_ID = "cn.ppps.forwarder.keepalive_audit"
    private const val AUDIT_CHANNEL_NAME = "保活设置检查提醒"

    //可编程判定的设置项：跳过后若仍缺省，审计时按实时状态重新出现引导补充；
    //不可检测的厂商项（自启动/省电策略/验证码保护/最近任务锁）"去过即算"，不再重复打扰
    private val DETECTABLE_STEPS = setOf(STEP_BATTERY, STEP_EXACT_ALARM, STEP_NOTIFY_LISTEN, STEP_SMS_CTA)

    //从"去设置"跳出后置位，回到应用时据此推进下一步
    private var waitingReturn = false

    //向导/审计对话框是否正在显示（避免 onResume 重复弹窗堆叠）
    private var dialogOpen = false

    //本轮是否为审计模式（可检测项忽略"去过"标记按实时状态重新出现），onResumed 沿用
    private var auditMode = false

    //本轮已处理（去设置/跳过）的步骤：审计模式下可检测项忽略"去过"标记，需按轮去重避免重复弹窗
    private val roundHandled = mutableSetOf<Int>()

    private data class Step(
        val id: Int,
        val titleRes: Int,
        val messageRes: Int,
        val goRes: Int,
        val go: (Activity) -> Unit
    )

    fun isPending(activity: Activity): Boolean {
        if (SettingUtils.enablePureClientMode) return false
        var done: Boolean by SharedPreference(SP_SETUP_DONE, false)
        return !done
    }

    /** 首启/未完成时进入向导；在权限授权流程结束后调用 */
    fun maybeRun(activity: Activity) {
        if (!isPending(activity)) return
        showNextPending(activity, audit = false, newRound = true)
    }

    /** 从厂商设置页返回后驱动下一步（向导与审计两条路径共用，不检查 done 标记） */
    fun onResumed(activity: Activity) {
        if (!waitingReturn) return
        waitingReturn = false
        showNextPending(activity)
    }

    /**
     * 保活缺项审计：打开主窗口时调用。
     * - 静默自愈：前台服务不在运行时直接拉起（无弹窗，每次都检查）；
     * - 缺项弹窗：向导完成后每 [AUDIT_INTERVAL_MS] 复查一次，步骤按实时状态动态构建，
     *   电池优化/精确闹钟/通知使用权等"可检测项"回归缺省时重新出现并提示用户设置。
     *   厂商自启动/省电策略属于"不可编程检测"项，去过一次即视为已配置，其回归缺省
     *   只能通过心跳断档检测（被杀未复苏引导）发现。
     */
    fun auditIfNeeded(activity: Activity) {
        if (SettingUtils.enablePureClientMode) return

        //静默自愈：前台服务被回收/查杀时直接拉起
        if (!cn.ppps.forwarder.service.ForegroundService.isRunning) {
            cn.ppps.forwarder.receiver.KeepAliveReceiver.restartForegroundService(activity)
        }

        if (dialogOpen || waitingReturn) return

        //首启向导未完成：不限流，继续引导
        if (isPending(activity)) {
            showNextPending(activity, audit = false, newRound = true)
            return
        }

        //向导已完成：限流复查缺项（可检测项按实时状态重新出现）
        var lastAudit: Long by SharedPreference(SP_LAST_AUDIT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAudit < AUDIT_INTERVAL_MS) return
        lastAudit = now
        showNextPending(activity, audit = true, newRound = true)
    }

    /**
     * 24 小时后台自动检测：由 [cn.ppps.forwarder.receiver.KeepAliveReceiver] 看门狗触发，
     * 无需打开主窗口。检测所有可编程判定的条件设置（电池优化/精确闹钟/通知使用权/小米反诈自动点击），
     * 发现缺项时发通知引导用户点击补充；同时重置主窗口审计节流，点开应用立即弹出缺项向导。
     */
    fun auditInBackground(context: Context) {
        if (SettingUtils.enablePureClientMode) return

        var lastAudit: Long by SharedPreference(SP_LAST_BG_AUDIT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastAudit < AUDIT_INTERVAL_MS) return
        lastAudit = now

        val missing = missingDetectableSteps(context)
        if (missing.isEmpty()) return

        //重置主窗口审计节流：点开通知进入主窗口时立即弹出缺项补充向导
        var lastUiAudit: Long by SharedPreference(SP_LAST_AUDIT, 0L)
        lastUiAudit = 0L

        postAuditNotification(context, missing)
    }

    /** 可编程判定的缺项（无需 Activity），返回缺失步骤的标题资源 */
    private fun missingDetectableSteps(context: Context): List<Int> {
        val missing = mutableListOf<Int>()
        if (!KeepAliveUtils.isIgnoreBatteryOptimization(context)) {
            missing.add(R.string.setup_wizard_battery_title)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                missing.add(R.string.setup_wizard_exact_alarm_title)
            }
        }
        if (!CommonUtils.isNotificationListenerServiceEnabled(context)) {
            missing.add(R.string.setup_wizard_notify_listen_title)
        }
        if (CommonUtils.isXiaomiFamily() && !(SettingUtils.enableAutoConfirmSend && SmsConfirmAccessibilityService.isEnabled(context))) {
            missing.add(R.string.setup_wizard_sms_cta_title)
        }
        return missing
    }

    /** 缺项提醒通知：点击进入主窗口，由 [auditIfNeeded] 立即弹出补充向导 */
    private fun postAuditNotification(context: Context, missingTitleRes: List<Int>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(AUDIT_CHANNEL_ID, AUDIT_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val names = missingTitleRes.joinToString("、") { context.getString(it) }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AUDIT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentTitle(context.getString(R.string.keepalive_audit_notify_title))
            .setContentText(context.getString(R.string.keepalive_audit_notify_content, missingTitleRes.size, names))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(AUDIT_NOTIFY_ID, notification)
    }

    private fun buildSteps(activity: Activity): List<Step> = buildList {
        //1 忽略电池优化：防熄屏休眠断网，保活基石（系统授权框，点一下即可）
        if (!KeepAliveUtils.isIgnoreBatteryOptimization(activity)) {
            add(
                Step(STEP_BATTERY, R.string.setup_wizard_battery_title, R.string.setup_wizard_battery_message, R.string.setup_wizard_go) {
                    KeepAliveUtils.ignoreBatteryOptimization(it)
                }
            )
        }
        //2 精确闹钟：Android 13+ 已随安装自动授予（USE_EXACT_ALARM），通常自动跳过
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                add(
                    Step(STEP_EXACT_ALARM, R.string.setup_wizard_exact_alarm_title, R.string.setup_wizard_exact_alarm_message, R.string.setup_wizard_go) {
                        it.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                )
            }
        }
        //3 厂商自启动：划卡存活的开关（不可编程检测，"去过即算完成"）
        add(
            Step(STEP_AUTOSTART, R.string.setup_wizard_autostart_title, R.string.setup_wizard_autostart_message, R.string.setup_wizard_go) {
                CommonUtils.startToAutoStartSetting(it)
            }
        )
        //4 厂商省电策略/后台权限：各定制系统都有对应页面（小米/华为/荣耀/OPPO系/vivo系/三星/魅族），
        //跳转失败时自动多级兜底；非定制系统（原生类）不占步骤
        if (CommonUtils.isDomesticOem()) {
            add(
                Step(STEP_POWER_SAVE, R.string.setup_wizard_power_save_title, R.string.setup_wizard_power_save_message, R.string.setup_wizard_go) {
                    CommonUtils.startToPowerSaveSetting(it)
                }
            )
        }
        //5 通知使用权：APP 通知转发功能 + 系统直接绑定的强保活锚点
        if (!CommonUtils.isNotificationListenerServiceEnabled(activity)) {
            add(
                Step(STEP_NOTIFY_LISTEN, R.string.setup_wizard_notify_listen_title, R.string.setup_wizard_notify_listen_message, R.string.setup_wizard_go) {
                    it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
        }
        //6 小米反诈确认弹窗：小米/澎湃对"非验证码"短信发送强制人工确认（CTA 反诈要求，系统级）。
        //首选出路是开启本应用的无障碍"自动确认发送"（可编程检测，开启后此步自动跳过、
        //日后被关闭时 24h 审计会重新出现）；不想开启可关"启用系统优化"或改用网络通道
        if (CommonUtils.isXiaomiFamily() && !(SettingUtils.enableAutoConfirmSend && SmsConfirmAccessibilityService.isEnabled(activity))) {
            add(
                Step(STEP_SMS_CTA, R.string.setup_wizard_sms_cta_title, R.string.setup_wizard_sms_cta_message, R.string.setup_wizard_sms_cta_go) {
                    //视同用户已选择启用该功能，无障碍授权完成后即自动生效（设置页开关同步打开）
                    SettingUtils.enableAutoConfirmSend = true
                    it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
        }
        //7 验证码安全保护：部分国产系统该开关开启时会禁止第三方应用读取短信验证码，
        //导致验证码类短信无法转发。各系统路径不一且不可编程检测，跳到默认短信应用"去过即算完成"
        if (CommonUtils.isDomesticOem()) {
            add(
                Step(STEP_SMS_CODE_PROTECT, R.string.setup_wizard_code_protect_title, R.string.setup_wizard_code_protect_message, R.string.setup_wizard_code_protect_go) {
                    val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(it)
                    val launch = if (defaultSmsPackage.isNullOrEmpty()) {
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    } else {
                        it.packageManager.getLaunchIntentForPackage(defaultSmsPackage) ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    }
                    it.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            )
        }
        //8 收尾说明：最近任务加锁（无对应设置页可跳，纯指引）
        add(
            Step(STEP_LOCK_TASK, R.string.setup_wizard_lock_task_title, R.string.setup_wizard_lock_task_message, R.string.setup_wizard_ok) {
            }
        )
    }

    private fun showNextPending(activity: Activity, audit: Boolean = auditMode, newRound: Boolean = false) {
        auditMode = audit
        if (newRound) roundHandled.clear()
        val steps = buildSteps(activity)
        val visited = loadVisited(activity)
        //向导模式：按"去过"标记逐个推进，每步必须逐一点击处理（强制引导）；
        //审计模式：可检测项忽略"去过"标记按实时状态重新出现，本轮已处理的不重复弹
        val pending = steps.filter {
            (!visited.contains(it.id) || (audit && DETECTABLE_STEPS.contains(it.id))) && !roundHandled.contains(it.id)
        }

        if (pending.isEmpty()) {
            finishWizard(activity)
            return
        }

        val step = pending.first()
        val content = StringBuilder(activity.getString(step.messageRes))
        if (step.id == STEP_AUTOSTART) {
            content.append("\n\n").append(CommonUtils.getAutoStartTips())
        }
        content.append("\n\n").append(activity.getString(R.string.setup_wizard_remain_hint, pending.size))

        dialogOpen = true
        MaterialDialog.Builder(activity)
            .title(step.titleRes)
            .content(content)
            .positiveText(step.goRes)
            .onPositive { _: MaterialDialog?, _: DialogAction? ->
                dialogOpen = false
                markVisited(activity, step.id)
                roundHandled.add(step.id)
                if (step.id == STEP_LOCK_TASK) {
                    //收尾步骤无跳转，直接结束
                    finishWizard(activity)
                } else {
                    waitingReturn = true
                    step.go(activity)
                }
            }
            .negativeText(R.string.setup_wizard_skip_step)
            .onNegative { _: MaterialDialog?, _: DialogAction? ->
                //跳过此步：仅跳过当前步骤，继续强制引导下一步（不可一键跳过全部）；
                //可检测项若仍缺省会在下轮审计重新提醒，厂商不可检测项由心跳断档检测兜底
                dialogOpen = false
                markVisited(activity, step.id)
                roundHandled.add(step.id)
                showNextPending(activity)
            }
            .cancelable(false)
            .show()
    }

    private fun finishWizard(activity: Activity) {
        var done: Boolean by SharedPreference(SP_SETUP_DONE, false)
        done = true
        Log.i(KeepAliveSetupWizard::class.java.simpleName, "保活设置向导完成")
    }

    private fun loadVisited(activity: Activity): MutableSet<Int> {
        var raw: String by SharedPreference(SP_SETUP_VISITED, "")
        return raw.split(",").mapNotNull { it.toIntOrNull() }.toMutableSet()
    }

    private fun markVisited(activity: Activity, stepId: Int) {
        val visited = loadVisited(activity)
        visited.add(stepId)
        var raw: String by SharedPreference(SP_SETUP_VISITED, "")
        raw = visited.joinToString(",")
    }

}
