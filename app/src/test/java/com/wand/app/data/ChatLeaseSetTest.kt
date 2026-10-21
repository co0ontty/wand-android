package com.wand.app.data
import org.junit.Assert.*
import org.junit.Test

class ChatLeaseSetTest {
    private class Peer(var session: String, var active: Boolean = true)
    @Test fun closingAndSwitchingOneLeaseDoesNotUnsubscribeAnotherReader() {
        val leases = ChatLeaseSet<Peer> { if (it.active) it.session else null }
        val a = Peer("a"); val b = Peer("b"); val a2 = Peer("a")
        leases.add(a); leases.add(b); leases.add(a2)
        assertEquals(3, leases.snapshot().size)
        leases.remove(a); assertTrue(leases.uses("a")); assertTrue(leases.uses("b"))
        a2.session = "c"; assertFalse(leases.uses("a")); assertTrue(leases.uses("c"))
        b.active = false; assertFalse(leases.uses("b"))
        leases.remove(b); leases.remove(a2); assertTrue(leases.isEmpty())
        a.active = true; leases.add(a); assertTrue(leases.uses("a"))
        val otherIdentity = ChatLeaseSet<Peer> { it.session }
        assertFalse(otherIdentity.uses("a"))
    }
}
