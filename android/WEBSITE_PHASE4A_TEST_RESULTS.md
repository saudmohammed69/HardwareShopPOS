# HardwareShopPOS Phase 4A — Local Storage and Capacity Results

18 October 2026, Asia/Kolkata. This report supersedes the earlier provisional storage projections for the Phase 3B schema. All measurements used synthetic data in Miniflare 4.20260730.0 / local workerd D1. There was no production Cloudflare or Firebase connection, Android shop database access, deployment, payment entry, or paid service activation.

## Result

The final Phase 3B protocol now has a local measured capacity profile and an abuse-counter storage optimization. The profile stays below the published daily D1 read/write and Workers request limits under the measured workload plus a modeled 1,000 dynamic catalog visitors. Storage is conditional on the owner verifying and compacting eligible orders regularly: with 50 new uncollected orders/day, the local model reaches 415,817,728 bytes at day 90, 34,182,272 bytes below the 450,000,000-byte admission cutoff. At 250 uncollected orders/day, it reaches 533,782,528 bytes at day 90 and crosses the operational cutoff. Without archiving, the measured 20-line workload projects 976,150,528 bytes at day 90.

This does **not** prove safe production capacity. The archive process requires verified, independent owner-controlled copies and daily operator action; this test put both copies on the same synthetic disk. Cloudflare allocation/reclamation, account headroom, account eligibility, provider CPU and the shop's actual backlog rate remain unverified. The current archive code is Node-only owner tooling; any future hosted cleanup must remain below D1 Free's 50-query-per-Worker-invocation limit and preserve all-or-nothing order compaction. Do not enable production intake from this local result alone.

## Final schema measured

The pre-optimization Phase 3B DDL was preserved as `tools/website-proof/evidence/phase4a-schema-before-optimization.sql`. The four final-schema capacity profiles use the Phase 4A DDL; the only persistent schema difference is the `admission` primary key. All order, catalog, claim, bill, event and retention paths remain the Phase 3B protocol. The actual final D1 schema is in `tools/website-proof/schema.sql`:

| Data | Stored shape and measured impact |
| --- | --- |
| Public settings | One `public_config` JSON row per shop. |
| Catalog | `catalog(shop,id,doc,published,revision)`, primary key `(shop,id)`. Order lines refer to the allowlisted public product projection; no stock or private costs are stored. The workload used 3,226 variants, including 25 maximum-text synthetic products. |
| Orders | `orders` rows retain order UUID, shop, submission key, capability hash, payload hash, JSON document, status/revision, last actor/operation, device claim/hash, bill ID and update time. Unique indexes protect `(shop,submission)` and `(shop,bill_id)`; `orders_active` supports owner views. |
| Order lines and adjustments | Lines, customer details and each customer-confirmed adjustment's before/after snapshots live in `orders.doc`; they are not separate rows. A document above 96,000 bytes is rejected without changing the order. |
| Audit and synchronization | `events` stores sequence, shop, order, revision, kind and time. Unique `(shop,order,revision)` plus `events_cursor` and `events_order` indexes protect cursor reads and bounded archive export. Device cursors are local to the Android cache; D1 has no separate per-device checkpoint table. `finalizedPosBillSyncId` stays in the online order document. |
| Abuse admission | `admission` stores one HMAC client bucket with its latest 10-minute window/count. `daily_admission` stores the current shop/day counter. |
| Archive metadata | Archive references/event ranges are kept in compacted order documents. The complete encrypted archive and owner attestations are local files, not another D1 table. `archive_guard` is temporary/empty after compaction. |

The Android `WebsiteOrdersDatabase` is a separate on-device Room cache and is not counted as D1 storage. `pos-proof.mjs` is a separate SQLite billing fixture and is not included in D1 measurements. Local `size_after` values include the measured D1 tables and indexes.

## Storage optimization and before/after evidence

