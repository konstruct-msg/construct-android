package com.construct.messenger

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.media.MediaImages
import javax.inject.Inject
import com.construct.messenger.diagnostics.Diagnostics
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — Hilt's dependency-injection root.
 *
 * `@HiltAndroidApp` triggers generation of the application-level DI container.
 * Without it any `@HiltViewModel` / `@AndroidEntryPoint` fails at runtime.
 * Registered in AndroidManifest.xml via `android:name=".KonstructApp"`.
 */
@HiltAndroidApp
class KonstructApp : Application(), ImageLoaderFactory {
    @Inject
    lateinit var media: MediaRepository

    /** Every `AsyncImage` in the app: message media among the images it can load. */
    override fun newImageLoader(): ImageLoader = MediaImages.loader(this, media)

    override fun onCreate() {
        // Before super: Hilt builds the graph there, and its first log lines belong in the file.
        Diagnostics.install(this)
        super.onCreate()
    }
}
