PRAGMA foreign_keys=ON;
CREATE TABLE IF NOT EXISTS public_config (shop TEXT PRIMARY KEY, doc TEXT NOT NULL CHECK(json_valid(doc)));
CREATE TABLE IF NOT EXISTS catalog (
 shop TEXT NOT NULL, id TEXT NOT NULL, doc TEXT NOT NULL CHECK(json_valid(doc)),
 published INTEGER NOT NULL CHECK(published IN (0,1)), revision INTEGER NOT NULL,
 PRIMARY KEY(shop,id)
);
CREATE TABLE IF NOT EXISTS orders (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL, submission TEXT NOT NULL,
 capability_hash TEXT NOT NULL, payload_hash TEXT NOT NULL,
 doc TEXT NOT NULL CHECK(json_valid(doc)),
 status TEXT NOT NULL CHECK(status IN ('NEW','ACCEPTED','PREPARING','READY_FOR_PICKUP','BILLING_IN_PROGRESS','COMPLETED','CANCELLED')),
 revision INTEGER NOT NULL, actor TEXT, operation TEXT NOT NULL,
 lock_device TEXT, lock_hash TEXT, bill_id TEXT, updated_at INTEGER NOT NULL,
 UNIQUE(shop,submission), UNIQUE(shop,bill_id)
);
CREATE INDEX IF NOT EXISTS orders_active ON orders(shop,status,updated_at);
CREATE TABLE IF NOT EXISTS events (
 seq INTEGER PRIMARY KEY AUTOINCREMENT, shop TEXT NOT NULL, order_id TEXT NOT NULL REFERENCES orders(id),
 revision INTEGER NOT NULL, kind TEXT NOT NULL, created_at INTEGER NOT NULL,
 UNIQUE(shop,order_id,revision)
);
CREATE INDEX IF NOT EXISTS events_cursor ON events(shop,seq);
-- Owner exports/checkpoint compaction address one order. Without this index,
-- historical exports scan unrelated events and can consume the daily read budget.
CREATE INDEX IF NOT EXISTS events_order ON events(order_id,seq);
-- Only the latest ten-minute abuse window is needed per HMAC client bucket.
-- Expired buckets are pruned only during explicit verified owner archive cleanup.
CREATE TABLE IF NOT EXISTS admission (
 shop TEXT NOT NULL, bucket TEXT NOT NULL, window INTEGER NOT NULL,
 count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 3), PRIMARY KEY(shop,bucket)
);
CREATE TABLE IF NOT EXISTS daily_admission (
 shop TEXT NOT NULL, day INTEGER NOT NULL, count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 1200), PRIMARY KEY(shop,day)
);
-- Deliberately no stock or reservation table/column: checkout never checks stock.
