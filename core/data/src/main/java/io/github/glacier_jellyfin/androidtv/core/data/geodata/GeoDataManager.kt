package io.github.glacier_jellyfin.androidtv.core.data.geodata

import android.annotation.SuppressLint
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why a place pack is not there. */
enum class GeoFailure {
    /** The download could not be reached or broke off. */
    Network,

    /** The file does not match the pinned SHA-256. */
    Checksum,

    /** The device has too little free space, or could not write or unpack the file. */
    Storage,

    /** Anything else, for example a pack that is not of format v1. */
    Other,
}

/** What the "Place names" setting is doing. */
sealed interface GeoDataState {
    /** The setting is off; nothing is stored. */
    data object Off : GeoDataState

    /**
     * The pack of [level] is being fetched: [bytes] of [total] so far ([total] is the size the table promises). For a
     * finer level than the bundled one, [fallbackActive] says that lookups meanwhile use the bundled pack.
     */
    data class Downloading(
        val level: Int,
        val bytes: Long,
        val total: Long,
        val fallbackActive: Boolean = false,
    ) : GeoDataState {
        /** 0..1 */
        val progress: Float get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }

    /** The pack of [level] is on the device and lookups work; [bytesOnDisk] is its unpacked size. */
    data class Ready(val level: Int, val bytesOnDisk: Long) : GeoDataState

    /**
     * The pack of [level] could not be fetched; [GeoDataManager.ensure] tries again. [usingFallback]: lookups use the
     * bundled pack meanwhile.
     */
    data class Failed(
        val level: Int,
        val reason: GeoFailure,
        val usingFallback: Boolean = false,
    ) : GeoDataState
}

/** Where the chosen level is kept: device-wide, see `AccountState.placeNamesLevel`. */
interface GeoDataSettings {
    /** The minimum number of inhabitants of the chosen pack (0: all places); null while the setting is off. */
    val level: Flow<Int?>

    suspend fun currentLevel(): Int?

    suspend fun setLevel(level: Int?)
}

/**
 * Offline place names for photos: owns the "Place names" setting, the pack files in `filesDir/geodata` and the
 * lookup. A pack `places-<level>.bin.gz` is fetched from a [PackSource], its SHA-256 is checked before anything is
 * unpacked, then it is unpacked to a temporary file and moved to `places-<level>-<sha prefix>.bin` in one step, so a
 * file with that name is always complete, and a changed pinned checksum makes the pack be unpacked or fetched again.
 *
 * The pack of [GeoPacks.BUNDLED_LEVEL] is an asset of the APK and is only unpacked, never downloaded: no network is
 * touched for it. A finer level is downloaded; until it is ready (or when it failed) lookups use the bundled pack, and
 * once it is ready the unpacked bundled file is deleted. Off deletes all unpacked and downloaded files.
 *
 * At most one fetch runs. [ensure] (call it at app start and when the photo viewer opens) starts it again when the
 * setting is on and the pack is missing, for example after a failure or when the app was killed meanwhile.
 */
