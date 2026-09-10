package com.wand.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class NetworkStateTrackerTest {

    @Test
    public void initialCallbackDoesNotLookLikeANetworkChange() {
        NetworkStateTracker<String> tracker = new NetworkStateTracker<>("wifi", true);

        assertNull(tracker.onAvailable("wifi"));
        assertNull(tracker.onCapabilitiesChanged("wifi", true, true));
        assertTrue(tracker.hasUsableNetwork());
    }

    @Test
    public void validationAfterAvailableGetsASecondRecoverySignal() {
        NetworkStateTracker<String> tracker = new NetworkStateTracker<>(null, false);

        assertEquals("available", tracker.onAvailable("wifi"));
        assertEquals("validated", tracker.onCapabilitiesChanged("wifi", true, true));
        assertNull(tracker.onCapabilitiesChanged("wifi", true, true));
    }

    @Test
    public void oldNetworkLossDoesNotHideAReplacementNetwork() {
        NetworkStateTracker<String> tracker = new NetworkStateTracker<>("wifi", true);

        assertEquals("changed", tracker.onAvailable("mobile"));
        assertNull(tracker.onLost("wifi"));
        assertTrue(tracker.hasUsableNetwork());
        assertEquals("validated", tracker.onCapabilitiesChanged("mobile", true, true));
    }

    @Test
    public void lossThenRecoveryProducesAvailableAndValidated() {
        NetworkStateTracker<String> tracker = new NetworkStateTracker<>("wifi", true);

        assertEquals("lost", tracker.onLost("wifi"));
        assertFalse(tracker.hasUsableNetwork());
        assertEquals("available", tracker.onAvailable("mobile"));
        assertEquals("validated", tracker.onCapabilitiesChanged("mobile", true, true));
    }
}
