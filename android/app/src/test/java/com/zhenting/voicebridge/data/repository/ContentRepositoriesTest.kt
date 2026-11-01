package com.zhenting.voicebridge.data.repository

import com.google.gson.JsonParser
import com.zhenting.voicebridge.data.model.MessageRole
import com.zhenting.voicebridge.data.model.SheetSaveStatus
import com.zhenting.voicebridge.data.model.UserProfile
import com.zhenting.voicebridge.data.remote.ApiException
import com.zhenting.voicebridge.data.remote.ApiIssue
import com.zhenting.voicebridge.data.remote.RetrofitClient
import com.zhenting.voicebridge.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ContentRepositoriesTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var sessions: SessionStore
    private lateinit var chat: RemoteChatRepository
    private lateinit var memo: RemoteMemoRepository
    private val token = "a".repeat(43)
    private val user = UserProfile("user-1", "Test User", null, null)
    private val date = "2026-10-28T12:00:00+00:00"
    private val requestId = "82bbd98a-49f5-488a-a2e8-e12b8b9e54af"

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).build()
        sessions = SessionStore { 1000L }.also { it.save(token, 100_000, user) }
        val api = RetrofitClient.create(server.url("/").toString(), client, sessions)
        chat = RemoteChatRepository(api, sessions, user.id)
        memo = RemoteMemoRepository(api, sessions, user.id)
    }

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun json(body: String, status: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun messages(prompt: String = "Hi", reply: String = "Hello") =
        """[{"id":1,"role":"user","content":"$prompt","created_at":"$date"},{"id":2,"role":"assistant","content":"$reply","created_at":"$date"}]"""

    private fun turn(sheet: String = """{"saved":true,"updated_rows":1,"updated_range":"Logs!A2:D2"}""") =
        """{"conversation_id":"c-1","reply":"Hello","messages":${messages()},"sheet":$sheet}"""

    private fun groundedTurn(): com.google.gson.JsonObject = JsonParser.parseString(turn()).asJsonObject.apply {
        add("memo_context", JsonParser.parseString("""[{"id":"m-1","title":"Project","prompt":"Introduction.\n\nOriginal source paragraph.","created_at":"$date"}]"""))
        getAsJsonArray("messages")[1].asJsonObject.add("sections", JsonParser.parseString("""[{"text":"Hello","kind":"note","sources":[{"memo_id":"m-1","memo_title":"Project","paragraph_id":"p2","quote":"Original source paragraph."}]}]"""))
    }

    @Test
    fun chatSendsOnlyTextAndConversationIdWithTheFlaskBearerToken() = runTest {
        json(turn())
        val result = chat.send("  Hi  ", "c-1")
        assertEquals("c-1", result.conversationId)
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), result.messages.map { it.role })
        assertEquals(SheetSaveStatus.Saved, result.sheetStatus)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/chat", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("message", "conversation_id"), body.keySet())
        assertEquals("Hi", body.get("message").asString)
        assertEquals("c-1", body.get("conversation_id").asString)
    }

    @Test
    fun sheetFailureDoesNotDiscardAValidAiReply() = runTest {
        json(turn("""{"saved":false,"code":"sheets_timeout"}"""))
        val result = chat.send("Hi", null)
        assertEquals("Hello", result.messages.last().content)
        assertEquals(SheetSaveStatus.Unconfirmed, result.sheetStatus)
        val body = JsonParser.parseString(server.takeRequest()!!.body.readUtf8()).asJsonObject
        assertFalse(body.has("conversation_id"))
    }

    @Test
    fun historyAndMessagesAreReadFromTheExistingUserEndpoints() = runTest {
        json("""{"conversations":[{"id":"c-1","title":"Hi","created_at":"$date","updated_at":"$date"}]}""")
        json("""{"conversation_id":"c-1","messages":${messages()}}""")
        assertEquals("c-1", chat.conversations().single().id)
        assertEquals("Hello", chat.messages("c-1").messages.last().content)
        assertEquals("/conversations", server.takeRequest()!!.path)
        assertEquals("/conversations/c-1/messages", server.takeRequest()!!.path)
    }

    @Test
    fun mismatchedOrMalformedChatResponsesAreNotAppendedToTheConversation() = runTest {
        for (body in listOf("{}", "not-json", turn().replace("\"c-1\"", "\"c-other\""),
            turn().replace("\"reply\":\"Hello\"", "\"reply\":\"Different\""))) {
            json(body)
            try {
                chat.send("Hi", "c-1")
                fail("Invalid chat response was accepted.")
            } catch (error: ApiException) {
                assertEquals(ApiIssue.InvalidResponse, error.issue)
                assertTrue(error.outcomeUnknown)
            }
        }
    }

    @Test
    fun selectedMemoIdsAreSentWithoutClientSuppliedMemoTextAndSnapshotsReturnWithHistory() = runTest {
        val context = """[{"id":"m-1","prompt":"Golf note","created_at":"$date"},{"id":"m-2","prompt":"Friday trip","created_at":"$date"}]"""
        json(turn().dropLast(1) + ",\"memo_context\":$context}")
        val result = chat.send("Hi", null, listOf("m-1", "m-2"))
        assertEquals(listOf("m-1", "m-2"), result.memoContext.map { it.id })
        val request = server.takeRequest()!!
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("message", "memo_ids"), body.keySet())
        assertEquals(listOf("m-1", "m-2"), body["memo_ids"].asJsonArray.map { it.asString })
        assertFalse(body.toString().contains("Golf note"))
        json("""{"conversation_id":"c-1","messages":${messages()},"memo_context":$context}""")
        val restored = chat.messages("c-1")
        assertEquals(result.memoContext, restored.memoContext)
    }

    @Test
    fun noteActionsSendOnlyAnActionCodeAndOwnedMemoIdsAndRestoreVerifiedSources() = runTest {
        val payload = groundedTurn()
        json(payload.toString())
        val result = chat.send("Hi", null, listOf("m-1"), com.zhenting.voicebridge.data.model.MemoAction.Summary)
        val source = result.messages.last().sections.single().sources.single()
        assertEquals("Original source paragraph.", source.quote)
        assertEquals("Project", source.memoTitle)
        val body = JsonParser.parseString(server.takeRequest()!!.body.readUtf8()).asJsonObject
        assertEquals("summary", body["memo_action"].asString)
        assertFalse(body.toString().contains("Original source"))
        payload.addProperty("conversation_id", "c-1")
        json(payload.toString())
        assertEquals(result.messages.last().sections, chat.messages("c-1").messages.last().sections)
    }

    @Test
    fun sourceReferencesCannotPointOutsideTheAttachedNotesOrInventAnOriginalQuote() = runTest {
        for ((field, value) in listOf("memo_id" to "other-user-note", "quote" to "Invented quote", "memo_title" to "Wrong title")) {
            val payload = groundedTurn()
            payload["messages"].asJsonArray[1].asJsonObject["sections"].asJsonArray[0].asJsonObject["sources"].asJsonArray[0].asJsonObject
                .addProperty(field, value)
            json(payload.toString())
            try { chat.send("Hi", null, listOf("m-1")); fail("An invalid reference was accepted.") }
            catch (error: ApiException) { assertEquals(ApiIssue.InvalidResponse, error.issue); assertTrue(error.outcomeUnknown) }
        }
        val mismatched = groundedTurn()
        mismatched["messages"].asJsonArray[1].asJsonObject.addProperty("content", "Different answer")
        mismatched.addProperty("reply", "Different answer")
        json(mismatched.toString())
        try { chat.send("Hi", null, listOf("m-1")); fail("Sources were attached to a different answer.") }
        catch (error: ApiException) { assertEquals(ApiIssue.InvalidResponse, error.issue) }
    }

    @Test
    fun ignoredOrMalformedMemoAttachmentsAreNotAcceptedAsASuccessfulChat() = runTest {
        json(turn())
        try { chat.send("Hi", null, listOf("m-1")); fail("Missing memo snapshots were accepted.") }
        catch (error: ApiException) {
            assertEquals(ApiIssue.InvalidResponse, error.issue)
            assertTrue(error.outcomeUnknown)
        }
        try { chat.send("Hi", "c-1", listOf("m-1")); fail("Existing chat accepted new memo IDs.") }
        catch (error: ApiException) { assertEquals(ApiIssue.InvalidInput, error.issue) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun confirmedMemoUsesPromptAndRequestIdAndCanSurviveASheetFailure() = runTest {
        json("""{"saved":true,"memo":{"id":"m-1","prompt":"My note","created_at":"$date","sheet_status":"saved"},"sheet":{"saved":true}}""", 201)
        val saved = memo.save("  My note  ", requestId)
        assertEquals("m-1", saved.id)
        assertEquals(SheetSaveStatus.Saved, saved.sheetStatus)
        val request = server.takeRequest()!!
        assertEquals("/memos", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(setOf("prompt", "request_id"), body.keySet())
        assertEquals("My note", body.get("prompt").asString)
        assertEquals(requestId, body.get("request_id").asString)
        json("""{"saved":true,"memo":{"id":"m-2","prompt":"My note","created_at":"$date","sheet_status":"unconfirmed"},"sheet":{"saved":false}}""", 201)
        assertEquals(SheetSaveStatus.Unconfirmed, memo.save("My note", requestId).sheetStatus)
    }

    @Test
    fun memoHistoryUsesTheAuthenticatedEndpointAndPreservesTheServerOrder() = runTest {
        json("""{"memos":[{"id":"new","prompt":"Newest note","created_at":"$date","sheet_status":"saved"},{"id":"old","prompt":"Older note","created_at":"2026-10-27T12:00:00Z","sheet_status":"pending"}]}""")
        val notes = memo.memos()
        assertEquals(listOf("new", "old"), notes.map { it.id })
        assertEquals(SheetSaveStatus.Unconfirmed, notes.last().sheetStatus)
        val request = server.takeRequest()!!
        assertEquals("GET", request.method)
        assertEquals("/memos", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test
    fun aTitledMemoIsSentWithItsTitleAndReadsBackTheVersionMetadata() = runTest {
        json("""{"saved":true,"memo":{"id":"m-1","title":"課程回顧","prompt":"My note","created_at":"$date","updated_at":"$date","version":0,"sheet_status":"saved"}}""", 201)
        val saved = memo.save("My note", requestId, "  課程回顧  ")
        assertEquals("課程回顧", saved.title)
        assertEquals(0, saved.version)
        val body = JsonParser.parseString(server.takeRequest()!!.body.readUtf8()).asJsonObject
        assertEquals("課程回顧", body["title"].asString)
    }

    @Test
    fun editingUsesAuthenticatedPutWithTheOriginalVersionAndAcceptsAnOutdatedSheetCopy() = runTest {
        json("""{"saved":true,"memo":{"id":"m-1","title":"Revised","prompt":"Changed text","created_at":"$date","updated_at":"2026-10-31T12:00:00Z","version":4,"sheet_status":"unconfirmed","sheet_outdated":true}}""")
        val result = memo.update("m-1", " Revised ", " Changed text ", 3)
        assertEquals(4, result.version)
        assertEquals(SheetSaveStatus.Outdated, result.sheetStatus)
        val request = server.takeRequest()!!
        assertEquals("PUT", request.method)
        assertEquals("/memos/m-1", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(3, body["version"].asInt)
        assertEquals("Revised", body["title"].asString)
        assertEquals("Changed text", body["prompt"].asString)
    }

    @Test
    fun editingReportsConflictsAndDoesNotClaimSuccessForMalformedVersionMetadata() = runTest {
        json("""{"code":"memo_changed"}""", 409)
        try { memo.update("m-1", "Title", "Text", 0); fail("Stale edit was accepted.") }
        catch (error: ApiException) { assertEquals(ApiIssue.Conflict, error.issue); assertFalse(error.outcomeUnknown) }
        json("""{"saved":true,"memo":{"id":"m-1","title":"Title","prompt":"Text","created_at":"$date","sheet_status":"saved"}}""")
        try { memo.update("m-1", "Title", "Text", 0); fail("Missing version was accepted.") }
        catch (error: ApiException) { assertEquals(ApiIssue.InvalidResponse, error.issue); assertTrue(error.outcomeUnknown) }
        json("""{"saved":true,"memo":{"id":"m-1","title":"Title","prompt":"Text","created_at":"$date","updated_at":"$date","version":1,"sheet_status":"invalid","sheet_outdated":true}}""")
        try { memo.update("m-1", "Title", "Text", 0); fail("Invalid copy status was accepted.") }
        catch (error: ApiException) { assertEquals(ApiIssue.InvalidResponse, error.issue); assertTrue(error.outcomeUnknown) }
    }

    @Test
    fun aMalformedMemoResponseDoesNotClaimThatTheNoteWasSaved() = runTest {
        json("""{"saved":true,"memo":{"id":"m-1","prompt":"Wrong note","created_at":"$date","sheet_status":"saved"}}""", 201)
        try {
            memo.save("My note", requestId)
            fail("A mismatched memo was accepted.")
        } catch (error: ApiException) {
            assertEquals(ApiIssue.InvalidResponse, error.issue)
            assertTrue(error.outcomeUnknown)
        }
    }

    @Test
    fun aLostMemoResponseIsNotRetriedAutomatically() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            memo.save("Keep this draft", requestId)
            fail("A lost response was accepted.")
        } catch (error: ApiException) {
            assertTrue(error.outcomeUnknown)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun userChangeBeforeSendingCannotTransmitThePreviousUsersDraft() = runTest {
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val heldClient = client.newBuilder().addInterceptor { chain ->
            reached.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            chain.proceed(chain.request())
        }.build()
        val heldMemo = RemoteMemoRepository(
            RetrofitClient.create(server.url("/").toString(), heldClient, sessions), sessions, user.id
        )
        try {
            val pending = async(Dispatchers.Default) {
                try { heldMemo.save("Private draft", requestId); null } catch (error: ApiException) { error.issue }
            }
            assertTrue(reached.await(3, TimeUnit.SECONDS))
            sessions.save("b".repeat(43), 100_000, user.copy(id = "user-2"))
            release.countDown()
            assertEquals(ApiIssue.SessionExpired, pending.await())
            assertEquals(0, server.requestCount)
            assertEquals("b".repeat(43), sessions.accessToken())
        } finally { release.countDown() }
    }

    @Test
    fun unauthorizedRequestsReturnToSignInWithoutKeepingTheOldSession() = runTest {
        json("""{"code":"unauthorized"}""", 401)
        try { chat.conversations(); fail("Unauthorized request was accepted.") }
        catch (error: ApiException) { assertEquals(ApiIssue.SessionExpired, error.issue) }
        assertNull(sessions.user.value)
    }
}
