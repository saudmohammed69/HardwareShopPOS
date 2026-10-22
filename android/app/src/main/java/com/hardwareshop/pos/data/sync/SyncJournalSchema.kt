package com.hardwareshop.pos.data.sync

/** SQL shared by fresh databases, migrations and SQLite regression tests.
 * Journal writes run in the SAME SQLite transaction as the original Room write.
 * No cloud/network code is called from a trigger.
 */
object SyncJournalSchema {
    data class Table(val name: String, val primaryKey: String = "id")

    val tables = listOf(
        Table("categories"), Table("product_types"), Table("products"),
        Table("customers"), Table("workers"), Table("bills"), Table("bill_items"),
        Table("payments"), Table("credit_transactions"), Table("stock_adjustments"),
        Table("suppliers"), Table("purchase_bills"), Table("purchase_bill_items"),
        Table("supplier_payments"), Table("sale_returns"), Table("sale_return_items"),
        Table("day_closings"), Table("audit_logs"), Table("settings", "key")
    )

    // Explicit allowlist: never copy Bluetooth, local sequences, request keys,
    // credentials, catalogue installer versions, carts or print-attempt markers.
    val storeSettingKeys = listOf("shop_name", "shop_phone", "shop_address", "shop_gst_number")

    // Local stock, photos, passwords and print markers have independent lifecycles.
    // Ignore timestamp-only writes too; timestamps change with those local actions.
    private val updateFields = mapOf(
        "products" to listOf("categoryId", "typeId", "name", "size", "unit", "sellingPricePaisa",
            "purchasePricePaisa", "gstRateBp", "searchAliases", "minimumStock", "isActive", "createdAt"),
        "workers" to listOf("name", "username", "phone", "maxDiscountPaisa",
            "requireOwnerApprovalForDiscount", "isActive", "createdAt"),
        "bills" to listOf("billNumber", "customerId", "customerName", "customerPhone", "workerId", "workerName",
            "subtotalPaisa", "discountPaisa", "discountNote", "gstEnabled", "gstAmountPaisa", "totalPaisa",
            "paymentMethod", "paidPaisa", "creditPaisa", "status", "cancellationReason", "shopGstNumber",
            "customerGstNumber", "notes", "createdAt")
    )

