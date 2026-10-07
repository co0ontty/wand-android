package com.wand.app.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wand.app.data.isRequestOutcomeUnconfirmed
import kotlinx.coroutines.CancellationException

/** 新对话创建共用的单次请求状态。各入口负责身份、目录和回执校验。 */
internal open class ConversationCreation<T>(private val label: String) {
    var busy by mutableStateOf(false)
        private set
    protected var created by mutableStateOf<T?>(null)
        private set
    var creationUnconfirmed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        protected set

    protected val canCreate: Boolean get() = !busy && created == null && !creationUnconfirmed

    /** prepare 失败时尚未发出创建请求，允许重试；request 包含回执校验。 */
    protected suspend fun createOnce(
        prepare: suspend () -> Unit = {},
        request: suspend () -> T,
    ): T? {
        if (!canCreate) return null
        busy = true
        error = null
        var requestStarted = false
        try {
            prepare()
            requestStarted = true
            return request().also { created = it }
        } catch (failure: Exception) {
            creationUnconfirmed = requestStarted && isRequestOutcomeUnconfirmed(failure)
            error = if (creationUnconfirmed) {
                "创建结果未确认，请刷新列表并打开新${label}核对，勿重复新建。"
            } else {
                failure.message ?: "创建${label}失败，请稍后重试。"
            }
            if (failure is CancellationException) throw failure
            return null
        } finally {
            busy = false
        }
    }
}
