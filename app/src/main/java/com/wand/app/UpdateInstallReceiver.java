package com.wand.app;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * PackageInstaller.commit 的状态回调。系统会先送来 STATUS_PENDING_USER_ACTION，
 * 必须再 startActivity 那个确认 Intent，安装界面才会出现。
 *
 * 部分 OEM（尤其小米）会给确认 Intent 塞一个 selector，导致 startActivity 静默失败；
 * 这里清掉 selector 再拉起。
 *
 * 安装成功时应用进程已被系统杀掉，用户看到的只是系统安装器的「完成」页 —— 应用不会
 * 自己回来。STATUS_SUCCESS 之后的「回到应用」有三条路（见 {@link #bringAppBack}）：
 *
 * 1. 直接 startActivity：Android 10+ 的后台启动限制会拦下它（实测 Android 16 返回
 *    BAL_BLOCK），只有部分给了「后台弹出界面 / 自启动」权限的 OEM 系统会放行；
 * 2. 闹钟兜底：曾经以为系统代发 PendingIntent 属于豁免路径，实测同样被拦
 *    （AlarmManager 以 MODE_BACKGROUND_ACTIVITY_START_DENIED 发送），所以删除；
 * 3. 通知：**唯一在原生 Android 上可靠的做法** —— 用户点通知由系统 UI 代发起，
 *    不受后台启动限制，点击即进入新版本。
 *
 * 回调按会话号过滤：系统不会因为用户放弃一次安装就取消那个会话，弹窗可能还活着，aborted
 * 回调隔几秒才到。不过滤的话，成功安装之后会被旧会话的失败回调改写成「安装失败」（实机日志
 * 2026-09-25 05:57:19 SUCCESS → 05:57:23 FAILURE_ABORTED）。判定规则见
 * {@link UpdateInstallState#isCurrentSession}。
 */
public final class UpdateInstallReceiver extends BroadcastReceiver {

    static final String ACTION_INSTALL_STATUS = "com.wand.app.UPDATE_INSTALL_STATUS";

    /** 提交安装时写入的 PackageInstaller 会话号；旧版本提交的回调没有这一项。 */
    static final String EXTRA_SESSION_ID = "com.wand.app.extra.INSTALL_SESSION_ID";

    private static final String TAG = "update";
    private static final int RELAUNCH_REQUEST_CODE = 4711;

    /** 「更新已完成」通知的 id（单条，重装后覆盖）。 */
    private static final int NOTIFICATION_ID_UPDATE_READY = 8801;

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
        int sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1);
        WandLog.i(TAG, "安装状态回调" + (sessionId >= 0 ? " sessionId=" + sessionId : "")
                + " status=" + statusName(status)
                + (statusMessage != null && !statusMessage.isEmpty() ? " · " + statusMessage : ""));

        ServerStore store = new ServerStore(context);
        int lastSessionId = store.getInstallSessionId();
        if (!UpdateInstallState.isCurrentSession(sessionId, lastSessionId)) {
            // 上一次提交的会话被超时/重试放弃，弹窗却还开着：用户晚点关掉它，回调才到这里。
            // 既不能把「安装成功」改写成失败，也不能弹出过期的安装界面，直接丢弃。
            WandLog.w(TAG, "忽略过期安装会话回调 sessionId=" + sessionId
                    + "（最近提交的会话是 " + lastSessionId + "）", null);
            return;
        }

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
            store.markInstallSucceeded();
            String installed = store.getPendingInstallVersion();
            WandLog.i(TAG, "安装成功，准备重启到新版本 " + (installed == null ? "" : installed));
            notifyStatus(status, null);
            bringAppBack(context);
            return;
        }

        // 失败 / 用户取消：清掉待安装状态，避免重启后仍显示「待安装」。
        store.clearInstallState();
        String message = describeFailure(status, statusMessage);
        WandLog.w(TAG, "安装未完成：" + message, null);
        notifyStatus(status, message);
        if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 安装成功后把应用交回用户：先试一次直接拉起（OEM 放行时体验最好），无论成败都发一条
     * 「更新已完成」通知作为兜底 —— 通知点击由系统 UI 代发起，不受后台启动限制。
     */
    private static void bringAppBack(Context context) {
        launchApp(context, "安装完成");
        postReadyNotification(context);
    }

    /** 尝试把应用拉回前台；被后台启动限制拦下时不会抛异常，只会在 logcat 留 BAL 记录。 */
    private static void launchApp(Context context, String reason) {
        try {
            context.startActivity(relaunchIntent(context));
            WandLog.i(TAG, "已请求回到前台（" + reason + "）");
        } catch (Exception e) {
            WandLog.w(TAG, "回到前台失败（" + reason + "）", e);
        }
    }

    /**
     * 「更新已完成」通知。系统安装完成时应用进程已被杀掉，唯一可靠的回前台方式就是让用户
     * 点一下通知；任意 Activity 恢复时会把它撤掉（见 {@link #cancelRelaunch}）。
     */
    private static void postReadyNotification(Context context) {
        try {
            NotificationHelper helper = new NotificationHelper(context);
            helper.createChannels();
            if (!helper.hasPostNotificationPermission()) {
                WandLog.w(TAG, "没有通知权限，无法提示用户回到应用", null);
                return;
            }
            PendingIntent tap = PendingIntent.getActivity(
                    context,
                    RELAUNCH_REQUEST_CODE,
                    relaunchIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    relaunchOptions());
            Notification notification =
                    new NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID_UPDATES)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("更新已完成")
                            .setContentText("点击打开新版本的 Wand")
                            .setContentIntent(tap)
                            .setAutoCancel(true)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                            .build();
            NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID_UPDATE_READY, notification);
            WandLog.i(TAG, "已发出「更新已完成」通知，等待用户点击回到应用");
        } catch (Exception e) {
            WandLog.w(TAG, "发送更新完成通知失败", e);
        }
    }

    /** 用户自己先回到了应用：撤掉通知与残留的 PendingIntent，避免重复拉起。 */
    static void cancelRelaunch(Context context) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.cancel(NOTIFICATION_ID_UPDATE_READY);
        } catch (Exception ignored) {
            // 通知已经消失或没有权限，忽略。
        }
        PendingIntent pending = PendingIntent.getActivity(
                context,
                RELAUNCH_REQUEST_CODE,
                relaunchIntent(context),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE,
                relaunchOptions());
        if (pending != null) pending.cancel();
    }

    /**
     * 拉起应用的目标 Intent。构造参数必须与 {@link #cancelRelaunch} 完全一致，
     * 否则 PendingIntent 身份不同，撤销会失效。
     */
    private static Intent relaunchIntent(Context context) {
        return new Intent(context, ConnectActivity.class)
                // CLEAR_TASK：更新后旧任务的恢复栈已经没有意义（进程已换），直接重新进入。
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /**
     * 拉起用的 ActivityOptions：目标 SDK ≥ 35 时创建方默认不传递自己的后台启动特权，
     * 这里显式允许（用户点通知时由系统 UI 代发起，本身就不受限制，这里是额外保险）。
     */
    private static android.os.Bundle relaunchOptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null;
        return ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
    }

    /**
     * 面向用户的失败文案 + 系统原始信息。PackageInstaller 的 message 是英文的
     * （INSTALL_FAILED_ABORTED: User rejected permissions），直接展示看不懂，
     * 但排查又需要原始值，所以中文在前、原始信息在后。
     */
    static String describeFailure(int status, String rawMessage) {
        String summary;
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                summary = "已取消安装";
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                summary = "被系统或安全软件拦截";
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                summary = "与已安装版本的签名冲突，需要先卸载再安装";
                break;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                summary = "安装包与当前系统不兼容";
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                summary = "安装包无效或已损坏";
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                summary = "存储空间不足";
                break;
            default:
                summary = "安装失败（" + statusName(status) + "）";
                break;
        }
        if (rawMessage == null || rawMessage.trim().isEmpty()) return summary;
        return summary + " · " + rawMessage.trim();
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