The prior abuse table had a distinct primary-key row for every client and every 10-minute window. The current proof schema keys by `(shop,bucket)` and resets the count when an accepted new request arrives in a later window. The existing three-per-window limit and daily limit remain enforced. Expired abuse counters and old daily counters are removed only inside explicit owner archive cleanup, after archive checksums, second-copy match, isolated restore, collection/resolution/bill attestations and owner confirmation pass. No order, submission identity, event, bill link, adjustment or active record is deleted by this optimization.

| Synthetic D1 experiment | Before | After | Change |
| --- | ---: | ---: | ---: |
| 1,000 repeat client buckets over 10 windows: stored admission rows | 10,000 | 1,000 | 9,000 fewer (90%) |
| Allocated D1 bytes after that workload | 843,776 | 155,648 | 688,128 fewer (81.6%) |
| D1 rows written for those 10,000 requests | 20,000 | 11,000 | 9,000 fewer (45%); the reused-key version read 9,000 rows to resolve conflicts |
| Explicit stale-counter cleanup fixture | 1,500 expired admission rows + 1 expired day removed; 1 current bucket kept | 1,501 metadata rows written; 1,503 rows read; allocated bytes 188,416 → 81,920 | Only expired limiter metadata was removed |

The repeat-client comparison uses a clean D1 schema and a synthetic SQL workload to isolate the primary-key behavior; it is not a traffic trace or a production allocation guarantee. A new unique client bucket still uses storage until owner cleanup. Evidence: `tools/website-proof/evidence/phase4a-schema-before-optimization.sql` and `phase4a-admission-storage.json`.

The dedicated index probe measured 1,000 rows written for plain inserts/updates versus 2,000 with a secondary lookup index, an extra 2,000 rows written across both operations. `EXPLAIN QUERY PLAN` confirmed the indexed lookup. This is why D1 usage must count index work, not just business rows.

## Final-schema order workloads

Each profile published a synthetic 3,226-product catalog through 84 owner API batches, read it concurrently at the listed customer count, submitted 1,000 orders through the actual Worker routes and tested customer edits, cancellations, worker acceptance/preparation, 85 confirmed adjustments, sticky billing claims, retry recovery and two-device event synchronization. Each workload produced 850 completed orders with unique bill identities, 50 cancellations and 100 `NEW` orders that remained uncollected and protected. The 20-line/100-customer profile also ran 3,000 foreground refresh requests and full archive/restore/cleanup.

| Lines/order | Concurrent customers | Measured catalog visitors / rows read | Measured Worker requests | Measured D1 rows read / written by API | D1 rows read projected for 1,000 dynamic catalog visitors | Worker requests projected for 1,000 visitors | Storage growth before archive |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 20 / 64,520 | 6,084 | 131,849 / 48,497 | 3,293,329 | 12,944 | 5,382,144 bytes |
| 5 | 100 | 100 / 322,600 | 6,644 | 389,929 / 48,497 | 3,293,329 | 12,944 | 5,378,048 bytes |
| 20 | 20 | 20 / 64,520 | 6,084 | 149,624 / 48,497 | 3,311,104 | 12,944 | 10,846,208 bytes |
| 20 | 100 | 100 / 322,600 | 9,644 | 410,704 / 48,497 | 3,374,549 | 15,944 | 10,833,920 bytes |

The 1,000-visitor catalog values extrapolate from 20 or 100 locally measured visitors; the projected reads include the measured 1,000-order API operations. The site can serve a static catalog, which may avoid those direct D1 catalog reads; production Pages caching/routing has not been tested. The local API workload actually ran 20/100 catalog visitors, not 1,000.

