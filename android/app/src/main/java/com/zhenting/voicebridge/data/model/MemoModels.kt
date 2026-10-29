package com.zhenting.voicebridge.data.model

const val MAX_CHAT_MEMOS = 10
const val MAX_MEMO_CONTEXT_CHARACTERS = 20_000

data class MemoContext(val id: String, val prompt: String, val createdAt: String)

fun memoContextSize(memos: List<MemoContext>): Int = memos.sumOf { chatCharacterCount(it.prompt) }

fun Memo.asContext(): MemoContext = MemoContext(id, prompt, createdAt)

data class Memo(
    val id: String,
    val prompt: String,
    val createdAt: String,
    val sheetStatus: SheetSaveStatus
)
