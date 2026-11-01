package com.zhenting.voicebridge.data.model

const val MAX_CHAT_MEMOS = 10
const val MAX_MEMO_CONTEXT_CHARACTERS = 20_000
const val MAX_MEMO_TITLE_CHARACTERS = 120

fun defaultMemoTitle(prompt: String): String {
    val firstLine = prompt.trim().split('\r', '\n').first().trim()
    return firstLine.substring(0, firstLine.offsetByCodePoints(0, minOf(80, chatCharacterCount(firstLine))))
}

data class MemoContext(val id: String, val prompt: String, val createdAt: String, val title: String? = null) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: defaultMemoTitle(prompt)
}

enum class MemoAction(val apiValue: String, val prompt: String, val titlePrefix: String) {
    Summary("summary", "Summarize the key points of this note.", "Summary"),
    Todos("todos", "List the explicit action items in this note. Include owners and deadlines only if stated.", "Action items"),
    Bilingual("bilingual", "Create concise Chinese-English key points from this note, grouped in aligned pairs.", "Chinese / English"),
    Ask("ask", "", "Ask note")
}

fun memoContextSize(memos: List<MemoContext>): Int = memos.sumOf { chatCharacterCount(it.prompt) }

fun Memo.asContext(): MemoContext = MemoContext(id, prompt, createdAt, title)

data class Memo(
    val id: String,
    val prompt: String,
    val createdAt: String,
    val sheetStatus: SheetSaveStatus,
    val title: String = defaultMemoTitle(prompt),
    val updatedAt: String = createdAt,
    val version: Int = 0
)
