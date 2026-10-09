package com.construct.messenger.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.construct.messenger.data.model.MessageMedia
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.data.repository.StorageRepository
import com.construct.messenger.diagnostics.Log
import com.construct.messenger.util.MediaWire
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Told when a message with media has just been stored — received, or our own from another device. */
fun interface MediaArrivals {
    fun onArrived(kind: String?, bytes: ByteArray?)

    companion object {
        /** Nothing ahead of time: the bubbles download what they show. */
        val NONE = MediaArrivals { _, _ -> }
    }
}

/**
 * Fetches a received album's attachments as its message arrives, as [MediaAutoDownloadPolicy] and
 * the setting allow — so the store's 7-day retention cannot expire under a chat nobody opened.
 *
 * Silent and not awaited: the message is already stored and shown. A failure is not an error and
 * marks nothing; the bubble downloads what it shows, as before — only the head start is lost.
 * Duplicates are harmless: the store answers one download per id and from disk when it is there.
 *
 * Albums only (photos, videos, files, video notes), as iOS `MediaWireCodec.prefetch`.
 */
@Singleton
class MediaPrefetcher @Inject constructor(
    @param:ApplicationContext context: Context,
    private val media: MediaRepository,
    private val storage: StorageRepository,
) : MediaArrivals {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A message with media [kind] / [bytes] (as stored on the row) has just been stored. */
    override fun onArrived(kind: String?, bytes: ByteArray?) {
        val setting = storage.settings.value.autoDownload
        if (setting == MediaAutoDownload.NEVER) return
        val album = MediaWire.decode(kind, bytes) as? MessageMedia.Album ?: return
        val metered = isMetered()
        val constrained = isDataSaverOn()
        for (item in album.items) {
            if (item.mediaId.startsWith(MediaWire.LOCAL_PREFIX)) continue
            if (!MediaAutoDownloadPolicy.shouldFetchOnArrival(setting, item.sizeBytes, metered, constrained)) continue
            scope.launch {
                try {
                    media.prefetch(item)
                    Log.d(TAG, "prefetched ${item.mediaId.take(8)}… on arrival")
                } catch (e: Exception) {
                    // Offline, the transport not up yet, the blob already gone: the bubble tries again.
                    Log.d(TAG, "prefetch skipped for ${item.mediaId.take(8)}…: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Metered unless an unmetered network other than a VPN is up. A VPN reports itself metered
     * by default whatever runs under it, and on the networks this app is built for a phone often
     * has one on: going by the default network alone, "Wi-Fi only" would never fetch.
     */
    @Suppress("DEPRECATION") // allNetworks: the one call that lists the networks under a VPN.
    private fun isMetered(): Boolean {
        val cm = connectivity ?: return true
        return cm.allNetworks.none { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@none false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    /** Data Saver on, and this app not exempted from it. */
    private fun isDataSaverOn(): Boolean =
        connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    private companion object {
        const val TAG = "MediaPrefetcher"
    }
}
