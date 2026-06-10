package com.wand.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 启动器图标切换（赛博虎妞 shorthair / 勤劳初二 garfield），通过启用/禁用
 * AndroidManifest 里的 activity-alias 实现。原生设置页（HomeActivity）与
 * WebView 桥（MainActivity.NativeBridge）共用这一份实现。
 */
final class AppIconSwitcher {

    private AppIconSwitcher() {
    }

    static void setAppIcon(Context context, ServerStore serverStore, String iconName) {
        if (!"shorthair".equals(iconName) && !"garfield".equals(iconName)) return;
        if (iconName.equals(serverStore.getAppIcon())) return;
        serverStore.setAppIcon(iconName);

        PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        ComponentName shorthairAlias = new ComponentName(pkg, pkg + ".ConnectActivity.Shorthair");
        ComponentName garfieldAlias = new ComponentName(pkg, pkg + ".ConnectActivity.Garfield");

        if ("garfield".equals(iconName)) {
            pm.setComponentEnabledSetting(shorthairAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(garfieldAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } else {
            pm.setComponentEnabledSetting(garfieldAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(shorthairAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
