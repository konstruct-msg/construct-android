package com.construct.messenger.stickers

import android.content.Context
import android.util.Base64
import com.construct.messenger.data.api.GrpcClient
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.stealth.ServerKeysProvider
import com.google.protobuf.ByteString
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import shared.proto.services.v1.StickerServiceOuterClass.GetStickerBlobRequest
import shared.proto.services.v1.StickerServiceOuterClass.GetStickerPackBlobsRequest
import shared.proto.services.v1.StickerServiceOuterClass.GetStickerPackManifestRequest
import shared.proto.services.v1.StickerServiceOuterClass.ListStickerPacksRequest

/**
 * Sticker packs on this phone: the bundled ones, the ones fetched for a message or installed from
 * the catalog, which are in the picker, and the recently sent. **Canon:** iOS `StickerService`,
 * `StickerPackStore`, `StickerBlobStore`, `BundledStickerPacks`, `StickerPackFetcher`.
 *
 * Content-addressed and immutable (`decisions/sticker-packs-content-addressed.md`): a manifest
 * under its pack id, a WebP under its own hash, never invalidated. In `files/`, not the cache: a
 * pack must resolve for as long as a message names it, and Android clears caches on its own.
 * A pack is present only once its manifest is written, and the manifest is written last.
 *
 * Fetching is whole packs, never single stickers: one request per pack per device, with no timing
 * relationship to any message — fetching sticker by sticker as they arrive would hand the server
 * the outline of a conversation.
 */
