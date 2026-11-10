package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Observer
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.entity.action.AlarmSetting
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.ACTION_STOP_ALARM
import cn.ppps.forwarder.utils.ACTION_UPDATE_NOTIFICATION
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.EVENT_ALARM_ACTION
import cn.ppps.forwarder.utils.EVENT_FRPC_RUNNING_ERROR
import cn.ppps.forwarder.utils.EVENT_FRPC_RUNNING_SUCCESS
import cn.ppps.forwarder.utils.FRONT_CHANNEL_ID
import cn.ppps.forwarder.utils.FRONT_CHANNEL_NAME
import cn.ppps.forwarder.utils.FRONT_NOTIFY_ID
import cn.ppps.forwarder.utils.FlashUtils
import cn.ppps.forwarder.utils.INTENT_FRPC_APPLY_FILE
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.KeepAliveUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.TASK_CONDITION_CRON
import cn.ppps.forwarder.utils.VibrationUtils
import cn.ppps.forwarder.utils.task.CronJobScheduler
import cn.ppps.forwarder.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xutil.XUtil
import frpclib.Frpclib
import io.reactivex.Single
import io.reactivex.SingleObserver
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.io.File

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName", "DeferredResultUnused", "OPT_IN_USAGE", "DEPRECATION")
class ForegroundService : Service() {

    private val TAG: String = ForegroundService::class.java.simpleName
    private var notificationManager: NotificationManager? = null

    private val compositeDisposable = CompositeDisposable()
    private val frpcObserver = Observer { uid: String ->
        if (!App.FrpclibInited || Frpclib.isRunning(uid)) return@Observer

        Core.frpc.get(uid).flatMap { (uid1, _, config) ->
            val error = Frpclib.runContent(uid1, config)
            Single.just(error)
        }.subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(object : SingleObserver<String> {
            override fun onSubscribe(d: Disposable) {
                compositeDisposable.add(d)
            }

            override fun onError(e: Throwable) {
                e.printStackTrace()
                Log.e(TAG, "onError: ${e.message}")
                LiveEventBus.get(EVENT_FRPC_RUNNING_ERROR, String::class.java).post(uid)
            }

            override fun onSuccess(msg: String) {
                if (!TextUtils.isEmpty(msg)) {
                    Log.e(TAG, msg)
                    LiveEventBus.get(EVENT_FRPC_RUNNING_ERROR, String::class.java).post(uid)
                } else {
                    LiveEventBus.get(EVENT_FRPC_RUNNING_SUCCESS, String::class.java).post(uid)
                }
            }
        })
    }

    // 振动控制
    private lateinit var vibrationUtils: VibrationUtils
    private var isVibrating = false

    // 闪光灯控制
    private lateinit var flashUtils: FlashUtils
    private var isFlash = false

    // 音乐播放器
    private var alarmPlayer: MediaPlayer? = null
    private var alarmPlayTimes = 0
    private val alarmObserver = Observer<AlarmSetting> { alarm ->
        Log.d(TAG, "Received alarm: $alarm")
        //停止振动
        if (vibrationUtils.isVibrating) {
            vibrationUtils.stopVibration()
        }
        //停止闪光灯
        if (::flashUtils.isInitialized && flashUtils.isFlashing) {
            flashUtils.stopFlashing()
        }
        //停止播放音乐
        alarmPlayer?.release()
        alarmPlayer = null
        if (alarm.action == "start") {
            //播放音乐
            if (alarm.playTimes >= 0) {
                //获取音量
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                Log.d(TAG, "maxVolume=$maxVolume, currentVolume=$currentVolume")
                //设置音量
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVolume * alarm.volume / 100), 0)
                //播放音乐
                alarmPlayer = MediaPlayer().apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                        setAudioAttributes(audioAttributes)
                    } else {
                        // 对于 Android 5.0 之前的版本，使用 setAudioStreamType
                        val audioStreamType = AudioManager.STREAM_ALARM
                        setAudioStreamType(audioStreamType)
                    }

