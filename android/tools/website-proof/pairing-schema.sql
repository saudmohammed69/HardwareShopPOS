-- Isolated local D1 prototype only. Never a Room/BackupManager migration.
CREATE TABLE IF NOT EXISTS pair_authority (
 shop TEXT PRIMARY KEY, epoch INTEGER NOT NULL, admin_fingerprint TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','QUARANTINED'))
);
CREATE TABLE IF NOT EXISTS pair_devices (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL REFERENCES pair_authority(shop),
 fingerprint TEXT NOT NULL UNIQUE, public_key TEXT NOT NULL,
 database_epoch TEXT NOT NULL, authority_epoch INTEGER NOT NULL,
 role TEXT NOT NULL CHECK(role IN ('OWNER','WORKER')), permissions TEXT NOT NULL CHECK(json_valid(permissions)),
 status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED')), version INTEGER NOT NULL DEFAULT 1,
 expires_at INTEGER NOT NULL, approved_by TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS pair_grants (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL, authority_epoch INTEGER NOT NULL,
 device_id TEXT NOT NULL UNIQUE, fingerprint TEXT NOT NULL, database_epoch TEXT NOT NULL,
 role TEXT NOT NULL, permissions TEXT NOT NULL, ticket_hash TEXT NOT NULL,
 expires_at INTEGER NOT NULL, approved_by TEXT NOT NULL, consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1))
);
CREATE INDEX IF NOT EXISTS pair_grants_expiry ON pair_grants(expires_at);
CREATE TABLE IF NOT EXISTS pair_audit (
 id TEXT PRIMARY KEY,shop TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,at INTEGER NOT NULL
);
-- Permanent operation identities/receipts, including uncertain billing recovery.
-- No automatic expiry; protected/replay/bill identities are never removed here.
CREATE TABLE IF NOT EXISTS pair_operations (
 shop TEXT NOT NULL,device TEXT NOT NULL,request_id TEXT NOT NULL,request_hash TEXT NOT NULL,
 order_id TEXT NOT NULL, result TEXT NOT NULL CHECK(json_valid(result)),at INTEGER NOT NULL,
 PRIMARY KEY(device,request_id)
);
CREATE INDEX IF NOT EXISTS pair_operations_order ON pair_operations(shop,order_id);
