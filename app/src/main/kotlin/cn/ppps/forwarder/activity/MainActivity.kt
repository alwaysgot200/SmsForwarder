package cn.ppps.forwarder.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayout
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.adapter.menu.DrawerAdapter
import cn.ppps.forwarder.adapter.menu.DrawerItem
import cn.ppps.forwarder.adapter.menu.SimpleItem
import cn.ppps.forwarder.adapter.menu.SpaceItem
import cn.ppps.forwarder.core.BaseActivity
import cn.ppps.forwarder.core.webview.AgentWebActivity
import cn.ppps.forwarder.databinding.ActivityMainBinding
import cn.ppps.forwarder.fragment.AboutFragment
import cn.ppps.forwarder.fragment.AppListFragment
import cn.ppps.forwarder.fragment.ClientFragment
import cn.ppps.forwarder.fragment.FrpcFragment
import cn.ppps.forwarder.fragment.LogsFragment
import cn.ppps.forwarder.fragment.RulesFragment
import cn.ppps.forwarder.fragment.SendersFragment
import cn.ppps.forwarder.fragment.ServerFragment
import cn.ppps.forwarder.fragment.SettingsFragment
import cn.ppps.forwarder.fragment.TasksFragment
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.CommonUtils.Companion.restartApplication
import cn.ppps.forwarder.utils.EVENT_LOAD_APP_LIST
import cn.ppps.forwarder.utils.FRPC_LIB_DOWNLOAD_URL
import cn.ppps.forwarder.utils.FRPC_LIB_VERSION
import cn.ppps.forwarder.utils.KeepAliveSetupWizard
import cn.ppps.forwarder.utils.KeepAliveUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import cn.ppps.forwarder.utils.sdkinit.XUpdateInit
import cn.ppps.forwarder.widget.GuideTipsDialog.Companion.showTips
import cn.ppps.forwarder.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.DownloadProgressCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xui.XUI.getContext
import com.xuexiang.xui.utils.ResUtils
import com.xuexiang.xui.utils.ThemeUtils
import com.xuexiang.xui.utils.ViewUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.GravityEnum
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.net.NetworkUtils
import com.yarolegovich.slidingrootnav.SlideGravity
import com.yarolegovich.slidingrootnav.SlidingRootNav
import com.yarolegovich.slidingrootnav.SlidingRootNavBuilder
import com.yarolegovich.slidingrootnav.callback.DragStateListener
import java.io.File

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>(), DrawerAdapter.OnItemSelectedListener {

    private val TAG: String = MainActivity::class.java.simpleName
    private val POS_LOG = 0
    private val POS_RULE = 1
    private val POS_SENDER = 2
    private val POS_SETTING = 3
    private val POS_TASK = 5 //4为空行
    private val POS_SERVER = 6
    private val POS_CLIENT = 7
    private val POS_FRPC = 8
    private val POS_APPS = 9
    private val POS_HELP = 11 //10为空行
    private val POS_ABOUT = 12
    private var needToAppListFragment = false

    private lateinit var mTabLayout: TabLayout
    private lateinit var mSlidingRootNav: SlidingRootNav
    private lateinit var mLLMenu: LinearLayout
    private lateinit var mMenuTitles: Array<String>
    private lateinit var mMenuIcons: Array<Drawable>
    private lateinit var mAdapter: DrawerAdapter

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initData()
        initViews()
        initSlidingMenu(savedInstanceState)

        //被杀未复苏检测：上次看门狗心跳断档超过阈值，说明曾被厂商系统力停清理且未能自动恢复，
        //引导用户完成厂商侧后台设置（自启动/省电策略），24小时内最多提示一次
        checkKillRecoveryGuide()

        //首装批量授权：短信收/读/发、电话状态、通话记录、联系人、通知——一次对话框全部勾完
        XXPermissions.with(this)
            .permission(PermissionLists.getReceiveSmsPermission())
            .permission(PermissionLists.getReadSmsPermission())
            .permission(PermissionLists.getSendSmsPermission())
            .permission(PermissionLists.getReadPhoneStatePermission())
            .permission(PermissionLists.getReadCallLogPermission())
            .permission(PermissionLists.getReadContactsPermission())
            .permission(PermissionLists.getNotificationServicePermission())
            .permission(PermissionLists.getPostNotificationsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    //保活高于一切：无论权限是否全部授予，前台服务必须启动（通知权限只影响通知可见性）
                    if (!ForegroundService.isRunning) {
                        val serviceIntent = Intent(getTopActivity(), ForegroundService::class.java)
                        serviceIntent.action = ACTION_START
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                    if (deniedList.isNotEmpty()) {
                        //Android 13+ 通知权限被拒时前台服务照常运行但通知不可见，用户易误以为未在运行：
                        //被"不再询问"永久拒绝时引导跳转权限设置页补授权（否则系统授权框不再弹出）
                        if (XXPermissions.isDoNotAskAgainPermissions(getTopActivity(), deniedList)) {
                            XXPermissions.startPermissionActivity(getContext(), deniedList)
                        }
                        XToastUtils.error(R.string.tips_notification)
                    }
                    //首启保活设置向导：能自动授权的已随安装生效，此处按优先级引导剩余人工步骤
                    KeepAliveSetupWizard.maybeRun(this@MainActivity)
                }
            })

        //监听已安装App信息列表加载完成事件
        LiveEventBus.get(EVENT_LOAD_APP_LIST, String::class.java).observe(this) {
            if (needToAppListFragment) {
                openNewPage(AppListFragment::class.java)
            }
        }
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_logs), R.drawable.selector_icon_tabbar_logs)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_rules), R.drawable.selector_icon_tabbar_rules)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_senders), R.drawable.selector_icon_tabbar_senders)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_settings), R.drawable.selector_icon_tabbar_settings)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        switchPage(LogsFragment::class.java)
        mTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                needToAppListFragment = false
                mAdapter.setSelected(tab.position)
                when (tab.position) {
                    POS_LOG -> switchPage(LogsFragment::class.java)
                    POS_RULE -> switchPage(RulesFragment::class.java)
                    POS_SENDER -> switchPage(SendersFragment::class.java)
                    POS_SETTING -> switchPage(SettingsFragment::class.java)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initData() {
        mMenuTitles = ResUtils.getStringArray(this, R.array.menu_titles)
        mMenuIcons = ResUtils.getDrawableArray(this, R.array.menu_icons)

        //仅当开启自动检查且有网络时自动检查更新/获取提示
        if (SettingUtils.autoCheckUpdate && NetworkUtils.isHaveInternet()) {
            showTips(this)
            XUpdateInit.checkUpdate(this, false, SettingUtils.joinPreviewProgram)
        }
    }

    //按返回键不退出回到桌面
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    fun openMenu() {
        mSlidingRootNav.openMenu()
    }

    fun closeMenu() {
        mSlidingRootNav.closeMenu()
    }

    fun isMenuOpen(): Boolean {
        return mSlidingRootNav.isMenuOpened
    }

    private fun initSlidingMenu(savedInstanceState: Bundle?) {
        mSlidingRootNav = SlidingRootNavBuilder(this).withGravity(if (ResUtils.isRtl(this)) SlideGravity.RIGHT else SlideGravity.LEFT).withMenuOpened(false).withContentClickableWhenMenuOpened(false).withSavedState(savedInstanceState).withMenuLayout(R.layout.menu_left_drawer).inject()
        mLLMenu = mSlidingRootNav.layout.findViewById(R.id.ll_menu)
        ViewUtils.setVisibility(mLLMenu, false)
        mAdapter = DrawerAdapter(
            mutableListOf(
                createItemFor(POS_LOG).setChecked(true),
                createItemFor(POS_RULE),
                createItemFor(POS_SENDER),
                createItemFor(POS_SETTING),
                SpaceItem(15),
                createItemFor(POS_TASK),
                createItemFor(POS_SERVER),
                createItemFor(POS_CLIENT),
                createItemFor(POS_FRPC),
                createItemFor(POS_APPS),
                SpaceItem(15),
                createItemFor(POS_HELP),
                createItemFor(POS_ABOUT),
            )
        )
        mAdapter.setListener(this)
        val list: RecyclerView = findViewById(R.id.list)
        list.isNestedScrollingEnabled = false
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = mAdapter
        mAdapter.setSelected(POS_LOG)
        mSlidingRootNav.isMenuLocked = false
        mSlidingRootNav.layout.addDragStateListener(object : DragStateListener {
            override fun onDragStart() {
                ViewUtils.setVisibility(mLLMenu, true)
            }

            override fun onDragEnd(isMenuOpened: Boolean) {
                ViewUtils.setVisibility(mLLMenu, isMenuOpened)
            }
        })
    }

    override fun onItemSelected(position: Int) {
        needToAppListFragment = false
        when (position) {
            POS_LOG, POS_RULE, POS_SENDER, POS_SETTING -> {
                val tab = mTabLayout.getTabAt(position)
                tab?.select()
                mSlidingRootNav.closeMenu()
            }

            POS_TASK -> openNewPage(TasksFragment::class.java)
            POS_SERVER -> openNewPage(ServerFragment::class.java)
            POS_CLIENT -> openNewPage(ClientFragment::class.java)
            POS_FRPC -> {
                if (App.FrpclibInited) {
                    openNewPage(FrpcFragment::class.java)
                    return
                }

                val title = if (!FileUtils.isFileExists(filesDir.absolutePath + "/libs/libgojni.so")) {
                    String.format(getString(R.string.frpclib_download_title), FRPC_LIB_VERSION)
                } else {
                    getString(R.string.frpclib_version_mismatch)
                }

                MaterialDialog.Builder(this)
                    .title(title)
                    .content(R.string.download_frpc_tips)
                    .positiveText(R.string.lab_yes)
                    .negativeText(R.string.lab_no)
                    .onPositive { _: MaterialDialog?, _: DialogAction? ->
                        downloadFrpcLib()
                    }
                    .show()
            }

            POS_APPS -> {
                //检查读取应用列表权限是否获取
                XXPermissions.with(this)
                    .permission(PermissionLists.getGetInstalledAppsPermission())
                    .request(object : OnPermissionCallback {
                        override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                            val allGranted = deniedList.isEmpty()
                            if (!allGranted) {
                                // 判断请求失败的权限是否被用户勾选了不再询问的选项
                                val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(getTopActivity(), deniedList)
                                if (doNotAskAgain) {
                                    XXPermissions.startPermissionActivity(getContext(), deniedList)
                                }
                                // 处理权限请求失败的逻辑
                                XToastUtils.error(R.string.tips_get_installed_apps)
                                return
                            }
                            // 处理权限请求成功的逻辑
                            if (App.UserAppList.isEmpty() && App.SystemAppList.isEmpty()) {
                                XToastUtils.info(getString(R.string.loading_app_list))
                                val request = OneTimeWorkRequestBuilder<LoadAppListWorker>().build()
                                WorkManager.getInstance(getContext()).enqueue(request)
                                needToAppListFragment = true
                                return
                            }
                            openNewPage(AppListFragment::class.java)
                        }
                    })
            }

            POS_HELP -> AgentWebActivity.goWeb(this, getString(R.string.url_help))
            POS_ABOUT -> openNewPage(AboutFragment::class.java)
        }
    }

    override fun onResume() {
        super.onResume()
        //保活缺项审计：静默自愈（拉起前台服务）+ 定期缺项弹窗；从厂商设置页返回后继续向导下一步
        KeepAliveSetupWizard.auditIfNeeded(this)
        KeepAliveSetupWizard.onResumed(this)
    }

    //被杀未复苏检测：上次看门狗心跳断档超过阈值，说明曾被厂商系统力停清理且未能自动恢复，
    //引导用户完成厂商侧后台设置（自启动/省电策略），24小时内最多提示一次
    private fun checkKillRecoveryGuide() {
        if (SettingUtils.enablePureClientMode) return
        //保活向导未完成时不叠弹：向导本身就在引导厂商侧设置
        if (KeepAliveSetupWizard.isPending(this)) return
        if (!KeepAliveUtils.shouldShowKillGuide(this)) return
        KeepAliveUtils.markKillGuideShown(this)

        val builder = MaterialDialog.Builder(this)
            .title(R.string.kill_guide_title)
            .content(R.string.kill_guide_message)
            .positiveText(R.string.kill_guide_go_auto_start)
            .onPositive { _: MaterialDialog?, _: DialogAction? ->
                CommonUtils.startToAutoStartSetting(this)
            }
            .negativeText(R.string.lab_no)
        if (CommonUtils.isXiaomiFamily()) {
            builder.neutralText(R.string.kill_guide_go_power_save)
                .onNeutral { _: MaterialDialog?, _: DialogAction? ->
                    CommonUtils.startToPowerSaveSetting(this)
                }
        }
        builder.show()
    }

    private fun createItemFor(position: Int): DrawerItem<*> {
        return SimpleItem(mMenuIcons[position], mMenuTitles[position])
            .withIconTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withTextTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withSelectedIconTint(ThemeUtils.getMainThemeColor(this))
            .withSelectedTextTint(ThemeUtils.getMainThemeColor(this))
    }

    //动态加载FrpcLib
    private fun downloadFrpcLib() {
        val cpuAbi = when (Build.CPU_ABI) {
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            "arm64-v8a" -> "arm64-v8a"
            else -> "armeabi-v7a"
        }

        val libPath = filesDir.absolutePath + "/libs"
        val soFile = File(libPath)
        if (!soFile.exists()) soFile.mkdirs()
        val downloadUrl = String.format(FRPC_LIB_DOWNLOAD_URL, FRPC_LIB_VERSION, cpuAbi)
        val mContext = this
        val dialog: MaterialDialog = MaterialDialog.Builder(mContext)
            .title(String.format(getString(R.string.frpclib_download_title), FRPC_LIB_VERSION))
            .content(getString(R.string.frpclib_download_content))
            .contentGravity(GravityEnum.CENTER)
            .progress(false, 0, true)
            .progressNumberFormat("%2dMB/%1dMB")
            .build()

        XHttp.downLoad(downloadUrl)
            .ignoreHttpsCert()
            .savePath(cacheDir.absolutePath)
            .execute(object : DownloadProgressCallBack<String?>() {
                override fun onStart() {
                    dialog.show()
                }

                override fun onError(e: ApiException) {
                    dialog.dismiss()
                    XToastUtils.error(e.message.toString())
                }

                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                    Log.d(TAG, "onProgress: bytesRead=$bytesRead, contentLength=$contentLength")
                    dialog.maxProgress = (contentLength / 1048576L).toInt()
                    dialog.setProgress((bytesRead / 1048576L).toInt())
                }

                override fun onComplete(srcPath: String) {
                    dialog.dismiss()
                    Log.d(TAG, "srcPath = $srcPath")

                    val srcFile = File(srcPath)
                    val destFile = File("$libPath/libgojni.so")
                    FileUtils.moveFile(srcFile, destFile, null)

                    MaterialDialog.Builder(this@MainActivity)
                        .iconRes(R.drawable.ic_menu_frpc)
                        .title(R.string.menu_frpc)
                        .content(R.string.download_frpc_tips2)
                        .cancelable(false)
                        .positiveText(R.string.confirm)
                        .onPositive { _: MaterialDialog?, _: DialogAction? ->
                            restartApplication()
                        }
                        .show()
                }
            })

    }

}
