package com.unsilence.app.ui.search

import com.unsilence.app.data.memory.FeedRow
import com.unsilence.app.data.memory.MuteList
import com.unsilence.app.data.memory.NostrEvent
import com.unsilence.app.data.memory.UserEntity
import com.unsilence.app.data.memory.WotLookup
import com.unsilence.app.data.model.EventModel
import com.unsilence.app.data.relay.ImpersonationRisk
import com.unsilence.app.data.relay.ProtectedProfile
import com.unsilence.app.data.relay.detectImpersonationRisk
import com.unsilence.app.data.relay.sortPeopleForSearch
import com.unsilence.app.data.relay.wotSubjectsForFeedRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal data class SearchResultsBundle(
    val localNotes: List<FeedRow>,
    val tagNotes: List<FeedRow>,
    val relayNotes: List<FeedRow>,
    val people: List<UserEntity>,
    val muteList: MuteList? = null,
    val hashtagCap: Int? = null,
    val followedPubkeys: Set<String>? = null,
)

internal data class SearchResultProjection(
    val notes: List<FeedRow>,
    val tags: List<FeedRow>,
    val people: List<UserEntity>,
    val impersonationRisks: Map<String, ImpersonationRisk>,
    val wotSubjects: Set<String>,
)

/**
 * One background projection, with only the latest pending input/output retained.
 * Cached models enrich trust subjects; a cache miss must never tokenize a whole
 * article here. Content/quote discovery belongs to the bounded card warm window
 * and the existing onWotSubjectsVisible callbacks, not every result invalidation.
 */
internal fun Flow<SearchResultsBundle>.projectSearchResults(
    query: String,
    explicitHashtag: Boolean,
    eventProvider: (String) -> NostrEvent?,
    cachedModelProvider: (String) -> EventModel?,
    wotLookup: (String) -> WotLookup,
    protectedProfiles: () -> List<ProtectedProfile>,
): Flow<SearchResultProjection> = conflate().map { results ->
    val localNotes = filterSearchNoteRows(results.localNotes, results.muteList, results.hashtagCap, eventProvider)
    val relayNotes = filterSearchNoteRows(results.relayNotes, results.muteList, results.hashtagCap, eventProvider)
    val notes = (localNotes + relayNotes).distinctBy { it.id }.sortedByDescending { it.createdAt }
    val localTags = filterSearchNoteRows(results.tagNotes, results.muteList, results.hashtagCap, eventProvider)
    val tags = (localTags + if (explicitHashtag) relayNotes else emptyList())
        .distinctBy { it.id }.sortedByDescending { it.createdAt }
    val people = sortPeopleForSearch(
        users = filterSearchPeople(results.people, results.muteList),
        query = query,
        followedPubkeys = results.followedPubkeys,
        limit = 50,
        lookup = wotLookup,
    )
    val protected = if (people.isEmpty()) emptyList() else protectedProfiles()
    val risks = people.mapNotNull { user ->
        detectImpersonationRisk(user, wotLookup(user.pubkey), protected)?.let { user.pubkey to it }
    }.toMap()
    val subjects = buildSet {
        addAll(people.map { it.pubkey })
        addAll(wotSubjectsForFeedRows(notes, parseMissingModels = false, modelProvider = cachedModelProvider))
        addAll(wotSubjectsForFeedRows(tags, parseMissingModels = false, modelProvider = cachedModelProvider))
    }
    SearchResultProjection(notes, tags, people, risks, subjects)
}.flowOn(Dispatchers.Default).buffer(Channel.CONFLATED)
