package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.ServerEntity
import com.example.data.V2RayDatabase
import com.example.data.V2RayRepository
import com.example.service.RuntimeSettings
import com.example.service.XrayConfigGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerAndFragmentSettingsIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: V2RayDatabase
    private lateinit var repository: V2RayRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("runtime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        V2RayRepository.initializeSettings(context)
        database = Room.inMemoryDatabaseBuilder(context, V2RayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = V2RayRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("runtime_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `server list orders measured healthy nodes fastest first and failures last`() = runBlocking {
        val dao = database.serverDao()
        val slowId = dao.insertServer(server("slow", ping = 310))
        val failedId = dao.insertServer(server("failed", ping = -2))
        val unknownId = dao.insertServer(server("unknown", ping = null))
        val fastId = dao.insertServer(server("fast", ping = 42))

        val initialOrder = dao.getAllServers().first()
        assertEquals(listOf(fastId, slowId), initialOrder.take(2).map { it.id })
        assertTrue(initialOrder.drop(2).all { it.id == failedId || it.id == unknownId })

        dao.updatePing(slowId, 25)
        val updatedOrder = dao.getAllServers().first()
        assertEquals(listOf(slowId, fastId), updatedOrder.take(2).map { it.id })
    }

    @Test
    fun `fragment settings persist and flow into a temporary ping tunnel config`() {
        val settings = RuntimeSettings(
            fragmentEnabled = true,
            fragmentPackets = "2-4",
            fragmentLength = "60-120",
            fragmentInterval = "5-12",
            fragmentMaxSplit = "8"
        )
        repository.saveRuntimeSettings(settings)

        val restored = repository.getRuntimeSettings()
        assertTrue(restored.fragmentEnabled)
        assertEquals("2-4", restored.fragmentPackets)
        assertEquals("60-120", restored.fragmentLength)
        assertEquals("5-12", restored.fragmentInterval)
        assertEquals("8", restored.fragmentMaxSplit)

        val probeConfig = XrayConfigGenerator.generate(
            server("probe", ping = null).copy(tls = true, sni = "node.example.com"),
            filesDir = null,
            socksPort = 45123,
            httpPort = 45124,
            runtimeSettings = restored
        )
        assertTrue(probeConfig.contains("\"port\": 45123"))
        assertTrue(probeConfig.contains("\"port\": 45124"))
        assertTrue(probeConfig.contains("\"dialerProxy\": \"fragment\""))
        assertTrue(probeConfig.contains("\"packets\": \"2-4\""))
    }

    private fun server(name: String, ping: Int?): ServerEntity = ServerEntity(
        name = name,
        type = "VLESS",
        address = "node.example.com",
        port = 443,
        uuid = "11111111-2222-3333-4444-555555555555",
        network = "ws",
        path = "/ws",
        tls = false,
        ping = ping
    )
}
