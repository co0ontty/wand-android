package com.wand.app

import java.util.Locale

internal enum class ByteSizeUnit { Megabytes, Gigabytes }

/** 下载、报告文件共用的二进制字节换算；保留各入口的语言与最大单位。 */
internal object ByteSizeFormatter {
    @JvmStatic
    @JvmOverloads
    fun format(
        bytes: Long,
        locale: Locale = Locale.getDefault(),
        maximumUnit: ByteSizeUnit = ByteSizeUnit.Gigabytes,
    ): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(locale, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 || maximumUnit == ByteSizeUnit.Megabytes ->
            String.format(locale, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(locale, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
