package com.wand.app.data

import org.json.JSONObject

/** Files on the connected server, independent of the session execution engine or Git. */
interface SessionFilesPort {
    suspend fun listDirectory(path: String): DirectoryListing
    suspend fun previewFile(path: String): ServerFilePreview
    suspend fun writeFile(file: ServerFilePreview, content: String): ServerFileWriteResult
}

data class ServerFilePreview(
    val path: String,
    val name: String,
    val kind: String,
    val size: Long?,
    val mtime: String?,
    val content: String? = null,
) {
    val canEdit: Boolean get() = kind == "text" && content != null && size != null && !mtime.isNullOrBlank()

    companion object {
        fun parse(json: JSONObject) = ServerFilePreview(
            path = json.getString("path"),
            name = json.getString("name"),
            kind = json.getString("kind"),
            size = if (json.isNull("size")) null else json.getLong("size"),
            mtime = json.str("mtime"),
            content = json.str("content"),
        )
    }
}

data class ServerFileWriteResult(val size: Long, val mtime: String) {
    companion object {
        fun parse(json: JSONObject): ServerFileWriteResult {
            require(json.optBoolean("ok")) { "服务端未确认保存，请重新读取文件核对" }
            return ServerFileWriteResult(json.getLong("size"), json.getString("mtime"))
        }
    }
}
