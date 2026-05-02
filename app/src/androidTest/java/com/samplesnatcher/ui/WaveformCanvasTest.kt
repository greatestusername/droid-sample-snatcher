package com.samplesnatcher.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for editor waveform drawing. [EditorScreen] starts with an empty peak array before
 * the first background compute; the canvas must not index into it.
 */
@RunWith(AndroidJUnit4::class)
class WaveformCanvasTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyPeaks_rendersWithoutCrashing() {
        composeRule.setContent {
            WaveformCanvas(
                peaks = floatArrayOf(),
                selectionStart = 0.05f,
                selectionEnd = 0.95f,
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test
    fun nonEmptyPeaks_rendersWithoutCrashing() {
        composeRule.setContent {
            WaveformCanvas(
                peaks = FloatArray(480) { 0.5f },
                selectionStart = 0f,
                selectionEnd = 1f,
            )
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
