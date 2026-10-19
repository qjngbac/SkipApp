package com.antigravity.skipapp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 李跳跳风格极简主界面：
 * 纯原生 Android SDK 实现，零第三方框架依赖，毫秒级启动，退出即走。
 */
class MainActivity : Activity() {

    private lateinit var btnToggle: FrameLayout
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusSub: TextView
    private lateinit var tvStatusDesc: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            updateServiceStatus()
            statusHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        // 每次返回主界面时，重新检查并刷新无障碍服务状态
        updateServiceStatus()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.postDelayed(statusRefresh, 2000L)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btn_toggle_service)
        tvStatusTitle = findViewById(R.id.tv_status_title)
        tvStatusSub = findViewById(R.id.tv_status_sub)
        tvStatusDesc = findViewById(R.id.tv_status_desc)
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            if (SkipAccessibilityService.isConnected && SkipAccessibilityService.instance != null) {
                Toast.makeText(this, "跳过服务已在运行中，退出软件仍会自动生效！", Toast.LENGTH_SHORT).show()
            } else {
                openAccessibilitySettings()
            }
        }

        findViewById<View>(R.id.card_guide_battery)?.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        findViewById<View>(R.id.card_guide_diag)?.setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }

        findViewById<View>(R.id.card_repair_binding)?.setOnClickListener {
            openServiceDetailPage()
        }
    }

    /**
     * 直达本服务的开关页（系统把服务标成"无法运行"时，从这里关一次再开一次即可恢复）。
     * 该 Intent 与 extra 键都是 AOSP 约定，厂商 ROM 可能不支持：任何异常都退回原有的
     * 无障碍列表页，绝不因为一个跳转把进程带崩（界面与服务同进程）。
     */
    private fun openServiceDetailPage() {
        try {
            val component = ComponentName(this, SkipAccessibilityService::class.java).flattenToString()
            val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            detail.putExtra(":settings:fragment_args_key", component)
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(detail)
            Toast.makeText(this, "把开关关掉，等两秒再打开", Toast.LENGTH_LONG).show()
            return
        } catch (_: Exception) {
        }
        openAccessibilitySettings()
    }

    private fun openBatteryOptimizationSettings() {
        try {
            // 在 ColorOS/HyperOS 等定制系统上，应用详情页可一站式开启【允许自启动】与【允许完全后台行为】
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "建议开启【允许自启动】并将耗电管理设为【允许完全后台行为】", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "请将【跳跳助手】设置为【不优化/无限制】", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateServiceStatus() {
        val permissionEnabled = isAccessibilitySettingsOn(this, SkipAccessibilityService::class.java)
        // Manifest 未声明独立进程，界面与服务同进程；进程死亡会清空这些内存状态。
        val isRunning = SkipAccessibilityService.isConnected && SkipAccessibilityService.instance != null

        val greenColor = getColor(R.color.primary_dark)
        val titleColor = getColor(R.color.text_title)
        val enabledBg = getDrawable(R.drawable.bg_circle_enabled)
        val disabledBg = getDrawable(R.drawable.bg_circle_disabled)

        if (isRunning) {
            btnToggle.background = enabledBg
            tvStatusTitle.text = getString(R.string.status_enabled)
            tvStatusTitle.setTextColor(greenColor)
            tvStatusSub.text = "正在守护 · 退出仍生效"
            tvStatusDesc.text = getString(R.string.status_enabled_desc)
        } else if (permissionEnabled) {
            btnToggle.background = disabledBg
            tvStatusTitle.text = getString(R.string.status_disconnected)
            tvStatusTitle.setTextColor(titleColor)
            tvStatusSub.text = "点击去恢复"
            tvStatusDesc.text = getString(R.string.status_disconnected_desc)
        } else {
            btnToggle.background = disabledBg
            tvStatusTitle.text = getString(R.string.status_disabled)
            tvStatusTitle.setTextColor(titleColor)
            tvStatusSub.text = "点击去开启"
            tvStatusDesc.text = getString(R.string.status_disabled_desc)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "请在列表中找到【跳跳助手】并开启", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法直接跳转，请在系统设置中搜索【无障碍】", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 仅检测系统授权，不能把“已授权”或旧持久化状态当成“服务正在运行”。
     */
    private fun isAccessibilitySettingsOn(context: Context, serviceClass: Class<*>): Boolean {
        val target = ComponentName(context, serviceClass)
        // Android 官方无障碍管理器 API
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            if (am != null) {
                val enabledServices = am.getEnabledAccessibilityServiceList(
                    android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                for (service in enabledServices) {
                    val component = service.id?.let { ComponentName.unflattenFromString(it) }
                    if (component == target) return true
                }
            }
        } catch (_: Throwable) {}

        // 系统 Secure 数据库兼容回退，使用完整组件名匹配。
        try {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!TextUtils.isEmpty(settingValue)) {
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(settingValue)
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (ComponentName.unflattenFromString(componentName) == target) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {}

        return false
    }
}
