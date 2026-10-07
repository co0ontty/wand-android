package com.wand.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** Compiled here; execution requires separate device authorization. Uses isolated test preferences. */
class ServerStoreHomeListModeInstrumentedTest {
    @Test fun missingKeyAndThreeModeValuesRoundTripWithoutNormalizingImToWork() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = target.getSharedPreferences("im-mode-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        val store = ServerStore(context)
        assertEquals("", store.homeListMode)
        for (mode in listOf("sessions", "board", "im")) {
            store.setHomeListMode(mode)
            assertEquals(mode, ServerStore(context).homeListMode)
        }
        preferences.edit().clear().commit()
    }
}
