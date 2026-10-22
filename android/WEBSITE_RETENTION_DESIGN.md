<!-- PHASE4J_CURRENT -->
## Phase 4J final local gate — 22 October 2026

**83.5% unchanged (+0%); Phase 5 entry BLOCKED.**

- ✅ Fresh minified/shrunk release build and actual APK hash/static DEX guards pass after AGP 8.6.1 compiler repair.
- ✅ Fresh debug/test build and 312/312 JVM tests, 0 failures/errors/skips.
- ✅ Archive scaling 2/2: 1,024 receipts need 1,026 source reads versus 50,689; 0 verification writes; no schema/index/deletion change.
- ✅ Local website build/artifact scan and 10/10 browser regressions (9 main+1 delayed-hours), 7 viewports, 0 console errors/external requests.
- ❌ Final backend:104 passed, 2 failed, 1 platform skip;106 executed/107 defined. Concurrent ordering and signed-read transport still fail.
- ❌ Native setup blocked before JUnit at 181.1 s; 0 native tests executed. Historical 15 s app/keyboard acceptance remains open.
- ⬜ Focused 200 sync/full capacity gated; no new sustained 500/day, 30/60/90-day or provider-capacity credit. Historical 90,833-write busy day leaves only 9,167 before unknowns.
- ✅ Final preservation audit: 9,585 historical evidence files unchanged; 0 unexpected historical changes. Billing/stock, Room 11, Backup 8, pipes, printing and FCMsource protected.

See [Phase 4J final results](WEBSITE_PHASE4J_RESULTS.md) for exact files, commands, artifacts, failures, cost limits and required approvals. No production connection, deployment, paid activation, real enrollment, live data or Phase 5 execution. Historical sections below remain historical.
<!-- END_PHASE4J_CURRENT -->

# Phase 3A — finite local archival and retention proof

## Version 1 disposition — 19 October 2026

The owner-confirmed requirements in WEBSITE_ARCHITECTURE_V1.md supersede older traffic/sign-in proposals: normal 200–500/day, sustained target 500/day, 1,000/day stress only, existing POS login unchanged and no separate worker cloud login. Older signed-owner/individual fixtures below remain historical local proofs; a compatible device-principal protocol is not implemented by reusing their identities.

New conditional load tests complete two consecutive 500-order cycles in each successful profile, with encrypted export/copy/isolated restore of 850 completed orders before owner-attested compaction. Each retains all 100 uncollected and 50 unresolved cancelled orders. The original failed high-concurrency attempt and sticky claims remain separate, unmodified evidence. Copies are on the same test disk; sustained production capacity, independent owner media and scalable hosted archives remain unverified. The 450,000,000-byte cutoff and protected-order rules are unchanged. See WEBSITE_V1_VERIFICATION_RESULTS.md for measurements, failed evidence and explicitly modeled 30/60/90-day growth.

## Phase 4D + 4E addendum — 19 October 2026

The historical local-only sections below remain the order-compaction baseline. The new local proof adds a **bounded hosted-compatible tenant snapshot**, requiring signed recently authenticated OWNER and HTTPS, plus owner-workstation encryption/second-file verification/isolated quarantined restore. It exports only the selected tenant and preserves all selected order states and bill/event identities. There is still **no hosted cleanup or live-restore route**. Larger than 200 rows/security table, 12 selected orders, 1,000 events or 2 MB fails closed without truncation. Scalable consistent pagination/streaming remains stopped for further architecture/capacity review; the bounded proof must not be presented as an unlimited hosted archive solution.

New local replay metadata has an explicit trusted shop tag, including enrollment proofs where a public-key fingerprint may occur in two shops. Old administrator snapshots retain unknown legacy nonce scope as quarantined administrator evidence, never guessed tenant authority. All ten current security tables are included in administrator export/restore; original nine-table archives remain recoverable into quarantine without inventing local worker approval. No production migration/backfill or automatic evidence cleanup was run.

Actual localhost HTTPS acceptance is blocked by Avast's substituted untrusted certificate; conceptual HTTPS route fixtures do not verify a trusted network connection. Separate same-disk encrypted copies are not independent physical recovery. The 450,000,000-byte admission cutoff is unchanged. Existing active orders/security/audit can still consume remaining physical headroom after admission stops; finite headroom cannot guarantee unlimited resolution traffic. If safe capacity is unavailable, preserve records and report the admission/write failure, never remove protected records or silently pay.

