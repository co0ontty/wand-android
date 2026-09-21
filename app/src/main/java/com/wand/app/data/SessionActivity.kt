package com.wand.app.data

private val PROVIDER_CLI = setOf("claude", "codex", "opencode", "grok", "qoder", "pi")

fun isProviderCliSession(provider: String?): Boolean {
    val id = provider?.trim()?.lowercase().orEmpty()
    return id.isNotEmpty() && id in PROVIDER_CLI
}

/** Web `ptyTurnActive`: provider CLI needs ptyBusy; bare shells stay busy while running. */
fun ptyTurnActive(
    sessionKind: String?,
    status: String?,
    provider: String?,
    ptyBusy: Boolean?,
): Boolean {
    if ((sessionKind ?: "pty") == "structured") return false
    if (status != "running") return false
    if (isProviderCliSession(provider)) return ptyBusy == true
    return true
}

fun sessionIsResponding(
    sessionKind: String?,
    status: String?,
    provider: String?,
    ptyBusy: Boolean?,
    providerCliActive: Boolean?,
    inFlight: Boolean?,
): Boolean {
    if ((sessionKind ?: "pty") == "structured") return inFlight == true
    if (providerCliActive == false) return false
    return ptyTurnActive(sessionKind, status, provider, ptyBusy)
}

/**
 * Status string for dots/labels. Provider CLI sitting at a prompt is idle,
 * not running — matching Web `isIdleAtPrompt`.
 */
fun effectiveSessionStatus(
    sessionKind: String?,
    status: String?,
    provider: String?,
    ptyBusy: Boolean?,
    providerCliActive: Boolean?,
    inFlight: Boolean?,
    permissionBlocked: Boolean = false,
): String {
    if (permissionBlocked) return "permission"
    if (sessionIsResponding(sessionKind, status, provider, ptyBusy, providerCliActive, inFlight)) {
        return if (status == "thinking") "thinking" else "running"
    }
    val normalized = status?.trim().orEmpty()
    return if (normalized.isEmpty() || normalized in BUSY_STATUSES) "idle" else normalized
}

/** 没有实时活动时仍需归一成 idle 的状态值。 */
private val BUSY_STATUSES = setOf("running", "initializing", "thinking")

fun SessionSnapshot.activityStatus(): String = effectiveSessionStatus(
    sessionKind = sessionKind,
    status = status,
    provider = provider,
    ptyBusy = ptyBusy,
    providerCliActive = providerCliActive,
    inFlight = structuredState?.inFlight,
    permissionBlocked = hasPendingPermission,
)

fun WorkspaceSessionSummary.activityStatus(): String = effectiveSessionStatus(
    sessionKind = sessionKind,
    status = status,
    provider = provider,
    ptyBusy = ptyBusy,
    providerCliActive = providerCliActive,
    inFlight = inFlight,
)
