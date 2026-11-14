package com.yasmin.astrocare.data.sync

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Endpoint resolution must never duplicate `/api/v1`: the schema example is a
 * host-only URL, while the demo default already includes the prefix. Both forms
 * must resolve to the same endpoint.
 */
class SyncClientTest {

    @Test
    fun `host-only base url gets the api prefix`() {
        assertEquals(
            "http://10.0.2.2:3000/api/v1/sync/batch",
            SyncClient.endpointUrl("http://10.0.2.2:3000"),
        )
    }

    @Test
    fun `base url with trailing slash is normalized`() {
        assertEquals(
            "https://host.example/api/v1/sync/batch",
            SyncClient.endpointUrl("https://host.example/"),
        )
    }

    @Test
    fun `base url that already contains the api prefix is not doubled`() {
        assertEquals(
            "https://backend.example/api/v1/sync/batch",
            SyncClient.endpointUrl("https://backend.example/api/v1"),
        )
        assertEquals(
            "https://backend.example/api/v1/sync/batch",
            SyncClient.endpointUrl("https://backend.example/api/v1/"),
        )
    }

    @Test
    fun `whitespace around the base url is ignored`() {
        assertEquals(
            "https://host.example/api/v1/sync/batch",
            SyncClient.endpointUrl("  https://host.example  "),
        )
    }

    @Test
    fun `non-http schemes are rejected`() {
        assertThrows(IOException::class.java) { SyncClient.endpointUrl("ftp://host.example") }
        assertThrows(IOException::class.java) { SyncClient.endpointUrl("backend.example") }
    }
}
