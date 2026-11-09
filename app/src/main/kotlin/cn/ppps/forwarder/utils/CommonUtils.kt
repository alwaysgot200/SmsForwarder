package cn.ppps.forwarder.utils

import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import cn.ppps.forwarder.App
import cn.ppps.forwarder.App.Companion.APP_TAG_MAP
import cn.ppps.forwarder.App.Companion.BATTERY_TAG_MAP
import cn.ppps.forwarder.App.Companion.CALL_TAG_MAP
import cn.ppps.forwarder.App.Companion.COMMON_TAG_MAP
import cn.ppps.forwarder.App.Companion.LOCATION_TAG_MAP
import cn.ppps.forwarder.App.Companion.NETWORK_TAG_MAP
import cn.ppps.forwarder.App.Companion.SMS_TAG_MAP
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.webview.AgentWebActivity
import cn.ppps.forwarder.core.webview.AgentWebFragment
import cn.ppps.forwarder.entity.ImageInfo
import cn.ppps.forwarder.fragment.MarkdownFragment
import cn.ppps.forwarder.fragment.ServiceProtocolFragment
import cn.ppps.forwarder.service.NotificationService
import com.xuexiang.xpage.base.XPageFragment
import com.xuexiang.xpage.core.PageOption
import com.xuexiang.xui.utils.ColorUtils
import com.xuexiang.xui.widget.dialog.DialogLoader
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog.SingleButtonCallback
import com.xuexiang.xui.widget.imageview.preview.PreviewBuilder
import com.xuexiang.xutil.XUtil
import com.xuexiang.xutil.common.StringUtils
import com.xuexiang.xutil.resource.ResUtils.getColor
import com.xuexiang.xutil.resource.ResUtils.getDrawable
import com.xuexiang.xutil.resource.ResUtils.getString
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.regex.Pattern

/**
 * 常用工具类
 */
