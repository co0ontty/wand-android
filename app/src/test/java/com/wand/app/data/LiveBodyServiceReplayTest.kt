package com.wand.app.data

import com.wand.app.ui.screens.MessageDisplayItem
import com.wand.app.ui.screens.SegmentRenderItem
import com.wand.app.ui.screens.DisplayItem
import com.wand.app.ui.screens.collapseActivityItems
import com.wand.app.ui.screens.groupExplorationTurns
import com.wand.app.ui.screens.pairToolBlocks
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional replay of installed-service packets; no network credentials or user bodies in output. */
class LiveBodyServiceReplayTest {
    @Test
    fun installedPacketsReachBodyDisplayWithoutReentering() {
        val path = System.getenv("WAND_LIVE_BODY_SAMPLE")
        assumeTrue("Installed-service evidence is supplied explicitly", !path.isNullOrBlank())
        val sample = JSONObject(File(path!!).readText())
        var state = ChatSessionEventReducer.applySnapshot(
            ChatSessionEventState(), SessionSnapshot.parse(sample.getJSONObject("baseline")),
        )
        val events = sample.getJSONArray("events")
        var outputs = 0
        var bodyUpdates = 0
        for (i in 0 until events.length()) {
            val incoming = WsIncoming.parse(events.getJSONObject(i))
            val event = incoming.toSessionEvent() ?: continue
            state = ChatSessionEventReducer.reduce(state, event)
            if (incoming.type != "output") continue
            outputs++
            val expected = incoming.data?.lastMessage ?: incoming.data?.messages?.lastOrNull() ?: continue
            if (expected.role != "assistant") continue
            val body = expected.content.filterIsInstance<ContentBlock.Text>().filter { it.subagent == null }
            val actual = state.messages.last().content.filterIsInstance<ContentBlock.Text>().filter { it.subagent == null }
            assertEquals("packet $i body lengths survive reducer", body.map { it.text.length }, actual.map { it.text.length })
            if (body.isNotEmpty()) {
                bodyUpdates++
                val displayed = groupExplorationTurns(state.messages).last() as MessageDisplayItem.Turn
                val visible = collapseActivityItems(
                    pairToolBlocks(displayed.turn.content), isLastTurn = true, isResponding = state.isResponding,
                ).filterIsInstance<SegmentRenderItem.Item>().mapNotNull {
                    ((it.item as? DisplayItem.Plain)?.block as? ContentBlock.Text)?.text
                }
                assertEquals("packet $i body lengths reach display", body.map { it.text.length }, visible.map { it.length })
            }
        }
        val history = SessionSnapshot.parse(sample.getJSONObject("history"))
        val reentered = ChatSessionEventReducer.applySnapshot(ChatSessionEventState(), history)
        assertEquals(
            "live body equals reentered REST body",
            reentered.messages.last().content.filterIsInstance<ContentBlock.Text>().map { it.text.length },
            state.messages.last().content.filterIsInstance<ContentBlock.Text>().map { it.text.length },
        )
        assertTrue("real outputs were observed", outputs > 0)
        assertTrue("real body updates were observed", bodyUpdates > 0)
        println("Installed service replay: outputs=$outputs bodyPackets=$bodyUpdates responding=${state.isResponding}")
    }
}
