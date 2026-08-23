package com.wand.app

import android.app.Application
import com.wand.app.ui.theme.WandAppearance
import com.wand.app.ui.theme.WandAppearanceMode

/** 进程启动就套上外观模式，避免连接页 / XML 对话框先闪系统夜间色。 */
class WandApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WandAppearance.apply(
            WandAppearanceMode.fromStorageValue(ServerStore(this).appearanceMode),
        )
    }
}
