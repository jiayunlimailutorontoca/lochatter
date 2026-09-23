package ink.jvm.chatter.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import ink.jvm.chatter.service.Notifications

/**
 * Chinese ROMs kill background apps unless the user whitelists them. There is no API for that,
 * only vendor settings pages, so we deep-link to the right one and explain what to toggle.
 */
object BackgroundSettings {

    val brand: String = (Build.MANUFACTURER ?: "").lowercase()

    fun isBatteryWhitelisted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Human steps for this phone, shown in the guidance dialog. */
    fun steps(): List<String> = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("blackshark") -> listOf(
            "自启动：在「自启动管理」里打开 lochatter",
            "省电策略：应用设置 → 省电策略 → 无限制",
            "最近任务里下拉 lochatter 卡片点「锁定」，防止一键清理",
            "通知：允许通知，并打开「悬浮通知」和「锁屏通知」",
        )
        brand.contains("huawei") || brand.contains("honor") -> listOf(
            "应用启动管理：关闭「自动管理」，手动打开自启动、关联启动、后台活动",
            "电池：应用启动管理设为手动后，系统不会再冻结 lochatter",
            "最近任务里下拉 lochatter 卡片加锁",
            "通知：允许通知，并允许横幅和锁屏显示",
        )
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
            "自启动：应用信息 → 允许自动启动",
            "电池：应用耗电管理 → 允许后台运行 / 不限制",
            "最近任务里锁定 lochatter",
            "通知：允许通知与横幅",
        )
        brand.contains("vivo") || brand.contains("iqoo") -> listOf(
            "自启动：i 管家 → 应用管理 → 自启动 → 允许 lochatter",
            "后台耗电管理：允许后台高耗电",
            "最近任务里锁定 lochatter",
            "通知：允许通知与横幅",
        )
        brand.contains("samsung") -> listOf(
            "电池：应用 → lochatter → 电池 → 不受限制",
            "设备维护 → 电池 → 后台使用限制 → 把 lochatter 从「休眠应用」里移除",
        )
        else -> listOf(
            "电池：应用 → lochatter → 电池 → 不受限制",
            "最近任务里锁定 lochatter（如有）",
        )
    }

    /** Opens the vendor's autostart / app-launch page; falls back to the app's own settings page. */
    fun openAutostart(ctx: Context): Boolean {
        val candidates = listOf(
            // Xiaomi
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Huawei / Honor
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            // Oppo / Realme / OnePlus (ColorOS)
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            // Vivo
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            // Samsung
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            // Meizu
            ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
        )
        for (cn in candidates) {
            val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (ctx.packageManager.resolveActivity(intent, 0) != null) {
                try {
                    ctx.startActivity(intent)
                    return true
                } catch (_: Exception) { }
            }
        }
        return try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            false
        } catch (_: Exception) {
            false
        }
    }

    /** Notifications allowed at all, and the message channel not silenced. */
    fun notificationsEnabled(ctx: Context): Boolean {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(Notifications.CH_MESSAGES) ?: return true
        return ch.importance != android.app.NotificationManager.IMPORTANCE_NONE
    }

    fun openNotificationSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Android 12/13 only: the exact-alarm toggle lives in system settings. 14+ grants USE_EXACT_ALARM at install. */
    fun requestExactAlarm(ctx: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun requestIgnoreBattery(ctx: Context) {
        if (isBatteryWhitelisted(ctx)) return
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
