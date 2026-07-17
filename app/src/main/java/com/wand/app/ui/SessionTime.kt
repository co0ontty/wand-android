package com.wand.app.ui

import java.time.Instant

internal fun parseIsoMillis(value: String?): Long? =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
