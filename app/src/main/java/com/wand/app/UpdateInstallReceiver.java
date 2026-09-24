package com.wand.app;

import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * PackageInstaller.commit 的状态回调。系统会先送来 STATUS_PENDING_USER_ACTION，
 * 必须再 startActivity 那个确认 Intent，安装界面才会出现。
 *
 * 部分 OEM（尤其小米）会给确认 Intent 塞一个 selector，导致 startActivity 静默失败；
 * 这里清掉 selector 再拉起。
 *
 * 安装成功时应用进程已被系统杀掉，用户看到的只是系统安装器的「完成」页 —— 应用不会
 * 自己回来。所以这里在 STATUS_SUCCESS 后安排一次「把应用拉回前台」的闹钟（见
 * {@link #scheduleRelaunch}），让客户端自动回到新版本，而不是停在旧界面。
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {

    static final String ACTION_INSTALL_STATUS = "com.wand.app.UPDATE_INSTALL_STATUS";

    private static final String TAG = "update";
    private static final int RELAUNCH_REQUEST_CODE = 4711;

    /** 安装完成后延迟拉起应用：给系统安装器一点时间画完「完成」页并释放焦点。 */
    private static final long RELAUNCH_DELAY_MS = 1_500L;

    /** 同进程内的 UI 回调（进程被杀后自然失效，不会泄漏到新进程）。 */
    interface StatusListener {
        void onInstallStatus(int status, String message);
    }

    private static volatile StatusListener statusListener;

    static void setStatusListener(StatusListener listener) {
        statusListener = listener;
    }

    static void notifyStatus(int status, String message) {
        StatusListener listener = statusListener;
        if (listener == null) return;
        try {
            listener.onInstallStatus(status, message);
        } catch (Exception e) {
            WandLog.w(TAG, "安装状态回调异常", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        WandLog.i(TAG, "安装状态回调 status=" + statusName(status)
                + (statusMessage != null && !statusMessage.isEmpty() ? " · " + statusMessage : ""));

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            if (confirm == null) {
                WandLog.w(TAG, "系统确认 Intent 为空，无法拉起安装界面", null);
                notifyStatus(PackageInstaller.STATUS_FAILURE, "无法打开系统安装界面");
                return;
            }
            notifyStatus(status, null);
            confirm.setSelector(null);
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(confirm);
            } catch (Exception e) {
                WandLog.e(TAG, "拉起系统安装界面失败", e);
                Toast.makeText(context, "无法打开系统安装界面", Toast.LENGTH_LONG).show();
                notifyStatus(PackageInstaller.STATUS_FAILURE, "无法打开系统安装界面");
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            ServerStore store = new ServerStore(context);
            store.markInstallSucceeded();
            String installed = store.getPendingInstallVersion();
            WandLog.i(TAG, "安装成功，准备重启到新版本 " + (installed == null ? "" : installed));
            notifyStatus(status, null);
            // 直接拉起 + 闹钟兜底：前台启动限制会把「后台组件直接 startActivity」静默
            // 拦下（只在 logcat 留一条 Background activity launch blocked），而闹钟是
            // 由系统代发 PendingIntent，属于该限制的豁免路径。谁先成功，另一个会被
            // WandApplication.onActivityResumed → cancelRelaunch 撤销，不会重复重启。
            launchApp(context, "安装完成");
            scheduleRelaunch(context);
            return;
        }

        // 失败 / 用户取消：清掉待安装状态，避免重启后仍显示「待安装」。
        new ServerStore(context).clearInstallState();
        String message = statusMessage;
        if (message == null || message.trim().isEmpty()) {
            message = "安装失败（" + statusName(status) + "）";
        }
        WandLog.w(TAG, "安装未完成：" + message, null);
        notifyStatus(status, message);
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    /** 尝试把应用拉回前台；被系统限制拦下时不会抛异常，所以返回 true 不代表一定会显示。 */
    private static void launchApp(Context context, String reason) {
        Intent launch = relaunchIntent(context);
        try {
            context.startActivity(launch);
            WandLog.i(TAG, "已请求回到前台（" + reason + "）");
        } catch (Exception e) {
            WandLog.w(TAG, "回到前台失败（" + reason + "）", e);
        }
    }

    /**
     * 拉起应用的目标 Intent。构造方式必须与 {@link #cancelRelaunch} 完全一致，
     * 否则 PendingIntent 身份不同，撤销会失效。
     */
    private static Intent relaunchIntent(Context context) {
        return new Intent(context, ConnectActivity.class)
                // CLEAR_TASK：更新后旧任务的恢复栈已经没有意义（进程已换），直接重新进入。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    static void scheduleRelaunch(Context context) {
        scheduleRelaunch(context, RELAUNCH_DELAY_MS);
    }

    /**
     * 创建拉起用的 PendingIntent。schedule / cancel 必须走同一份参数
     * （requestCode + Intent + ActivityOptions），否则身份不同，撤销会失效。
     */
    private static PendingIntent relaunchPendingIntent(Context context, int extraFlags) {
        ActivityOptions options = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 目标 SDK ≥ 35 时创建方默认不传递自己的后台启动特权；这里显式允许，
            // 让系统代发这次拉起时不会被 BAL 限制拦下。
            options = ActivityOptions.makeBasic()
                    .setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        }
        return PendingIntent.getActivity(
                context,
                RELAUNCH_REQUEST_CODE,
                relaunchIntent(context),
                extraFlags | PendingIntent.FLAG_IMMUTABLE,
                options != null ? options.toBundle() : null);
    }

    static void scheduleRelaunch(Context context, long delayMs) {
        PendingIntent pending = relaunchPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        long triggerAt = SystemClock.elapsedRealtime() + delayMs;
        try {
            // 精确闹钟需要 SCHEDULE_EXACT_ALARM（14 起默认拒绝），这里用非精确闹钟：
            // 安装完成时屏幕是亮的、设备不在休眠，误差通常在 1 秒内。
            alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending);
            WandLog.i(TAG, "已安排 " + delayMs + "ms 后自动回到应用");
        } catch (Exception e) {
            WandLog.w(TAG, "安排自动回到应用失败", e);
        }
    }

    /** 用户自己先打开了应用时撤销这次多余的自动拉起。 */
    static void cancelRelaunch(Context context) {
        PendingIntent pending = relaunchPendingIntent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending == null) return;
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(pending);
        pending.cancel();
    }

    static String statusName(int status) {
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                return "SUCCESS";
            case PackageInstaller.STATUS_FAILURE:
                return "FAILURE";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "FAILURE_ABORTED";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "FAILURE_BLOCKED";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "FAILURE_CONFLICT";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "FAILURE_INCOMPATIBLE";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "FAILURE_INVALID";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "FAILURE_STORAGE";
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                return "PENDING_USER_ACTION";
            default:
                return "STATUS_" + status;
        }
    }
}
