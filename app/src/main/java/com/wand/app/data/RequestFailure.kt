package com.wand.app.data

/** 请求已发出后的失败分类；取消或解析失败不能证明服务端未执行。 */
private enum class RequestFailureOutcome { Rejected, Unconfirmed, UnexpectedStatus }

private fun requestFailureOutcome(error: Throwable): RequestFailureOutcome {
    val status = (error as? WandApiException)?.status
    return when {
        status == null || status >= 500 || status == 408 || status == 409 -> RequestFailureOutcome.Unconfirmed
        status in 400..499 -> RequestFailureOutcome.Rejected
        else -> RequestFailureOutcome.UnexpectedStatus
    }
}

internal fun isDefiniteRequestRejection(error: Throwable): Boolean =
    requestFailureOutcome(error) == RequestFailureOutcome.Rejected

internal fun isRequestOutcomeUnconfirmed(error: Throwable): Boolean =
    requestFailureOutcome(error) == RequestFailureOutcome.Unconfirmed