@Singleton
class StickerStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val grpc: GrpcClient,
    private val serverKeys: ServerKeysProvider,
) : com.construct.messenger.data.repository.StickersRepository {
    private val root = File(context.filesDir, "stickers")
    private val manifests = File(root, "manifests")
    private val blobs = File(root, "blobs")
    private val prefs = context.getSharedPreferences("stickers", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packs = ConcurrentHashMap<String, StickerPack>()
    private val inFlight = ConcurrentHashMap<String, Deferred<StickerPack?>>()
    private val retryAt = ConcurrentHashMap<String, Pair<Long, Long>>()

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever a pack lands or the picker's lists change — what a bubble reloads on. */
    override val generation: StateFlow<Int> = _generation

    /** The fetched bundle key first, then the build-time pin (iOS `BundleSigningTrust`). */
    private fun trustedKeys(): List<ByteArray> =
        listOfNotNull(serverKeys.bundleVerificationKey()) + PINNED_BUNDLE_SIGNING_KEYS.map { Base64.decode(it, Base64.DEFAULT) }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** The pack, when it is here. Read from disk once and kept; never fetched from here. */
    fun pack(packHex: String): StickerPack? {
        packs[packHex]?.let { return it }
        val file = File(manifests, "$packHex.pb").takeIf { it.isFile } ?: return null
        // Verified once, at install; the app's own disk is not the server.
        return runCatching { StickerPack.verify(file.readBytes(), emptyList(), checkSignature = false) }
            .getOrNull()?.takeIf { it.hex == packHex }?.also { packs[packHex] = it }
    }

    /** The image of [ref], or null while its pack is absent (the bubble shows the emoji). */
    override fun file(ref: StickerReference): File? {
        val entry = pack(ref.packHex)?.stickers?.getOrNull(ref.index) ?: return null
        return File(blobs, "${entry.hex}.webp").takeIf { it.isFile }
    }

    override fun file(entry: StickerPack.Entry): File = File(blobs, "${entry.hex}.webp")

    /** The packs in the picker, in the order they were added. */
    override fun installed(): List<StickerPack> = installedIds().mapNotNull(::pack)

    override fun recents(): List<StickerReference> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RECENT, "[]"))
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            StickerReference.of(o.getString("pack").hexBytes() ?: return@mapNotNull null, o.getInt("index"), o.getString("emoji"))
        }
    }.getOrDefault(emptyList()).filter { file(it) != null }

    /** Newest first, each once, at most [RECENT_LIMIT]. */
    fun recordUsed(ref: StickerReference) {
        val list = (listOf(ref) + recents().filter { it != ref }).take(RECENT_LIMIT)
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("pack", it.packHex).put("index", it.index).put("emoji", it.emoji)) }
        prefs.edit().putString(KEY_RECENT, array.toString()).apply()
        _generation.value++
    }

    // ── Bundled packs ───────────────────────────────────────────────────────

    /**
     * The packs shipped in the APK (`assets/stickers`, the same files iOS bundles), verified like a
     * fetched one and written to the store. Each is added to the picker once: a pack the person
     * removed is not put back, and a retired one is only kept so old messages still render.
     */
    suspend fun seedBundled() = withContext(Dispatchers.IO) {
        val assets = context.assets
        val names = assets.list(ASSET_DIR).orEmpty()
        val seeded = prefs.getStringSet(KEY_SEEDED, emptySet()).orEmpty().toMutableSet()
        for (name in names.filter { it.startsWith("sticker-pack-") && it.endsWith(".pb") }) {
            try {
                val bytes = assets.open("$ASSET_DIR/$name").use { it.readBytes() }
                val pack = StickerPack.verify(bytes, trustedKeys())
                if (pack(pack.hex) == null) {
                    for (entry in pack.stickers) {
                        val target = file(entry)
                        if (target.isFile) continue
                        val blob = assets.open("$ASSET_DIR/${entry.hex}.webp").use { it.readBytes() }
                        putBlob(entry, blob)
                    }
                    writeManifest(pack, bytes)
                }
                if (pack.hex !in seeded) {
                    seeded += pack.hex
                    if (pack.hex !in RETIRED_PACKS) addInstalled(pack.hex)
                }
            } catch (e: Exception) {
                Log.w(TAG, "bundled $name refused: ${e.message}")
            }
        }
        prefs.edit().putStringSet(KEY_SEEDED, seeded).apply()
        _generation.value++
    }

    // ── Fetching ────────────────────────────────────────────────────────────

    /**
     * Make the pack [packId] present: manifest, verify, every blob in one stream (naming those
     * already here), each checked against the signed hash, manifest last. Coalesced per pack; a
     * failure backs off 30 s, doubling to 15 min, as iOS does.
     */
    override suspend fun ensurePresent(packId: ByteArray): StickerPack? {
        val hex = packId.toHex()
        pack(hex)?.let { return it }
        retryAt[hex]?.let { (at, _) -> if (System.currentTimeMillis() < at) return null }
        val job = inFlight.computeIfAbsent(hex) {
            scope.async {
                try {
                    fetch(packId).also { retryAt.remove(hex) }
                } catch (e: Exception) {
                    val wait = (retryAt[hex]?.second?.times(2) ?: BACKOFF_START_MS).coerceAtMost(BACKOFF_MAX_MS)
                    retryAt[hex] = (System.currentTimeMillis() + wait) to wait
                    Log.w(TAG, "pack ${hex.take(8)}… not fetched: ${e.message}")
                    null
                } finally {
                    inFlight.remove(hex)
                }
            }
        }
        return job.await()
    }

    private suspend fun fetch(packId: ByteArray): StickerPack {
        val stub = grpc.stickers
        val manifest = withTimeout(MANIFEST_TIMEOUT_MS) {
            stub.getStickerPackManifest(GetStickerPackManifestRequest.newBuilder().setPackId(ByteString.copyFrom(packId)).build())
        }.manifest
        val bytes = manifest.toByteArray()
        val pack = StickerPack.verify(bytes, trustedKeys())
        if (!pack.id.contentEquals(packId)) throw IllegalStateException("server answered with another pack")
        val wanted = pack.stickers.filterNot { file(it).isFile }
        if (wanted.isNotEmpty()) {
            val have = pack.stickers.filter { file(it).isFile }.map { ByteString.copyFrom(it.sha256) }
            val bySha = wanted.associateBy { it.hex }
            withTimeout(PACK_TIMEOUT_MS) {
                stub.getStickerPackBlobs(
                    GetStickerPackBlobsRequest.newBuilder().setPackId(ByteString.copyFrom(packId)).addAllHaveSha256(have).build(),
                ).collect { part ->
                    val entry = bySha[part.sha256.toByteArray().toHex()] ?: return@collect
                    putBlob(entry, part.data.toByteArray())
                }
            }
            val missing = wanted.count { !file(it).isFile }
            if (missing > 0) throw IllegalStateException("$missing sticker(s) did not arrive")
        }
        writeManifest(pack, bytes)
        Log.i(TAG, "pack ${pack.hex.take(8)}… fetched (${pack.stickers.size} stickers)")
        _generation.value++
        return pack
    }

    /** The catalog: packs that exist, as pointers. Trusted for nothing until installed. */
    override suspend fun catalog(): List<CatalogEntry> {
        val out = mutableListOf<CatalogEntry>()
        var token = ByteString.EMPTY
        do {
            val page = withTimeout(MANIFEST_TIMEOUT_MS) {
                grpc.stickers.listStickerPacks(ListStickerPacksRequest.newBuilder().setPageToken(token).build())
            }
            page.packsList
                .filter { it.packId.size() == 32 && it.coverSha256.size() == 32 && it.stickerCount in 1..120 }
                .forEach { out += CatalogEntry(it.packId.toByteArray(), it.title, it.publisher, it.stickerCount, it.coverSha256.toByteArray(), it.totalBytes) }
            token = page.nextPageToken
        } while (!token.isEmpty && out.size < 500)
        return out
    }

    /** A catalog cover: one sticker by hash, checked against it. Covers only — never a sticker on receive. */
    override suspend fun cover(sha256: ByteArray): ByteArray? = runCatching {
        val local = File(blobs, "${sha256.toHex()}.webp")
        if (local.isFile) return@runCatching local.readBytes()
        val data = withTimeout(MANIFEST_TIMEOUT_MS) {
            grpc.stickers.getStickerBlob(GetStickerBlobRequest.newBuilder().setSha256(ByteString.copyFrom(sha256)).build())
        }.data.toByteArray()
        data.takeIf { MessageDigest.getInstance("SHA-256").digest(it).contentEquals(sha256) }
    }.getOrNull()

    override suspend fun install(packId: ByteArray): Boolean {
        val pack = ensurePresent(packId) ?: return false
        addInstalled(pack.hex)
        _generation.value++
        return true
    }

    /** Out of the picker only: the files stay, so the transcript still shows what was sent. */
    override fun uninstall(packHex: String) {
        prefs.edit().putString(KEY_INSTALLED, JSONArray(installedIds() - packHex).toString()).apply()
        _generation.value++
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    private fun putBlob(entry: StickerPack.Entry, data: ByteArray) {
        require(data.size == entry.byteLen && data.size <= StickerPack.MAX_BYTES) { "sticker ${entry.hex.take(8)}… has the wrong size" }
        require(MessageDigest.getInstance("SHA-256").digest(data).contentEquals(entry.sha256)) { "sticker ${entry.hex.take(8)}… fails its hash" }
        blobs.mkdirs()
        val tmp = File(blobs, "${entry.hex}.part")
        tmp.writeBytes(data)
        tmp.renameTo(file(entry))
    }

    private fun writeManifest(pack: StickerPack, bytes: ByteArray) {
        manifests.mkdirs()
        val tmp = File(manifests, "${pack.hex}.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(File(manifests, "${pack.hex}.pb"))
        packs[pack.hex] = pack
    }

    private fun installedIds(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_INSTALLED, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun addInstalled(hex: String) {
        val ids = installedIds()
        if (hex !in ids) prefs.edit().putString(KEY_INSTALLED, JSONArray(ids + hex).toString()).apply()
    }

    class CatalogEntry(
        val packId: ByteArray,
        val title: String,
        val publisher: String,
        val count: Int,
        val coverSha256: ByteArray,
        val totalBytes: Long,
    ) {
        val hex: String get() = packId.toHex()
    }

    private companion object {
        const val TAG = "StickerStore"
        const val ASSET_DIR = "stickers"
        const val KEY_SEEDED = "bundled.seeded"
        const val KEY_INSTALLED = "installed"
        const val KEY_RECENT = "recent"
        const val RECENT_LIMIT = 24
        const val MANIFEST_TIMEOUT_MS = 20_000L
        const val PACK_TIMEOUT_MS = 60_000L
        const val BACKOFF_START_MS = 30_000L
        const val BACKOFF_MAX_MS = 15 * 60_000L

        /** iOS `VEILConfig.pinnedBundleSigningKeys`. */
        val PINNED_BUNDLE_SIGNING_KEYS = listOf("kDGyaafEFExKTdB7MJXl4xYf0pFnxEg7bZJh7BDP9LI=")

        /** "Momo the cat" v1 (2026-09-24): kept so old messages render, never offered in the picker. */
        val RETIRED_PACKS = setOf("96faa6e6d33167276d172920cd00d8bc8017fc39ab66e366516b338fcfc882f1")
    }
}

internal fun String.hexBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
}
