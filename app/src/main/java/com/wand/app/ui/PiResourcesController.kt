package com.wand.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.PiResourceKind
import com.wand.app.data.PiResourceSelection
import com.wand.app.data.PiResourcesPort
import com.wand.app.data.PiResourcesResponse
import com.wand.app.data.PiSkillMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Session-bound disclosure state. No draft/attachment/send-lock ownership. */
class PiResourcesController(private val id: String, private val port: PiResourcesPort, private val scope: CoroutineScope) {
    var open by mutableStateOf(false); private set
    var data by mutableStateOf<PiResourcesResponse?>(null); private set
    var phase by mutableStateOf("idle"); private set
    var message by mutableStateOf(""); private set
    var query by mutableStateOf("")
    private var revision = 0L
    private var request: Job? = null
    val busy: Boolean get() = phase == "loading" || phase == "saving"

    fun toggle() { if (open) dismiss() else { open = true; load() } }
    fun dismiss() {
        open = false; query = ""; revision++; request?.cancel(); request = null; phase = "idle"
    }
    fun load() {
        if (!open) return
        val key = ++revision
        request?.cancel(); data = null; phase = "loading"; message = "正在读取已安装的 Skills / MCP…"
        request = scope.launch {
            try {
                val result = port.getPiResources(id)
                if (!open || key != revision) return@launch
                data = result; phase = "ready"
                message = if (result.supported) "修改后从下一轮生效，并成为新建 Pi 会话的默认" else result.reason
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!open || key != revision) return@launch
                phase = "failed"; message = error.message ?: "读取失败，请重试"
            }
        }
    }
    fun toggleItem(kind: PiResourceKind, itemId: String, checked: Boolean) {
        save((data?.selection ?: PiResourceSelection()).toggle(kind, itemId, checked))
    }
    fun setSkillMode(itemId: String, mode: PiSkillMode) {
        val current = data ?: return
        if (!open || !current.supported || !current.skillLocksAvailable || busy) return
        val expected = current.withSkillMode(itemId, mode)
        val key = ++revision
        phase = "saving"; message = "正在保存 Skill 设置…"
        request = scope.launch {
            try {
                val saved = port.setPiSkillSelection(id, expected)
                if (!open || key != revision) return@launch
                check(saved == expected) { "服务端未确认 Skill 开关或锁定，请重新读取核对" }
                data = current.copy(selection = saved.selection, lockedSkills = saved.lockedSkills)
                phase = "saved"; message = "已保存 · 下一轮生效"
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!open || key != revision) return@launch
                phase = "failed"; message = error.message ?: "保存失败，原开关和锁定保持"
            }
        }
    }

    fun setAutomaticResources(enabled: Boolean) {
        val current = data ?: return
        if (!open || !current.supported || busy || (enabled && !current.autoResourcesAvailable)) return
        val key = ++revision
        phase = "saving"; message = "正在保存自动配置…"
        request = scope.launch {
            try {
                val saved = port.setPiAutoResources(id, enabled)
                if (!open || key != revision) return@launch
                val expected = current.selection ?: if (enabled) PiResourceSelection() else null
                check(saved.enabled == enabled && saved.selection == expected) {
                    "服务端未确认自动配置或手选项，请重新读取核对"
                }
                data = current.copy(autoResources = saved.enabled, selection = saved.selection)
                phase = "saved"; message = "已保存 · 自动配置从下一轮生效"
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!open || key != revision) return@launch
                phase = "failed"; message = error.message ?: "保存失败，原开关未改变"
            }
        }
    }

    fun setCodemode(mode: String) {
        val current = data ?: return
        if (!open || !current.codemodeAvailable || busy || mode !in listOf("follow", "off", "on", "only")) return
        val key = ++revision
        phase = "saving"; message = "正在保存 CodeMode…"
        request = scope.launch {
            try {
                val saved = port.setPiCodemode(id, mode)
                if (!open || key != revision) return@launch
                check(saved == mode) { "服务端未确认 CodeMode 设置，请重新读取核对" }
                data = current.copy(codemode = saved); phase = "saved"
                message = "已保存 · CodeMode 从下一轮生效"
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!open || key != revision) return@launch
                phase = "failed"; message = error.message ?: "保存失败，原模式未改变"
            }
        }
    }

    fun save(selection: PiResourceSelection) {
        val current = data ?: return
        if (!open || !current.supported || busy) return
        val key = ++revision
        phase = "saving"; message = "正在保存选择…"
        request = scope.launch {
            try {
                val saved = port.setPiResources(id, selection)
                if (!open || key != revision) return@launch
                check(saved == selection) { "服务端未确认本次资源选择，请重新读取核对" }
                data = current.copy(selection = saved); phase = "saved"
                message = "已保存 · 下一轮生效"
            } catch (_: CancellationException) {
            } catch (error: Exception) {
                if (!open || key != revision) return@launch
                phase = "failed"; message = error.message ?: "保存失败，原选择未改变"
            }
        }
    }
}
