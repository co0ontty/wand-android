package com.wand.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages server URL persistence using SharedPreferences.
 */
public class ServerStore {

    private static final String PREFS_NAME = "wand_servers";
    private static final String KEY_RECENT = "recent_urls";
    private static final String KEY_LAST = "last_url";
    private static final String KEY_APP_TOKEN = "app_token";
    private static final String KEY_APP_ICON = "app_icon";
    private static final String KEY_NOTIFICATION_SOUND = "notification_sound";
    private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
    private static final String KEY_HAPTIC_ENABLED = "haptic_enabled";
    private static final String KEY_BETA_CHANNEL = "update_beta_channel";
    private static final String KEY_APPEARANCE_MODE = "wand.appearanceMode";
    private static final int MAX_RECENT = 5;

    private final SharedPreferences prefs;

    public ServerStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getLastUrl() {
        return prefs.getString(KEY_LAST, "");
    }

    public void setLastUrl(String url) {
        prefs.edit().putString(KEY_LAST, url).apply();
    }

    public List<String> getRecentUrls() {
        List<String> list = new ArrayList<>();
        String json = prefs.getString(KEY_RECENT, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (JSONException e) {
            // ignore
        }
        return list;
    }

    public void addRecentUrl(String url) {
        List<String> list = getRecentUrls();
        list.remove(url);
        list.add(0, url);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray(list);
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply();
    }

    public void removeRecentUrl(String url) {
        List<String> list = getRecentUrls();
        list.remove(url);
        JSONArray arr = new JSONArray(list);
        prefs.edit().putString(KEY_RECENT, arr.toString()).apply();
    }

    public void clearRecent() {
        prefs.edit().putString(KEY_RECENT, "[]").apply();
    }

    public String getSkippedVersion() {
        return prefs.getString("skipped_apk_version", "");
    }

    public String getSkippedVersion(String channel) {
        return prefs.getString(channelKey("skipped_apk_version", channel), "");
    }

    public void setSkippedVersion(String version) {
        prefs.edit().putString("skipped_apk_version", version).apply();
    }

    public void setSkippedVersion(String version, String channel) {
        prefs.edit().putString(channelKey("skipped_apk_version", channel), version).apply();
    }

    public String getDownloadedApkVersion() {
        return prefs.getString("downloaded_apk_version", "");
    }

    public String getDownloadedApkVersion(String channel) {
        return prefs.getString(channelKey("downloaded_apk_version", channel), "");
    }

    public void setDownloadedApkVersion(String version) {
        prefs.edit().putString("downloaded_apk_version", version).apply();
    }

    public void setDownloadedApkVersion(String version, String channel) {
        prefs.edit().putString(channelKey("downloaded_apk_version", channel), version).apply();
    }

    public String getAppToken() {
        return prefs.getString(KEY_APP_TOKEN, "");
    }

    public void setAppToken(String token) {
        prefs.edit().putString(KEY_APP_TOKEN, token).apply();
    }

    public void clearAppToken() {
        prefs.edit().remove(KEY_APP_TOKEN).apply();
    }

    public String getAppIcon() {
        return prefs.getString(KEY_APP_ICON, "shorthair");
    }

    public void setAppIcon(String iconName) {
        prefs.edit().putString(KEY_APP_ICON, iconName).apply();
    }

    public String getNotificationSound() {
        return prefs.getString(KEY_NOTIFICATION_SOUND, "chime");
    }

    public void setNotificationSound(String soundName) {
        prefs.edit().putString(KEY_NOTIFICATION_SOUND, soundName).apply();
    }

    public int getNotificationVolume() {
        return prefs.getInt(KEY_NOTIFICATION_VOLUME, 80);
    }

    public void setNotificationVolume(int volume) {
        prefs.edit().putInt(KEY_NOTIFICATION_VOLUME, Math.max(0, Math.min(100, volume))).apply();
    }

    public boolean isHapticEnabled() {
        return prefs.getBoolean(KEY_HAPTIC_ENABLED, true);
    }

    public void setHapticEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply();
    }

    /** 更新通道：true = beta（接收 -debug 开发构建），false = stable（只提示正式版）。 */
    public boolean isBetaChannel() {
        return prefs.getBoolean(KEY_BETA_CHANNEL, false);
    }

    public void setBetaChannel(boolean enabled) {
        prefs.edit().putBoolean(KEY_BETA_CHANNEL, enabled).apply();
    }

    public String getAppearanceMode() {
        return prefs.getString(KEY_APPEARANCE_MODE, "system");
    }

    public void setAppearanceMode(String mode) {
        String normalized;
        if ("light".equals(mode) || "dark".equals(mode) || "system".equals(mode)) {
            normalized = mode;
        } else {
            normalized = "system";
        }
        prefs.edit().putString(KEY_APPEARANCE_MODE, normalized).apply();
    }

    private static String channelKey(String base, String channel) {
        String normalized = "beta".equals(channel) ? "beta" : "stable";
        return base + "_" + normalized;
    }
}
