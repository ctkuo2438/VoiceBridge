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
import com.zhenting.voicebridge.ui.auth.AuthPhase
import com.zhenting.voicebridge.ui.chat.ChatScreen
import com.zhenting.voicebridge.ui.chat.ChatUiState
import com.zhenting.voicebridge.ui.home.HomeMode
import com.zhenting.voicebridge.ui.home.HomeScreen
import com.zhenting.voicebridge.ui.memo.MemoScreen
import com.zhenting.voicebridge.ui.memo.MemoUiState
import com.zhenting.voicebridge.ui.speech.SpeechInput
import com.zhenting.voicebridge.ui.speech.SpeechTarget
import com.zhenting.voicebridge.ui.speech.SpeechUiState
import com.zhenting.voicebridge.ui.speech.SpeechPhase
import com.zhenting.voicebridge.ui.tts.TtsUiState
import com.zhenting.voicebridge.ui.tts.TtsPhase
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
                    Scaffold { padding -> key(scene, dark, scale) { Fixture(scene, Modifier.padding(padding), stage == "journey") } }
                }
            }
        }
        val cases = if (stage == "journey") mutableListOf(
            Triple("auth", false, 1f), Triple("auth-verifying", false, 1f),
            Triple("chat-empty", false, 1f), Triple("chat-recording", false, 1f),
            Triple("chat-transcribing", false, 1f), Triple("chat-review", false, 1f),
            Triple("chat-draft", false, 1f), Triple("chat-sending", false, 1f), Triple("chat", false, 1f),
            Triple("chat-tts-generating", false, 1f), Triple("chat-tts-preparing", false, 1f), Triple("chat-tts-playing", false, 1f),
            Triple("memo-recording", false, 1f), Triple("memo-transcribing", false, 1f), Triple("memo-review", false, 1f),
            Triple("memo-draft", false, 1f), Triple("memo-history", false, 1f),
            Triple("memo-confirm", false, 1f), Triple("memo-saving", false, 1f),
            Triple("memo-selected", false, 1f), Triple("chat-memos", false, 1f), Triple("chat-memos-reply", false, 1f),
            Triple("memo-tts-generating", false, 1f), Triple("memo-tts-preparing", false, 1f), Triple("memo-tts-playing", false, 1f)
        ) else mutableListOf(
            Triple("auth", false, 1f), Triple("chat-empty", false, 1f),
            Triple("chat", false, 1f), Triple("memo", false, 1f), Triple("memo-history", false, 1f)
        )
        if (stage != "before" && stage != "journey") cases.addAll(listOf(
            Triple("chat", true, 1f), Triple("memo-history", true, 1f),
            Triple("auth", false, 1.5f), Triple("chat", false, 1.5f), Triple("memo-history", false, 1.5f)
        ))
        for ((name, isDark, fontScale) in cases) {
            compose.runOnIdle { scene = name; dark = isDark; scale = fontScale }
            compose.waitForIdle()
            if (name in setOf("memo-history", "memo-selected", "memo-tts-generating", "memo-tts-preparing", "memo-tts-playing")) {
                compose.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_morning"))
                compose.waitForIdle()
            }
            // Journey screenshots include platform dialog windows as well as the app background.
            val screenshot = if (stage == "journey") {
                android.os.SystemClock.sleep(300)
                instrumentation.uiAutomation.takeScreenshot()
            } else compose.onRoot().captureToImage().asAndroidBitmap()
            val suffix = if (isDark) "dark" else "light"
            val filename = "$name-$suffix-${(fontScale * 100).toInt()}.png"
            File(output, filename).outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        }
    }

    @Composable private fun Fixture(scene: String, modifier: Modifier, journey: Boolean) {
        if (scene.startsWith("auth")) {
            AuthScreen(AuthUiState(phase = if (scene == "auth-verifying") AuthPhase.Verifying else AuthPhase.Idle),
                {}, {}, {}, {}, modifier)
            return
        }
        val mode = if (scene.startsWith("memo")) HomeMode.Memo else HomeMode.Chat
        val target = if (mode == HomeMode.Memo) SpeechTarget.Memo else SpeechTarget.Chat
        val speech = when {
            scene.endsWith("-recording") -> SpeechUiState(SpeechPhase.Recording, target, elapsedSeconds = 7)
            scene.endsWith("-transcribing") -> SpeechUiState(SpeechPhase.Transcribing, target)
            scene.endsWith("-review") -> SpeechUiState(SpeechPhase.Review, target,
                text = if (mode == HomeMode.Memo) "Tomorrow I will review my notes before class."
                    else "Can you help me plan a relaxed weekend?")
            else -> SpeechUiState()
        }
        val playback = if (scene.contains("tts-")) TtsUiState(
            itemId = if (mode == HomeMode.Memo) "memo:morning" else "chat:preview:2",
            phase = when {
                scene.endsWith("playing") -> TtsPhase.Playing
                scene.endsWith("preparing") -> TtsPhase.Preparing
                else -> TtsPhase.Generating
            },
            part = 1, totalParts = 1) else TtsUiState()
        val memos = listOf(
            Memo("morning", if (journey) "Tomorrow I will review my notes before class."
                else "Make time for a morning walk, then review my class notes over coffee.", "2026-10-29T09:00:00Z", SheetSaveStatus.Saved),
            Memo("weekend", "Weekend idea: try a new trail and take a few photos along the way.", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved)
        )
        HomeScreen(AuthUiState(user = UserProfile("preview", "Alex Chen", null, null)), {}, {}, modifier,
            mode = mode, onModeChange = {}, voiceBusy = speech.isBusy,
            voiceControls = {
                SpeechInput(speech, if (mode == HomeMode.Chat) SpeechTarget.Chat else SpeechTarget.Memo,
                    {}, {}, {}, {}, {}, {}, true)
            }) {
            if (mode == HomeMode.Chat) {
                val messages = if (scene in setOf("chat-empty", "chat-recording", "chat-transcribing", "chat-review", "chat-draft", "chat-sending", "chat-memos")) emptyList() else listOf(
                    ChatMessage(1, MessageRole.User, if (scene == "chat-memos-reply") "Help me make a plan from these notes."
                        else "Can you help me plan a relaxed weekend?", "2026-10-29T09:00:00Z"),
                    ChatMessage(2, MessageRole.Assistant,
                        if (scene == "chat-memos-reply") "Review your notes before class tomorrow, then plan a trail walk and take photos on the weekend."
                        else "Start with a morning walk, make time for a hobby, and keep Sunday evening free to recharge.",
                        "2026-10-29T09:01:00Z")
                )
                ChatScreen(ChatUiState(conversationId = if (messages.isEmpty()) null else "preview",
                    title = if (messages.isEmpty()) null else "Weekend inspiration", messages = messages,
                    draft = when (scene) {
                        "chat-draft", "chat-sending" -> "Can you help me plan a relaxed weekend?"
                        "chat-memos" -> "Help me make a plan from these notes."
                        else -> ""
                    }, isSending = scene == "chat-sending",
                    memoContext = if (scene.startsWith("chat-memos")) memos.map { it.asContext() } else emptyList()),
                    {}, {}, {}, {}, {}, {}, {}, {}, ttsState = playback)
            } else {
                MemoScreen(MemoUiState(memos = memos,
                    draft = if (scene in setOf("memo-draft", "memo-confirm", "memo-saving")) "Tomorrow I will review my notes before class." else "",
                    confirmation = if (scene == "memo-confirm") "Tomorrow I will review my notes before class." else null,
                    isSaving = scene == "memo-saving",
                    selectedMemoIds = if (scene == "memo-selected") setOf("morning", "weekend") else emptySet()),
                    {}, {}, {}, {}, {}, {}, {}, true, ttsState = playback)
            }
        }
    }
}