                    try {
                        if (alarm.music.isEmpty() || !File(alarm.music).exists()) {
                            val fd = resources.openRawResourceFd(R.raw.alarm)
                            setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                        } else {
                            setDataSource(alarm.music)
                        }

                        setOnPreparedListener {
                            Log.d(TAG, "MediaPlayer prepared")
                            start()
                            alarmPlayTimes++
                            //更新通知栏
                            updateNotification(alarm.description, R.drawable.auto_task_icon_alarm, true)
                        }

                        setOnCompletionListener {
                            Log.d(TAG, "MediaPlayer completed")
                            if (alarm.playTimes == 0 || alarmPlayTimes < alarm.playTimes) {
                                start()
                                alarmPlayTimes++
                            } else {
                                stop()
                                reset()
                                release()
                                alarmPlayer = null
                                alarmPlayTimes = 0
                                //恢复音量
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                //恢复通知栏
                                updateNotification(SettingUtils.notifyContent)
                            }
                        }

                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                            release()
                            return@setOnErrorListener true
                        }

                        setVolume(alarm.volume / 100F, alarm.volume / 100F)
                        prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "MediaPlayer Exception: ${e.message}")
                    }
                }
            }
            //振动提醒
            if (alarm.repeatTimes >= 0) {
                isVibrating = true
                vibrationUtils.startVibration(alarm.vibrate, alarm.repeatTimes)
            }
            //闪光灯提醒
            if (alarm.flashTimes >= 0 && ::flashUtils.isInitialized && flashUtils.isFlashSupported) {
                isFlash = true
                flashUtils.startFlashing(alarm.flash, alarm.flashTimes)
            }
        }
    }

    companion object {
        var isRunning = false
    }

    //主动停止标记：ACTION_STOP 停止后看门狗不再复活服务
    private var deliberateStop = false

    override fun onCreate() {
        super.onCreate()

        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return

        //创建通知渠道
        createNotificationChannel()

        //初始化振动
        vibrationUtils = VibrationUtils(this)

        //初始化闪光灯
        flashUtils = FlashUtils(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        //纯客户端模式
        if (SettingUtils.enablePureClientMode) return START_NOT_STICKY

        //确保看门狗心跳链持续（覆盖式替换，不会累积闹钟）
        KeepAliveUtils.scheduleServiceWatchdog(this)

        if (intent != null) {
            when (intent.action) {
                ACTION_START -> {
                    deliberateStop = false
                    startForegroundService()
                }

                ACTION_STOP -> {
                    //主动停止：onDestroy 不再安排看门狗复活（否则与停止意图自相矛盾）
                    deliberateStop = true
                    stopForegroundService()
                }

                ACTION_UPDATE_NOTIFICATION -> {
                    updateNotification(foregroundNotificationContent())
                }

                ACTION_STOP_ALARM -> {
                    alarmPlayer?.release()
                    alarmPlayer = null
                    updateNotification(SettingUtils.notifyContent)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        //非纯客户端模式
        if (!SettingUtils.enablePureClientMode) {
            stopForegroundService()
            //服务被系统回收时安排延迟自愈（非立即重启，避免与系统查杀形成重启风暴）；
            //主动停止（ACTION_STOP）不复活，尊重停止意图
            if (!deliberateStop) KeepAliveUtils.scheduleServiceWatchdog(this, 30_000L)
        }
        if (::flashUtils.isInitialized) {
            flashUtils.release()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        //用户从最近任务划掉应用：此刻进程仍存活而厂商系统随后才杀进程，
        //第一时间原地重启前台服务，成功则通知几乎无感断线；
        //无论成败都安排 3 秒看门狗复查（原地重启成功后进程再被杀、或 Android 12+ 拒绝后台启动时兜底）
        if (!SettingUtils.enablePureClientMode) {
            try {
                val intent = Intent(this, ForegroundService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                Log.w(TAG, "onTaskRemoved：已原地重启前台服务")
            } catch (e: Exception) {
                Log.e(TAG, "onTaskRemoved：原地重启失败，退回看门狗自愈: ${e.message}")
            } finally {
                KeepAliveUtils.scheduleServiceWatchdog(this, 3_000L)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    private fun startForegroundService() {
        //每次 START 都先保证前台通知存在：onTaskRemoved 原地重启时 isRunning 仍为 true，
        //若直接跳过 startForeground 会在 5 秒后触发 "did not then call startForeground" 崩溃
        val notification = createNotification(foregroundNotificationContent())
        //Android 14（API 34）起 startForeground 须传入前台服务类型（三参重载为 API 29+）；
        //SPECIAL_USE 位在 34 以下系统未定义，传入会抛 IllegalArgumentException，需按版本区分
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FRONT_NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FRONT_NOTIFY_ID, notification)
        }

        if (isRunning) return
        isRunning = true

        try {
            //开关通知监听服务
            if (SettingUtils.enableAppNotify && CommonUtils.isNotificationListenerServiceEnabled(this)) {
                CommonUtils.toggleNotificationListenerService(this)
            }

            //启动定时任务
            GlobalScope.async(Dispatchers.IO) {
                val taskList = Core.task.getByType(TASK_CONDITION_CRON)
                taskList.forEach { task ->
                    Log.d(TAG, "task = $task")
                    CronJobScheduler.cancelTask(task.id)
                    CronJobScheduler.scheduleTask(task)
                }
            }

            //异步获取所有已安装 App 信息
            if (SettingUtils.enableLoadAppList) {
                val request = OneTimeWorkRequestBuilder<LoadAppListWorker>().build()
                WorkManager.getInstance(XUtil.getContext()).enqueue(request)
            }

            //启动 Frpc
            if (App.FrpclibInited) {
                //监听Frpc启动指令
                LiveEventBus.get(INTENT_FRPC_APPLY_FILE, String::class.java).observeForever(frpcObserver)
                //自启动的Frpc
                GlobalScope.async(Dispatchers.IO) {
                    val frpcList = Core.frpc.getAutorun()

                    if (frpcList.isEmpty()) {
                        Log.d(TAG, "没有自启动的Frpc")
                        return@async
                    }

                    for (frpc in frpcList) {
                        Log.d(TAG, "自启动的Frpc: $frpc")
                        GlobalScope.async(Dispatchers.IO) {
                            val error = Frpclib.runContent(frpc.uid, frpc.config)
                            Log.d(TAG, "自启动的Frpc: uid=${frpc.uid}, error=$error")
                            if (!TextUtils.isEmpty(error)) {
                                Log.e(TAG, error)
                            }
                        }
                    }
                }
            }

            //播放警报
            LiveEventBus.get<AlarmSetting>(EVENT_ALARM_ACTION).observeForever(alarmObserver)

        } catch (e: Exception) {
            handleException(e, "startForegroundService")
        }

    }

    private fun stopForegroundService() {
        try {
            stopForeground(true)
            stopSelf()
            compositeDisposable.dispose()
            isRunning = false
            alarmPlayer?.release()
            alarmPlayer = null
            //停止振动
            if (vibrationUtils.isVibrating) {
                vibrationUtils.stopVibration()
            }
            //停止闪光灯
            if (::flashUtils.isInitialized && flashUtils.isFlashing) {
                flashUtils.stopFlashing()
            }
        } catch (e: Exception) {
            handleException(e, "stopForegroundService")
        }
    }

    private fun createNotificationChannel() {
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val notificationChannel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, importance)
            notificationChannel.description = getString(R.string.notification_content)
            notificationChannel.enableLights(true)
            notificationChannel.lightColor = Color.GREEN
            notificationChannel.vibrationPattern = longArrayOf(0, 1000, 500, 1000)
            notificationChannel.enableVibration(true)
            if (notificationManager != null) {
                notificationManager!!.createNotificationChannel(notificationChannel)
            }
        }
    }

    private fun foregroundNotificationContent(): String = if (HttpServerUtils.safetyMeasures == 0) {
        getString(R.string.http_server_no_security_warning)
    } else {
        SettingUtils.notifyContent
    }

    private fun createNotification(content: String, largeIconResId: Int? = null, showStopButton: Boolean = false): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags)

        val builder = NotificationCompat.Builder(this, FRONT_CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(content).setSmallIcon(R.drawable.ic_forwarder).setContentIntent(pendingIntent).setWhen(System.currentTimeMillis())

        // 设置大图标（可选）
        if (largeIconResId != null) {
            builder.setLargeIcon(BitmapFactory.decodeResource(resources, largeIconResId))
        } else {
            builder.setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_menu_frpc))
        }

        // 添加停止按钮（可选）
        if (showStopButton) {
            val stopIntent = Intent(this, ForegroundService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags)
            builder.addAction(R.drawable.ic_stop, getString(R.string.stop), stopPendingIntent)
        }

        return builder.build()
    }

    private fun updateNotification(updatedContent: String, largeIconResId: Int? = null, showStopButton: Boolean = false) {
        try {
            val notification = createNotification(updatedContent, largeIconResId, showStopButton)
            notificationManager?.notify(FRONT_NOTIFY_ID, notification)
        } catch (e: Exception) {
            handleException(e, "updateNotification")
        }
    }

    private fun handleException(e: Exception, methodName: String) {
        e.printStackTrace()
        Log.e(TAG, "$methodName: $e")
        isRunning = false
    }

}
