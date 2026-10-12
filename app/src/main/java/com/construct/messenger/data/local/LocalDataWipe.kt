package com.construct.messenger.data.local

import android.app.ActivityManager
import android.content.Context
import com.construct.messenger.diagnostics.Log

/**
 * Everything this app holds on the device, removed: `decisions/sign-out-wipes-the-device.md`.
 * **Canon:** iOS `LocalDataWipe` — sign-out, account deletion, this device removed from another
 * one, and the lock screen's escape hatch all end here.
 *
 * The system's "Clear storage": the process is ended first, then every file, database, preference
 * and Keystore key of the app goes. That is iOS's "removed by default, kept by exception" with no
 * exceptions — a store added later is wiped without anyone listing it, and nothing still running
 * can write a file back. The next start is a fresh onboarding.
 */
object LocalDataWipe {
    fun run(context: Context, reason: String) {
        Log.i("LocalDataWipe", "LOCAL_WIPE: reason=$reason")
        context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }
}
