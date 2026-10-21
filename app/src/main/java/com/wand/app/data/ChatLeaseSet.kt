package com.wand.app.data

/** Weak UI leases, scoped by the hub's exact API identity. No input is buffered or replayed. */
internal class ChatLeaseSet<T : Any>(private val session: (T) -> String?) {
    private val peers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<T, Boolean>())
    fun add(peer: T) { peers.add(peer) }
    fun remove(peer: T) { peers.remove(peer) }
    fun snapshot(): List<T> = peers.toList()
    fun uses(id: String): Boolean = peers.any { session(it) == id }
    fun isEmpty(): Boolean = peers.isEmpty()
}
