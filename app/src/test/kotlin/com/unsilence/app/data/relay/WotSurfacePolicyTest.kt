package com.unsilence.app.data.relay

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotAssertionEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.ContentWarnings
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.model.MediaManifest
import com.unsilence.app.data.model.Segment
import com.unsilence.app.data.model.ThreadRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WotSurfacePolicyTest {

    @Test
    fun `feed exception renders absent but not pending or healthy scored`() {
        assertNull(wotFeedException(WotLookup.Pending))
        assertEquals(FeedWotSignal.Absent, wotFeedException(WotLookup.Absent))
        assertNull(wotFeedException(scored(rank = 87)))
    }

    @Test
    fun `feed numbers mode renders rank and absent dash but not pending`() {
        assertNull(wotFeedSignal(WotLookup.Pending, FeedWotDisplayMode.NUMBERS))
        assertEquals(FeedWotSignal.Absent, wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.NUMBERS))
        assertEquals(FeedWotSignal.Rank(rank = 87), wotFeedSignal(scored(rank = 87), FeedWotDisplayMode.NUMBERS))
    }

    @Test
    fun `feed exceptions mode preserves exception-only logic`() {
        assertNull(wotFeedSignal(scored(rank = 87), FeedWotDisplayMode.EXCEPTIONS_ONLY))
        assertEquals(FeedWotSignal.Absent, wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.EXCEPTIONS_ONLY))
        assertEquals(
            FeedWotSignal.Distrusted(muters = 1, reporters = 0),
            wotFeedSignal(scored(rank = 42, muters = 1), FeedWotDisplayMode.EXCEPTIONS_ONLY),
        )
    }

    @Test
    fun `feed off mode renders no signal`() {
        assertNull(wotFeedSignal(WotLookup.Absent, FeedWotDisplayMode.OFF))
        assertNull(wotFeedSignal(scored(rank = 99), FeedWotDisplayMode.OFF))
    }

    @Test
    fun `feed numbers mode tints positive distrust through rank signal`() {
        assertEquals(
            FeedWotSignal.Rank(rank = 42, distrusted = true),
            wotFeedSignal(scored(rank = 42, muters = 1), FeedWotDisplayMode.NUMBERS),
        )
    }

    @Test
    fun `feed exception reserves red distrust for positive distrust evidence`() {
        assertEquals(
            FeedWotSignal.Distrusted(muters = 1, reporters = 0),
            wotFeedException(scored(rank = 42, muters = 1)),
        )
    }

    @Test
    fun `mention sort uses rank descending with nulls last`() {
        val a = user("a")
        val b = user("b")
        val c = user("c")
        val lookups = mapOf(
            a.pubkey to scored(rank = 40),
            b.pubkey to WotLookup.Pending,
            c.pubkey to scored(rank = 99),
        )

        assertEquals(
            listOf(c.pubkey, a.pubkey, b.pubkey),
            sortMentionsByWotRank(listOf(a, b, c)) { lookups[it] }.map { it.pubkey },
        )
    }

    @Test
    fun `search ordering uses rank then verified followers`() {
        val lowPopular = user("a")
        val highSparse = user("b")
        val highPopular = user("c")
        val absent = user("d")
        val lookups = mapOf(
            lowPopular.pubkey to scored(rank = 50, followers = 10_000),
            highSparse.pubkey to scored(rank = 90, followers = 4),
            highPopular.pubkey to scored(rank = 90, followers = 700),
            absent.pubkey to WotLookup.Absent,
        )

        assertEquals(
            listOf(highPopular.pubkey, highSparse.pubkey, lowPopular.pubkey, absent.pubkey),
            sortPeopleForSearch(listOf(absent, lowPopular, highSparse, highPopular), query = "user") { lookups[it] }
                .map { it.pubkey },
        )
    }

    @Test
    fun `search ranks trusted same-tier candidate before display truncation`() {
        val lowTrust = (0 until 60).map { index ->
            user("candidate-$index", displayName = "Odell ${index.toString().padStart(2, '0')}")
        }
        val trusted = user("trusted", displayName = "Z Odell")
        val lookups = buildMap<String, WotLookup> {
            lowTrust.forEach { put(it.pubkey, scored(rank = 1, followers = 0)) }
            put(trusted.pubkey, scored(rank = 100, followers = 500))
        }

        val result = sortPeopleForSearch(
            users = lowTrust + trusted,
            query = "odell",
            limit = 50,
        ) { lookups[it] }

        assertEquals(50, result.size)
        assertEquals(trusted.pubkey, result.first().pubkey)
        assertTrue(trusted.pubkey in result.map { it.pubkey })
    }

    @Test
    fun `candidate cap counts matches rather than profiles scanned`() {
        val nonMatches = (0 until 100).map { index ->
            user("nonmatch-$index", displayName = "Unrelated $index")
        }
        val match = user("match", displayName = "Odell")

        assertEquals(
            listOf(match.pubkey),
            boundedIdentitySearchCandidates(
                users = (nonMatches + match).asSequence(),
                query = "odell",
                limit = 1,
            ).map { it.pubkey },
        )
    }

    @Test
    fun `exact identity match beats higher-rank partial match`() {
        val exact = user("exact", displayName = "Odell")
        val partial = user("partial", displayName = "Odell Updates")
        val lookups = mapOf(
            exact.pubkey to scored(rank = 0, followers = 0),
            partial.pubkey to scored(rank = 100, followers = 10_000),
        )

        assertEquals(
            listOf(exact.pubkey, partial.pubkey),
            sortPeopleForSearch(listOf(partial, exact), query = "odell") { lookups[it] }
                .map { it.pubkey },
        )
    }

    @Test
    fun `zero-WoT candidates remain visible when no scored match exists`() {
        val pending = user("pending")
        val absent = user("absent")
        val unknown = user("unknown")
        val lookups = mapOf(
            pending.pubkey to WotLookup.Pending,
            absent.pubkey to WotLookup.Absent,
        )

        assertEquals(
            setOf(pending.pubkey, absent.pubkey, unknown.pubkey),
            sortPeopleForSearch(listOf(pending, absent, unknown), query = "user") { lookups[it] }
                .map { it.pubkey }
                .toSet(),
        )
    }

    @Test
    fun `search ordering groups prefix and word boundary identity matches before substrings`() {
        val prefix = user("a", displayName = "Calle")
        val wordBoundary = user("b", displayName = "A Calle Person")
        val substring = user("c", displayName = "Unretrocalled")
        val lookups = mapOf(
            prefix.pubkey to scored(rank = 50),
            wordBoundary.pubkey to scored(rank = 80),
            substring.pubkey to scored(rank = 100),
        )

        assertEquals(
            listOf(prefix.pubkey, wordBoundary.pubkey, substring.pubkey),
            sortPeopleForSearch(listOf(substring, prefix, wordBoundary), query = "calle") { lookups[it] }
                .map { it.pubkey },
        )
    }

    @Test
    fun `identity search uses nip05 local part`() {
        assertEquals(3, identitySearchMatchTier(user("a", nip05 = "calle@getalby.com"), "calle"))
        assertEquals(-1, identitySearchMatchTier(user("b", about = "called by friends"), "called"))
    }

    @Test
    fun `search impersonation policy uses weighted signal threshold matrix`() {
        data class Case(
            val reporters: Long? = null,
            val muters: Long? = null,
            val followers: Long? = null,
            val followed: Boolean = false,
            val followsResolved: Boolean = true,
            val expected: SearchImpersonationDisposition,
        )

        val cases = listOf(
            Case(reporters = 2, muters = 2, followers = 0, expected = SearchImpersonationDisposition.KEEP),
            Case(reporters = 3, followers = 0, expected = SearchImpersonationDisposition.DEMOTE),
            Case(muters = 3, followers = 0, expected = SearchImpersonationDisposition.DEMOTE),
            Case(reporters = 10, followers = 2, expected = SearchImpersonationDisposition.DEMOTE),
            Case(reporters = 10, followers = null, expected = SearchImpersonationDisposition.DEMOTE),
            Case(reporters = 10, followers = 1, expected = SearchImpersonationDisposition.DROP),
            Case(
                reporters = 10,
                followers = 0,
                followed = true,
                expected = SearchImpersonationDisposition.KEEP,
            ),
            Case(
                reporters = 10,
                followers = 0,
                followsResolved = false,
                expected = SearchImpersonationDisposition.DEMOTE,
            ),
        )

        cases.forEachIndexed { index, case ->
            assertEquals(
                "case $index",
                case.expected,
                searchImpersonationDisposition(
                    lookup = scored(
                        rank = 5,
                        followers = case.followers,
                        muters = case.muters,
                        reporters = case.reporters,
                    ),
                    isFollowed = case.followed,
                    followsResolved = case.followsResolved,
                ),
            )
        }
    }

    @Test
    fun `reported account with followers is demoted but present`() {
        val clean = user("clean")
        val reported = user("reported")
        val lookups = mapOf(
            clean.pubkey to scored(rank = 1, followers = 1),
            reported.pubkey to scored(rank = 100, followers = 500, reporters = 20),
        )

        assertEquals(
            listOf(clean.pubkey, reported.pubkey),
            sortPeopleForSearch(listOf(reported, clean), query = "user") { lookups[it] }
                .map { it.pubkey },
        )
    }

    @Test
    fun `heavily reported account with near-zero followers is dropped unless followed`() {
        val candidate = user("c")
        val lookup = scored(rank = 100, followers = 0, reporters = 20)

        assertEquals(
            "Unresolved follows must conservatively retain the candidate",
            listOf(candidate.pubkey),
            sortPeopleForSearch(listOf(candidate), query = "user") { lookup }.map { it.pubkey },
        )
        assertTrue(
            sortPeopleForSearch(
                users = listOf(candidate),
                query = "user",
                followedPubkeys = emptySet(),
            ) { lookup }.isEmpty(),
        )
        assertEquals(
            listOf(candidate.pubkey),
            sortPeopleForSearch(
                users = listOf(candidate),
                query = "user",
                followedPubkeys = setOf(candidate.pubkey),
            ) { lookup }.map { it.pubkey },
        )
    }

    @Test
    fun `impersonation normalization strips non alphanumerics and lowercases`() {
        assertEquals("calle42", normalizeImpersonationName("C Alle-42!"))
    }

    @Test
    fun `impersonation risk detects distance one low trust lookalike`() {
        val protected = ProtectedProfile(
            pubkey = hex("e"),
            normalizedName = "calle",
            displayLabel = "Calle",
        )
        val candidate = user("d", displayName = "Calie")

        assertNotNull(
            detectImpersonationRisk(
                candidate = candidate,
                lookup = WotLookup.Absent,
                protectedProfiles = listOf(protected),
            ),
        )
    }

    @Test
    fun `impersonation risk does not claim unicode homoglyph detection in v1`() {
        val protected = ProtectedProfile(
            pubkey = hex("e"),
            normalizedName = "calle",
            displayLabel = "Calle",
        )
        val candidate = user("d", displayName = "\u0441alle")

        assertEquals("", normalizeImpersonationName("\u0441alle"))
        assertNull(
            detectImpersonationRisk(
                candidate = candidate,
                lookup = WotLookup.Absent,
                protectedProfiles = listOf(protected),
            ),
        )
    }

    @Test
    fun `impersonation risk never flags followed or self candidates`() {
        val followed = user("f", displayName = "Calle")
        val protected = protectedProfileFor(followed)!!

        assertNull(
            detectImpersonationRisk(
                candidate = followed,
                lookup = WotLookup.Absent,
                protectedProfiles = listOf(protected),
            ),
        )
    }

    @Test
    fun `impersonation risk never flags pending or rank ten plus candidates`() {
        val protected = ProtectedProfile(
            pubkey = hex("e"),
            normalizedName = "calle",
            displayLabel = "Calle",
        )
        val candidate = user("d", displayName = "Calle")

        assertNull(
            detectImpersonationRisk(
                candidate = candidate,
                lookup = WotLookup.Pending,
                protectedProfiles = listOf(protected),
            ),
        )
        assertNull(
            detectImpersonationRisk(
                candidate = candidate,
                lookup = scored(rank = 10),
                protectedProfiles = listOf(protected),
            ),
        )
        assertNotNull(
            detectImpersonationRisk(
                candidate = candidate,
                lookup = scored(rank = 9),
                protectedProfiles = listOf(protected),
            ),
        )
    }

    @Test
    fun `protected profile threshold starts at rank forty`() {
        assertFalse(isProtectedWotLookup(scored(rank = 39)))
        assertTrue(isProtectedWotLookup(scored(rank = 40)))
    }

    @Test
    fun `verified follower ordering keeps nulls last`() {
        val a = user("a")
        val b = user("b")
        val c = user("c")
        val lookups = mapOf(
            a.pubkey to scored(rank = 80, followers = 1),
            b.pubkey to scored(rank = 60, followers = 20),
            c.pubkey to WotLookup.Pending,
        )

        assertEquals(
            listOf(b.pubkey, a.pubkey, c.pubkey),
            sortPeopleByWotFollowers(listOf(c, a, b)) { lookups[it] }.map { it.pubkey },
        )
    }

    @Test
    fun `all WoT sorts snapshot hostile lookup values before comparing`() {
        val users = (1..32).map { index ->
            UserEntity(
                pubkey = index.toString(16).padStart(64, '0'),
                displayName = "User $index",
            )
        }.reversed()
        val expected = users.sortedByDescending { it.pubkey.toInt(16) }.map { it.pubkey }
        val sorters = listOf<(List<UserEntity>, (String) -> WotLookup?) -> List<UserEntity>>(
            { input, lookup -> sortPeopleForSearch(input, "User", lookup = lookup) },
            { input, lookup -> sortPeopleByWotFollowers(input, lookup) },
            { input, lookup -> sortMentionsByWotRank(input, lookup) },
        )

        for (sorter in sorters) {
            val calls = mutableMapOf<String, Int>()
            val result = sorter(users) { pubkey ->
                val invocation = (calls[pubkey] ?: 0) + 1
                calls[pubkey] = invocation
                val base = pubkey.toInt(16)
                scored(
                    rank = base + invocation * 1_000,
                    followers = (base + invocation * 1_000).toLong(),
                )
            }

            assertEquals(expected, result.map { it.pubkey })
            assertTrue("Each pubkey must be looked up exactly once", calls.values.all { it == 1 })
        }
    }

    @Test
    fun `feed subjects include cached parent and quoted authors`() {
        val rowAuthor = hex("a")
        val parentAuthor = hex("b")
        val quoteHintAuthor = hex("c")
        val resolvedQuoteAuthor = hex("d")
        val addressAuthor = hex("e")
        val row = feedRow(
            id = "reply",
            pubkey = rowAuthor,
            replyToId = "parent",
        )
        val models = mapOf(
            "reply" to model(
                id = "reply",
                pubkey = rowAuthor,
                segments = listOf(
                    Segment.QuoteEvent(eventId = "quote", hints = emptyList(), author = quoteHintAuthor),
                    Segment.QuoteAddress(kind = 30023, author = addressAuthor, dTag = "article", hints = emptyList()),
                ),
            ),
            "parent" to model(id = "parent", pubkey = parentAuthor),
            "quote" to model(id = "quote", pubkey = resolvedQuoteAuthor),
        )

        assertEquals(
            setOf(rowAuthor, parentAuthor, quoteHintAuthor, resolvedQuoteAuthor, addressAuthor),
            wotSubjectsForFeedRows(listOf(row)) { models[it] },
        )
    }

    private fun user(
        seed: String,
        name: String? = null,
        displayName: String? = "User $seed",
        nip05: String? = null,
        about: String? = null,
    ): UserEntity =
        UserEntity(
            pubkey = hex(seed),
            name = name,
            displayName = displayName,
            nip05 = nip05,
            about = about,
        )

    private fun feedRow(
        id: String,
        pubkey: String,
        replyToId: String? = null,
    ): FeedRow =
        FeedRow(
            id = id,
            pubkey = pubkey,
            kind = 1,
            content = "",
            createdAt = 1,
            tags = "[]",
            relayUrl = "wss://relay.example",
            replyToId = replyToId,
            rootId = null,
            hasContentWarning = false,
            contentWarningReason = null,
            zapTotalSats = 0,
            authorName = null,
            authorDisplayName = null,
            authorPicture = null,
            authorNip05 = null,
            reactionCount = 0,
            replyCount = 0,
            repostCount = 0,
            zapCount = 0,
        )

    private fun model(
        id: String,
        pubkey: String,
        segments: List<Segment> = emptyList(),
    ): EventModel =
        EventModel(
            id = id,
            pubkey = pubkey,
            sourcePubkey = pubkey,
            kind = 1,
            effectiveKind = 1,
            displayContent = "",
            articleContent = null,
            createdAt = 1,
            sourceCreatedAt = 1,
            relayUrl = "wss://relay.example",
            engagementId = id,
            navigateId = id,
            segments = segments,
            media = MediaManifest(images = emptyList(), videos = emptyList(), ogCandidate = null, youtubes = emptyList()),
            thread = ThreadRefs(replyToId = null, rootId = null),
            repost = null,
            article = null,
            warnings = ContentWarnings(hasContentWarning = false, reason = null),
        )

    private fun hex(seed: String): String = seed.repeat(64).take(64)

    private fun scored(
        rank: Int,
        followers: Long? = null,
        muters: Long? = null,
        reporters: Long? = null,
    ): WotLookup.Scored {
        val subject = rank.toString(16).padStart(64, '0').takeLast(64)
        return WotLookup.Scored(
            WotAssertionEntity(
                subjectPubkey = subject,
                providerPubkey = "f".repeat(64),
                rank = rank,
                verifiedFollowers = followers,
                verifiedMuters = muters,
                verifiedReporters = reporters,
            ),
        )
    }
}
