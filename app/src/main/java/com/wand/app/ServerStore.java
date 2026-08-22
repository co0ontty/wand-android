package com.wand.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.wand.app.data.ServerProfile;
import com.wand.app.data.ServerProfiles;
import com.wand.app.data.ServerProfilesState;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Manages server URL persistence using SharedPreferences.
 */
public class ServerStore {

    private static final String PREFS_NAME = "wand_servers";
    private static final String KEY_RECENT = "recent_urls";
    private static final String KEY_LAST = "last_url";
    private static final String KEY_APP_TOKEN = "app_token";
    private static final String KEY_SERVER_PROFILES_STATE = "server_profiles_v2";
    private static final String KEY_SERVER_PROFILES_LEGACY_FINGERPRINT =
            "server_profiles_v2_legacy_fingerprint";
    private static final String KEY_NOTIFICATION_SOUND_ENABLED = "notification_sound_enabled";
    private static final String KEY_NOTIFICATION_SOUND = "notification_sound";
    private static final String KEY_NOTIFICATION_VOLUME = "notification_volume";
    private static final String KEY_HAPTIC_ENABLED = "haptic_enabled";
    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";
    private static final String KEY_BETA_CHANNEL = "update_beta_channel";
    private static final String KEY_APPEARANCE_MODE = "wand.appearanceMode";
    private static final int MAX_RECENT = 5;
    private static final Object PROFILE_LOCK = new Object();

    private final SharedPreferences prefs;

    public ServerStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Returns all saved endpoints in most-recently-selected order. */
    public List<ServerProfile> getServerProfiles() {
        synchronized (PROFILE_LOCK) {
            return new ArrayList<>(readProfileStateLocked().getProfiles());
        }
    }

    public ServerProfile getServerProfile(String id) {
        if (id == null) return null;
        synchronized (PROFILE_LOCK) {
            for (ServerProfile profile : readProfileStateLocked().getProfiles()) {
                if (id.equals(profile.getId())) return profile;
            }
            return null;
        }
    }

    public ServerProfile getServerProfileByUrl(String url) {
        if (url == null) return null;
        synchronized (PROFILE_LOCK) {
            return ServerProfiles.profileByUrl(readProfileStateLocked(), url);
        }
    }

    public ServerProfile getActiveServerProfile() {
        synchronized (PROFILE_LOCK) {
            ServerProfilesState state = readProfileStateLocked();
            String activeId = state.getActiveServerId();
            if (activeId == null) return null;
            for (ServerProfile profile : state.getProfiles()) {
                if (activeId.equals(profile.getId())) return profile;
            }
            return null;
        }
    }

    /** Saves or replaces the endpoint credential. A null token explicitly clears an old token. */
    public ServerProfile saveServerProfile(String baseUrl, String token) {
        synchronized (PROFILE_LOCK) {
            ServerProfilesState next = ServerProfiles.withSavedProfile(
                    readProfileStateLocked(), baseUrl, token);
            writeProfileStateLocked(next);
            return next.getProfiles().get(0);
        }
    }

    /** Selects a saved endpoint. Null disconnects without deleting any saved profiles. */
    public void setActiveServerId(String id) {
        synchronized (PROFILE_LOCK) {
            ServerProfilesState current = readProfileStateLocked();
            ServerProfilesState next = ServerProfiles.withActiveServerId(current, id);
            if (!next.equals(current)) writeProfileStateLocked(next);
        }
    }

    /** Removing the active endpoint deterministically activates the first remaining profile. */
    public void removeServerProfile(String id) {
        if (id == null) return;
        synchronized (PROFILE_LOCK) {
            ServerProfilesState current = readProfileStateLocked();
            ServerProfilesState next = ServerProfiles.withoutProfile(current, id);
            if (!next.equals(current)) writeProfileStateLocked(next);
        }
    }

    public void clearServerProfiles() {
        synchronized (PROFILE_LOCK) {
            writeProfileStateLocked(new ServerProfilesState());
        }
    }

