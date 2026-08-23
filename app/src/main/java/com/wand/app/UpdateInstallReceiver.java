package com.wand.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * PackageInstaller.commit 的状态回调。系统会先送来 STATUS_PENDING_USER_ACTION，
 * 必须再 startActivity 那个确认 Intent，安装界面才会出现。
 *
 * 部分 OEM（尤其小米）会给确认 Intent 塞一个 selector，导致 startActivity 静默失败；
 * 这里清掉 selector 再拉起。
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {

    static final String ACTION_INSTALL_STATUS = "com.wand.app.UPDATE_INSTALL_STATUS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm == null) return;
            confirm.setSelector(null);
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Exception e) {
                Toast.makeText(context, "无法打开系统安装界面", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message == null || message.trim().isEmpty()) {
            message = "安装失败（" + status + "）";
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
