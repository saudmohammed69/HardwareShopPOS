-- Disposable D1 only, not a Room migration. Three orders per anonymous session
-- remain enforced; shared IP is an emergency circuit breaker, not user identity.
CREATE TABLE IF NOT EXISTS customer_session_admission (
 shop TEXT NOT NULL,bucket TEXT NOT NULL,window INTEGER NOT NULL,
 count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 3),expires_at INTEGER NOT NULL,
 PRIMARY KEY(shop,bucket)
);
CREATE TABLE IF NOT EXISTS customer_ip_admission (
 shop TEXT NOT NULL,bucket TEXT NOT NULL,window INTEGER NOT NULL,
 count INTEGER NOT NULL CHECK(count BETWEEN 1 AND 200),expires_at INTEGER NOT NULL,
 PRIMARY KEY(shop,bucket)
);
CREATE TABLE IF NOT EXISTS customer_challenge_uses (
 shop TEXT NOT NULL,token_hash TEXT NOT NULL,order_id TEXT NOT NULL REFERENCES orders(id),expires_at INTEGER NOT NULL,
 PRIMARY KEY(shop,token_hash)
);
CREATE INDEX IF NOT EXISTS customer_sessions_expiry ON customer_session_admission(expires_at);
CREATE INDEX IF NOT EXISTS customer_ips_expiry ON customer_ip_admission(expires_at);
CREATE INDEX IF NOT EXISTS customer_challenges_expiry ON customer_challenge_uses(expires_at);