    fun createStatements(includeBoxCount: Boolean = false, includePipe: Boolean = false): List<String> = buildList {
        add("""CREATE TABLE IF NOT EXISTS sync_journal_control (
            id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1),
            storeId TEXT NOT NULL, suppressTracking INTEGER NOT NULL,
            deviceId TEXT NOT NULL, bootstrapComplete INTEGER NOT NULL
        )""".trimIndent())
        add("""INSERT OR IGNORE INTO sync_journal_control
            (id,storeId,suppressTracking,deviceId,bootstrapComplete)
            VALUES(1,'',0,lower(hex(randomblob(16))),0)""".trimIndent())
        add("""CREATE TABLE IF NOT EXISTS sync_identity (
            tableName TEXT NOT NULL, localKey TEXT NOT NULL,
            syncId TEXT NOT NULL UNIQUE, revision INTEGER NOT NULL,
            deleted INTEGER NOT NULL, PRIMARY KEY(tableName,localKey)
        )""".trimIndent())
        add("""CREATE TABLE IF NOT EXISTS sync_dirty (
            syncId TEXT NOT NULL PRIMARY KEY, revision INTEGER NOT NULL,
            deleted INTEGER NOT NULL, tableName TEXT NOT NULL,
            localKey TEXT NOT NULL, lastError TEXT
        )""".trimIndent())
        add("""CREATE TABLE IF NOT EXISTS sync_stock_movements (
            movementId TEXT NOT NULL PRIMARY KEY, productSyncId TEXT NOT NULL,
            delta REAL NOT NULL, kind TEXT NOT NULL, acknowledged INTEGER NOT NULL
        )""".trimIndent())
        add("CREATE INDEX IF NOT EXISTS index_sync_stock_pending ON sync_stock_movements(acknowledged)")
        add("""CREATE TABLE IF NOT EXISTS sync_stock_revisions (
            localKey TEXT NOT NULL PRIMARY KEY,revision INTEGER NOT NULL)""".trimIndent())
        add("""INSERT OR IGNORE INTO sync_stock_revisions(localKey,revision)
            SELECT CAST(id AS TEXT),0 FROM products""".trimIndent())
        add("DROP TRIGGER IF EXISTS sync_stock_revision_insert")
        add("""CREATE TRIGGER sync_stock_revision_insert AFTER INSERT ON products BEGIN
            UPDATE sync_stock_revisions SET revision=revision+1 WHERE localKey=CAST(NEW.id AS TEXT);
            INSERT INTO sync_stock_revisions(localKey,revision)
            SELECT CAST(NEW.id AS TEXT),1 WHERE NOT EXISTS
                (SELECT 1 FROM sync_stock_revisions WHERE localKey=CAST(NEW.id AS TEXT));
            END""".trimIndent())
        // This counter tracks remote changes too. Stock could change and return to its
        // previous quantity while an owner is reviewing a first-link stock snapshot.
        add("DROP TRIGGER IF EXISTS sync_stock_revision_update")
        add("""CREATE TRIGGER sync_stock_revision_update AFTER UPDATE OF currentStock ON products
            WHEN NEW.currentStock IS NOT OLD.currentStock BEGIN
            UPDATE sync_stock_revisions SET revision=revision+1 WHERE localKey=CAST(NEW.id AS TEXT);
            INSERT INTO sync_stock_revisions(localKey,revision)
            SELECT CAST(NEW.id AS TEXT),1 WHERE NOT EXISTS
                (SELECT 1 FROM sync_stock_revisions WHERE localKey=CAST(NEW.id AS TEXT));
            END""".trimIndent())
        add("""CREATE TABLE IF NOT EXISTS sync_applied_movements (
            movementId TEXT NOT NULL PRIMARY KEY, productSyncId TEXT NOT NULL,
            delta REAL NOT NULL
        )""".trimIndent())
        add("""CREATE TABLE IF NOT EXISTS sync_foreign_identity (
            syncId TEXT NOT NULL PRIMARY KEY, tableName TEXT NOT NULL,
            localKey TEXT NOT NULL
        )""".trimIndent())

        for (table in tables) {
            val name = table.name
            val pk = table.primaryKey
            val enabled = "(SELECT suppressTracking FROM sync_journal_control WHERE id=1)=0"
            fun filter(alias: String): String = if (name == "settings")
                " AND $alias.`key` IN (${storeSettingKeys.joinToString(",") { "'$it'" }})" else ""
            // Room's UPDATE OR ABORT overrides a trigger's OR IGNORE/REPLACE.
            // Guard inserts instead; this also works on Android 8's pre-UPSERT SQLite.
            fun identity(alias: String): String = """INSERT INTO sync_identity
                (tableName,localKey,syncId,revision,deleted)
                SELECT '$name',CAST($alias.`$pk` AS TEXT),lower(hex(randomblob(16))),0,0
                WHERE NOT EXISTS(SELECT 1 FROM sync_identity
                    WHERE tableName='$name' AND localKey=CAST($alias.`$pk` AS TEXT));""".trimIndent()
            fun dirty(alias: String, deleted: Int): String = """
                UPDATE sync_identity SET revision=revision+1, deleted=$deleted
                  WHERE tableName='$name' AND localKey=CAST($alias.`$pk` AS TEXT);
                UPDATE sync_dirty SET revision=(SELECT revision FROM sync_identity WHERE syncId=sync_dirty.syncId),
                    deleted=$deleted,lastError=NULL
                    WHERE syncId IN (SELECT syncId FROM sync_identity
                        WHERE tableName='$name' AND localKey=CAST($alias.`$pk` AS TEXT));
                INSERT INTO sync_dirty(syncId,revision,deleted,tableName,localKey,lastError)
                  SELECT syncId,revision,deleted,tableName,localKey,NULL FROM sync_identity
                  WHERE tableName='$name' AND localKey=CAST($alias.`$pk` AS TEXT)
                    AND NOT EXISTS(SELECT 1 FROM sync_dirty d WHERE d.syncId=sync_identity.syncId);
            """.trimIndent()
            for ((verb, alias, deleted) in listOf(Triple("INSERT", "NEW", 0), Triple("UPDATE", "NEW", 0), Triple("DELETE", "OLD", 1))) {
                // CREATE TRIGGER IF NOT EXISTS does not replace a trigger created by an
                // earlier APK. Drop each generated trigger first so upgraded phones cannot
                // retain the old plain INSERT into sync_identity (SQLite error 1555).
                add("DROP TRIGGER IF EXISTS sync_track_${name}_${verb.lowercase()}")
                val tracked = updateFields[name]?.let { if (name == "products") it + (if (includeBoxCount) listOf("piecesPerBox") else emptyList()) + (if (includePipe) listOf("isPipe", "pipeLengthPricePaisa", "pipeFootPricePaisa", "pipeFeetPerLength") else emptyList()) else it }
                val changed = if (verb == "UPDATE") tracked?.joinToString(" OR ") {
                    "NEW.`$it` IS NOT OLD.`$it`"
                } else null
                val revive = if (verb == "INSERT") """UPDATE sync_identity
                    SET syncId=lower(hex(randomblob(16))),revision=0,deleted=0
                    WHERE tableName='$name' AND localKey=CAST(NEW.`$pk` AS TEXT) AND deleted=1;""".trimIndent() else ""
                // The opening movement must live in the SAME trigger as the identity write:
                // SQLite does not guarantee firing order between same-event triggers, and a
                // separate trigger can read the identity before this one writes or revives it.
                val opening = if (name == "products" && verb == "INSERT") """
                    INSERT INTO sync_stock_movements(movementId,productSyncId,delta,kind,acknowledged)
                      SELECT lower(hex(randomblob(16))),syncId,NEW.currentStock,'OPENING',0
                      FROM sync_identity WHERE tableName='products' AND localKey=CAST(NEW.`$pk` AS TEXT);""".trimIndent() else ""
                add("""CREATE TRIGGER IF NOT EXISTS sync_track_${name}_${verb.lowercase()}
                    AFTER $verb ON `$name` WHEN $enabled${filter(alias)}${changed?.let { " AND ($it)" } ?: ""} BEGIN
                    ${identity(alias)}
                    $revive
                    ${dirty(alias, deleted)}
                    $opening
                    END""".trimIndent())
            }
        }
        // The opening movement is written by sync_track_products_insert above (same trigger
        // as the identity write — immune to trigger firing order). Drop the legacy separate
        // trigger: phones that ran an earlier build still hold it, and it could record the
        // opening against a stale identity when SQLite fired it before the track trigger.
        add("DROP TRIGGER IF EXISTS sync_stock_insert")
        add("DROP TRIGGER IF EXISTS sync_stock_update")
        add("""CREATE TRIGGER sync_stock_update AFTER UPDATE OF currentStock ON products
            WHEN NEW.currentStock != OLD.currentStock
              AND (SELECT suppressTracking FROM sync_journal_control WHERE id=1)=0 BEGIN
            INSERT INTO sync_stock_movements(movementId,productSyncId,delta,kind,acknowledged)
              SELECT lower(hex(randomblob(16))),syncId,NEW.currentStock-OLD.currentStock,'DELTA',0
              FROM sync_identity WHERE tableName='products' AND localKey=CAST(NEW.id AS TEXT);
            END""".trimIndent())
    }

