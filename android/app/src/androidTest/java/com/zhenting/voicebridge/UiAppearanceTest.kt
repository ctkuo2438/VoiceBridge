package com.zhenting.voicebridge

import android.graphics.Bitmap
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhenting.voicebridge.data.model.*
import com.zhenting.voicebridge.ui.auth.AuthScreen
import com.zhenting.voicebridge.ui.auth.AuthUiState
import com.zhenting.voicebridge.ui.chat.ChatScreen
import com.zhenting.voicebridge.ui.chat.ChatUiState
import com.zhenting.voicebridge.ui.home.HomeMode
import com.zhenting.voicebridge.ui.home.HomeScreen
import com.zhenting.voicebridge.ui.memo.MemoScreen
import com.zhenting.voicebridge.ui.memo.MemoUiState
import com.zhenting.voicebridge.ui.speech.SpeechInput
import com.zhenting.voicebridge.ui.speech.SpeechTarget
import com.zhenting.voicebridge.ui.speech.SpeechUiState
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Render real screens with sample data for visual review; never call external APIs. */
@RunWith(AndroidJUnit4::class)
class UiAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureLightDarkAndLargeTextScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stage = InstrumentationRegistry.getArguments().getString("uiReviewStage") ?: "after"
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "ui-review/$stage").apply { mkdirs() }
        var scene by mutableStateOf("auth")
        var dark by mutableStateOf(false)
        var scale by mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                VoiceBridgeTheme(darkTheme = dark, dynamicColor = false) {
                    Scaffold { padding -> key(scene, dark, scale) { Fixture(scene, Modifier.padding(padding)) } }
                }
            }
        }
        val cases = mutableListOf(
            Triple("auth", false, 1f), Triple("chat-empty", false, 1f),
            Triple("chat", false, 1f), Triple("memo", false, 1f), Triple("memo-history", false, 1f)
        )
        if (stage != "before") cases.addAll(listOf(
            Triple("chat", true, 1f), Triple("memo-history", true, 1f),
            Triple("auth", false, 1.5f), Triple("chat", false, 1.5f), Triple("memo-history", false, 1.5f)
        ))
        for ((name, isDark, fontScale) in cases) {
            compose.runOnIdle { scene = name; dark = isDark; scale = fontScale }
            compose.waitForIdle()
            if (name == "memo-history") {
                compose.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_morning"))
                compose.waitForIdle()
            }
            // Capture after Compose synchronizes its draw, rather than racing the system compositor.
            val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
            val suffix = if (isDark) "dark" else "light"
            val filename = "$name-$suffix-${(fontScale * 100).toInt()}.png"
            File(output, filename).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }

    @Composable private fun Fixture(scene: String, modifier: Modifier) {
        if (scene == "auth") {
            AuthScreen(AuthUiState(), {}, {}, {}, {}, modifier)
            return
        }
        val mode = if (scene.startsWith("memo")) HomeMode.Memo else HomeMode.Chat
        HomeScreen(AuthUiState(user = UserProfile("preview", "Alex Chen", null, null)), {}, {}, modifier,
            mode = mode, onModeChange = {},
            voiceControls = {
                SpeechInput(SpeechUiState(), if (mode == HomeMode.Chat) SpeechTarget.Chat else SpeechTarget.Memo,
                    {}, {}, {}, {}, {}, {}, true)
            }) {
            if (mode == HomeMode.Chat) {
                val messages = if (scene == "chat-empty") emptyList() else listOf(
                    ChatMessage(1, MessageRole.User, "Can you help me plan a relaxed weekend?", "2026-10-29T09:00:00Z"),
                    ChatMessage(2, MessageRole.Assistant,
                        "Start with a morning walk, make time for a hobby, and keep Sunday evening free to recharge.",
                        "2026-10-29T09:01:00Z")
                )
                ChatScreen(ChatUiState(conversationId = "preview", title = if (messages.isEmpty()) null else "Weekend inspiration",
                    messages = messages), {}, {}, {}, {}, {}, {}, {}, {})
            } else {
                MemoScreen(MemoUiState(memos = listOf(
                    Memo("morning", "Make time for a morning walk, then review my class notes over coffee.", "2026-10-29T09:00:00Z", SheetSaveStatus.Saved),
                    Memo("weekend", "Weekend idea: try a new trail and take a few photos along the way.", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved)
                )), {}, {}, {}, {}, {}, {}, {}, true)
            }
        }
    }
}
