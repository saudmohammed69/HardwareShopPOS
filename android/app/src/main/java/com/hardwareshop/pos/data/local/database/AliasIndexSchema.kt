package com.hardwareshop.pos.data.local.database

/** Derived from the existing synced aliases field; local and received edits update it atomically. */
object AliasIndexSchema {
    val tableStatements=listOf(
        """CREATE TABLE IF NOT EXISTS product_aliases (productId INTEGER NOT NULL, alias TEXT NOT NULL,
            normalizedAlias TEXT NOT NULL, PRIMARY KEY(productId,alias),
            FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        "CREATE INDEX IF NOT EXISTS index_product_aliases_productId ON product_aliases(productId)",
        "CREATE INDEX IF NOT EXISTS index_product_aliases_normalizedAlias ON product_aliases(normalizedAlias)"
    )
    private fun split(source:String):String = """SELECT productId,trim(alias),lower(trim(alias)) FROM (
        WITH RECURSIVE chunks(productId,alias,rest) AS (
            SELECT id,'',replace(replace(CASE WHEN instr(searchAliases,'[[pos-variant:')>0
                THEN substr(searchAliases,1,instr(searchAliases,'[[pos-variant:')-1) ELSE searchAliases END,
                ';',char(10)),',',char(10))||char(10) FROM $source
            UNION ALL SELECT productId,substr(rest,1,instr(rest,char(10))-1),substr(rest,instr(rest,char(10))+1)
            FROM chunks WHERE rest<>''
        ) SELECT productId,alias FROM chunks
        ) WHERE trim(alias)<>''""".trimIndent()
    fun triggerStatements()=listOf(
        """CREATE TRIGGER IF NOT EXISTS product_alias_index_insert AFTER INSERT ON products BEGIN
            INSERT OR IGNORE INTO product_aliases(productId,alias,normalizedAlias)
            ${split("(SELECT NEW.id AS id,NEW.searchAliases AS searchAliases)")}; END""",
        """CREATE TRIGGER IF NOT EXISTS product_alias_index_update AFTER UPDATE OF searchAliases ON products
            WHEN NEW.searchAliases IS NOT OLD.searchAliases BEGIN
            DELETE FROM product_aliases WHERE productId=NEW.id;
            INSERT OR IGNORE INTO product_aliases(productId,alias,normalizedAlias)
            ${split("(SELECT NEW.id AS id,NEW.searchAliases AS searchAliases)")}; END"""
    )
    fun backfillStatement()="INSERT OR IGNORE INTO product_aliases(productId,alias,normalizedAlias) ${split("products") }"
}
