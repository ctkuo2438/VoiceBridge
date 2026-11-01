package com.zhenting.voicebridge.data.local

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.zhenting.voicebridge.data.model.MemoContext
import com.zhenting.voicebridge.data.model.Memo
import com.zhenting.voicebridge.data.model.MemoAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

enum class DraftDestination { Chat, Memo }
enum class LocalSaveStatus { Saved, Saving, Failed }
data class LocalSaveProgress(val revision: Long = 0, val status: LocalSaveStatus = LocalSaveStatus.Saved)
data class MemoDraft(val text: String = "", val pendingPrompt: String? = null, val requestId: String? = null,
    val title: String = "", val pendingTitle: String? = null)
data class MemoEditDraft(val memo: Memo, val title: String = memo.title, val text: String = memo.prompt,
    val outcomeUnknown: Boolean = false)
data class ChatDraft(
    val text: String = "", val conversationId: String? = null, val title: String? = null,
    val memoContext: List<MemoContext> = emptyList(), val requiresReload: Boolean = false,
    val memoAction: MemoAction? = null
)
data class PendingSpeech(
    val id: String, val target: DraftDestination, val audioPath: String,
    val text: String? = null, val elapsedSeconds: Int = 0
)
data class RecoverySnapshot(
    val memo: MemoDraft = MemoDraft(), val chat: ChatDraft = ChatDraft(),
    val speech: PendingSpeech? = null, val destination: DraftDestination = DraftDestination.Chat,
    val memoEdit: MemoEditDraft? = null
)

/** One loaded store per user. UI updates are immediate; production disk writes run on IO. */
open class RecoveryStore(initial: RecoverySnapshot = RecoverySnapshot()) {
    @Volatile var snapshot: RecoverySnapshot = initial
        private set
    protected val progress = MutableStateFlow(LocalSaveProgress())
    val saveProgress = progress.asStateFlow()

    @Synchronized
    fun update(transform: (RecoverySnapshot) -> RecoverySnapshot) {
        val next = transform(snapshot)
        if (next == snapshot) return
        snapshot = next
        changed(next)
    }

    protected open fun changed(snapshot: RecoverySnapshot) = Unit
    open suspend fun flush(): Boolean = true

    /** Accept text and clear its speech review in one durable snapshot, preventing duplicate appends. */
    @Synchronized
    fun consumeSpeech(id: String): Pair<DraftDestination, String>? {
        val speech = snapshot.speech?.takeIf { it.id == id && !it.text.isNullOrBlank() } ?: return null
        val existing = if (speech.target == DraftDestination.Memo) snapshot.memo.text else snapshot.chat.text
        val combined = if (existing.isBlank()) speech.text!!.trim() else existing.trimEnd() + "\n" + speech.text!!.trim()
        update {
            if (speech.target == DraftDestination.Memo) {
                it.copy(memo = it.memo.copy(text = combined), speech = null, destination = speech.target)
            } else it.copy(chat = it.chat.copy(text = combined,
                memoAction = it.chat.memoAction?.takeIf { action -> action == MemoAction.Ask },
                title = if (it.chat.conversationId == null && it.chat.memoAction != null && it.chat.memoAction != MemoAction.Ask) null else it.chat.title),
                speech = null, destination = speech.target)
        }
        return speech.target to combined
    }
}

class PersistentRecoveryStore private constructor(
    private val dataStore: DataStore<RecoverySnapshot>, initial: RecoverySnapshot, scope: CoroutineScope
) : RecoveryStore(initial) {
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L

    init {
        scope.launch {
            for (signal in writes) {
                val (version, value) = synchronized(this@PersistentRecoveryStore) { revision to snapshot }
                val status = try {
                    dataStore.updateData { value }
                    LocalSaveStatus.Saved
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    LocalSaveStatus.Failed
                }
                synchronized(this@PersistentRecoveryStore) {
                    if (version == revision) progress.value = LocalSaveProgress(version, status)
                }
            }
        }
    }

    override fun changed(snapshot: RecoverySnapshot) {
        revision++
        progress.value = LocalSaveProgress(revision, LocalSaveStatus.Saving)
        writes.trySend(Unit)
    }

    override suspend fun flush(): Boolean {
        val target = synchronized(this) {
            if (progress.value.status == LocalSaveStatus.Failed) {
                progress.value = LocalSaveProgress(revision, LocalSaveStatus.Saving)
                writes.trySend(Unit)
            }
            revision
        }
        return saveProgress.first { it.revision >= target && it.status != LocalSaveStatus.Saving }
            .status == LocalSaveStatus.Saved
    }

    companion object {
        /** Call once per file; AppContainer caches the opening operation for each account. */
        suspend fun open(directory: File, scope: CoroutineScope): PersistentRecoveryStore {
            val job = SupervisorJob(scope.coroutineContext[Job])
            val storeScope = CoroutineScope(scope.coroutineContext + job)
            val store = DataStoreFactory.create(serializer = RecoverySerializer, scope = storeScope,
                produceFile = { File(directory, "drafts.json") })
            try {
                val snapshot = store.data.first()
                val audioDirectory = File(directory, "audio")
                snapshot.speech?.let {
                    if (File(it.audioPath).canonicalFile.parentFile != audioDirectory.canonicalFile) {
                        throw IOException("Invalid local recording location.")
                    }
                }
                // A completed, registered clip survives reopening. Only unregistered scratch files are removed.
                audioDirectory.listFiles()?.filter { it.absolutePath != snapshot.speech?.audioPath }
                    ?.forEach { if (it.isFile) it.delete() }
                return PersistentRecoveryStore(store, snapshot, storeScope)
            } catch (error: Throwable) {
                withContext(NonCancellable) { job.cancelAndJoin() }
                throw error
            }
        }
    }
}

internal object RecoverySerializer : Serializer<RecoverySnapshot> {
    private val gson = Gson()
    override val defaultValue = RecoverySnapshot()

    override suspend fun readFrom(input: InputStream): RecoverySnapshot {
        try {
            val value = gson.fromJson(input.reader(Charsets.UTF_8), RecoverySnapshot::class.java)
                ?: throw CorruptionException("Empty local draft data.")
            // Gson can assign null to Kotlin non-null fields. Reject corrupt state without erasing it.
            requireNotNull(value.memo); requireNotNull(value.memo.text); requireNotNull(value.memo.title)
            requireNotNull(value.chat); requireNotNull(value.chat.text); requireNotNull(value.chat.memoContext)
            requireNotNull(value.destination)
            value.speech?.let { requireNotNull(it.id); requireNotNull(it.target); requireNotNull(it.audioPath) }
            value.memoEdit?.let {
                requireNotNull(it.memo); requireNotNull(it.title); requireNotNull(it.text)
                requireNotNull(it.memo.id); requireNotNull(it.memo.title); requireNotNull(it.memo.prompt)
                requireNotNull(it.memo.createdAt); requireNotNull(it.memo.updatedAt); requireNotNull(it.memo.sheetStatus)
                require(it.memo.version >= 0)
            }
            return value
        } catch (error: JsonParseException) {
            throw CorruptionException("Cannot read local drafts.", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("Invalid local draft data.", error)
        }
    }

    override suspend fun writeTo(t: RecoverySnapshot, output: OutputStream) {
        output.write(gson.toJson(t).toByteArray(Charsets.UTF_8))
    }
}
