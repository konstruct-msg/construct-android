package com.construct.messenger

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class — Hilt's dependency-injection root.
 *
 * `@HiltAndroidApp` triggers generation of the application-level DI container.
 * Without it any `@HiltViewModel` / `@AndroidEntryPoint` fails at runtime.
 * Registered in AndroidManifest.xml via `android:name=".KonstructApp"`.
 */
@HiltAndroidApp
class KonstructApp : Application()
