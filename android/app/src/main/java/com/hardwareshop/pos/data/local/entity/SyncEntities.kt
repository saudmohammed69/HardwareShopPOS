package com.hardwareshop.pos.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Durable local outbox. A row remains PENDING until the cloud acknowledges it. */
@Entity(
    tableName = "sync_outbox",
    primaryKeys = ["recordKey"],
    indices = [Index("state"), Index("updatedAt")]
)
data class SyncOutboxEntity(
    val recordKey: String,
    val entityType: String,
    val payloadJson: String,
    val updatedAt: Long,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "sync_checkpoint")
data class SyncCheckpointEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val storeId: String,
    val lastServerTimeMs: Long = 0L,
    val lastRunAt: Long = 0L,
    val lastError: String? = null
)
