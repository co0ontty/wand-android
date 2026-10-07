package com.wand.app.data

/** 项目目录的比较键：保留根目录，去除首尾空白与尾部分隔符，不解析服务端文件系统。 */
internal fun normalizeWorkspacePath(path: String): String =
    path.trim().trimEnd('/').ifEmpty { "/" }
