package com.construct.messenger

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.construct.messenger.data.repository.MediaRepository
import com.construct.messenger.media.MediaImages
import javax.inject.Inject
import com.construct.messenger.diagnostics.Diagnostics
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch

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

    @Inject
    lateinit var stickers: com.construct.messenger.stickers.StickerStore

    @Inject
    lateinit var calls: com.construct.messenger.calls.CallManager

    @Inject
    lateinit var callTelecom: com.construct.messenger.calls.CallTelecom

    /** Every `AsyncImage` in the app: message media among the images it can load. */
    override fun newImageLoader(): ImageLoader = MediaImages.loader(this, media)

    override fun onCreate() {
        // Before super: Hilt builds the graph there, and its first log lines belong in the file.
        Diagnostics.install(this)
        super.onCreate()
        // The packs in the APK, into the sticker store — once each, off the main thread (iOS
        // seeds on every launch too; a pack already there costs one file check).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { stickers.seedBundled() }
        // Before any service can decrypt a call signal: the inbox keeps nothing for a late listener.
        calls.start()
        callTelecom.start()
        // Android takes the camera from an app nobody sees; the peer is told it is off rather
        // than left on a frozen frame. iOS does the same on didEnterBackground.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = calls.setInBackground(false)
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) = calls.setInBackground(true)
        })
    }
}
