package com.hardwareshop.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.hardwareshop.pos.data.local.entity.SyncCheckpointEntity
import com.hardwareshop.pos.data.local.entity.SyncOutboxEntity

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SyncOutboxEntity>)

    @Query("SELECT * FROM sync_outbox WHERE recordKey = :key")
    suspend fun get(key: String): SyncOutboxEntity?

    @Query("SELECT * FROM sync_outbox WHERE state != 'SYNCED' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET state = :state, attempts = attempts + 1, lastError = :error WHERE recordKey = :key")
    suspend fun mark(key: String, state: String, error: String? = null)

    @Query("SELECT * FROM sync_checkpoint WHERE id = 1")
    suspend fun checkpoint(): SyncCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCheckpoint(row: SyncCheckpointEntity)
}