The 20-line/100-concurrent archive run added 60,445 source D1 rows read and 7,973 written for the complete archive/verification/compaction operations. Including this work gives 56,470 rows written/day. Its 1,000 dynamic visitor model reads 3,374,549 rows/day. Relative to the published free allowances of 5,000,000 D1 reads/day, 100,000 D1 writes/day and 100,000 Workers requests/day, these are 67.5%, 56.5% and 15.9%, respectively, before other account applications or retries. The provider limits are published, but account usage is not measured. See the [D1 pricing and quotas](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) and [Workers limits](https://developers.cloudflare.com/workers/platform/limits/).

The largest profile's order-request p50/p95 local wall times were 2,998/9,992 ms; maximum observed was 25,095 ms. Its two worker devices observed 10,970 event deliveries across 112 paged sync requests; the maximum sync page wall time was 575 ms. Five-device foreground polling was compressed into 3,000 local requests. The local Node process used 46,812 ms CPU and local workerd used 136,797 ms CPU over the whole scenario. These are local aggregate measurements and do not prove the Cloudflare Free 10 ms CPU budget per invocation.

## Archive and backlog results

The 20-line/100-concurrent database measured 11,931,648 allocated bytes before archive compaction. The synthetic owner workflow exported 850 completed orders (with finalized POS bill IDs), encrypted 807,921 bytes across nine archive batches, made matching second copies, restored and compared all source rows/events into isolated D1 instances, then compacted the online records. All 1,000 order identities stayed online; 850 retained archive references and bill IDs. All 50 unresolved cancellations, all 100 uncollected new orders, their full documents and histories remained online. The retained event table had 1,065 rows: one terminal checkpoint per archived order and the unpruned histories for protected orders.

Allocated D1 storage fell to 6,361,088 bytes after archive compaction and safe limiter-metadata cleanup (5,570,560 bytes below the hot database). Logical order documents fell from 7,023,106 to 1,076,537 bytes. The owner cleanup measured 60,445 rows read and 7,973 rows written on source D1; the synthetic setup rows were reported separately. The two archive copies were files on the same synthetic disk. They do not prove independent disaster recovery or production disk availability.

The 30/60/90-day values below are a model from the one-day final-schema workload. Each protected order added per day replaces one measured archived row with a full row. The zero-backlog case assumes every order is collected/resolved and owner-archived each day; the 50/day case assumes 950/day qualify. Larger text, delayed cleanup, schema changes and retained online data can increase actual use.

| New uncollected/unresolved orders retained per day | Day 30 allocated bytes | Day 60 | Day 90 | 450,000,000-byte cutoff by day 90? |
| ---: | ---: | ---: | ---: | :---: |
| 0 | 129,507,328 | 257,916,928 | 386,326,528 | No |
| 50 | 139,337,728 | 277,577,728 | 415,817,728 | No; 34,182,272 bytes remain |
| 250 | 178,659,328 | 356,220,928 | 533,782,528 | Yes |
| 500 | 227,811,328 | 454,524,928 | 681,238,528 | Yes |
| 1,000 (no terminal cleanup) | 326,115,328 | 651,132,928 | 976,150,528 | Yes; exceeds the published 500 MB database limit |

The 50/day scenario is conditional and leaves little operational headroom at day 90. The 250/day scenario crosses the safety cutoff; the 1,000/day unarchived scenario exceeds nominal D1 Free storage. Active/uncollected/unresolved orders are never aged out to make a model pass. There is no unlimited or guaranteed ₹0 capacity claim.

Archive-file growth at the measured 850 archived orders/day is approximately 807,921 bytes per copy/day: about 72,712,890 bytes per copy at 90 days, or 145,425,780 bytes for two copies, plus temporary restore space and safety margin. Actual owner disk capacity and independent backup media remain unverified.

## Maximum input and recovery checks

- A 32,768-byte valid customer request with 25 maximum-text variants, a 100-character customer name, 1,000-character order note/missing-item request, and 300-character line notes was accepted. A 32,769-byte request returned HTTP 413 and created no order. Replaying the maximum valid submission returned the same order identity.
- Maximum permitted pipe values (`999999999` full lengths and `999999999.99` extra feet) passed server pricing and matched the shared pipe calculation.
- A 25-line order accepted one 300-character-reason adjustment with 300-character line notes. The next adjustment was rejected at the 96,000-byte safe document limit; the persisted document was 69,793 bytes and the existing accepted order remained unchanged.
- The standard workload covered retry/recovery after a deliberately discarded post-commit response, duplicate submission, repeated device-owned billing claim/completion and two independent sync cursors. It simulates a lost response; it does not simulate a real Cloudflare network fault.
- Local quota guard tests continue to reject new submissions safely at storage/daily caps, preserve the cart and allow known-identity recovery. Provider quota exhaustion was not induced.

## Commands and results

| Command | Result |
| --- | --- |
| `npm test` (default sandbox) | Environment-blocked before normal test execution: Windows `connect EACCES 127.0.0.1` for Miniflare's loopback fixture. Not counted as a product failure or pass. |
| `npm test` (local loopback permission) | **39/39 passed**, 21.8 seconds. Full log: `tools/website-proof/evidence/phase4a-backend-tests.log`. |
| `npm run capacity:admission` | Passed. Baseline/optimized D1 rows, allocated bytes, rows read/written and expired-counter cleanup recorded in `phase4a-admission-storage.json`. |
| `npm run capacity:phase4a` | **Four of four** 1,000-order profiles passed, plus max request/pipe, max adjustment, index and verified archive/restore checks. Evidence: `phase4a-capacity-results.json` and four profile JSON files. |
| `node --check phase4a-capacity.mjs; node --check admission-storage.mjs; node --check worker.mjs; node --check retention.mjs; node --check runtime.mjs` | Passed. |

The first capacity harness attempt rejected a test catalog because a generated size exceeded the backend's 100-character limit; the fixture was corrected. The next attempt completed the first load but hit a report-format `ReferenceError`; the output field was corrected and the complete four-profile rerun passed. Neither attempt changed app/product data. The initial capacity errors are harness failures, not successful test results.

The Phase 4A Android application/build was not rerun because no Android application, billing, Room, backup, printing, Firebase or UI source changed. The latest Android evidence remains Phase 3C: test APK compilation succeeded and direct JVM JUnit ran 312/312; the API 36 pipe IME visibility test and normal Gradle worker remain unresolved/environment-blocked as documented in `WEBSITE_PHASE3C_TEST_RESULTS.md`.

## Exact Phase 4A changes

- `tools/website-proof/schema.sql`, `worker.mjs`, `retention.mjs`, and `runtime.mjs`: reused per-client abuse-window rows, explicit expired-counter cleanup within verified owner archive cleanup, and selectable local schema input for the comparison harness.
- `tools/website-proof/test/phase4a.test.mjs`: rate-limit/reuse and owner-cleanup preservation regressions.
- `tools/website-proof/admission-storage.mjs`, `phase4a-capacity.mjs`, and `package.json`: repeat-client storage comparison and repeatable capacity/load commands.
- `tools/website-proof/evidence/phase4a-schema-before-optimization.sql`, `phase4a-admission-storage.json`, `phase4a-capacity-results.json`, `phase4a-capacity-5-lines-20-concurrent.json`, `phase4a-capacity-5-lines-100-concurrent.json`, `phase4a-capacity-20-lines-20-concurrent.json`, `phase4a-capacity-20-lines-100-concurrent.json`, and `phase4a-backend-tests.log`.
- `WEBSITE_PROGRESS.md`, `WEBSITE_ZERO_COST_AUDIT.md`, `WEBSITE_CODE_AUDIT.md`, `WEBSITE_RETENTION_DESIGN.md`, and this report.

No POS source, product, price, stock, bill, Room 11 schema, backup format 8, production database, Firebase project, Cloudflare service or billing workflow was modified or accessed. Phase 4B is not started. The remaining storage, real-account, real-CPU, security and Android IME blockers are handed off in this report and require fresh approval before any later work.
