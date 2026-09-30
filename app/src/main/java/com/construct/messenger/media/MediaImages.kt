package com.construct.messenger.media

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import com.construct.messenger.data.model.MediaItem
import com.construct.messenger.data.repository.MediaRepository
import okio.Buffer

/**
 * Coil, taught to load a [MediaItem]: `AsyncImage(model = item)` fetches the blob through
 * [MediaRepository], opens it, and decodes it like any image.
 *
 * The loader keeps decoded images in memory only. Coil's disk cache is off: it would write the
 * decrypted picture to disk, next to the encrypted copy that exists so that nothing readable does.
 */
object MediaImages {

    fun loader(context: Context, media: MediaRepository): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ItemFetcher.Factory(media))
                add(ItemKeyer())
            }
            .diskCache(null)
            .crossfade(true)
            .build()

    private class ItemFetcher(
        private val item: MediaItem,
        private val options: Options,
        private val media: MediaRepository,
    ) : Fetcher {
        override suspend fun fetch(): FetchResult = SourceResult(
            source = ImageSource(Buffer().write(media.bytes(item)), options.context),
            mimeType = item.mimeType,
            dataSource = DataSource.DISK,
        )

        class Factory(private val media: MediaRepository) : Fetcher.Factory<MediaItem> {
            override fun create(data: MediaItem, options: Options, imageLoader: ImageLoader): Fetcher =
                ItemFetcher(data, options, media)
        }
    }

    /** One media id is one picture, however many rows name it. */
    private class ItemKeyer : Keyer<MediaItem> {
        override fun key(data: MediaItem, options: Options): String = "media:${data.mediaId}"
    }
}
