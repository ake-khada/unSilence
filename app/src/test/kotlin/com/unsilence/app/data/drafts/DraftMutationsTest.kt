package com.unsilence.app.data.drafts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DraftMutationsTest {
    private fun draft(
        key: String,
        at: Long,
        text: String = "text",
        context: DraftContext = DraftContext.New,
    ) = Draft(
        key = key,
        blocks = listOf(DraftBlock.Text(text)),
        isSensitive = false,
        updatedAt = at,
        context = context,
    )

    @Test
    fun `upsert replaces same key`() {
        val out = DraftMutations.upsert(
            existing = listOf(draft("new", 1, "old")),
            draft = draft("new", 2, "new"),
        )

        assertEquals(1, out.size)
        assertEquals("new", (out.first().blocks.first() as DraftBlock.Text).content)
    }

    @Test
    fun `upsert keeps newest first`() {
        val out = DraftMutations.upsert(
            existing = listOf(draft("new", 1)),
            draft = draft("reply:a", 2, context = DraftContext.Reply("a")),
        )

        assertEquals(listOf("reply:a", "new"), out.map { it.key })
    }

    @Test
    fun `upsert evicts oldest past cap`() {
        var list = emptyList<Draft>()
        for (i in 1..(MAX_DRAFTS_PER_PUBKEY + 5)) {
            list = DraftMutations.upsert(list, draft("k$i", i.toLong()))
        }

        assertEquals(MAX_DRAFTS_PER_PUBKEY, list.size)
        assertEquals("k${MAX_DRAFTS_PER_PUBKEY + 5}", list.first().key)
        assertNull(DraftMutations.find(list, "k1"))
    }

    @Test
    fun `delete removes only matching key`() {
        val list = listOf(draft("new", 1), draft("quote:z", 2))

        assertEquals(listOf("quote:z"), DraftMutations.delete(list, "new").map { it.key })
    }

    @Test
    fun `context keys are stable`() {
        assertEquals("new", DraftContext.New.key)
        assertEquals("reply:a", DraftContext.Reply("a", "pk").key)
        assertEquals("quote:b", DraftContext.Quote("b", "pk").key)
        assertEquals(
            "article:30023:pk:d:root",
            DraftContext.ArticleComment(
                articleId = "event",
                articleCoord = "30023:pk:d",
                articlePubkey = "pk",
            ).key,
        )
        assertEquals(
            "article:30023:pk:d:parent",
            DraftContext.ArticleComment(
                articleId = "event",
                articleCoord = "30023:pk:d",
                articlePubkey = "pk",
                parentId = "parent",
            ).key,
        )
    }

    @Test
    fun `attachment block round trips blossom blob`() {
        val block = DraftBlock.Attachment(
            url = "https://media.example/blob",
            sha256 = "abc",
            sizeBytes = 42,
            mimeType = "image/jpeg",
            width = 10,
            height = 20,
            blurhash = "blur",
            thumbnailUrl = "https://media.example/thumb",
            durationMs = 1000,
        )
        val blob = block.toBlob()
        val out = blob.toDraftAttachment()

        assertEquals(block, out)
    }
}
