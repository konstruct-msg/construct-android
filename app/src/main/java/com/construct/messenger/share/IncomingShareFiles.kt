package com.construct.messenger.share

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.CancellationSignal
import android.provider.OpenableColumns
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import com.construct.messenger.crypto.CryptoManager
import com.construct.messenger.data.model.IncomingShare
import com.construct.messenger.data.model.SharedSource
import com.construct.messenger.media.PickedFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

/** Private sealed snapshots. The provider exposes a seekable, memory-only decrypted view to Media3. */
@Singleton
class IncomingShareFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoManager,
) {
    private val root = File(context.noBackupFilesDir, "incoming-share")
    @Volatile private var readable: IncomingShare? = null
    private val reads by lazy { Handler(HandlerThread("IncomingShareRead").apply { start() }.looper) }
    private val imports = Executors.newSingleThreadExecutor { work ->
        Thread(work, "IncomingShareImport").apply { isDaemon = true }
    }

    // The same bound as one core media operation, also applied to the entire import. Not 99 files
    // each of this size. Native seal/open still operate whole-file; no claim of streaming crypto.
    val byteLimit: Long get() = crypto.incomingSourceByteLimit
    // Whole-file FFI, encryption and decoders can hold several copies. Reserve most of the
    // process heap for the live messenger; the budget is local resource admission, not wire size.
    val importBudget: Long get() = minOf(byteLimit, Runtime.getRuntime().maxMemory() / 8)

    fun install(draft: IncomingShare?) { readable = draft }

    fun uri(request: String, item: String): Uri = ShareSourceUris.source(context.packageName, request, item)

    suspend fun import(request: String, uri: Uri, remaining: Long): SharedSource = withContext(Dispatchers.IO) {
        require(ShareSourceUris.isId(request))
        require(remaining >= 0)
        val provider = context.packageManager.resolveContentProvider(uri.authority.orEmpty(), 0)
            ?: throw FileNotFoundException()
        // Do not let an explicit external Intent make this app read its own private providers.
        if (!IncomingShareIntent.isExternalContent(uri) || provider.applicationInfo.uid == android.os.Process.myUid()) {
            throw SecurityException("private share source")
        }
        val source = readSource(uri, remaining)
        val plain = source.bytes
        try {
            val id = UUID.randomUUID().toString()
            val sealed = crypto.sealIncomingSource(plain)
            val file = file(request, id)
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val temp = File(file.parentFile, "$id.tmp")
            // External format exception (rule 6): this is the existing opaque media.rs wire
            // object, as in MediaRepository.stage, not a Kotlin state format. Its metadata and
            // keys are separately core-written CFE; no plaintext source file is persisted.
            temp.outputStream().use { it.write(sealed.blob) }
            check(temp.renameTo(file))
            SharedSource(id, source.name, source.mime, plain.size.toLong(), sealed.key)
        } finally {
            plain.fill(0)
        }
    }

    private data class ReadSource(val name: String, val mime: String, val bytes: ByteArray)

    /** Cancellation closes the descriptor even when a provider blocks a read. The single worker
     * also bounds damage from a provider that ignores CancellationSignal in query/open. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun readSource(uri: Uri, remaining: Long): ReadSource = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val stream = AtomicReference<InputStream?>()
        val task = imports.submit {
            try {
                var name = "file"
                var declaredSize = -1L
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null, signal)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.let { name = com.construct.messenger.media.FileContent.safeName(it) }
                        if (!cursor.isNull(1)) declaredSize = cursor.getLong(1)
                    }
                }
                if (declaredSize > remaining) throw PickedFiles.TooLarge(declaredSize)
                val mime = context.contentResolver.getType(uri)?.takeIf { it.contains('/') }?.lowercase() ?: "application/octet-stream"
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw FileNotFoundException()
                val plain = descriptor.use { handle ->
                    handle.createInputStream().use { input ->
                        stream.set(input)
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size().toLong() + n > remaining) throw PickedFiles.TooLarge(out.size().toLong() + n)
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                }
                val result = ReadSource(name, mime, plain)
                continuation.resume(result) { plain.fill(0) }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally {
                runCatching { stream.getAndSet(null)?.close() }
            }
        }
        continuation.invokeOnCancellation {
            signal.cancel()
            runCatching { stream.getAndSet(null)?.close() }
            task.cancel(true)
        }
    }

    fun describe(uri: Uri): SharedSource {
        val (request, item) = ShareSourceUris.ids(uri, context.packageName) ?: throw FileNotFoundException()
        val held = readable?.takeIf { it.id == request } ?: throw FileNotFoundException()
        return held.items.firstOrNull { it.id == item } ?: throw FileNotFoundException()
    }

    fun open(uri: Uri): ParcelFileDescriptor {
        val (request, item) = ShareSourceUris.ids(uri, context.packageName) ?: throw FileNotFoundException()
        val source = describe(uri)
        if (source.size > importBudget) throw PickedFiles.TooLarge(source.size)
        val sealed = file(request, item)
        // Corrupt local files must not turn readBytes into an unbounded allocation. A valid
        // padded blob fits well inside twice this whole-file import budget.
        if (sealed.length() !in 1..(importBudget * 2)) throw FileNotFoundException()
        val bytes = crypto.openIncomingSource(source.key, sealed.readBytes())
        if (bytes.size.toLong() != source.size) {
            bytes.fill(0)
            throw FileNotFoundException()
        }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = bytes.size.toLong()
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                if (offset < 0 || size < 0) throw ErrnoException("read", OsConstants.EINVAL)
                if (offset >= bytes.size) return 0
                val n = minOf(size, data.size, bytes.size - offset.toInt())
                bytes.copyInto(data, 0, offset.toInt(), offset.toInt() + n)
                return n
            }
            override fun onRelease() { bytes.fill(0) }
        }
        return try {
            context.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, reads)
        } catch (e: Exception) {
            bytes.fill(0)
            throw e
        }
    }

    fun present(draft: IncomingShare): Boolean = ShareSourceUris.isId(draft.id) &&
        draft.items.size <= com.construct.messenger.data.model.ComposerCapacity.ATTACHMENTS &&
        draft.items.map { it.id }.distinct().size == draft.items.size &&
        draft.items.all { ShareSourceUris.isId(it.id) && it.size in 0..byteLimit && file(draft.id, it.id).isFile } &&
        draft.items.sumOf { it.size } in 0..byteLimit

    /** After store restore, only that request can own snapshots. No age timer instead of ownership. */
    fun prune(keep: String?) {
        root.listFiles()?.filter { it.name != keep }?.forEach { check(it.deleteRecursively()) }
    }

    private fun file(request: String, item: String): File {
        require(ShareSourceUris.isId(request) && ShareSourceUris.isId(item))
        return File(File(root, request), "$item.blob")
    }
}
