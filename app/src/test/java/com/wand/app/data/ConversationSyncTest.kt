package com.wand.app.data
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationSyncTest {
    @Test fun appliesEditsRemovalsAndHistoryExpansionWithoutDuplicatingRows() {
        val old = JSONObject("""{"id":"c","messages":[{"messageId":"a","content":[]},{"messageId":"b","content":[]}]}""")
        val update = JSONObject("""{"id":"c","history":{"revision":"2","from":"anchor","hasEarlier":true,"total":5},"messageOrder":["b","d"],"messages":[{"messageId":"b","content":[{"type":"text","text":"edit"}]},{"messageId":"d","content":[]}]}""")
        val result = mergeConversationUpdate(old, update)
        assertEquals(2, result.getJSONArray("messages").length())
        assertEquals("b", result.getJSONArray("messages").getJSONObject(0).getString("messageId"))
        assertEquals("edit", result.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
        val unchanged = mergeConversationUpdate(result, JSONObject("""{"id":"c","unchanged":true,"history":{"revision":"2","hasEarlier":true,"total":5}}"""))
        assertEquals(result.getJSONArray("messages").toString(), unchanged.getJSONArray("messages").toString())
        val reset = mergeConversationUpdate(result, JSONObject("""{"id":"c","reset":true,"detail":{"id":"c"},"history":{},"messageOrder":["z"],"messages":[{"messageId":"z"}]}"""))
        assertEquals(1, reset.getJSONArray("messages").length())
        assertEquals("z", reset.getJSONArray("messages").getJSONObject(0).getString("messageId"))
    }
    @Test fun cannotUseAnotherConversationAsABaseline() {
        assertThrows(IllegalStateException::class.java) { mergeConversationUpdate(JSONObject("""{"id":"other","messages":[]}"""),
            JSONObject("""{"id":"c","unchanged":true,"history":{}}""")) }
    }
}
