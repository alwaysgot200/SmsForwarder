package cn.ppps.forwarder

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.multidex.MultiDex
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.database.AppDatabase
import cn.ppps.forwarder.database.repository.FrpcRepository
import cn.ppps.forwarder.database.repository.LogsRepository
import cn.ppps.forwarder.database.repository.MsgRepository
import cn.ppps.forwarder.database.repository.RuleRepository
import cn.ppps.forwarder.database.repository.SenderRepository
import cn.ppps.forwarder.database.repository.TaskRepository
import cn.ppps.forwarder.entity.SimInfo
import cn.ppps.forwarder.receiver.BatteryReceiver
import cn.ppps.forwarder.receiver.BluetoothReceiver
import cn.ppps.forwarder.receiver.LockScreenReceiver
import cn.ppps.forwarder.receiver.NetworkChangeReceiver
import cn.ppps.forwarder.receiver.SimStateReceiver
import cn.ppps.forwarder.receiver.MmsDbObserver
import cn.ppps.forwarder.receiver.SmsDbObserver
import cn.ppps.forwarder.service.BluetoothScanService
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.service.HttpServerService
import cn.ppps.forwarder.service.LocationService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.AppInfo
import cn.ppps.forwarder.utils.DuplicateFilter
import cn.ppps.forwarder.utils.FRPC_LIB_VERSION
import cn.ppps.forwarder.utils.HistoryUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.KeepAliveUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.ProximitySensorScreenHelper
import cn.ppps.forwarder.utils.SecurityDefaults
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SharedPreference
import cn.ppps.forwarder.utils.sdkinit.UMengInit
import cn.ppps.forwarder.utils.sdkinit.XBasicLibInit
import cn.ppps.forwarder.utils.sdkinit.XUpdateInit
import cn.ppps.forwarder.utils.tinker.TinkerLoadLibrary
import cn.ppps.forwarder.workers.KeepAliveWorker
import com.hjq.language.MultiLanguages
import com.hjq.language.OnLanguageListener
import com.king.location.LocationClient
import com.xuexiang.xutil.file.FileUtils
import frpclib.Frpclib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class App : Application(), Configuration.Provider by Core {

    val applicationScope = CoroutineScope(SupervisorJob())
    val database by lazy { AppDatabase.getInstance(this) }
    val frpcRepository by lazy { FrpcRepository(database.frpcDao()) }
    val msgRepository by lazy { MsgRepository(database.msgDao()) }
    val logsRepository by lazy { LogsRepository(database.logsDao()) }
    val ruleRepository by lazy { RuleRepository(database.ruleDao()) }
    val senderRepository by lazy { SenderRepository(database.senderDao()) }
    val taskRepository by lazy { TaskRepository(database.taskDao()) }

    companion object {
        const val TAG: String = "SmsForwarder"

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

        //自定义模板可用变量标签
        var COMMON_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var SMS_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var CALL_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var APP_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var LOCATION_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var BATTERY_TAG_MAP: MutableMap<String, String> = mutableMapOf()
        var NETWORK_TAG_MAP: MutableMap<String, String> = mutableMapOf()

        //通话类型：1.来电挂机 2.去电挂机 3.未接来电 4.来电提醒 5.来电接通 6.去电拨出
        var CALL_TYPE_MAP: MutableMap<String, String> = mutableMapOf()
        var FILED_MAP: MutableMap<String, String> = mutableMapOf()
        var CHECK_MAP: MutableMap<String, String> = mutableMapOf()
        var SIM_SLOT_MAP: MutableMap<String, String> = mutableMapOf()
        var FORWARD_STATUS_MAP: MutableMap<Int, String> = mutableMapOf()
        var BARK_LEVEL_MAP: MutableMap<String, String> = mutableMapOf()
        var BARK_ENCRYPTION_ALGORITHM_MAP: MutableMap<String, String> = mutableMapOf()

        //已插入SIM卡信息
        var SimInfoList: MutableMap<Int, SimInfo> = mutableMapOf()

        //已安装App信息
        var LoadingAppList = false
        var UserAppList: MutableList<AppInfo> = mutableListOf()
        var SystemAppList: MutableList<AppInfo> = mutableListOf()

        /**
         * @return 当前app是否是调试开发模式
         */
        var isDebug: Boolean = BuildConfig.DEBUG

        //Location相关
        val LocationClient by lazy { LocationClient(context) }
        val Geocoder by lazy { Geocoder(context) }
        val DateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

        //Frpclib是否已经初始化
        var FrpclibInited = false

        //是否需要在拼接字符串时添加空格
        var isNeedSpaceBetweenWords = false
    }

    override fun attachBaseContext(base: Context) {
        //super.attachBaseContext(base)
        // 绑定语种
        super.attachBaseContext(MultiLanguages.attach(base))
        //解决4.x运行崩溃的问题
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        // 设置全局异常捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()
            try {
                val logPath = this.cacheDir.absolutePath + "/logs"
                val logDir = File(logPath)
                if (!logDir.exists()) logDir.mkdirs()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                val logFile = File(logPath, "crash_$currentDateTime.txt")
                BufferedWriter(FileWriter(logFile, true)).use { writer ->
                    writer.append("$throwable\n")
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            //使用默认的处理方式让APP停止运行
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            context = applicationContext
            initLibs()

            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //初始化WorkManager
            WorkManager.initialize(this, Configuration.Builder().build())

            //动态加载FrpcLib
            val libPath = filesDir.absolutePath + "/libs"
            val soFile = File(libPath)
            if (soFile.exists()) {
                try {
                    TinkerLoadLibrary.installNativeLibraryPath(classLoader, soFile)
                    FrpclibInited = FileUtils.isFileExists(filesDir.absolutePath + "/libs/libgojni.so") && FRPC_LIB_VERSION == Frpclib.getVersion()
                } catch (throwable: Throwable) {
                    Log.e("APP", throwable.message.toString())
                }
            }

            //启动前台服务
            val foregroundServiceIntent = Intent(this, ForegroundService::class.java)
            foregroundServiceIntent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(foregroundServiceIntent)
            } else {
                startService(foregroundServiceIntent)
            }

            //启动保活看门狗心跳：进程被厂商系统查杀后由系统侧闹钟周期唤醒自愈
            //（灭屏时直接用短周期：开机广播拉起阶段多处于灭屏，厂商查杀风险最高）
            KeepAliveUtils.scheduleAdaptiveWatchdog(this)

            //WorkManager 周期看门狗：与闹钟看门狗互为冗余，重启/覆盖更新后自动恢复（KEEP 策略避免重复调度）
            val keepAliveRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(KeepAliveUtils.WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(KeepAliveWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, keepAliveRequest)

            //启动HttpServer
            //"校验签名"模式下密钥为空时拒绝自启，避免开机即处于未认证状态
            if (HttpServerUtils.enableServerAutorun && (HttpServerUtils.safetyMeasures != 1 || HttpServerUtils.isSignKeyConfigured())) {
                Intent(this, HttpServerService::class.java).also {
                    startService(it)
                }
            }

            //启动LocationService
            if (SettingUtils.enableLocation) {
                val locationServiceIntent = Intent(this, LocationService::class.java)
                locationServiceIntent.action = ACTION_START
                startService(locationServiceIntent)
            }

            //监听电量&充电状态变化
            val batteryReceiver = BatteryReceiver()
            val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, batteryFilter)

            //监听SIM卡状态变化（SIM_STATE_CHANGED 自 Android 8 起不再投递给 manifest 声明的接收器，必须动态注册）
            registerReceiver(SimStateReceiver(), IntentFilter("android.intent.action.SIM_STATE_CHANGED"))

            //监听蓝牙状态变化
            val bluetoothReceiver = BluetoothReceiver()
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
                addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            registerReceiver(bluetoothReceiver, filter)
            if (SettingUtils.enableBluetooth) {
                val bluetoothScanServiceIntent = Intent(this, BluetoothScanService::class.java)
                bluetoothScanServiceIntent.action = ACTION_START
                startService(bluetoothScanServiceIntent)
            }

            //监听网络变化
            val networkReceiver = NetworkChangeReceiver()
            val networkFilter = IntentFilter().apply {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                //addAction("android.intent.action.DATA_CONNECTION_STATE_CHANGED")
            }
            registerReceiver(networkReceiver, networkFilter)

            //监听锁屏&解锁
            val lockScreenReceiver = LockScreenReceiver()
            val lockScreenFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(lockScreenReceiver, lockScreenFilter)

            //监听短信库变更，兜底捕获不触发 SMS_RECEIVED 广播的短信（如系统"智能信息"通道写入的短信）
            SmsDbObserver(applicationContext).register()
            //监听彩信库变更，转发彩信的文字部分（用户需求：只要文字，不要图片/多媒体）
            MmsDbObserver(applicationContext).register()
            //靠近听筒关屏
            ProximitySensorScreenHelper.refresh(this)

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "onCreate: $e")
        }
    }

    /**
     * 初始化基础库
     */
    private fun initLibs() {
        Core.init(this)
        // 配置文件初始化
        SharedPreference.init(applicationContext)
        // 服务端安全默认值迁移，必须早于任何服务端配置读取
        SecurityDefaults.migrate()
        // X系列基础库初始化
        XBasicLibInit.init(this)
        // 初始化日志打印
        isDebug = SettingUtils.enableDebugMode
        Log.init(applicationContext)
        // 转发历史工具类初始化
        HistoryUtils.init(applicationContext)
        // 一次性清理旧版本遗留的去重存储键（升级后首次启动执行）
        DuplicateFilter.migrateLegacy()
        // 版本更新初始化
        XUpdateInit.init(this)
        // 运营统计数据
        UMengInit.init(this)
        // 初始化语种切换框架
        MultiLanguages.init(this)
        // 设置语种变化监听器
        MultiLanguages.setOnLanguageListener(object : OnLanguageListener {
            override fun onAppLocaleChange(oldLocale: Locale, newLocale: Locale) {
                // 注意：只有setAppLanguage时触发，clearAppLanguage时不触发
                Log.i(TAG, "监听到应用切换了语种，旧语种：$oldLocale，新语种：$newLocale")
                switchLanguage(newLocale)
            }

            override fun onSystemLocaleChange(oldLocale: Locale, newLocale: Locale) {
                Log.i(TAG, "监听到系统切换了语种，旧语种：$oldLocale，新语种：$newLocale")
                switchLanguage(newLocale)
                /*val isFlowSystem = SettingUtils.isFlowSystemLanguage //MultiLanguages.isSystemLanguage(context)取值不对，一直是false
                Log.i(TAG, "监听到系统切换了语种，旧语种：$oldLocale，新语种：$newLocale，是否跟随系统：$isFlowSystem")
                if (isFlowSystem) {
                    CommonUtils.switchLanguage(oldLocale, newLocale)
                }*/
            }
        })
        switchLanguage(MultiLanguages.getAppLanguage(this))
    }

    //多语言切换时枚举常量自动切换语言
    private fun switchLanguage(newLocale: Locale) {
        isNeedSpaceBetweenWords = !newLocale.language.contains("zh")

        //自定义模板可用变量标签
        COMMON_TAG_MAP.clear()
        COMMON_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_receive_time) to getString(R.string.insert_tag_receive_time),
                getString(R.string.tag_current_time) to getString(R.string.insert_tag_current_time),
                getString(R.string.tag_device_name) to getString(R.string.insert_tag_device_name),
                getString(R.string.tag_app_version) to getString(R.string.insert_tag_app_version),
            )
        )
        SMS_TAG_MAP.clear()
        SMS_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_from) to getString(R.string.insert_tag_from),
                getString(R.string.tag_sms) to getString(R.string.insert_tag_sms),
                getString(R.string.tag_card_slot) to getString(R.string.insert_tag_card_slot),
                getString(R.string.tag_card_subid) to getString(R.string.insert_tag_card_subid),
                getString(R.string.tag_contact_name) to getString(R.string.insert_tag_contact_name),
                getString(R.string.tag_phone_area) to getString(R.string.insert_tag_phone_area),
                getString(R.string.tag_rule_title) to getString(R.string.insert_tag_rule_title),
            )
        )
        CALL_TAG_MAP.clear()
        CALL_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_from) to getString(R.string.insert_tag_from),
                getString(R.string.tag_sms) to getString(R.string.insert_tag_msg),
                getString(R.string.tag_card_slot) to getString(R.string.insert_tag_card_slot),
                getString(R.string.tag_card_subid) to getString(R.string.insert_tag_card_subid),
                getString(R.string.tag_call_type) to getString(R.string.insert_tag_call_type),
                getString(R.string.tag_contact_name) to getString(R.string.insert_tag_contact_name),
                getString(R.string.tag_phone_area) to getString(R.string.insert_tag_phone_area),
                getString(R.string.tag_rule_title) to getString(R.string.insert_tag_rule_title),
            )
        )
        APP_TAG_MAP.clear()
        APP_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_uid) to getString(R.string.insert_tag_uid),
                getString(R.string.tag_package_name) to getString(R.string.insert_tag_package_name),
                getString(R.string.tag_app_name) to getString(R.string.insert_tag_app_name),
                getString(R.string.tag_title) to getString(R.string.insert_tag_title),
                getString(R.string.tag_msg) to getString(R.string.insert_tag_msg),
                getString(R.string.tag_rule_title) to getString(R.string.insert_tag_rule_title),
            )
        )
        LOCATION_TAG_MAP.clear()
        LOCATION_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_location) to getString(R.string.insert_tag_location),
                getString(R.string.tag_location_longitude) to getString(R.string.insert_tag_location_longitude),
                getString(R.string.tag_location_latitude) to getString(R.string.insert_tag_location_latitude),
                getString(R.string.tag_location_address) to getString(R.string.insert_tag_location_address),
            )
        )
        BATTERY_TAG_MAP.clear()
        BATTERY_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_battery_pct) to getString(R.string.insert_tag_battery_pct),
                getString(R.string.tag_battery_status) to getString(R.string.insert_tag_battery_status),
                getString(R.string.tag_battery_plugged) to getString(R.string.insert_tag_battery_plugged),
                getString(R.string.tag_battery_info) to getString(R.string.insert_tag_battery_info),
                getString(R.string.tag_battery_info_simple) to getString(R.string.insert_tag_battery_info_simple),
            )
        )
        NETWORK_TAG_MAP.clear()
        NETWORK_TAG_MAP.putAll(
            mapOf(
                getString(R.string.tag_ipv4) to getString(R.string.insert_tag_ipv4),
                getString(R.string.tag_ipv6) to getString(R.string.insert_tag_ipv6),
                getString(R.string.tag_ip_list) to getString(R.string.insert_tag_ip_list),
                getString(R.string.tag_net_type) to getString(R.string.insert_tag_net_type),
            )
        )

        CALL_TYPE_MAP.clear()
        CALL_TYPE_MAP.putAll(
            mapOf(
                //"0" to getString(R.string.unknown_call),
                "1" to getString(R.string.incoming_call_ended),
                "2" to getString(R.string.outgoing_call_ended),
                "3" to getString(R.string.missed_call),
                "4" to getString(R.string.incoming_call_received),
                "5" to getString(R.string.incoming_call_answered),
                "6" to getString(R.string.outgoing_call_started),
            )
        )

        FILED_MAP.clear()
        FILED_MAP.putAll(
            mapOf(
                "transpond_all" to getString(R.string.rule_transpond_all),
                "phone_num" to getString(R.string.rule_phone_num),
                "msg_content" to getString(R.string.rule_msg_content),
                "multi_match" to getString(R.string.rule_multi_match),
                "package_name" to getString(R.string.rule_package_name),
                "inform_content" to getString(R.string.rule_inform_content),
                "call_type" to getString(R.string.rule_call_type),
                "uid" to getString(R.string.rule_uid),
            )
        )

        CHECK_MAP.clear()
        CHECK_MAP.putAll(
            mapOf(
                "is" to getString(R.string.rule_is),
                "notis" to getString(R.string.rule_notis),
                "contain" to getString(R.string.rule_contain),
                "startwith" to getString(R.string.rule_startwith),
                "endwith" to getString(R.string.rule_endwith),
                "notcontain" to getString(R.string.rule_notcontain),
                "regex" to getString(R.string.rule_regex),
            )
        )

        SIM_SLOT_MAP.clear()
        SIM_SLOT_MAP.putAll(
            mapOf(
                "ALL" to getString(R.string.rule_any),
                "SIM1" to "SIM1",
                "SIM2" to "SIM2",
            )
        )

        FORWARD_STATUS_MAP.clear()
        FORWARD_STATUS_MAP.putAll(
            mapOf(
                0 to getString(R.string.failed),
                1 to getString(R.string.processing),
                2 to getString(R.string.success),
            )
        )

        BARK_LEVEL_MAP.clear()
        BARK_LEVEL_MAP.putAll(
            mapOf(
                "critical" to getString(R.string.bark_level_critical),
                "active" to getString(R.string.bark_level_active),
                "timeSensitive" to getString(R.string.bark_level_timeSensitive),
                "passive" to getString(R.string.bark_level_passive)
            )
        )

        BARK_ENCRYPTION_ALGORITHM_MAP.clear()
        BARK_ENCRYPTION_ALGORITHM_MAP.putAll(
            mapOf(
                "none" to getString(R.string.bark_encryption_algorithm_none),
                "AES128/CBC/PKCS7Padding" to "AES128/CBC/PKCS7Padding",
                "AES128/ECB/PKCS7Padding" to "AES128/ECB/PKCS7Padding",
                "AES192/CBC/PKCS7Padding" to "AES192/CBC/PKCS7Padding",
                "AES192/ECB/PKCS7Padding" to "AES192/ECB/PKCS7Padding",
                "AES256/CBC/PKCS7Padding" to "AES256/CBC/PKCS7Padding",
                "AES256/ECB/PKCS7Padding" to "AES256/ECB/PKCS7Padding",
                "AES256/GCM/NoPadding" to "AES256/GCM/NoPadding",
            )
        )
    }

}
