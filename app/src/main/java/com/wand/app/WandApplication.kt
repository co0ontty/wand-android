package com.wand.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.wand.app.ui.theme.WandAppearance
import com.wand.app.ui.theme.WandAppearanceMode

/** 进程启动就套上外观模式，避免连接页 / XML 对话框先闪系统夜间色。 */
class WandApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 诊断日志必须最先就位：崩溃处理器 / 落盘目录都要在第一帧之前准备好。
        WandLog.install(this)
        logStartup()
        WandAppearance.apply(
            WandAppearanceMode.fromStorageValue(ServerStore(this).appearanceMode),
        )
    }

    /** 启动横幅：版本 + 上次异常退出一行摘要，方便导出后直接定位崩溃。 */
    private fun logStartup() {
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("v?")
        WandLog.i("app", "进程启动 $version · pid ${android.os.Process.myPid()}")
        val previous = runCatching { WandDiagnostics.previousExitSummaries(this, 3) }
            .getOrDefault(emptyList())
        previous.forEach { WandLog.w("app", "上次退出：$it") }
    }
}