// The free space only has to say whether a download fits; clearable cache is no help here.
@SuppressLint("UsableSpace")
class GeoDataManager internal constructor(
    private val dir: File,
    private val settings: GeoDataSettings,
    /** Where finer packs are downloaded from. */
    private val source: PackSource,
    /** Where the pack of [GeoPacks.BUNDLED_LEVEL] comes from: the APK assets. */
    private val bundled: PackSource,
    private val packs: List<GeoPack>,
    /** True while a debug test server is set: the SHA-256 then comes from `<file>.sha256` of that server. */
    private val checksumFromSource: () -> Boolean,
    private val scope: CoroutineScope,
    /** Bytes the device can still store in a directory. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    private val _state = MutableStateFlow<GeoDataState>(GeoDataState.Off)

    val state: StateFlow<GeoDataState> = _state.asStateFlow()

    /** The chosen level, null while off. */
    val level: Flow<Int?> get() = settings.level

    private val lock = Mutex()
    private var job: Job? = null
    private var jobLevel: Int? = null

    private val readerLock = Any()
    private var reader: Pair<Int, PlacePack>? = null
    private val cache = object : LinkedHashMap<String, Place?>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Place?>) = size > CACHE_SIZE
    }

    private val bundledPack get() = packs.firstOrNull { it.level == GeoPacks.BUNDLED_LEVEL }

    /** Changes the setting ([level] null: off): deletes the other packs and starts the download when needed. */
    suspend fun setLevel(level: Int?) {
        require(level == null || packs.any { it.level == level }) { "No pack for level $level" }
        settings.setLevel(level)
        reconcile()
    }

    /** Brings the files in line with the setting; fetches in the background when the pack is missing. */
    suspend fun ensure() {
        reconcile()
    }

    /** [ensure] from a place without a coroutine, such as `Application.onCreate`. */
    fun start() {
        scope.launch { reconcile() }
    }

    /**
     * The place nearest to the photo, in [language] where the pack has names in it. Null when the setting is off, no
     * pack is usable yet, or nothing is within 50 km. Runs on the I/O dispatcher.
     */
    suspend fun lookup(
        lat: Double,
        lon: Double,
        language: String = Locale.getDefault().language,
    ): Place? = withContext(Dispatchers.IO) {
        val level = usableLevel() ?: return@withContext null
        val key = "$level/$language/${(lat * CACHE_SCALE).roundToLong()}/${(lon * CACHE_SCALE).roundToLong()}"
        synchronized(cache) { if (cache.containsKey(key)) return@withContext cache[key] }
        val pack = openReader(level) ?: return@withContext null
        val started = System.nanoTime()
        val place = try {
            pack.nearest(lat, lon, language)
        } catch (e: BrokenPackException) {
            broken(level, e)
            return@withContext null
        }
        synchronized(cache) { cache[key] = place }
        // Lets the lookup time be measured on a device: adb logcat -s GeoData
        runCatching { Log.d(TAG, "Lookup in level $level took ${(System.nanoTime() - started) / 1000} us") }
        place
    }

    /** The level whose file answers lookups now: the chosen one when ready, else the bundled one as fallback. */
    private fun usableLevel(): Int? = when (val s = _state.value) {
        GeoDataState.Off -> null
        is GeoDataState.Ready -> s.level
        is GeoDataState.Downloading -> GeoPacks.BUNDLED_LEVEL.takeIf { s.fallbackActive }
        is GeoDataState.Failed -> GeoPacks.BUNDLED_LEVEL.takeIf { s.usingFallback }
    }

    /** Test hook: waits for a running fetch to end. */
    internal suspend fun awaitIdle() {
        job?.join()
    }

    private fun openReader(level: Int): PlacePack? = synchronized(readerLock) {
        reader?.takeIf { it.first == level }?.let { return it.second }
        val pack = packs.firstOrNull { it.level == level } ?: return null
        try {
            PlacePack.open(fileOf(pack)).also { reader = level to it }
        } catch (e: BrokenPackException) {
            broken(level, e)
            null
        } catch (e: IOException) {
            warn("Cannot open the place pack", e)
            null
        }
    }

    /** The file is no pack: drop it and fetch it again. */
    private fun broken(level: Int, error: Exception) {
        warn("Place pack of level $level is broken, fetching it again", error)
        dropReader()
        packs.firstOrNull { it.level == level }?.let { fileOf(it).delete() }
        scope.launch { reconcile() }
    }

    private fun dropReader() {
        synchronized(readerLock) { reader = null }
        synchronized(cache) { cache.clear() }
    }

    private suspend fun reconcile() = withContext(Dispatchers.IO) {
        lock.withLock {
            val level = settings.currentLevel()
            val running = job
            if (running?.isActive == true && jobLevel == level) return@withLock
            running?.cancelAndJoin()
            job = null
            val pack = level?.let { wanted -> packs.firstOrNull { it.level == wanted } }
            if (level == null || pack == null) {
                dropReader()
                clean(keep = emptySet())
                _state.value = if (level == null) GeoDataState.Off else GeoDataState.Failed(level, GeoFailure.Other)
                return@withLock
            }
            val target = fileOf(pack)
            val fallback = bundledPack?.takeIf { it.level != level }?.let(::fileOf)
            if (reader?.first != level) dropReader()
            if (isComplete(target)) {
                // A finer pack replaces the bundled one for good.
                if (fallback != null && reader?.first == GeoPacks.BUNDLED_LEVEL) dropReader()
                clean(keep = setOf(target))
                _state.value = GeoDataState.Ready(level, target.length())
                return@withLock
            }
            target.delete()
            clean(keep = setOfNotNull(target, fallback))
            _state.value = GeoDataState.Downloading(level, 0, pack.size, fallback?.let(::isComplete) == true)
            jobLevel = level
            job = scope.launch { fetch(pack, target) }
        }
    }

    private fun isComplete(file: File): Boolean {
        if (!file.isFile) return false
        return try {
            PlacePack.check(file)
            true
        } catch (e: IOException) {
            warn("Place pack on disk is unusable", e)
            false
        }
    }

    /** Deletes everything in the geodata directory except [keep]: other packs and leftovers of interrupted fetches. */
    private fun clean(keep: Set<File>) {
        dir.listFiles()?.forEach { if (it !in keep) it.deleteRecursively() }
    }

    private fun fileOf(pack: GeoPack) = File(dir, "places-${pack.level}-${pack.sha256.take(SHA_PREFIX)}.bin")

    private suspend fun fetch(pack: GeoPack, target: File) {
        if (pack.level == GeoPacks.BUNDLED_LEVEL) {
            val failure = install(pack, target, bundled) { done ->
                _state.value = GeoDataState.Downloading(pack.level, done, pack.size)
            }
            _state.value = failure?.let { GeoDataState.Failed(pack.level, it) }
                ?: GeoDataState.Ready(pack.level, target.length())
            return
        }
        // Lookups use the bundled pack while the finer one comes in.
        val fallbackPack = bundledPack
        val fallbackFile = fallbackPack?.let(::fileOf)
        if (fallbackPack != null && fallbackFile != null && !isComplete(fallbackFile)) {
            install(fallbackPack, fallbackFile, bundled) {}
        }
        val fallback = fallbackFile != null && isComplete(fallbackFile)
        _state.value = GeoDataState.Downloading(pack.level, 0, pack.size, fallback)
        val failure = install(pack, target, source) { done ->
            _state.value = GeoDataState.Downloading(pack.level, done, pack.size, fallback)
        }
        if (failure != null) {
            _state.value = GeoDataState.Failed(pack.level, failure, fallback)
            return
        }
        dropReader()
        fallbackFile?.delete()
        _state.value = GeoDataState.Ready(pack.level, target.length())
    }

    /**
     * Fetches [pack] from [from], checks it, unpacks it and moves it to [target]; null on success, else why not.
     * Temporary files are gone afterwards, whatever happens.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun install(
        pack: GeoPack,
        target: File,
        from: PackSource,
        onProgress: (Long) -> Unit,
    ): GeoFailure? {
        dir.mkdirs()
        // The download and the unpacked file exist side by side for a moment.
        if (freeSpace(dir) < pack.size + pack.rawSize + SPACE_MARGIN) {
            warn("Not enough storage for ${pack.file}", null)
            return GeoFailure.Storage
        }
        val partial = File(dir, pack.file + PART)
        val unpacked = File(dir, target.name + TMP)
        return try {
            val expected = if (from === source && checksumFromSource()) readChecksum(from, pack) else pack.sha256
            if (download(from, pack, partial, onProgress) != expected.lowercase()) {
                throw GeoException(GeoFailure.Checksum, "SHA-256 of ${pack.file} does not match")
            }
            unpack(partial, unpacked)
            partial.delete()
            move(pack, unpacked, target)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeoException) {
            warn("Place pack of level ${pack.level} failed: ${e.message}", e.cause)
            e.reason
        } catch (e: Exception) {
            warn("Place pack of level ${pack.level} failed", e)
            GeoFailure.Other
        } finally {
            partial.delete()
            unpacked.delete()
        }
    }

    private fun move(
        pack: GeoPack,
        unpacked: File,
        target: File,
    ) {
        try {
            PlacePack.check(unpacked)
        } catch (e: BrokenPackException) {
            throw GeoException(GeoFailure.Other, "${pack.file} is not a place pack", e)
        }
        try {
            Files.move(unpacked.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw GeoException(GeoFailure.Storage, "Cannot store ${target.name}", e)
        }
    }

    private fun readChecksum(from: PackSource, pack: GeoPack): String = try {
        from.open(pack.file + ".sha256").use { it.readBytes().toString(Charsets.US_ASCII) }
            .trim().substringBefore(' ').lowercase()
    } catch (e: IOException) {
        throw GeoException(GeoFailure.Network, "Cannot read the checksum of ${pack.file}", e)
    }

    /** Writes the asset to [partial] and returns its SHA-256 as lower-case hex. */
    @Suppress("ThrowsCount") // each step maps its failure to a reason
    private suspend fun download(
        from: PackSource,
        pack: GeoPack,
        partial: File,
        onProgress: (Long) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val context = currentCoroutineContext()
        val input = try {
            from.open(pack.file)
        } catch (e: IOException) {
            throw GeoException(GeoFailure.Network, "Cannot fetch ${pack.file}", e)
        }
        input.use {
            val output = try {
                partial.outputStream()
            } catch (e: IOException) {
                throw GeoException(GeoFailure.Storage, "Cannot write ${partial.name}", e)
            }
            output.use {
                val buffer = ByteArray(BUFFER)
                var done = 0L
                var reported = 0L
                while (true) {
                    context.ensureActive()
                    val read = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        throw GeoException(GeoFailure.Network, "Download of ${pack.file} interrupted", e)
                    }
                    if (read < 0) break
                    try {
                        output.write(buffer, 0, read)
                    } catch (e: IOException) {
                        throw GeoException(GeoFailure.Storage, "Cannot write ${partial.name}", e)
                    }
                    digest.update(buffer, 0, read)
                    done += read
                    if (done - reported >= PROGRESS_STEP) {
                        reported = done
                        onProgress(done)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("NestedBlockDepth")
    private suspend fun unpack(gz: File, out: File) {
        val context = currentCoroutineContext()
        try {
            GZIPInputStream(gz.inputStream().buffered(BUFFER)).use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            throw GeoException(GeoFailure.Storage, "Cannot unpack ${gz.name}", e)
        }
    }

    private class GeoException(
        val reason: GeoFailure,
        message: String,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    // android.util.Log is not available in plain unit tests.
    private fun warn(message: String, error: Throwable?) {
        runCatching { Log.w(TAG, message, error) }
    }

    private companion object {
        const val TAG = "GeoData"
        const val PART = ".part"
        const val TMP = ".tmp"
        const val BUFFER = 64 * 1024
        const val PROGRESS_STEP = 256 * 1024L
        const val CACHE_SIZE = 64
        const val SPACE_MARGIN = 50L * 1024 * 1024
        const val CACHE_SCALE = 1000.0
        const val SHA_PREFIX = 8
    }
}
