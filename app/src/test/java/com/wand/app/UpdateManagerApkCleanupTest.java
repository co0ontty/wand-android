package com.wand.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 启动清扫保留规则的核心：按主版本号判断一个落盘 APK 是不是待安装更新。 */
public class UpdateManagerApkCleanupTest {

    @Test
    public void extractsVersionsFromRealFileNames() {
        assertEquals("4.42.1-debug.08150708",
                UpdateManager.extractVersionFromFileName("wand-v4.42.1-debug.08150708.apk"));
        assertEquals("2.13.0+202607121802",
                UpdateManager.extractVersionFromFileName("wand-v2.13.0+202607121802.apk"));
        assertEquals("1.53.1", UpdateManager.extractVersionFromFileName("wand-v1.53.1.apk"));
        assertNull(UpdateManager.extractVersionFromFileName("wand-update.apk"));
        assertNull(UpdateManager.extractVersionFromFileName(null));
    }

    @Test
    public void sanitizesGitHubBuildMetadataPlusSigns() {
        assertEquals("wand-v4.48.0-202608232136.apk",
                UpdateManager.sanitizeApkFileName("wand-v4.48.0+202608232136.apk"));
        assertEquals("wand-v4.48.0-202608232136.apk",
                UpdateManager.sanitizeApkFileName("dir/wand-v4.48.0+202608232136.apk"));
        assertEquals("wand-update.apk", UpdateManager.sanitizeApkFileName(""));
        assertEquals("wand-update.apk", UpdateManager.sanitizeApkFileName(null));
        assertEquals("4.48.0-202608232136",
                UpdateManager.extractVersionFromFileName(
                        UpdateManager.sanitizeApkFileName("wand-v4.48.0+202608232136.apk")));
    }

    @Test
    public void sameCoreVersionWithDifferentDebugStampIsNotNewer() {
        // 已装 4.42.1-debug.08132148，磁盘上留的 4.42.1-debug.08150708 主版本相同：
        // 这是已消费过的包（或同版本重下的），不是待安装更新，清扫时应删除。
        assertFalse(UpdateManager.isNewerCoreVersion(
                "4.42.1-debug.08150708", "4.42.1-debug.08132148"));
    }

    @Test
    public void higherOrLowerCoreVersionDetected() {
        assertTrue(UpdateManager.isNewerCoreVersion("4.43.0", "4.42.1-debug.08150708"));
        assertTrue(UpdateManager.isNewerCoreVersion("4.42.2+202608092056", "4.42.1"));
        assertTrue(UpdateManager.isNewerCoreVersion("5.0.0", "4.99.9"));
        assertFalse(UpdateManager.isNewerCoreVersion("4.41.0", "4.42.1"));
        assertFalse(UpdateManager.isNewerCoreVersion("4.42.1", "4.42.1"));
    }

    @Test
    public void unparsableVersionsAreNeverKept() {
        assertFalse(UpdateManager.isNewerCoreVersion(null, "4.42.1"));
        assertFalse(UpdateManager.isNewerCoreVersion("latest", "4.42.1"));
        assertFalse(UpdateManager.isNewerCoreVersion("4.42.1", null));
    }
}
