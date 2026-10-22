package com.hardwareshop.pos.data.sync

object SyncTransferSchema {
    val completeProjectionSql = """DELETE FROM sync_dirty WHERE EXISTS(
        SELECT 1 FROM sync_transfer_projections p WHERE p.operationId=?
          AND p.syncId=sync_dirty.syncId AND p.revision=sync_dirty.revision)
        AND NOT EXISTS(SELECT 1 FROM sync_transfer_projections p
          WHERE p.syncId=sync_dirty.syncId AND p.revision=sync_dirty.revision AND p.acknowledged=0)""".trimIndent()

    fun statements(): List<String> = listOf(
        """CREATE TABLE IF NOT EXISTS sync_scan_progress (
            stream TEXT NOT NULL PRIMARY KEY,sequence INTEGER NOT NULL)""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_transfer_outbox (
            operationId TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, recordId TEXT NOT NULL,
            localRevision INTEGER NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL,
            attempts INTEGER NOT NULL, lastError TEXT, UNIQUE(scope,recordId))""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_sync_transfer_outbox_state ON sync_transfer_outbox(state)",
        """CREATE TABLE IF NOT EXISTS sync_transfer_versions (
            scope TEXT NOT NULL, recordId TEXT NOT NULL, version INTEGER NOT NULL,
            PRIMARY KEY(scope,recordId))""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_transfer_inbox (
            scope TEXT NOT NULL, operationId TEXT NOT NULL, recordId TEXT NOT NULL,
            version INTEGER NOT NULL, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL,
            body TEXT NOT NULL, state TEXT NOT NULL, lastError TEXT,
            PRIMARY KEY(scope,operationId))""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_sync_transfer_inbox_state ON sync_transfer_inbox(state)",
        """CREATE TABLE IF NOT EXISTS sync_transfer_cursors (
            scope TEXT NOT NULL PRIMARY KEY, seconds INTEGER NOT NULL, nanos INTEGER NOT NULL,
            operationId TEXT NOT NULL)""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_transfer_conflicts (
            operationId TEXT NOT NULL PRIMARY KEY, remoteBody TEXT NOT NULL,
            reason TEXT NOT NULL)""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_transfer_projections (
            operationId TEXT NOT NULL PRIMARY KEY, syncId TEXT NOT NULL,
            revision INTEGER NOT NULL, acknowledged INTEGER NOT NULL)""".trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_sync_transfer_projections_record ON sync_transfer_projections(syncId,revision)",
        """CREATE TABLE IF NOT EXISTS sync_record_receipts (
            scope TEXT NOT NULL,recordId TEXT NOT NULL,version INTEGER NOT NULL,
            operationId TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(scope,recordId))""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_financial_bundles (
            direction TEXT NOT NULL,bundleId TEXT NOT NULL,rootId TEXT NOT NULL,scope TEXT NOT NULL,body TEXT NOT NULL,
            state TEXT NOT NULL,lastError TEXT,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,
            PRIMARY KEY(direction,bundleId))""".trimIndent(),
        """CREATE TABLE IF NOT EXISTS sync_bundle_cursors (
            scope TEXT NOT NULL PRIMARY KEY,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,bundleId TEXT NOT NULL)""".trimIndent()
    )
}
