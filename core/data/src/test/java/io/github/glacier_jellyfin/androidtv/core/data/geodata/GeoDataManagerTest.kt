package io.github.glacier_jellyfin.androidtv.core.data.geodata

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoDataManagerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val level = MutableStateFlow<Int?>(15_000)

    // The bundled pack knows Munich, the downloaded ones Oslo. A test can swap the bytes (a new data release).
    private var bundledRaw = TestPack.build(listOf(TestPlace("Munich", 48.13743, 11.57549, "DE")), level = 15_000)
    private var bundledGz = TestPack.gzip(bundledRaw)
    private var finerRaw = TestPack.build(listOf(TestPlace("Oslo", 59.91273, 10.74609, "NO")), level = 1_000)
    private var finerGz = TestPack.gzip(finerRaw)
    private val networkOpened = ArrayList<String>()
    private val bundledOpened = ArrayList<String>()
    private var free = Long.MAX_VALUE
    private var failNetwork = false
    private var finerSha = sha(finerGz)

    private lateinit var dir: File
    private lateinit var manager: GeoDataManager

    private val settings = object : GeoDataSettings {
        override val level = this@GeoDataManagerTest.level

        override suspend fun currentLevel(): Int? = level.value

        override suspend fun setLevel(level: Int?) {
            this@GeoDataManagerTest.level.value = level
        }
    }

    @Before
    fun setUp() {
        dir = File(folder.root, "geodata")
        manager = newManager()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun pack(
        level: Int,
        gz: ByteArray,
        raw: ByteArray,
        sha: String,
    ) = GeoPack(level, "places-$level.bin.gz", sha, gz.size.toLong(), raw.size.toLong())

    private fun newManager() = GeoDataManager(
        dir = dir,
        settings = settings,
        source = PackSource { name ->
            networkOpened += name
            if (failNetwork) throw IOException("offline")
            ByteArrayInputStream(finerGz)
        },
        bundled = PackSource { name ->
            bundledOpened += name
            ByteArrayInputStream(bundledGz)
        },
        packs = listOf(
            pack(15_000, bundledGz, bundledRaw, sha(bundledGz)),
            pack(1_000, finerGz, finerRaw, finerSha),
            pack(500, finerGz, finerRaw, finerSha),
        ),
        checksumFromSource = { false },
        scope = scope,
        freeSpace = { free },
    )

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun files() = dir.list().orEmpty().sorted()

    private fun bundledFile() = "places-15000-${sha(bundledGz).take(8)}.bin"

    private fun finerFile(level: Int = 1_000) = "places-$level-${finerSha.take(8)}.bin"

    private fun set(level: Int?) = runBlocking {
        manager.setLevel(level)
        manager.awaitIdle()
    }

    private fun ensure() = runBlocking {
        manager.ensure()
        manager.awaitIdle()
    }

    private fun lookup() = runBlocking { manager.lookup(48.14, 11.58, "en") }

    @Test
    fun `the bundled pack serves level 15000 without any network`() {
        set(15_000)
        assertEquals(GeoDataState.Ready(15_000, bundledRaw.size.toLong()), manager.state.value)
        assertEquals(listOf(bundledFile()), files())
        assertEquals("Munich", lookup()?.name)
        assertTrue(networkOpened.isEmpty())
    }

    @Test
    fun `lookup is null while off`() {
        set(null)
        assertNull(lookup())
        assertEquals(GeoDataState.Off, manager.state.value)
    }

    @Test
    fun `a finer pack is downloaded and replaces the bundled one`() {
        set(1_000)
        assertEquals(GeoDataState.Ready(1_000, finerRaw.size.toLong()), manager.state.value)
        assertEquals(listOf(finerFile()), files())
        assertEquals("Oslo", runBlocking { manager.lookup(59.9, 10.7, "en") }?.name)
    }

    @Test
    fun `a failed finer download falls back to the bundled pack and ensure retries`() {
        failNetwork = true
        set(1_000)
        assertEquals(GeoDataState.Failed(1_000, GeoFailure.Network, usingFallback = true), manager.state.value)
        assertEquals(listOf(bundledFile()), files())
        assertEquals("Munich", lookup()?.name)
        failNetwork = false
        ensure()
        assertEquals(GeoDataState.Ready(1_000, finerRaw.size.toLong()), manager.state.value)
    }

    @Test
    fun `a checksum mismatch fails, leaves no download and keeps the fallback`() {
        finerSha = "0".repeat(64)
        manager = newManager()
        set(1_000)
        assertEquals(GeoDataState.Failed(1_000, GeoFailure.Checksum, usingFallback = true), manager.state.value)
        assertEquals(listOf(bundledFile()), files())
    }

    @Test
    fun `too little storage fails before downloading`() {
        free = 1
        set(1_000)
        assertEquals(GeoDataState.Failed(1_000, GeoFailure.Storage, usingFallback = false), manager.state.value)
        assertTrue(networkOpened.isEmpty())
        assertEquals(emptyList<String>(), files())
    }

    @Test
    fun `switching from one finer level to another deletes the old pack`() {
        set(1_000)
        File(dir, "stray.part").writeText("x")
        set(500)
        assertEquals(GeoDataState.Ready(500, finerRaw.size.toLong()), manager.state.value)
        assertEquals(listOf(finerFile(500)), files())
    }

    @Test
    fun `going back to 15000 deletes the downloaded pack`() {
        set(1_000)
        set(15_000)
        assertEquals(listOf(bundledFile()), files())
    }

    @Test
    fun `off deletes everything`() {
        set(1_000)
        File(dir, "places-1000.bin.gz.part").writeText("x")
        set(null)
        assertEquals(GeoDataState.Off, manager.state.value)
        assertEquals(emptyList<String>(), files())
        assertNull(lookup())
    }

    @Test
    fun `a pack that is there is not fetched again`() {
        set(1_000)
        networkOpened.clear()
        bundledOpened.clear()
        ensure()
        assertTrue(networkOpened.isEmpty() && bundledOpened.isEmpty())
        assertFalse(manager.state.value is GeoDataState.Downloading)
    }

    @Test
    fun `a broken pack on disk is replaced`() {
        set(15_000)
        File(dir, bundledFile()).writeBytes(ByteArray(10))
        ensure()
        assertEquals(GeoDataState.Ready(15_000, bundledRaw.size.toLong()), manager.state.value)
    }

    @Test
    fun `a downloaded pack with a new pinned checksum is fetched again`() {
        set(1_000)
        assertEquals(GeoDataState.Ready(1_000, finerRaw.size.toLong()), manager.state.value)
        val oldFile = finerFile()

        // App update with a new data release: the pinned checksum changes.
        finerRaw = TestPack.build(listOf(TestPlace("Bergen", 60.39299, 5.32415, "NO")), level = 1_000)
        finerGz = TestPack.gzip(finerRaw)
        finerSha = sha(finerGz)
        manager = newManager()
        networkOpened.clear()
        ensure()

        assertFalse(networkOpened.isEmpty())
        assertEquals(GeoDataState.Ready(1_000, finerRaw.size.toLong()), manager.state.value)
        assertEquals(listOf(finerFile()), files())
        assertFalse(oldFile == finerFile())
        assertEquals("Bergen", runBlocking { manager.lookup(60.39, 5.32, "en") }?.name)
    }

    @Test
    fun `the bundled pack is unpacked again when its pinned checksum changes`() {
        set(15_000)
        val oldFile = bundledFile()
        assertEquals(listOf(oldFile), files())

        // App update with a new bundled asset: the pinned checksum changes.
        bundledRaw = TestPack.build(listOf(TestPlace("Berlin", 52.52, 13.405, "DE")), level = 15_000)
        bundledGz = TestPack.gzip(bundledRaw)
        manager = newManager()
        bundledOpened.clear()
        ensure()

        assertFalse(bundledOpened.isEmpty())
        assertTrue(networkOpened.isEmpty())
        assertEquals(GeoDataState.Ready(15_000, bundledRaw.size.toLong()), manager.state.value)
        assertEquals(listOf(bundledFile()), files())
        assertFalse(oldFile == bundledFile())
        assertEquals("Berlin", runBlocking { manager.lookup(52.52, 13.4, "en") }?.name)
    }
}
