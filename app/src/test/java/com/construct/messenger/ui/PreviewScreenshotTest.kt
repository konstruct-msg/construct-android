package com.construct.messenger.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.construct.messenger.ui.components.LocalDecorRandom
import com.construct.messenger.ui.theme.CTColor
import com.construct.messenger.ui.theme.KonstructMessengerTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.android.screenshotid.AndroidPreviewScreenshotIdBuilder
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

/**
 * Every `@Preview` in the app, drawn in the dark and the light theme and compared with the image in
 * `src/test/screenshots/`.
 *
 * Step 0 of the Material 3 move (`docs/MATERIAL3_MIGRATION.md`): the code moves to Material first
 * and the screen must not change, and this is what proves it. A preview is found by the scanner,
 * so a new one is covered the moment it is written.
 *
 * The preview is wrapped in the app's theme, not drawn bare: a Material component reads
 * `MaterialTheme`, and a bare preview would show it in the library's baseline purple, which no
 * screen of the app does. The ground is [CTColor.bg] for the same reason — the preview's own
 * `backgroundColor` is fixed to the dark value and would lie in the light pass.
 *
 * `./gradlew recordRoborazziDebug` rewrites the references; `verifyRoborazziDebug` (and
 * `scripts/verify.sh`) fails on a changed pixel; a plain `test` run draws without comparing.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A bare Application: the real one starts Hilt, the Keystore and the messaging runtime, none of
// which a preview draws.
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class PreviewScreenshotTest(
    private val preview: ComposablePreview<AndroidPreviewInfo>,
    private val dark: Boolean,
) {
    /**
     * Times print in the machine's zone and dates in its locale; pinned, so a reference recorded in
     * Moscow matches a run on CI in UTC.
     */
    @Before
    fun pinZoneAndLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The clock is stopped and moved by hand. Left running, an endless animation (the connection
     * pulse, the onboarding signal) never lets the screen go idle and each capture waited out the
     * test's timeout — 100–250 s a preview. One second in, entry animations have finished and an
     * endless one stands at the same phase on every run.
     */
    @Test
    fun snapshot() {
        val id = AndroidPreviewScreenshotIdBuilder(preview).build()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDecorRandom provides Random(0)) {
                KonstructMessengerTheme(darkTheme = dark) {
                    Box(Modifier.background(CTColor.bg)) { preview() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        val path = "src/test/screenshots/$id.${if (dark) "dark" else "light"}.png"
        // A dialog draws in its own window, which the root node does not include: a preview named
        // …DialogPreview is captured as the whole screen, dim and all, as a person sees it.
        if (preview.methodName.endsWith("DialogPreview")) captureScreenRoboImage(path)
        else compose.onRoot().captureRoboImage(path)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} dark={1}")
        fun previews(): List<Array<Any>> =
            AndroidComposablePreviewScanner()
                .scanPackageTrees("com.construct.messenger.ui")
                .includePrivatePreviews()
                .getPreviews()
                .flatMap { listOf(arrayOf<Any>(it, true), arrayOf<Any>(it, false)) }
    }
}
