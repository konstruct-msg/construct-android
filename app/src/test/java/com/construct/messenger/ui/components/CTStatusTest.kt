package com.construct.messenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.ui.graphics.Color
import com.construct.messenger.ui.theme.CTColor
import org.junit.Assert.assertEquals
import org.junit.Test

class CTStatusTest {

    @Test
    fun okStatusUsesCheckCircleAndAccentColor() {
        assertEquals(Icons.Filled.CheckCircle, CTStatus.OK.icon)
        assertEquals(CTColor.accent, CTStatus.OK.color)
    }

    @Test
    fun onStatusUsesCheckCircleAndDimAccentColor() {
        assertEquals(Icons.Filled.CheckCircle, CTStatus.ON.icon)
        assertEquals(CTColor.accentDim, CTStatus.ON.color)
    }

    @Test
    fun errorStatusUsesErrorIconAndDangerColor() {
        assertEquals(Icons.Filled.Error, CTStatus.ERROR.icon)
        assertEquals(CTColor.danger, CTStatus.ERROR.color)
    }

    @Test
    fun warningStatusUsesWarningIconAndOrangeColor() {
        assertEquals(Icons.Filled.Warning, CTStatus.WARNING.icon)
        assertEquals(CTColor.warning, CTStatus.WARNING.color)
    }

    @Test
    fun offStatusUsesOutlinedCircleAndDimColor() {
        assertEquals(Icons.Outlined.Circle, CTStatus.OFF.icon)
        assertEquals(CTColor.textDim, CTStatus.OFF.color)
    }

    @Test
    fun busyStatusUsesSyncIconAndDimColor() {
        assertEquals(Icons.Filled.Sync, CTStatus.BUSY.icon)
        assertEquals(CTColor.textDim, CTStatus.BUSY.color)
    }

    @Test
    fun unknownStatusUsesHelpOutlineAndDimColor() {
        assertEquals(Icons.AutoMirrored.Filled.HelpOutline, CTStatus.UNKNOWN.icon)
        assertEquals(CTColor.textDim, CTStatus.UNKNOWN.color)
    }
}