    private ServerProfilesState readProfileStateLocked() {
        if (prefs.contains(KEY_SERVER_PROFILES_STATE)) {
            ServerProfilesState decoded = ServerProfiles.decodeOrNull(
                    prefs.getString(KEY_SERVER_PROFILES_STATE, null));
            String storedFingerprint = prefs.getString(
                    KEY_SERVER_PROFILES_LEGACY_FINGERPRINT, null);
            String currentFingerprint = currentLegacyFingerprint();
            if (decoded != null && storedFingerprint == null) {
                // A previous v2 build had no marker. Trust it only when its projected legacy view
                // still equals the actual legacy keys; a mismatch means an older APK changed them.
                if (legacyFingerprintForState(decoded).equals(currentFingerprint)) {
                    writeProfileStateLocked(decoded);
                    return decoded;
                }
            }
            if (decoded != null && storedFingerprint != null
                    && storedFingerprint.equals(currentFingerprint)) {
                return decoded;
            }
            // Corrupt v2, or legacy keys changed while an older APK was installed. Import the
            // downgrade-era state instead of resurrecting stale profiles/credentials.
        }
        ServerProfilesState migrated = ServerProfiles.migrateLegacy(
                getLastUrl(), getRecentUrls(), getAppToken());
        writeProfileStateLocked(migrated);
        return migrated;
    }

    private void writeProfileStateLocked(ServerProfilesState state) {
        JSONArray recent = new JSONArray();
        ServerProfile active = null;
        for (ServerProfile profile : state.getProfiles()) {
            // Keep the legacy view downgrade-compatible without ever writing a raw connect code.
            recent.put(profile.getBaseUrl());
            if (profile.getId().equals(state.getActiveServerId())) active = profile;
        }

        String legacyLast = active == null ? "" : active.getBaseUrl();
        String legacyToken = active == null || active.getToken() == null
                ? "" : active.getToken();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(KEY_SERVER_PROFILES_STATE, ServerProfiles.encode(state))
                .putString(KEY_RECENT, recent.toString())
                .putString(
                        KEY_SERVER_PROFILES_LEGACY_FINGERPRINT,
                        legacyFingerprint(legacyLast, recent.toString(), legacyToken));
        if (active == null) {
            editor.putString(KEY_LAST, "").remove(KEY_APP_TOKEN);
        } else {
            editor.putString(KEY_LAST, active.getBaseUrl());
            if (active.getToken() == null || active.getToken().isEmpty()) {
                editor.remove(KEY_APP_TOKEN);
            } else {
                editor.putString(KEY_APP_TOKEN, active.getToken());
            }
        }
        editor.apply();
    }

    private String currentLegacyFingerprint() {
        return legacyFingerprint(
                prefs.getString(KEY_LAST, ""),
                prefs.getString(KEY_RECENT, "[]"),
                prefs.getString(KEY_APP_TOKEN, ""));
    }

    private static String legacyFingerprintForState(ServerProfilesState state) {
        JSONArray recent = new JSONArray();
        ServerProfile active = null;
        for (ServerProfile profile : state.getProfiles()) {
            recent.put(profile.getBaseUrl());
            if (profile.getId().equals(state.getActiveServerId())) active = profile;
        }
        String last = active == null ? "" : active.getBaseUrl();
        String token = active == null || active.getToken() == null ? "" : active.getToken();
        return legacyFingerprint(last, recent.toString(), token);
    }

    private static String legacyFingerprint(String last, String recent, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigestField(digest, last);
            updateDigestField(digest, recent);
            updateDigestField(digest, token);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void updateDigestField(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
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

    public String getNotificationSound() {
        return prefs.getString(KEY_NOTIFICATION_SOUND, "chime");
    }

    public boolean isNotificationSoundEnabled() {
        return prefs.getBoolean(KEY_NOTIFICATION_SOUND_ENABLED, true);
    }

    public void setNotificationSoundEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND_ENABLED, enabled).apply();
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

    public boolean isKeepAliveEnabled() {
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    public void setKeepAliveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
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
