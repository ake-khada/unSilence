package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.NostrRelaySetEntity
import com.unsilence.app.data.db.entity.NostrRelaySetMemberEntity
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

@Dao
abstract class NostrRelaySetDao {

    /**
     * Locally deleted relay set d-tags. Bootstrap re-fetches kind 30002 from relays
     * and would re-insert deleted sets — this guard prevents that.
     * Keyed by "ownerPubkey:dTag" for multi-account safety.
     * Cleared on logout (process restart).
     */
    val deletedDTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Query("SELECT * FROM nostr_relay_sets WHERE owner_pubkey = :ownerPubkey ORDER BY title ASC")
    abstract fun getAllSets(ownerPubkey: String): Flow<List<NostrRelaySetEntity>>

    @Query("SELECT * FROM nostr_relay_set_members WHERE set_d_tag = :dTag AND owner_pubkey = :ownerPubkey ORDER BY relay_url ASC")
    abstract fun getSetMembers(dTag: String, ownerPubkey: String): Flow<List<NostrRelaySetMemberEntity>>

    @Query("SELECT * FROM nostr_relay_set_members WHERE set_d_tag = :dTag AND owner_pubkey = :ownerPubkey ORDER BY relay_url ASC")
    abstract suspend fun getSetMembersSnapshot(dTag: String, ownerPubkey: String): List<NostrRelaySetMemberEntity>

    @Query("SELECT MAX(event_created_at) FROM nostr_relay_sets WHERE d_tag = :dTag AND owner_pubkey = :ownerPubkey")
    abstract suspend fun maxCreatedAt(dTag: String, ownerPubkey: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSet(entity: NostrRelaySetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMembers(members: List<NostrRelaySetMemberEntity>)

    @Query("DELETE FROM nostr_relay_set_members WHERE set_d_tag = :dTag AND owner_pubkey = :ownerPubkey")
    abstract suspend fun deleteMembersByDTag(dTag: String, ownerPubkey: String)

    @Query("DELETE FROM nostr_relay_set_members WHERE set_d_tag = :dTag AND owner_pubkey = :ownerPubkey AND relay_url = :relayUrl")
    abstract suspend fun deleteMember(dTag: String, ownerPubkey: String, relayUrl: String)

    @Query("DELETE FROM nostr_relay_sets WHERE d_tag = :dTag AND owner_pubkey = :ownerPubkey")
    abstract suspend fun deleteSetInternal(dTag: String, ownerPubkey: String)

    /** Delete a relay set and mark it so bootstrap won't re-insert from relay data. */
    open suspend fun deleteSet(dTag: String, ownerPubkey: String) {
        deletedDTags.add("$ownerPubkey:$dTag")
        deleteSetInternal(dTag, ownerPubkey)
    }

    @Query("DELETE FROM nostr_relay_sets")
    abstract suspend fun clearAllSets()

    @Query("DELETE FROM nostr_relay_set_members")
    abstract suspend fun clearAllMembers()

    /** Claim orphaned relay sets (from migration with empty owner_pubkey) for the current user. */
    @Query("UPDATE nostr_relay_sets SET owner_pubkey = :ownerPubkey WHERE owner_pubkey = ''")
    abstract suspend fun claimOrphanedSets(ownerPubkey: String)

    @Query("UPDATE nostr_relay_set_members SET owner_pubkey = :ownerPubkey WHERE owner_pubkey = ''")
    abstract suspend fun claimOrphanedMembers(ownerPubkey: String)

    @Transaction
    open suspend fun claimOrphaned(ownerPubkey: String) {
        claimOrphanedSets(ownerPubkey)
        claimOrphanedMembers(ownerPubkey)
    }

    /**
     * Replace a relay set, but ONLY if the incoming event is newer
     * (parameterized replaceable event semantics — keyed on d_tag + owner).
     */
    @Transaction
    open suspend fun replaceSet(
        set: NostrRelaySetEntity,
        members: List<NostrRelaySetMemberEntity>,
        eventCreatedAt: Long,
    ) {
        // Skip sets that were locally deleted this session
        val key = "${set.ownerPubkey}:${set.dTag}"
        if (key in deletedDTags) return

        val existing = maxCreatedAt(set.dTag, set.ownerPubkey) ?: 0L
        if (eventCreatedAt <= existing) return
        deleteMembersByDTag(set.dTag, set.ownerPubkey)
        insertSet(set.copy(eventCreatedAt = eventCreatedAt))
        if (members.isNotEmpty()) insertMembers(members)
    }
}
