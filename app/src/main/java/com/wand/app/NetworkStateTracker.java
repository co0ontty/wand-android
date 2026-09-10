package com.wand.app;

import java.util.Objects;

/**
 * Small, thread-safe state machine for the default network callback.
 *
 * Android reports a network as available before its internet validation finishes. Keeping those
 * two transitions separate is important: a reconnect triggered by onAvailable may still fail, and
 * the later validated callback must get a chance to trigger another attempt.
 */
final class NetworkStateTracker<T> {

    private T activeNetwork;
    private boolean validated;

    NetworkStateTracker(T activeNetwork, boolean validated) {
        this.activeNetwork = activeNetwork;
        this.validated = activeNetwork != null && validated;
    }

    synchronized String onAvailable(T network) {
        if (network == null) return null;
        if (Objects.equals(activeNetwork, network)) return null;

        boolean hadNetwork = activeNetwork != null;
        activeNetwork = network;
        validated = false;
        return hadNetwork ? "changed" : "available";
    }

    synchronized String onCapabilitiesChanged(
            T network,
            boolean hasInternet,
            boolean isValidated
    ) {
        if (!Objects.equals(activeNetwork, network)) return null;
        if (!hasInternet) {
            validated = false;
            return null;
        }
        if (!isValidated) {
            validated = false;
            return null;
        }
        if (validated) return null;
        validated = true;
        return "validated";
    }

    synchronized String onLost(T network) {
        if (!Objects.equals(activeNetwork, network)) return null;
        activeNetwork = null;
        validated = false;
        return "lost";
    }

    synchronized boolean hasUsableNetwork() {
        return activeNetwork != null;
    }
}
