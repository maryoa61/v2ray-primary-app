package com.example

import android.util.Base64
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets

/**
 * Host-JVM (Robolectric) tests for share-link parsing. These exercise the
 * subscription/import path that previously mis-decoded VMess links whose
 * "port" is a JSON string (optInt fell back to 0 -> immediate connect failure).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareLinkParseTest {

    private val repo = V2RayRepository(
        // A real Room DB is fine here (Robolectric provides the SQLite
        // backend); parseShareLink() itself never touches the DAOs.
        V2RayDatabase.getDatabase(
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
        )
    )

    @Test
    fun `vmess link with string port parses correct port`() {
        // Some subscription exporters emit "port":"443" as a JSON string.
        val payload = """
            {"v":"2","ps":"StringPortNode","add":"example.com","port":"8443",
             "id":"uuid-1234","aid":"0","net":"ws","path":"/w","host":"example.com",
             "tls":"tls","sni":"example.com"}
        """.trimIndent()
        val encoded = Base64.encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val link = "vmess://$encoded"

        val server = repo.parseShareLink(link)
        assertNotNull("link should parse", server)
        assertEquals(8443, server!!.port)
        assertEquals("example.com", server.address)
        assertEquals("VMESS", server.type)
        assertTrue(server.tls)
    }

    @Test
    fun `vmess link with numeric port still parses`() {
        val payload = """
            {"v":"2","ps":"NumericPortNode","add":"example.com","port":443,
             "id":"uuid-1234","aid":0,"net":"tcp","tls":""}
        """.trimIndent()
        val encoded = Base64.encodeToString(
            payload.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val server = repo.parseShareLink("vmess://$encoded")
        assertNotNull(server)
        assertEquals(443, server!!.port)
    }

    @Test
    fun `vless reality link parses keys`() {
        val link = "vless://uuid-abc@example.com:443?security=reality&type=tcp" +
                "&pbk=PUBKEY&sni=www.microsoft.com&fp=chrome&sid=ab12&flow=xtls-rprx-vision#node"
        val server = repo.parseShareLink(link)
        assertNotNull(server)
        assertEquals("reality", server!!.security)
        assertEquals("PUBKEY", server.publicKey)
        assertEquals("ab12", server.shortId)
        assertEquals("xtls-rprx-vision", server.flow)
        assertEquals("chrome", server.fingerprint)
    }

    @Test
    fun `invalid link returns null`() {
        val server = repo.parseShareLink("not-a-link")
        assertEquals(null, server)
    }
}
