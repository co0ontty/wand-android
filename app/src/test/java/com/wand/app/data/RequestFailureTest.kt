package com.wand.app.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestFailureTest {
    @Test
    fun explicitClientRejectionCanRestoreDraftOrRetryCreation() {
        for (status in listOf(400, 401, 403, 404, 413, 422, 429, 499)) {
            val failure = WandApiException(status, "拒收")
            assertTrue("HTTP $status", isDefiniteRequestRejection(failure))
            assertFalse("HTTP $status", isRequestOutcomeUnconfirmed(failure))
        }
    }

    @Test
    fun timeoutConflictAndServerFailureNeverPermitBlindResubmission() {
        for (status in listOf(null, 408, 409, 500, 502, 503, 599)) {
            val failure = WandApiException(status, "未确认")
            assertFalse("HTTP $status", isDefiniteRequestRejection(failure))
            assertTrue("HTTP $status", isRequestOutcomeUnconfirmed(failure))
        }
    }

    @Test
    fun cancellationAndInvalidAcknowledgementDoNotProveRejection() {
        for (failure in listOf(CancellationException("离开页面"), IllegalStateException("回执无效"))) {
            assertFalse(isDefiniteRequestRejection(failure))
            assertTrue(isRequestOutcomeUnconfirmed(failure))
        }
    }

    @Test
    fun unexpectedStatusKeepsExistingDistinctDraftAndCreationPolicies() {
        for (status in listOf(0, 200, 302, 399)) {
            val failure = WandApiException(status, "非预期状态")
            assertFalse(isDefiniteRequestRejection(failure))
            assertFalse(isRequestOutcomeUnconfirmed(failure))
        }
    }
}
