package com.wand.app.data

import org.json.JSONObject

data class SessionDirectoryTree(val path: String, val name: String, val branch: String)
data class SessionDirectory(val root: String, val branch: String, val isGit: Boolean, val worktrees: List<SessionDirectoryTree>) {
    companion object {
        fun parse(o: JSONObject): SessionDirectory {
            val trees = o.optJSONArray("worktrees")
            return SessionDirectory(o.getString("root"), o.getString("branch"), o.getBoolean("isGit"),
                (0 until (trees?.length() ?: 0)).map { index -> val t = trees!!.getJSONObject(index); SessionDirectoryTree(t.getString("path"), t.getString("name"), t.getString("branch")) })
        }
    }
}
