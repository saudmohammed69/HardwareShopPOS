-- Additive LOCAL security store. Never applied to the POS Room database.
CREATE TABLE IF NOT EXISTS auth_users (
 uid TEXT PRIMARY KEY, disabled INTEGER NOT NULL DEFAULT 0,
 valid_after INTEGER NOT NULL DEFAULT 0, checked_until INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_memberships (
 shop TEXT NOT NULL, uid TEXT NOT NULL REFERENCES auth_users(uid),
 role TEXT NOT NULL CHECK(role IN ('OWNER','WORKER')),
 active INTEGER NOT NULL CHECK(active IN (0,1)), version INTEGER NOT NULL DEFAULT 1,
 valid_after INTEGER NOT NULL DEFAULT 0,
 PRIMARY KEY(shop,uid)
);
CREATE TABLE IF NOT EXISTS auth_devices (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL, uid TEXT NOT NULL,
 enrollment_id TEXT NOT NULL, enrollment_hash TEXT NOT NULL, public_key TEXT NOT NULL,
 fingerprint TEXT NOT NULL, key_version INTEGER NOT NULL DEFAULT 1,
 status TEXT NOT NULL CHECK(status IN ('PENDING','ACTIVE','REVOKED')),
 version INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
 approved_by TEXT, UNIQUE(shop,uid,enrollment_id), UNIQUE(shop,uid,fingerprint),
 FOREIGN KEY(shop,uid) REFERENCES auth_memberships(shop,uid)
);
CREATE TABLE IF NOT EXISTS auth_nonces (
 device TEXT NOT NULL, nonce TEXT NOT NULL, expires_at INTEGER NOT NULL,
 shop TEXT NOT NULL DEFAULT '',
 PRIMARY KEY(device,nonce)
);
CREATE INDEX IF NOT EXISTS auth_nonces_expiry ON auth_nonces(expires_at);
CREATE TABLE IF NOT EXISTS auth_audit (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL, actor TEXT NOT NULL, device TEXT NOT NULL,
 action TEXT NOT NULL, target TEXT NOT NULL, at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_guard (ok INTEGER NOT NULL CONSTRAINT auth_guard_ok CHECK(ok=1));
-- Provisioned by a trusted identity administrator, never from JWT custom claims
-- or a local POS worker selector. One provider UID represents one individual.
CREATE TABLE IF NOT EXISTS auth_identity_bindings (
 uid TEXT PRIMARY KEY REFERENCES auth_users(uid), person_id TEXT NOT NULL UNIQUE,
 kind TEXT NOT NULL CHECK(kind IN ('INDIVIDUAL','SHARED')),
 checked_until INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_worker_links (
 shop TEXT NOT NULL, uid TEXT NOT NULL, worker_ref TEXT NOT NULL,
 PRIMARY KEY(shop,uid), UNIQUE(shop,worker_ref),
 FOREIGN KEY(shop,uid) REFERENCES auth_memberships(shop,uid)
);
-- Owner-reviewed installation/account binding. No credentials or Room migration.
-- Immutable: replacing an account/database requires new installation approval.
CREATE TABLE IF NOT EXISTS auth_local_worker_bindings (
 device TEXT PRIMARY KEY REFERENCES auth_devices(id), shop TEXT NOT NULL,
 uid TEXT NOT NULL, person_id TEXT NOT NULL, worker_ref TEXT NOT NULL,
 database_epoch TEXT NOT NULL, account_hash TEXT NOT NULL,
 membership_version INTEGER NOT NULL, owner TEXT NOT NULL, created_at INTEGER NOT NULL,
 FOREIGN KEY(shop,uid) REFERENCES auth_memberships(shop,uid)
);
-- One out-of-band, UID/key-bound bootstrap grant per shop. No public creation.
CREATE TABLE IF NOT EXISTS auth_bootstrap (
 shop TEXT PRIMARY KEY, uid TEXT NOT NULL, fingerprint TEXT NOT NULL,
 grant_hash TEXT NOT NULL, expires_at INTEGER NOT NULL, request_hash TEXT,
 device_id TEXT
);
-- Evidence intake only: uncertain claims stay locked and active. This is not a
-- replacement completion/billing engine or permission to bill on another device.
CREATE TABLE IF NOT EXISTS auth_claim_reviews (
 id TEXT PRIMARY KEY, shop TEXT NOT NULL, order_id TEXT NOT NULL,
 bill_id TEXT NOT NULL, original_device TEXT NOT NULL, revision INTEGER NOT NULL,
 evidence_hash TEXT NOT NULL, evidence_doc TEXT NOT NULL CHECK(json_valid(evidence_doc)), owner TEXT NOT NULL, decision TEXT NOT NULL,
 created_at INTEGER NOT NULL, UNIQUE(shop,order_id,evidence_hash)
);
