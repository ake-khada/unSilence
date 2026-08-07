package com.unsilence.app.ui.shared

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.toEventModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostActionsPolicyTest {
    @Test
    fun `native signed body is copyable without a cached model`() {
        assertEquals("hello", copyablePostText(row(content = "hello"), null))
    }

    @Test
    fun `reference-only repost envelope is never copied`() {
        val targetId = "e".repeat(64)
        val wrapper = row(
            id = "wrapper",
            kind = 6,
            content = """{"id":"$targetId"}""",
            tags = """[["e","$targetId"]]""",
            rootId = targetId,
        )
        val wrapperModel = wrapper.toEventModel()

        assertNull(copyablePostText(wrapper) { id -> wrapperModel.takeIf { id == wrapper.id } })
    }

    @Test
    fun `reference-only repost copies independently resolved target text`() {
        val targetId = "e".repeat(64)
        val wrapper = row(
            id = "wrapper",
            kind = 16,
            content = """{"id":"$targetId"}""",
            tags = """[["e","$targetId"],["k","1"]]""",
            rootId = targetId,
        )
        val target = row(id = targetId, content = "authenticated target")
        val models = mapOf(
            wrapper.id to wrapper.toEventModel(),
            target.id to target.toEventModel(),
        )

        assertEquals("authenticated target", copyablePostText(wrapper, models::get))
    }

    private fun row(
        id: String = "note",
        kind: Int = 1,
        content: String,
        tags: String = "[]",
        rootId: String? = null,
    ) = FeedRow(
        id = id,
        pubkey = "a".repeat(64),
        kind = kind,
        content = content,
        createdAt = 1L,
        tags = tags,
        relayUrl = "wss://relay.example",
        replyToId = null,
        rootId = rootId,
        hasContentWarning = false,
        contentWarningReason = null,
        zapTotalSats = 0L,
        authorName = null,
        authorDisplayName = null,
        authorPicture = null,
        authorNip05 = null,
        reactionCount = 0,
        replyCount = 0,
        repostCount = 0,
        zapCount = 0,
    )
}
