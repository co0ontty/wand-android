package com.wand.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtyTerminalSnapshotTest {
    @Test fun parsesRenderCheckpointInOperationOrder() {
        val frame = JSONObject("""{
          "type":"init","sessionId":"pty-1","seq":42,
          "data":{"id":"pty-1","terminalState":{
            "version":1,"data":"\\u001b[H你好","cols":80,"rows":24,
            "pending":[{"type":"data","data":"hello"},
                       {"type":"resize","cols":40,"rows":12},
                       {"type":"data","data":"world"}]}}
        }""")
        val incoming = WsIncoming.parse(frame)
        val state = incoming.data?.terminalState
        assertNotNull(state)
        assertTrue(state!!.isReplayable)
        assertEquals(3, state.pending.size)
        assertEquals(PtyTerminalSnapshot.Operation.Data("hello"), state.pending[0])
        assertEquals(PtyTerminalSnapshot.Operation.Resize(40, 12), state.pending[1])
        assertEquals(PtyTerminalSnapshot.Operation.Data("world"), state.pending[2])
        assertEquals(42, incoming.seq)
    }

    @Test fun rejectsIncompleteUnknownOrInvalidSnapshots() {
        val base = """"version":1,"data":"x","cols":80,"rows":24,"pending":[]"""
        assertNull(PtyTerminalSnapshot.parse(JSONObject("{$base}".replace("\"version\":1", "\"version\":2"))))
        assertNull(PtyTerminalSnapshot.parse(JSONObject("{$base}".replace("\"cols\":80", "\"cols\":0"))))
        assertNull(PtyTerminalSnapshot.parse(JSONObject("""{"version":1,"data":"x","cols":80,"rows":24,
          "pending":[{"type":"unknown"}]}""")))
        assertNull(PtyTerminalSnapshot.parse(JSONObject("""{"version":1,"data":"x","cols":80,"rows":24,
          "pending":[{"type":"resize","cols":9999,"rows":24}]}""")))
        assertNull(PtyTerminalSnapshot.parse(JSONObject("""{"version":1,"data":"x","cols":80,"rows":24}""")))
    }

    @Test fun splitsPtyInputWithoutBreakingUtf8ScalarsOrAppendingNewlines() {
        val chunks = WandSocket.ptyInputChunks("你😀ab\r", 6)
        assertEquals("你😀ab\r", chunks.joinToString(""))
        assertEquals(listOf("你", "😀ab", "\r"), chunks)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 6 })
        assertFalse(chunks.first().contains('\n'))
    }
}
