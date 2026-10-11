package com.wand.app.ui.components

import com.wand.app.data.ContentBlock
import com.wand.app.data.ConversationTurn

enum class AvatarFileActivity(val wire: String) { Idle("idle"), Reading("reading"), Writing("writing") }

/** Matches composer-avatar-activity.ts: actual unfinished parent tools, current user round only. */
fun avatarFileActivity(messages: List<ConversationTurn>, executing: Boolean): AvatarFileActivity {
    if (!executing) return AvatarFileActivity.Idle
    val start = messages.indexOfLast { it.role == "user" }
    val calls = linkedMapOf<String, AvatarFileActivity>()
    val completed = mutableSetOf<String>()
    for (turn in messages.drop(start + 1)) for (block in turn.content) {
        when (block) {
            is ContentBlock.ToolResult -> if (block.subagent == null) completed.add(block.toolUseId)
            is ContentBlock.ToolUse -> if (block.subagent == null && turn.completedAt == null) {
                when (block.activity?.kind) {
                    "read_file" -> calls[block.id] = AvatarFileActivity.Reading
                    "edit_file" -> calls[block.id] = AvatarFileActivity.Writing
                }
            }
            else -> Unit
        }
    }
    return calls.entries.lastOrNull { it.key !in completed }?.value ?: AvatarFileActivity.Idle
}

/** Text streaming, tool execution and waiting without a thinking event are not thinking. */
fun companionThinking(messages: List<ConversationTurn>, executing: Boolean, activity: AvatarFileActivity): Boolean {
    if (!executing || activity != AvatarFileActivity.Idle) return false
    val start = messages.indexOfLast { it.role == "user" }
    val assistant = messages.drop(start + 1).lastOrNull { it.role == "assistant" } ?: return false
    if (assistant.completedAt != null) return false
    val last = assistant.content.lastOrNull() ?: return false
    return last is ContentBlock.Thinking && last.subagent == null
}
