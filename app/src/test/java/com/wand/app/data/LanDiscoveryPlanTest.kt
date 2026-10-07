package com.wand.app.data

import org.junit.Assert.*
import org.junit.Test

class LanDiscoveryPlanTest {
    @Test fun discoveryNeverScansPublicOrLoopbackAddresses() {
        for (address in listOf("8.8.8.8", "127.0.0.1", "172.32.1.1", "10.0.0.999", "::1", "example.com")) {
            assertFalse(LanDiscoveryPlan.isPrivateIpv4(address))
            assertTrue(LanDiscoveryPlan.endpoints(address, 24, emptyList()).isEmpty())
        }
    }

    @Test fun aLargePrivateNetworkIsBoundedToTheCurrentSlash24() {
        val endpoints = LanDiscoveryPlan.endpoints("10.20.30.40", 8, emptyList())
        assertEquals(508, endpoints.size)
        assertTrue(endpoints.all { it.startsWith("https://10.20.30.") || it.startsWith("http://10.20.30.") })
        assertTrue(endpoints.none { it.contains(".0:") || it.contains(".255:") })
        assertTrue(endpoints.contains("http://10.20.30.40:8443"))
    }

    @Test fun smallerSubnetMasksAreRespected() {
        val endpoints = LanDiscoveryPlan.endpoints("192.168.5.5", 30, emptyList())
        assertEquals(setOf("http://192.168.5.5:8443", "https://192.168.5.5:8443",
            "http://192.168.5.6:8443", "https://192.168.5.6:8443"), endpoints.toSet())
    }

    @Test fun savedPrivatePortsAreIncludedButCredentialsAndPublicHostsAreNot() {
        val endpoints = LanDiscoveryPlan.endpoints("172.16.4.10", 24, listOf(
            ServerProfile("local", "http://172.16.4.12:8123", "secret-fixture"),
            ServerProfile("public", "https://example.com:9999", "other-fixture"),
        ))
        assertEquals("http://172.16.4.12:8123", endpoints.first())
        assertTrue(endpoints.contains("https://172.16.4.20:8123"))
        assertTrue(endpoints.none { it.contains("secret") || it.contains("9999") || it.contains("example.com") })
    }

    @Test fun discoveryStripsUrlCredentialsAndPrivateQueryDataFromSavedAddresses() {
        val endpoints = LanDiscoveryPlan.endpoints("192.168.1.5", 30,
            listOf(ServerProfile("saved", "https://fixture-user:fixture-password@192.168.1.6:8443/?token=fixture#private")))
        assertTrue(endpoints.none { it.contains("fixture") || it.contains("private") || it.contains('@') || it.contains('?') })
        assertEquals("https://192.168.1.6:8443", endpoints.first())
    }

    @Test fun excessiveSavedPortsAndInvalidPrefixesStayBounded() {
        val profiles = (8000..8100).map { ServerProfile("$it", "http://192.168.1.10:$it") }
        assertTrue(LanDiscoveryPlan.endpoints("192.168.1.5", 24, profiles).size <= 2556)
        assertTrue(LanDiscoveryPlan.endpoints("192.168.1.5", 0, profiles).isEmpty())
        assertTrue(LanDiscoveryPlan.endpoints("192.168.1.5", 32, profiles).isEmpty())
    }

    @Test fun onlyTheWandPublicProbeIdentifiesADiscoveredService() {
        assertTrue(LanDiscoveryPlan.isWandProbe("{\"authed\":false}"))
        assertTrue(LanDiscoveryPlan.isWandProbe("{\"authed\":true}"))
        for (body in listOf(null, "<html>router</html>", "{}", "{\"authed\":\"false\"}", "{\"status\":\"ok\"}", "{\"authed\":true,\"unrelated\":true}")) {
            assertFalse(LanDiscoveryPlan.isWandProbe(body))
        }
    }
}