@Suppress("RegExpRedundantEscape", "unused", "RegExpUnnecessaryNonCapturingGroup")
class CommonUtils private constructor() {
    companion object {
        /**
         * 这里填写你的应用隐私政策网页地址
         */
        private const val PRIVACY_URL = "https://gitee.com/pp/SmsForwarder/raw/main/PRIVACY"

        /**
         * 显示隐私政策的提示
         *
         * @param context
         * @param submitListener 同意的监听
         * @return
         */
        @Suppress("SameParameterValue", "NAME_SHADOWING")
        @JvmStatic
        fun showPrivacyDialog(context: Context, submitListener: SingleButtonCallback?): Dialog {
            val dialog = MaterialDialog.Builder(context).title(R.string.title_reminder).autoDismiss(false).cancelable(false).positiveText(R.string.lab_agree).onPositive { dialog1: MaterialDialog, which: DialogAction? ->
                if (submitListener != null) {
                    submitListener.onClick(dialog1, which!!)
                } else {
                    dialog1.dismiss()
                }
            }.negativeText(R.string.lab_disagree).onNegative { dialog, _ ->
                dialog.dismiss()
                DialogLoader.getInstance().showConfirmDialog(
                    context, getString(R.string.title_reminder), String.format(
                        getString(R.string.content_privacy_explain_again), getString(R.string.app_name)
                    ), getString(R.string.lab_look_again), { dialog, _ ->
                        dialog.dismiss()
                        showPrivacyDialog(context, submitListener)
                    }, getString(R.string.lab_still_disagree)
                ) { dialog, _ ->
                    dialog.dismiss()
                    DialogLoader.getInstance().showConfirmDialog(
                        context, getString(R.string.content_think_about_it_again), getString(R.string.lab_look_again), { dialog, _ ->
                            dialog.dismiss()
                            showPrivacyDialog(context, submitListener)
                        }, getString(R.string.lab_exit_app)
                    ) { dialog, _ ->
                        dialog.dismiss()
                        XUtil.exitApp()
                    }
                }
            }.build()
            dialog.setContent(getPrivacyContent(context))
            //开始响应点击事件
            dialog.contentView!!.movementMethod = LinkMovementMethod.getInstance()
            dialog.show()
            return dialog
        }

        /**
         * @return 隐私政策说明
         */
        private fun getPrivacyContent(context: Context): SpannableStringBuilder {
            return SpannableStringBuilder().append("    ").append(getString(R.string.privacy_content_1)).append(" ").append(getString(R.string.app_name)).append("!\n").append("    ").append(getString(R.string.privacy_content_2)).append("    ").append(getString(R.string.privacy_content_3)).append(getPrivacyLink(context, PRIVACY_URL)).append(getString(R.string.privacy_content_4)).append("    ").append(getString(R.string.privacy_content_5)).append(getPrivacyLink(context, PRIVACY_URL)).append(getString(R.string.privacy_content_6)).append("    ").append(getString(R.string.privacy_content_7))
        }

        /**
         * @param context 隐私政策的链接
         * @return
         */
        @Suppress("SameParameterValue")
        private fun getPrivacyLink(context: Context, privacyUrl: String): SpannableString {
            val privacyName = String.format(
                getString(R.string.lab_privacy_name), getString(R.string.app_name)
            )
            val spannableString = SpannableString(privacyName)
            spannableString.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    goWeb(context, privacyUrl)
                }
            }, 0, privacyName.length, Spanned.SPAN_MARK_MARK)
            return spannableString
        }

        /**
         * 请求浏览器
         *
         * @param url
         */
        @JvmStatic
        fun goWeb(context: Context, url: String?) {
            val intent = Intent(context, AgentWebActivity::class.java)
            intent.putExtra(AgentWebFragment.KEY_URL, url)
            context.startActivity(intent)
        }

        /**
         * 打开用户协议和隐私协议
         *
         * @param fragment
         * @param isPrivacy   是否是隐私协议
         * @param isImmersive 是否沉浸式
         */
        @JvmStatic
        fun gotoProtocol(fragment: XPageFragment?, isPrivacy: Boolean, isImmersive: Boolean) {
            PageOption.to(ServiceProtocolFragment::class.java).putString(
                ServiceProtocolFragment.KEY_PROTOCOL_TITLE, if (isPrivacy) getString(R.string.title_privacy_protocol) else getString(
                    R.string.title_user_protocol
                )
            ).putBoolean(ServiceProtocolFragment.KEY_IS_IMMERSIVE, isImmersive).open(fragment!!)
        }

        /**
         * 是否是深色的颜色
         *
         * @param color
         * @return
         */
        @JvmStatic
        fun isColorDark(@ColorInt color: Int): Boolean {
            return ColorUtils.isColorDark(color, 0.382)
        }

        //焦点位置插入文本
        fun insertOrReplaceText2Cursor(editText: EditText, str: String) {
            if (TextUtils.isEmpty(str)) return

            editText.requestFocus()

            val editable = editText.text ?: return
            val textLength = editable.length
            var cursor = editText.selectionStart
            if (cursor < 0 || cursor > textLength) {
                cursor = textLength
            }

            editable.insert(cursor, str)
            val newCursor = (cursor + str.length).coerceAtMost(editable.length)
            editText.setSelection(newCursor)
        }

        //==========图片预览===========//
        /**
         * 大图预览
         *
         * @param fragment
         * @param url      图片资源
         * @param view     小图加载控件
         */
        fun previewPicture(fragment: Fragment?, url: String, view: View?) {
            if (fragment == null || StringUtils.isEmpty(url)) {
                return
            }
            val bounds = Rect()
            view?.getGlobalVisibleRect(bounds)
            PreviewBuilder.from(fragment).setImgs(ImageInfo.newInstance(url, bounds)).setCurrentIndex(0).setSingleFling(true).setProgressColor(R.color.xui_config_color_main_theme).setType(PreviewBuilder.IndicatorType.Number).start()
        }

        /**
         * 打开Markdown链接并渲染
         *
         * @param fragment
         * @param url   Markdown链接
         * @param isImmersive 是否沉浸式
         */
        @JvmStatic
        fun previewMarkdown(fragment: XPageFragment?, title: String, url: String, isImmersive: Boolean) {
            PageOption.to(MarkdownFragment::class.java).putString(MarkdownFragment.KEY_MD_TITLE, title).putString(MarkdownFragment.KEY_MD_URL, url).putBoolean(MarkdownFragment.KEY_IS_IMMERSIVE, isImmersive).open(fragment!!)
        }

        //检查自定义模板中的标签是否合法
        fun checkTemplateTag(template: String): String {
            val tagRegex = "\\{\\{[^#]+###([^=]+)===(.*?)\\}\\}".toRegex()
            tagRegex.findAll(template).forEach {
                try {
                    it.groupValues[1].toRegex()
                    //TODO:怎么测试反向引用是否正确？
                    /*val replacement = it.groupValues[2]
                    if (replacement.isNotEmpty()) {
                        "pppscn/SmsForwarder".replace(regex, replacement)
                    }*/
                } catch (e: Exception) {
                    return String.format(getString(R.string.invalid_tag), it.value, e.message)
                }
            }
            return ""
        }

        //是否合法的url
        fun checkUrl(url: String?, emptyResult: Boolean = false): Boolean {
            if (url.isNullOrEmpty()) return emptyResult

            val regex = Regex("^https?://\\S+\$")
            return regex.matches(url)
        }

        //是否合法的URL Scheme
        fun checkUrlScheme(url: String?, emptyResult: Boolean = false): Boolean {
            if (url.isNullOrEmpty()) return emptyResult

            val regex = Regex("^[a-zA-Z\\d]+://\\S+\$")
            return regex.matches(url)
        }

        //是否合法的IP地址
        fun checkIP(ip: String): String {
            if (TextUtils.isEmpty(ip)) return "Neither"

            if (ip.contains(".")) {
                val chunkIPv4 = "([\\d]|[1-9][\\d]|1[\\d][\\d]|2[0-4][\\d]|25[0-5])"
                val pattenIPv4 = Pattern.compile("^($chunkIPv4\\.){3}$chunkIPv4$")
                return if (pattenIPv4.matcher(ip).matches()) "IPv4" else "Neither"
            } else if (ip.contains(":")) {
                val chunkIPv6 = "([\\da-fA-F]{1,4})"
                val pattenIPv6 = Pattern.compile("^($chunkIPv6\\:){7}$chunkIPv6$")
                return if (pattenIPv6.matcher(ip).matches()) "IPv6" else "Neither"
            }
            return "Neither"
        }

        //是否合法的域名
        fun checkDomain(domain: String): Boolean {
            val pattenDomain = Pattern.compile("^(?=^.{3,255}$)(?:(?:(?:[a-zA-Z\\d]|[a-zA-Z\\d][a-zA-Z\\d\\-]*[a-zA-Z\\d])\\.){1,126}(?:[A-Za-z\\d]|[A-Za-z\\d][A-Za-z\\d\\-]*[A-Za-z\\d]))$")
            return pattenDomain.matcher(domain).matches()
        }

        //是否合法的端口号
        fun checkPort(port: String): Boolean {
            if (TextUtils.isEmpty(port)) return false
            val pattenPort = Pattern.compile("^((6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])|[0-5]?\\d{0,4})$")
            return pattenPort.matcher(port).matches()
        }

        fun checkEmail(email: String): Boolean {
            val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\$")
            return emailRegex.matches(email)
        }

        //是否启用通知监听服务
        fun isNotificationListenerServiceEnabled(context: Context): Boolean {
            val packageNames = NotificationManagerCompat.getEnabledListenerPackages(context)
            return packageNames.contains(context.packageName)
        }

        //开关通知监听服务
        fun toggleNotificationListenerService(context: Context) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                ComponentName(context.applicationContext, NotificationService::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context.applicationContext, NotificationService::class.java), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        }

    //==================== 保活：厂商后台设置跳转 ====================

    //获取当前手机品牌的"自启动"设置提示文案
    fun getAutoStartTips(): String {
        return when (Build.BRAND.lowercase(Locale.ROOT)) {
            "huawei" -> getString(R.string.auto_start_huawei)
            "honor" -> getString(R.string.auto_start_honor)
            "xiaomi" -> getString(R.string.auto_start_xiaomi)
            "redmi" -> getString(R.string.auto_start_redmi)
            "oppo" -> getString(R.string.auto_start_oppo)
            "vivo" -> getString(R.string.auto_start_vivo)
            "meizu" -> getString(R.string.auto_start_meizu)
            "samsung" -> getString(R.string.auto_start_samsung)
            "letv" -> getString(R.string.auto_start_letv)
            "smartisan" -> getString(R.string.auto_start_smartisan)
            else -> getString(R.string.auto_start_unknown)
        }
    }

    //Intent跳转到[自启动]页面全网最全适配机型解决方案
    private val autoStartMap = object : HashMap<String?, List<String?>?>() {
        init {
            put(
                "Xiaomi", listOf(
                    "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",  //MIUI10_9.8.1(9.0)
                    "com.miui.securitycenter"
                )
            )
            put(
                "samsung", listOf(
                    "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity", "com.samsung.android.sm_cn/com.samsung.android.sm.ui.appmanagement.AppManagementActivity", "com.samsung.android.sm_cn/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity", "com.samsung.android.sm_cn/.ui.ram.RamActivity", "com.samsung.android.sm_cn/.app.dashboard.SmartManagerDashBoardActivity", "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity", "com.samsung.android.sm/com.samsung.android.sm.ui.appmanagement.AppManagementActivity", "com.samsung.android.sm/com.samsung.android.sm.ui.cstyleboard.SmartManagerDashBoardActivity", "com.samsung.android.sm/.ui.ram.RamActivity", "com.samsung.android.sm/.app.dashboard.SmartManagerDashBoardActivity", "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity", "com.samsung.android.sm_cn", "com.samsung.android.sm"
                )
            )
            put(
                "HUAWEI", listOf(
                    "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",  //EMUI9.1.0(方舟,9.0)
                    "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity", "com.huawei.systemmanager/.optimize.process.ProtectActivity", "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity", "com.huawei.systemmanager" //最后一行可以写包名, 这样如果签名的类路径在某些新版本的ROM中没找到 就直接跳转到对应的安全中心/手机管家 首页.
                )
            )
            put(
                "vivo", listOf(
                    "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager", "com.iqoo.secure/.safeguard.PurviewTabActivity", "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",  //"com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity", //这是白名单, 不是自启动
                    "com.iqoo.secure", "com.vivo.permissionmanager"
                )
            )
            put(
                "Meizu", listOf(
                    "com.meizu.safe/.permission.SmartBGActivity",  //Flyme7.3.0(7.1.2)
                    "com.meizu.safe/.permission.PermissionMainActivity",  //网上的
                    "com.meizu.safe"
                )
            )
            put(
                "OPPO", listOf(
                    "com.coloros.safecenter/.startupapp.StartupAppListActivity", "com.coloros.safecenter/.permission.startup.StartupAppListActivity", "com.oppo.safe/.permission.startup.StartupAppListActivity", "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity", "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity", "com.coloros.safecenter", "com.oppo.safe", "com.coloros.oppoguardelf"
                )
            )
            put(
                "oneplus", listOf(
                    "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity", "com.oneplus.security"
                )
            )
            put(
                "letv", listOf(
                    "com.letv.android.letvsafe/.AutobootManageActivity", "com.letv.android.letvsafe/.BackgroundAppManageActivity",  //应用保护
                    "com.letv.android.letvsafe"
                )
            )
            put(
                "zte", listOf(
                    "com.zte.heartyservice/.autorun.AppAutoRunManager", "com.zte.heartyservice"
                )
            )

            //金立
            put(
                "F", listOf(
                    "com.gionee.softmanager/.MainActivity", "com.gionee.softmanager"
                )
            )

            //以下为未确定(厂商名也不确定)
            put(
                "smartisanos", listOf(
                    "com.smartisanos.security/.invokeHistory.InvokeHistoryActivity", "com.smartisanos.security"
                )
            )

            //360
            put(
                "360", listOf(
                    "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity", "com.yulong.android.coolsafe"
                )
            )

            //360
            put(
                "ulong", listOf(
                    "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity", "com.yulong.android.coolsafe"
                )
            )

            //酷派
            put(
                "coolpad" /*厂商名称不确定是否正确*/, listOf(
                    "com.yulong.android.security/com.yulong.android.seccenter.tabbarmain", "com.yulong.android.security"
                )
            )

            //联想
            put(
                "lenovo" /*厂商名称不确定是否正确*/, listOf(
                    "com.lenovo.security/.purebackground.PureBackgroundActivity", "com.lenovo.security"
                )
            )
            put(
                "htc" /*厂商名称不确定是否正确*/, listOf(
                    "com.htc.pitroad/.landingpage.activity.LandingPageActivity", "com.htc.pitroad"
                )
            )

            //华硕
            put(
                "asus" /*厂商名称不确定是否正确*/, listOf(
                    "com.asus.mobilemanager/.MainActivity", "com.asus.mobilemanager"
                )
            )
        }
    }

    //跳转自启动页面
    fun startToAutoStartSetting(context: Context) {
        Log.e("Util", "******************The current phone model is:" + Build.MANUFACTURER)
        val entries: MutableSet<MutableMap.MutableEntry<String?, List<String?>?>> = autoStartMap.entries
        var has = false
        for ((manufacturer, actCompatList) in entries) {
            if (Build.MANUFACTURER.equals(manufacturer, ignoreCase = true)) {
                if (actCompatList != null) {
                    for (act in actCompatList) {
                        try {
                            var intent: Intent?
                            if (act?.contains("/") == true) {
                                intent = Intent()
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                val componentName = ComponentName.unflattenFromString(act)
                                intent.component = componentName
                            } else {
                                //找不到? 网上的做法都是跳转到设置... 这基本上是没意义的 基本上自启动这个功能是第三方厂商自己写的安全管家类app
                                //所以我是直接跳转到对应的安全管家/安全中心
                                intent = act?.let { context.packageManager.getLaunchIntentForPackage(it) }
                            }
                            context.startActivity(intent)
                            has = true
                            break
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e("Util", "******************e:" + e.message)
                        }
                    }
                }
            }
        }
        if (!has) {
            XToastUtils.info(R.string.tips_compatible_solution)
            try {
                val intent = Intent()
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                intent.data = Uri.fromParts("package", context.packageName, null)
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("Util", "******************e:" + e.message)
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    //是否小米系（MIUI/HyperOS 划卡清理为力停级查杀，其省电策略对保活影响最大）
    fun isXiaomiFamily(): Boolean {
        return Build.BRAND.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi") || Build.MANUFACTURER.lowercase(Locale.ROOT) == "xiaomi"
    }

    //跳转 MIUI/HyperOS [省电策略] 配置页，将本应用的省电策略改为"无限制"
    fun startToPowerSaveSetting(context: Context) {
        try {
            val label = try {
                val appInfo = context.applicationInfo
                context.packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                context.packageName
            }
            val intent = Intent()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            intent.putExtra("package_name", context.packageName)
            intent.putExtra("package_label", label)
            context.startActivity(intent)
        } catch (e: Exception) {
            //页面不存在（非小米系统或新版本更名）时退回安全中心首页
            e.printStackTrace()
            try {
                val launch = context.packageManager.getLaunchIntentForPackage("com.miui.securitycenter")
                if (launch != null) {
                    context.startActivity(launch)
                } else {
                    startToAutoStartSetting(context)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                startToAutoStartSetting(context)
            }
        }
    }


        //获取本机IP地址
        fun getIPAddresses(): List<String> {
            val ipAddresses = mutableListOf<String>()

            try {
                val networkInterfaces = NetworkInterface.getNetworkInterfaces()

                while (networkInterfaces.hasMoreElements()) {
                    val networkInterface = networkInterfaces.nextElement()
                    val addresses = networkInterface.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        if (address is Inet4Address || address is Inet6Address) {
                            address.hostAddress?.let { ipAddresses.add(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("CommonUtils", "getIPAddresses: ${e.message}")
            }

            return ipAddresses
        }

        fun removeInterfaceFromIP(ipAddress: String): String {
            val index = ipAddress.indexOf("%")
            return if (index != -1) {
                ipAddress.substring(0, index)
            } else {
                ipAddress
            }
        }

        fun restartApplication() {
            val context = App.context
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component
            val mainIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(mainIntent)
            XUtil.exitApp()
        }

        // 动态创建标签按钮并设置点击事件(将标签插入指定输入框)
        fun createTagButtons(context: Context, gridLayout: GridLayout, editText: EditText, scene: String = "basic", excludeButtons: Array<String> = emptyArray()) {
            // 将排除的按钮转换成一个集合，方便查找
            val excludeSet = excludeButtons.toSet()

            // 清空GridLayout中的所有视图
            gridLayout.removeAllViews()

            // 根据场景动态拼接所有按钮数据
            val allButtons = when (scene) {
                "sms" -> SMS_TAG_MAP
                "call" -> CALL_TAG_MAP
                "app" -> APP_TAG_MAP
                else -> CALL_TAG_MAP + SMS_TAG_MAP + APP_TAG_MAP
            }.toMutableMap()

            if (SettingUtils.enableLocation) {
                allButtons += LOCATION_TAG_MAP
            }
            if (scene == "all") {
                allButtons += BATTERY_TAG_MAP
                allButtons += NETWORK_TAG_MAP
            }
            allButtons += COMMON_TAG_MAP

            val btnBackground = getDrawable(R.drawable.rounded_button)
            val btnTextColor = getColor(android.R.color.white)

            // 遍历所有按钮数据，过滤掉需要排除的按钮
            allButtons.forEach { (tag, lable) ->
                if (excludeSet.isNotEmpty() && excludeSet.contains(tag)) {
                    return@forEach
                }

                val button = TextView(context).apply {
                    text = lable
                    setOnClickListener {
                        insertOrReplaceText2Cursor(editText, tag)
                    }

                    // 设置紧凑样式
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(5, 5, 5, 5)
                    gravity = android.view.Gravity.CENTER
                    background = btnBackground
                    setTextColor(btnTextColor)

                    // 布局参数
                    layoutParams = GridLayout.LayoutParams().apply {
                        height = GridLayout.LayoutParams.WRAP_CONTENT
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            width = 0
                            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        } else {
                            width = GridLayout.LayoutParams.WRAP_CONTENT
                        }
                        setMargins(8, 8, 8, 8)
                    }
                }

                gridLayout.addView(button)
            }

        }

    }


    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