See WEBSITE_AUTH_SECURITY_DESIGN.md, WEBSITE_PHASE4D_4E_TEST_RESULTS.md, WEBSITE_ZERO_COST_AUDIT.md and WEBSITE_RELEASE_BLOCKERS.md for current verified measurements and approval gates. Room 11, BackupManager 8, original billing/stock and ordinary offline counter billing remain unchanged.

---

17 October 2026. Local synthetic development only. No production cleanup, cloud account, migration, extra account, paid storage or deployment was used. This is a recoverability design and executable local proof, not a production storage guarantee.

## Policy and retained records

Keep every NEW, ACCEPTED, PREPARING, READY_FOR_PICKUP and BILLING_IN_PROGRESS order in full. Never expire active, uncollected or unresolved orders by age. COMPLETED alone is insufficient: the owner must explicitly attest collection, resolution and the matching finalized bill ID. Resolved cancellations require an explicit owner attestation too. A retained uncollected backlog can fill any finite database; stop accepting new website orders safely when necessary.

The existing POS accounting bills remain in the normal POS database/history. This phase neither archives nor alters those bills. The website proof retains each order's original UUID, submission key, capability hash, original payload hash, status/revision, total, device claim and finalized bill ID. A SHA-256 archive reference and event range replace only the full order document after verification. Duplicate submission recovery and completion retries continue returning the original identity/bill. The order number now uses the entire unique order UUID; truncating it to eight characters could create ambiguous public numbers at this workload.

Full order documents, every original event row and owner attestations are exported together. Online event history is compacted to its latest terminal event, with its original monotonically increasing sequence. A lagging device can still fetch the terminal snapshot; the archive reference explicitly signals that detailed historical contents require the owner archive. A future native adapter must recognize this checkpoint and must not try to bill an archived terminal order. Native adapter implementation/acknowledgement is a later gate. Existing proof events record revision, kind and time, rather than a complete actor/value-diff audit ledger; archival preserves their exact contents and does not manufacture missing audit data.

## Owner workflow and verification

The Node-only `tools/website-proof/retention.mjs` operates through the separately keyed local D1 fixture driver. There is no public cleanup route, scheduled expiry or customer-controlled archive operation.

1. Review terminal orders, actual collection/resolution and finalized bill links. Supply explicit owner attestations for selected IDs. The library rejects active/missing-bill/unattested/already-compacted rows.
2. Export a bounded batch to existing owner-controlled local disk. The format is versioned JSON, gzip-compressed and AES-256-GCM encrypted with a fresh random salt/nonce and scrypt-derived key. Use a private passphrase of at least 16 characters. The test passphrase protects synthetic data only and is never a production secret.
3. Flush the archive and checksum sidecar to disk. SHA-256 covers the encrypted file and the canonical plaintext. Preserve the passphrase separately; loss of it prevents recovery. These files contain sensitive customer information when decrypted.
4. Copy the archive to a second owner-controlled destination, ideally an already-owned independent device or removable disk. Both copies must match the encrypted-file digest. The local capacity harness uses separate files on the same disk; it does **not** demonstrate independent physical disaster recovery or existing production disk capacity. If an independent recoverable copy cannot be provided without purchasing storage, production cleanup must remain disabled.
5. Before each cleanup, reread the archive, verify authentication/checksums, restore all orders/events into a **new disposable local D1 database**, and compare every restored field and event with the archive. No function accepts a live POS restore target.
6. Explicitly confirm cleanup. A guarded D1 batch checks the exported status, revision and original document before compacting. Any stale row aborts the entire batch. Repeated cleanup, missing/corrupt copies and wrong passphrases fail closed. All unselected orders remain intact.
7. Keep an owner ledger of archive filename, checksum, covered IDs, bill IDs and copy locations; the checksum sidecar identifies each generated file. Periodically repeat isolated restore tests and verify copies. Archival is manual local tooling in this phase; authenticated native owner controls and production transport are not implemented.

Local restore batches use at most 40 statements. The current owner compaction utility may batch up to 100 orders because it is Node/test tooling; a future hosted API must chunk its three-statements-per-order transaction below D1 Free's 50-query-per-invocation limit. It must add real owner authentication, account quota budgeting, durable export-copy acknowledgements and native checkpoint handling before any production use.

