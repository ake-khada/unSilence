package com.unsilence.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FollowListPolicyTest {
    @Test
    fun `merge table preserves metadata while authoritative set wins`() {
        data class Case(
            val name: String,
            val authoritative: Set<String>,
            val retained: RetainedFollowList,
            val expectedTags: List<List<String>>,
            val expectedContent: String,
        )

        val cases = listOf(
            Case(
                name = "decorated retained follow and new bare follow",
                authoritative = setOf("alice", "new"),
                retained = RetainedFollowList(
                    tags = listOf(
                        listOf("p", "alice", "wss://alice.example", "Alice"),
                    ),
                    content = "legacy-relay-map",
                ),
                expectedTags = listOf(
                    listOf("p", "alice", "wss://alice.example", "Alice"),
                    listOf("p", "new"),
                ),
                expectedContent = "legacy-relay-map",
            ),
            Case(
                name = "stale retained event cannot resurrect removed follow",
                authoritative = setOf("kept"),
                retained = RetainedFollowList(
                    tags = listOf(
                        listOf("p", "removed", "wss://removed.example", "Removed"),
                        listOf("p", "kept", "wss://kept.example", "Kept"),
                    ),
                    content = "{}",
                ),
                expectedTags = listOf(
                    listOf("p", "kept", "wss://kept.example", "Kept"),
                ),
                expectedContent = "{}",
            ),
            Case(
                name = "non-p tags remain verbatim and in relative order",
                authoritative = setOf("alice"),
                retained = RetainedFollowList(
                    tags = listOf(
                        listOf("client", "other-client", "opaque"),
                        listOf("p", "alice", "wss://alice.example", "Alice", "extension"),
                        listOf("r", "wss://legacy.example", "read"),
                    ),
                    content = "{\"wss://legacy.example\":{\"read\":true}}\n",
                ),
                expectedTags = listOf(
                    listOf("client", "other-client", "opaque"),
                    listOf("p", "alice", "wss://alice.example", "Alice", "extension"),
                    listOf("r", "wss://legacy.example", "read"),
                ),
                expectedContent = "{\"wss://legacy.example\":{\"read\":true}}\n",
            ),
            Case(
                name = "decorated duplicate wins over an earlier bare p-tag",
                authoritative = setOf("alice"),
                retained = RetainedFollowList(
                    tags = listOf(
                        listOf("p", "alice"),
                        listOf("p", "alice", "wss://alice.example", "Alice"),
                    ),
                    content = "legacy",
                ),
                expectedTags = listOf(
                    listOf("p", "alice", "wss://alice.example", "Alice"),
                ),
                expectedContent = "legacy",
            ),
        )

        for (case in cases) {
            val actual = mergeFollowListDraft(case.authoritative, case.retained)
            assertEquals(case.name, case.expectedTags, actual.tags)
            assertEquals(case.name, case.expectedContent, actual.content)
        }
    }

    @Test
    fun `absent retained event exactly matches legacy bare-tag output`() {
        val draft = mergeFollowListDraft(
            authoritativeFollows = setOf("charlie", "alice", "bob"),
            retained = null,
        )

        assertEquals(
            listOf(
                listOf("p", "alice"),
                listOf("p", "bob"),
                listOf("p", "charlie"),
            ),
            draft.tags,
        )
        assertEquals("", draft.content)
    }

    @Test
    fun `unfollow drops its decorated p-tag but preserves unrelated rows`() {
        val draft = mergeFollowListDraft(
            authoritativeFollows = setOf("kept"),
            retained = RetainedFollowList(
                tags = listOf(
                    listOf("p", "removed", "wss://hint.example", "Petname"),
                    listOf("client", "opaque"),
                    listOf("p", "kept"),
                ),
                content = "legacy-content",
            ),
        )

        assertFalse(draft.tags.any { it.getOrNull(1) == "removed" })
        assertEquals(listOf(listOf("client", "opaque"), listOf("p", "kept")), draft.tags)
        assertEquals("legacy-content", draft.content)
    }
}