    /** One-time snapshot of pre-existing rows; never recreates identities on reopen. */
    fun bootstrapStatements(): List<String> = buildList {
        for (table in tables) {
            val where = if (table.name == "settings") " AND `key` IN (${storeSettingKeys.joinToString(",") { "'$it'" }})" else ""
            add("""INSERT OR IGNORE INTO sync_identity(tableName,localKey,syncId,revision,deleted)
                SELECT '${table.name}',CAST(`${table.primaryKey}` AS TEXT),lower(hex(randomblob(16))),1,0
                FROM `${table.name}` WHERE (SELECT bootstrapComplete FROM sync_journal_control WHERE id=1)=0$where""".trimIndent())
        }
        add("""INSERT OR IGNORE INTO sync_dirty(syncId,revision,deleted,tableName,localKey,lastError)
            SELECT syncId,revision,deleted,tableName,localKey,NULL FROM sync_identity
            WHERE (SELECT bootstrapComplete FROM sync_journal_control WHERE id=1)=0""".trimIndent())
        add("""INSERT OR IGNORE INTO sync_stock_movements(movementId,productSyncId,delta,kind,acknowledged)
            SELECT 'opening_' || i.syncId,i.syncId,p.currentStock,'OPENING',0
            FROM products p JOIN sync_identity i ON i.tableName='products' AND i.localKey=CAST(p.id AS TEXT)
            WHERE (SELECT bootstrapComplete FROM sync_journal_control WHERE id=1)=0
              AND NOT EXISTS(SELECT 1 FROM sync_stock_movements m WHERE m.productSyncId=i.syncId AND m.kind='OPENING')""".trimIndent())
        add("UPDATE sync_journal_control SET bootstrapComplete=1 WHERE id=1")
    }

    fun statements(includeBoxCount: Boolean = false, includePipe: Boolean = false): List<String> = createStatements(includeBoxCount, includePipe) + bootstrapStatements()
}
