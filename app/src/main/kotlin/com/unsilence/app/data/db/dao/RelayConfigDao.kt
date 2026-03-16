package com.unsilence.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unsilence.app.data.db.entity.RelayConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RelayConfigDao {

    // ── Queries ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM relay_configs WHERE kind = :kind ORDER BY relay_url ASC")
    abstract fun getByKind(kind: Int): Flow<List<RelayConfigEntity>>

    /** Kind 10002 (NIP-65): read/write relays. */
    @Query("SELECT * FROM relay_configs WHERE kind = 10002 ORDER BY relay_url ASC")
    abstract fun getReadWriteRelays(): Flow<List<RelayConfigEntity>>

    /** Kind 10002 snapshot (non-reactive, for publishing). */
    @Query("SELECT * FROM relay_configs WHERE kind = 10002 ORDER BY relay_url ASC")
    abstract suspend fun getAllReadWriteRelays(): List<RelayConfigEntity>

    /** Kind 10002 write relay URLs only. */
    @Query("SELECT relay_url FROM relay_configs WHERE kind = 10002 AND (marker IS NULL OR marker = 'write')")
    abstract suspend fun writeRelayUrls(): List<String>

    /** Kind 10006 (NIP-51): blocked relays. */
    @Query("SELECT * FROM relay_configs WHERE kind = 10006 ORDER BY relay_url ASC")
    abstract fun getBlockedRelays(): Flow<List<RelayConfigEntity>>

    /** Kind 10006 snapshot for connection filtering. */
    @Query("SELECT relay_url FROM relay_configs WHERE kind = 10006")
    abstract suspend fun blockedRelayUrls(): List<String>

    /** Kind 10007 (NIP-51): search relays. */
    @Query("SELECT * FROM relay_configs WHERE kind = 10007 ORDER BY relay_url ASC")
    abstract fun getSearchRelays(): Flow<List<RelayConfigEntity>>

    /** Kind 10007 snapshot. */
    @Query("SELECT relay_url FROM relay_configs WHERE kind = 10007")
    abstract suspend fun searchRelayUrls(): List<String>

    /** Kind 10012 (NIP-51): favorite/browsable relays (relay URLs + set refs). */
    @Query("SELECT * FROM relay_configs WHERE kind = 10012 ORDER BY relay_url ASC")
    abstract fun getFavoriteRelays(): Flow<List<RelayConfigEntity>>

    /** Kind 10012 snapshot (non-reactive, for publishing). */
    @Query("SELECT * FROM relay_configs WHERE kind = 10012 ORDER BY relay_url ASC")
    abstract suspend fun getAllFavoriteRelays(): List<RelayConfigEntity>

    /** Max event_created_at for a given kind (replaceable event semantics). */
    @Query("SELECT MAX(event_created_at) FROM relay_configs WHERE kind = :kind")
    abstract suspend fun maxCreatedAt(kind: Int): Long?

    // ── Mutations ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(entities: List<RelayConfigEntity>)

    @Query("DELETE FROM relay_configs WHERE kind = :kind")
    abstract suspend fun deleteByKind(kind: Int)

    @Query("DELETE FROM relay_configs WHERE kind = :kind AND relay_url = :relayUrl")
    abstract suspend fun deleteRelay(kind: Int, relayUrl: String)

    @Query("DELETE FROM relay_configs WHERE kind = :kind AND set_ref = :setRef")
    abstract suspend fun deleteBySetRef(kind: Int, setRef: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(entity: RelayConfigEntity)

    @Query("DELETE FROM relay_configs")
    abstract suspend fun clearAll()

    /**
     * Replace all configs for a given kind, but ONLY if the incoming event
     * is newer than what's already stored (replaceable event semantics).
     */
    @Transaction
    open suspend fun replaceForKind(
        kind: Int,
        ownerPubkey: String,
        eventCreatedAt: Long,
        entities: List<RelayConfigEntity>,
    ) {
        val existing = maxCreatedAt(kind) ?: 0L
        if (eventCreatedAt <= existing) return
        deleteByKind(kind)
        insertAll(entities.map { it.copy(ownerPubkey = ownerPubkey, eventCreatedAt = eventCreatedAt) })
    }
}