## Monitoring and safe failure

Use actual D1 `meta.size_after` allocated bytes, rather than JSON lengths alone. Monitor database bytes **and** total account usage, writes, reads, owner-disk free space, last verified export and uncollected backlog. The local owner storage endpoint uses a one-row size probe rather than a growing-table COUNT scan. Local `storagePolicy` supports database and account thresholds; the real account telemetry connection is intentionally absent.

Using conservative decimal limits, warnings start at 65%, become critical at 80%, and stop new website admission at 90% of 500,000,000 database bytes or 5,000,000,000 account bytes. The worker enforces the per-database gate before new creation; existing same-identity recovery remains available. Account-wide enforcement, owner notifications and real provider telemetry remain production gates. Actual persistence/quota errors still fail closed. There is no paid fallback. A customer receives no invented order number/success and retains the original cart/identity. Normal POS billing remains independent.

Do not assume DELETE immediately shrinks provider allocation. Measure allocated bytes after each cleanup and subsequent insertion; local behavior is not proof of production reclamation. Retained identity rows/checkpoints and unresolved orders grow indefinitely. Maximum notes and larger real text may exceed synthetic averages.

## Capacity conclusion

See `WEBSITE_PHASE3A_TEST_RESULTS.md` and `WEBSITE_ZERO_COST_AUDIT.md` for final measured values and exact commands. The original approximately 863 MB projection cannot remain entirely online in one 500 MB free database. The tested encrypted offline archive preserves records while reducing online growth. A finite local-disk design adds no recurring cloud charge but depends on already-owned disk space, backups, operator cleanup cadence and recoverability.

The pre-index three-day model projected 450,306,048 bytes at day 90, already above the deliberately conservative 450 MB admission cutoff. Its export queries also exposed excessive scans; the added `events(order_id,seq)` index reduces source reads in the final three-day proof from 11,336,652 to 156,397. The final indexed storage projection is 458,276,864 bytes, above the 450 MB operational cutoff. No guaranteed 90-day headroom, unlimited retention or guaranteed ₹0 production capacity is claimed. The final indexed result lacks safe headroom: the production-capacity part is stopped and this local proof is retained for review. Never delete protected orders, silently pay, or open accounts to evade quotas.

## Phase 4A measured schema and limiter cleanup — 18 October 2026

The prior three-day storage projection is historical and is superseded by the [Phase 4A final-schema test report](WEBSITE_PHASE4A_TEST_RESULTS.md). The measured schema still keeps complete order documents/events until the owner has collected/resolved an order, confirmed its final bill link, encrypted the export, verified a second copy and compared an isolated restore. The local test compacted 850 attested completions from a 1,000-order cohort; all 50 unresolved cancellations and 100 uncollected orders remained complete online.

The abuse limiter was changed to reuse one row per HMAC client bucket across 10-minute windows. Repeated-client D1 measurement fell from 10,000 rows and 843,776 bytes to 1,000 rows and 155,648 bytes over ten synthetic windows. The current three-per-window and daily admission limits are unchanged. Expired rate counters and prior-day counters are removed only as part of the explicitly confirmed archive cleanup, after restore checks succeed. This saves limiter metadata; it does not reduce protected order requirements.

In the measured full workflow, allocated D1 size was 11,931,648 bytes before archive and 6,361,088 bytes after archive verification, compaction and expired-counter cleanup. With 50 new uncollected orders/day, the one-day cohort model projects 415,817,728 bytes by day 90; with 250/day, it projects 533,782,528; without archiving, 976,150,528. These are conditional local projections. Owner archive copies were separate files on one synthetic disk, and Cloudflare reclamation, independent recovery media, real account CPU/quota headroom and actual backlog cadence remain unverified. No retention policy guarantees unlimited storage or permanent ₹0 capacity.

The Phase 4A implementation changes only the local proof schema/Worker admission metadata and Node-only owner maintenance. No hosted archive cleanup endpoint, scheduled deletion, POS database migration or production cleanup exists. Any future production migration must preserve current limiter counts and demonstrate backup, rollback and replay behavior before use. Phase 4B and deployment require fresh approval.
