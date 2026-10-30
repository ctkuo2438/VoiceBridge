package com.zhenting.voicebridge

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zhenting.voicebridge.data.model.*
import com.zhenting.voicebridge.data.repository.AuthRepository
import com.zhenting.voicebridge.data.repository.ChatRepository
import com.zhenting.voicebridge.data.repository.GoogleLoginChallenge
import com.zhenting.voicebridge.data.repository.MemoRepository
import com.zhenting.voicebridge.data.repository.SpeechRepository
import com.zhenting.voicebridge.data.audio.AudioRecorder
import com.zhenting.voicebridge.data.audio.AudioPlayer
import com.zhenting.voicebridge.data.repository.TtsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import android.Manifest
import java.io.File
import com.zhenting.voicebridge.ui.auth.AuthRoute
import com.zhenting.voicebridge.ui.auth.AuthViewModel
import com.zhenting.voicebridge.ui.home.HomeRoute
import com.zhenting.voicebridge.ui.home.WorkspaceViewModel
import com.zhenting.voicebridge.ui.theme.VoiceBridgeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val microphonePermission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val auth = FakeAuth()
    private val chats = mutableMapOf<String, FakeChat>()
    private val memos = mutableMapOf<String, FakeMemo>()
    private val readTexts = mutableListOf<Pair<String, String>>()
    private val player = FakePlayer()
    private var audioGate: CompletableDeferred<ByteArray>? = null

    private fun start() {
        composeRule.setContent {
            val authModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(auth))
            val workspace: WorkspaceViewModel = viewModel(factory = WorkspaceViewModel.factory(auth))
            VoiceBridgeTheme {
                AuthRoute(authModel, { "test-google-id-token" }, {},
                    signedInContent = { state, signOut, modifier ->
                        HomeRoute(workspace, state,
                            { id -> chats.getOrPut(id) { FakeChat { memos[id]?.records?.toList().orEmpty() } } },
                            { id -> memos.getOrPut(id) { FakeMemo() } },
                            signOut, authModel::dismissError, modifier,
                            createSpeechRepository = { object : SpeechRepository {
                                override suspend fun transcribe(audio: File) = "Recognized speech"
                            } },
                            createAudioRecorder = { FakeRecorder() },
                            createTtsRepository = { id -> object : TtsRepository {
                                override suspend fun synthesize(text: String): ByteArray {
                                    readTexts.add(id to text)
                                    return audioGate?.await() ?: byteArrayOf(1)
                                }
                            } },
                            createAudioPlayer = { player })
                    })
            }
        }
    }

    @Test
    fun modeSwitchKeepsBothDraftsAndMemoIsSavedOnlyAfterConfirmation() {
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Unsent chat draft")
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_input").performTextInput("My memo")
        composeRule.onNodeWithTag("chat_tab").performClick()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Unsent chat draft")
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_input").assertTextContains("My memo")
        composeRule.onNodeWithTag("save_memo_button").performScrollTo().performClick()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.runOnIdle { assertTrue(memos.getValue("a").prompts.isEmpty()) }
        composeRule.onNodeWithTag("save_memo_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_save_memo_button").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_saved"))
        composeRule.onNodeWithTag("memo_saved").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("My memo"), memos.getValue("a").prompts) }
    }

    @Test
    fun selectedHistoryContinuesItsConversationAndNewChatStartsWithoutAnId() {
        start()
        composeRule.onNodeWithTag("chat_history_button").performClick()
        composeRule.onNodeWithTag("conversation_c-old").performClick()
        composeRule.onNodeWithTag("conversation_title").assertTextEquals("Earlier chat")
        composeRule.onNodeWithTag("chat_input").performTextInput("What is my sport?")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.onNodeWithTag("message_102").assertTextEquals("Reply: What is my sport?")
        composeRule.runOnIdle { assertEquals(listOf("c-old"), chats.getValue("a").sentTo) }
        composeRule.onNodeWithTag("new_chat_button").performClick()
        composeRule.onNodeWithTag("chat_input").performTextInput("Hello")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.runOnIdle { assertEquals(listOf("c-old", null), chats.getValue("a").sentTo) }
    }

    @Test
    fun signOutAndAnotherUsersLoginCannotRestoreThePreviousUsersDrafts() {
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Private chat draft")
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_input").performTextInput("Private memo draft")
        composeRule.onNodeWithTag("home_sign_out_button").performClick()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
        composeRule.runOnIdle { auth.nextUser = UserProfile("b", "Bob", null, null) }
        composeRule.onNodeWithTag("google_sign_in_button").performClick()
        composeRule.onNodeWithTag("home_user").assertTextEquals("Bob")
        assertEquals("", composeRule.onNodeWithTag("chat_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
        composeRule.onNodeWithTag("memo_tab").performClick()
        assertEquals("", composeRule.onNodeWithTag("memo_input").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun speechIsReviewedAndEditedBeforeItIsAddedToTheMemoDraft() {
        memos["a"] = FakeMemo().apply {
            repeat(8) { index -> records.add(Memo("old-$index", "Earlier note $index", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved)) }
        }
        start()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_input").performTextInput("Existing note")
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_old-7"))
        composeRule.onNodeWithTag("speech_record_button").performClick()
        composeRule.onNodeWithTag("speech_stop_button").performClick()
        composeRule.onNodeWithTag("speech_transcript_input").assertTextContains("Recognized speech")
        composeRule.runOnIdle { assertTrue(memos.getValue("a").prompts.isEmpty()) }
        composeRule.onNodeWithTag("speech_transcript_input").performTextReplacement("Edited voice memo")
        composeRule.onNodeWithTag("speech_use_text_button").performClick()
        composeRule.onNodeWithTag("memo_input").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_input").assertTextContains("Existing note\nEdited voice memo")
        composeRule.runOnIdle { assertTrue(memos.getValue("a").prompts.isEmpty()) }
    }

    @Test
    fun reviewedSpeechInChatStillRequiresPressingSend() {
        start()
        composeRule.onNodeWithTag("speech_record_button").performClick()
        composeRule.onNodeWithTag("speech_stop_button").performClick()
        composeRule.onNodeWithTag("speech_use_text_button").performClick()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Recognized speech")
        composeRule.runOnIdle { assertTrue(chats.getValue("a").sentTo.isEmpty()) }
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.onNodeWithTag("message_102").assertTextEquals("Reply: Recognized speech")
    }

    @Test
    fun memoHistorySurvivesReloginAndAnotherUserOnlySeesTheirOwnMemos() {
        val saved = Memo("saved-a", "Alice saved memo", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved)
        memos["a"] = FakeMemo().apply { records.add(saved) }
        start()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_saved-a"))
        composeRule.onNodeWithTag("memo_saved-a").assertIsDisplayed()
        composeRule.onNodeWithTag("home_sign_out_button").performClick()
        composeRule.onNodeWithTag("google_sign_in_button").performClick()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_saved-a"))
        composeRule.onNodeWithTag("memo_saved-a").assertIsDisplayed()
        composeRule.onNodeWithTag("home_sign_out_button").performClick()
        composeRule.runOnIdle { auth.nextUser = UserProfile("b", "Bob", null, null) }
        composeRule.onNodeWithTag("google_sign_in_button").performClick()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_history_empty"))
        composeRule.onNodeWithTag("memo_history_empty").assertIsDisplayed()
        composeRule.onNodeWithTag("memo_saved-a").assertDoesNotExist()
    }

    @Test
    fun savingAddsANoteToHistoryEvenWhenItsSheetCopyIsUnconfirmed() {
        memos["a"] = FakeMemo().apply { sheetStatus = SheetSaveStatus.Unconfirmed }
        start()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_input").performTextInput("Persistent note")
        composeRule.onNodeWithTag("save_memo_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_save_memo_button").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("memo_note-1"))
        composeRule.onNodeWithTag("memo_note-1").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("Persistent note", memos.getValue("a").records.single().prompt) }
    }

    @Test
    fun chatCanPickMultipleMemosKeepTheDraftAndRestoreAttachmentsFromHistory() {
        memos["a"] = FakeMemo().apply {
            records.add(Memo("one", "Golf note", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved))
            records.add(Memo("two", "Friday trip", "2026-10-28T11:00:00Z", SheetSaveStatus.Saved))
        }
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Summarize these notes")
        composeRule.onNodeWithTag("choose_memos_button").performClick()
        composeRule.onNodeWithTag("pick_memo_one").performClick()
        composeRule.onNodeWithTag("pick_memo_two").performClick()
        composeRule.onNodeWithTag("memo_picker_count").assertTextEquals("2 / 10 memos selected")
        composeRule.onNodeWithTag("apply_memos_button").performClick()
        composeRule.onNodeWithTag("attached_memos_button").assertTextContains("Attached memos (2)")
        composeRule.onNodeWithTag("chat_input").assertTextContains("Summarize these notes")
        composeRule.runOnIdle { assertTrue(chats.getValue("a").sentTo.isEmpty()) }
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.runOnIdle { assertEquals(listOf(listOf("one", "two")), chats.getValue("a").sentMemoIds) }
        composeRule.onNodeWithTag("new_chat_button").performClick()
        composeRule.onNodeWithTag("chat_history_button").performClick()
        composeRule.onNodeWithTag("conversation_c-new").performClick()
        composeRule.onNodeWithTag("attached_memos_button").assertTextContains("Attached memos (2)")
    }

    @Test
    fun aSingleSelectedMemoCanOpenANewChatDirectlyFromMemoMode() {
        memos["a"] = FakeMemo().apply {
            records.add(Memo("one", "Golf note", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved))
        }
        start()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("select_memo_one"))
        composeRule.onNodeWithTag("select_memo_one").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("chat_with_memos_button"))
        composeRule.onNodeWithTag("chat_with_memos_button").performClick()
        composeRule.onNodeWithTag("chat_input").assertIsDisplayed()
        composeRule.onNodeWithTag("attached_memos_button").assertTextContains("Attached memos (1)")
        composeRule.runOnIdle { assertTrue(chats.getValue("a").sentTo.isEmpty()) }
    }

    @Test
    fun cancellingMemoSelectionKeepsTheQuestionAndDoesNotAttachOrSendAnything() {
        memos["a"] = FakeMemo().apply {
            records.add(Memo("one", "Golf note", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved))
        }
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Unsent question")
        composeRule.onNodeWithTag("choose_memos_button").performClick()
        composeRule.onNodeWithTag("pick_memo_one").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithTag("chat_input").assertTextContains("Unsent question")
        composeRule.onNodeWithTag("attached_memos_button").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(chats.getValue("a").sentTo.isEmpty()) }
    }

    @Test
    fun assistantRepliesCanBeReadAndStoppedWithoutResendingTheChat() {
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Hello")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.onNodeWithTag("tts_play_chat:c-new:102").performClick()
        composeRule.onNodeWithTag("tts_stop_chat:c-new:102").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("a" to "Reply: Hello"), readTexts)
            assertTrue(player.playing)
            assertEquals(1, chats.getValue("a").sentTo.size)
        }
        composeRule.onNodeWithTag("tts_stop_chat:c-new:102").performClick()
        composeRule.runOnIdle { assertTrue(!player.playing) }
        composeRule.onNodeWithTag("tts_play_chat:c-new:102").performClick()
        composeRule.onNodeWithTag("new_chat_button").performClick()
        composeRule.runOnIdle { assertTrue(!player.playing) }
    }

    @Test
    fun savedMemosCanBeReadAndModeSwitchStopsTheirPlayback() {
        memos["a"] = FakeMemo().apply {
            records.add(Memo("read-me", "Read this saved memo.", "2026-10-28T12:00:00Z", SheetSaveStatus.Saved))
        }
        start()
        composeRule.onNodeWithTag("memo_tab").performClick()
        composeRule.onNodeWithTag("memo_list").performScrollToNode(hasTestTag("tts_play_memo:read-me"))
        composeRule.onNodeWithTag("tts_play_memo:read-me").performClick()
        composeRule.onNodeWithTag("tts_stop_memo:read-me").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("a" to "Read this saved memo."), readTexts)
            assertTrue(memos.getValue("a").prompts.isEmpty())
        }
        composeRule.onNodeWithTag("chat_tab").performClick()
        composeRule.runOnIdle { assertTrue(!player.playing) }
    }

    @Test
    fun cancellingGenerationPreventsLateAudioAndSignOutStopsPlayback() {
        audioGate = CompletableDeferred()
        start()
        composeRule.onNodeWithTag("chat_input").performTextInput("Hello")
        composeRule.onNodeWithTag("send_message_button").performClick()
        composeRule.onNodeWithTag("tts_play_chat:c-new:102").performClick()
        composeRule.onNodeWithTag("tts_status_chat:c-new:102").assertTextEquals("Generating audio…")
        composeRule.onNodeWithTag("tts_stop_chat:c-new:102").performClick()
        composeRule.runOnIdle { checkNotNull(audioGate).complete(byteArrayOf(1)) }
        composeRule.onNodeWithTag("tts_play_chat:c-new:102").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!player.playing) }
        composeRule.onNodeWithTag("tts_play_chat:c-new:102").performClick()
        composeRule.runOnIdle { assertTrue(player.playing) }
        composeRule.onNodeWithTag("home_sign_out_button").performClick()
        composeRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!player.playing) }
    }

    private class FakePlayer : AudioPlayer {
        var playing = false
        override suspend fun play(audio: ByteArray, onStarted: () -> Unit) {
            playing = true
            onStarted()
            try { awaitCancellation() } finally { playing = false }
        }
        override fun stop() { playing = false }
    }

    private class FakeRecorder : AudioRecorder {
        private var file: File? = null
        override fun start(onError: () -> Unit) {
            file = File.createTempFile("test-audio", ".m4a",
                InstrumentationRegistry.getInstrumentation().targetContext.cacheDir).apply { writeText("audio") }
        }
        override fun stop(): File = checkNotNull(file).also { file = null }
        override fun cancel() { file?.delete(); file = null }
    }

    private class FakeAuth : AuthRepository {
        var nextUser = UserProfile("a", "Alice", null, null)
        override val user = MutableStateFlow<UserProfile?>(nextUser)
        override suspend fun createGoogleChallenge() = GoogleLoginChallenge("test-client", "test-nonce", Long.MAX_VALUE)
        override suspend fun loginWithGoogle(challenge: GoogleLoginChallenge, idToken: String) { user.value = nextUser }
        override suspend fun refreshUser() = Unit
        override suspend fun logout() { user.value = null }
    }
    private class FakeChat(private val sourceMemos: () -> List<Memo>) : ChatRepository {
        val sentTo = mutableListOf<String?>()
        val sentMemoIds = mutableListOf<List<String>>()
        private val date = "2026-10-28T12:00:00+00:00"
        private val saved = mutableMapOf<String, ConversationContent>()
        override suspend fun conversations() = listOf(Conversation("c-old", "Earlier chat", date, date)) +
            saved.keys.filter { it != "c-old" }.map { Conversation(it, "Memo chat", date, date) }
        override suspend fun messages(conversationId: String) = saved[conversationId] ?: ConversationContent(listOf(
            ChatMessage(1, MessageRole.User, "My sport is golf.", date),
            ChatMessage(2, MessageRole.Assistant, "I will remember golf in this conversation.", date)))
        override suspend fun send(message: String, conversationId: String?, memoIds: List<String>): ChatTurn {
            sentTo.add(conversationId)
            sentMemoIds.add(memoIds)
            val id = 100L + sentTo.size * 2
            val target = conversationId ?: "c-new"
            val previous = if (conversationId == null) ConversationContent(emptyList()) else messages(conversationId)
            val context = if (conversationId == null) memoIds.map { selected ->
                sourceMemos().first { it.id == selected }.asContext()
            } else previous.memoContext
            val turn = ChatTurn(target, listOf(
                ChatMessage(id - 1, MessageRole.User, message, date),
                ChatMessage(id, MessageRole.Assistant, "Reply: $message", date)), SheetSaveStatus.Saved, context)
            saved[target] = ConversationContent(previous.messages + turn.messages, context)
            return turn
        }
    }
    private class FakeMemo : MemoRepository {
        val prompts = mutableListOf<String>()
        val records = mutableListOf<Memo>()
        var sheetStatus = SheetSaveStatus.Saved
        override suspend fun memos(): List<Memo> = records.toList()
        override suspend fun save(prompt: String, requestId: String): Memo {
            prompts.add(prompt)
            return Memo("note-${prompts.size}", prompt, "2026-10-28T12:00:00Z", sheetStatus)
                .also { records.add(0, it) }
        }
    }
}
