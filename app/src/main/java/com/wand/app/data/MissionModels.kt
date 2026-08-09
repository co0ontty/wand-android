package com.wand.app.data

import org.json.JSONArray
import org.json.JSONObject

data class AgentActivityItem(
    val sessionId: String,
    val missionId: String?,
    val attemptId: String?,
    val state: String,
    val title: String,
    val summary: String?,
    val provider: String?,
    val cwd: String?,
    val updatedAt: String,
    val readAt: String?,
) {
    companion object {
        fun parseList(array: JSONArray?): List<AgentActivityItem> = array?.parseEach { item ->
            val sessionId = item.str("sessionId") ?: return@parseEach null
            AgentActivityItem(
                sessionId = sessionId,
                missionId = item.str("missionId"),
                attemptId = item.str("attemptId"),
                state = item.str("state") ?: "done",
                title = item.str("title") ?: "Agent 会话",
                summary = item.str("summary"),
                provider = item.str("provider"),
                cwd = item.str("cwd"),
                updatedAt = item.str("updatedAt") ?: "",
                readAt = item.str("readAt"),
            )
        } ?: emptyList()
    }
}

data class MissionAttempt(
    val id: String,
    val missionId: String,
    val sessionId: String?,
    val provider: String,
    val state: String,
    val branch: String?,
    val worktreePath: String?,
    val baseRef: String?,
    val summary: String?,
    val error: String?,
) {
    companion object {
        fun parseList(array: JSONArray?): List<MissionAttempt> = array?.parseEach { item ->
            val id = item.str("id") ?: return@parseEach null
            MissionAttempt(
                id = id,
                missionId = item.str("missionId") ?: "",
                sessionId = item.str("sessionId"),
                provider = item.str("provider") ?: "claude",
                state = item.str("state") ?: "queued",
                branch = item.str("branch"),
                worktreePath = item.str("worktreePath"),
                baseRef = item.str("baseRef"),
                summary = item.str("summary"),
                error = item.str("error"),
            )
        } ?: emptyList()
    }
}

data class MissionReviewComment(
    val id: String,
    val attemptId: String,
    val filePath: String,
    val line: Int?,
    val side: String,
    val body: String,
    val status: String,
) {
    companion object {
        fun parseList(array: JSONArray?): List<MissionReviewComment> = array?.parseEach { item ->
            val id = item.str("id") ?: return@parseEach null
            MissionReviewComment(
                id = id,
                attemptId = item.str("attemptId") ?: "",
                filePath = item.str("filePath") ?: "",
                line = item.int("line"),
                side = item.str("side") ?: "new",
                body = item.str("body") ?: "",
                status = item.str("status") ?: "pending",
            )
        } ?: emptyList()
    }
}

data class MissionInfo(
    val id: String,
    val title: String,
    val prompt: String,
    val cwd: String,
    val status: String,
    val baseRef: String?,
    val attempts: List<MissionAttempt>,
    val comments: List<MissionReviewComment>,
) {
    companion object {
        fun parse(item: JSONObject): MissionInfo = MissionInfo(
            id = item.str("id") ?: "",
            title = item.str("title") ?: "任务",
            prompt = item.str("prompt") ?: "",
            cwd = item.str("cwd") ?: "",
            status = item.str("status") ?: "dispatching",
            baseRef = item.obj("worktree")?.str("baseRef"),
            attempts = MissionAttempt.parseList(item.arr("attempts")),
            comments = MissionReviewComment.parseList(item.arr("comments")),
        )

        fun parseList(array: JSONArray?): List<MissionInfo> = array?.parseEach(::parse) ?: emptyList()
    }
}

data class MissionDiff(
    val missionId: String,
    val attemptId: String,
    val baseRef: String,
    val fileCount: Int,
    val patch: String,
    val truncated: Boolean,
) {
    companion object {
        fun parse(item: JSONObject): MissionDiff = MissionDiff(
            missionId = item.str("missionId") ?: "",
            attemptId = item.str("attemptId") ?: "",
            baseRef = item.str("baseRef") ?: "",
            fileCount = item.arr("files")?.length() ?: 0,
            patch = item.str("patch") ?: "",
            truncated = item.bool("truncated") ?: false,
        )
    }
}

interface MissionsPort {
    suspend fun defaultMissionCwd(): String
    suspend fun fetchInbox(): List<AgentActivityItem>
    suspend fun fetchMissions(): List<MissionInfo>
    suspend fun createMission(
        title: String?,
        prompt: String,
        cwd: String,
        providers: List<String>,
        baseRef: String?,
        sharedDirectories: List<String>,
        copyPaths: List<String>,
    ): MissionInfo
    suspend fun fetchMissionDiff(missionId: String, attemptId: String): MissionDiff
    suspend fun addMissionReviewComment(
        missionId: String,
        attemptId: String,
        filePath: String,
        line: Int?,
        side: String,
        body: String,
    ): MissionReviewComment
    suspend fun sendMissionReview(missionId: String, attemptId: String): List<MissionReviewComment>
    suspend fun archiveMission(missionId: String): MissionInfo
    suspend fun markInboxRead(sessionId: String?)
}
