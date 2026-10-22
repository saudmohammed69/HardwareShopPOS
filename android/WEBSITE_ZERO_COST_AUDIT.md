<!-- CATALOG_PUBLICATION_20261022 -->
## Published static catalog cost boundary — 22 October 2026

The approved 1,478-row catalog is now hosted at https://hardwareshoppos.pages.dev/ on direct Cloudflare Pages. Nine static build files, no Functions, Workers code, D1, Firebase sender or order mutations. Cloudflare states static asset requests are free on both free and paid plans: https://developers.cloudflare.com/pages/functions/pricing/. This publication fits the documented Free file/size limits and required no payment, upgrade or purchased domain. Only Pages write plus account/user read OAuth permissions were granted. Billing subscriptions/account-wide usage were not audited because Billing Read permission was not requested; no guarantee about unrelated account charges or future live-ordering capacity is claimed. Existing sustained-500/day and shared-account/D1/CPU/archive uncertainties remain open. Tests remain owner-deferred, with no new capacity credit. Full evidence is in `WEBSITE_FINAL_ENGINEERING_RESULTS.md`.
<!-- END_CATALOG_PUBLICATION_20261022 -->

<!-- FINAL_EXECUTION_CURRENT -->
## Final local feasibility checkpoint — 22 October 2026

Official Cloudflare documentation was rechecked today. Published Free limits are D1: 5 million rows read/day, 100,000 rows written/day, 500 MB/database, 5 GB/account and 50 queries per Worker invocation; Workers: 100,000 requests/day and 10 ms CPU/request; Pages: 500 builds/month, one concurrent build, 20,000 files/site and 25 MiB per file. These are provider-published limits, not measured account eligibility or guaranteed sustained capacity. See the official [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and [Pages limits](https://developers.cloudflare.com/pages/platform/limits/).

No Cloudflare/Firebase account, shared-account usage, production CPU, live D1 metrics or 500-order workload was tested in this final recheck. Firebase’s current plan guide lists FCM as no-cost and Spark as requiring no payment information for no-cost Firebase products; FCM still needs a trusted sending environment. The retained five-device 500-order model used 90,833 known writes on its busier day, leaving only 9,167 below the published daily allowance before unmeasured shared usage, retries, archive and notification work. **500 orders/day and permanent ₹0 operation remain unverified.** The new static catalog preview uses no Worker/D1/FCM and does not change the order-capacity result. Keep the storage cutoff, protect unresolved orders and do not silently delete or admit work beyond verified headroom. Sources: [Firebase plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans), [FCM architecture](https://firebase.google.com/docs/cloud-messaging).

<!-- END_FINAL_EXECUTION_CURRENT -->

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

<!-- PHASE4I_FINAL -->
## Phase 4I final local gate — 21 October 2026

**83.5% unchanged; Phase 5 NOT recommended.** Actual new paired native methods pass, total native67/68 and JVM312/312; cold15-second navigation remains failed. Two-cycle five-device500 profile passes with1,000 unique orders/850 completed bill identities/150 protected orders. Conservative known busy-day source envelope90,833 writes; offline restore costs, growing archive reads and provider/shared-account limits remain separate. Ten-device500,200 and1,000 stress profiles fail; uncertain claims are preserved and never reassigned. Final backend counts: {'tests': 105, 'pass': 102, 'fail': 2, 'skipped': 1, 'cancelled': 0}; browser: 10 executed, 10 passed, 0 failed; 0 console errors; 0 external requests. Actual release scan: BLOCKED.

See [WEBSITE_PHASE4I_RESULTS.md](WEBSITE_PHASE4I_RESULTS.md) for exact files, commands, failures, costs, archive recovery and evidence gaps. No live data, deployment, production activation/enrollment, paid service or Phase5 activity. Older phase conclusions below remain historical.
<!-- END_PHASE4I_FINAL -->

<!-- PHASE4H_FINAL_GATE -->
## Phase 4H final local gate — 21 October 2026

**83.5% unchanged; Phase 5 NOT recommended.** Final APK/test build and fresh JVM 312/312 pass; website build/scan and 10/10 browser cases pass. Five-device 500/day completes two local cycles; ten-device Windows attempt fails with expired proofs and six preserved uncertain claims. Known five-device busy-day archive schedule costs 107,824 writes, above the Free 100,000/day allowance. Strict actual TCP TLS passes in Linux; raw backend transport failures remain. Native acceptance is BLOCKED before JUnit (emulator readiness timeout; no 15-second threshold change). Final focused result: FAIL. All details/attempts: [WEBSITE_PHASE4H_RESULTS.md](WEBSITE_PHASE4H_RESULTS.md). No deployment, production connection, paid plan, real enrollment, live data or automatic protected-order deletion.

**Additional native source-review gate:** successful payload-derived operation receipts need separation from later intentional identical catalog publications (for example show/hide/show). The new adapter is DEBUG/synthetic only and remains unaccepted; this inferred lifecycle issue needs a dedicated native regression and reviewed correction before integration. Existing POS billing identity/transactions were not changed.

### Final paired-schema local measurements

6 retained cohort result files; platform/lines/concurrency/device counts must remain attached to each figure.

| Orders/lines/customers/devices | Outcome | Cycles | Known reads | Writes | Requests | Post-archive bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| Linux 1000/20/100/10 | FAIL | 0/1 | partial 32,94,536 | partial 93,107 | partial 12,779 | unverified |
| Windows 200/5/20/5 | PASS | 1/1 | 7,10,423 | 41,364 | 8,707 | 26,46,016 |
| Windows 300/5/20/5 | PASS | 1/1 | 10,51,599 | 55,308 | 10,557 | 32,97,280 |
| Windows 400/20/20/5 | PASS | 1/1 | 13,99,739 | 69,206 | 12,409 | 44,48,256 |
| Windows 500/20/100/10 | FAIL | 0/2 | partial 16,48,226 | partial 47,183 | partial 6,368 | unverified |
| Windows 500/20/20/5 | PASS | 2/2 | 18,03,302 | 1,07,824 | 14,395 | 92,28,288 |

Five-device 500/day: actual cycle API writes 44,929 and 45,993; source archive 57,262 writes. Conservative busy-day API/setup/revocation plus all cohort archive work = **107,824 writes**, 7,824 above the Free account-day allowance. Source archive reads 73,891; maximum API queries/invocation 46. Private seed/schema/admin setup, hosted disaster recovery, real challenge/sender/outbox/device renewal and shared-account traffic remain unverified, not zero. Local CPU does not prove the 10 ms provider bound.

Conditional retained 30/60/90-day allocation: 120,705,024 / 240,144,384 / 359,583,744 bytes; no-terminal-cleanup sensitivity 208,134,144 / 415,002,624 / 621,871,104. These are linear models from two compressed cycles, not multimonth replay. Additional modeled 30% uncollected backlog crosses the 450,000,000-byte cutoff by day 90. Full-ledger snapshot/index writes and temporary allocation grow with permanent receipts. The earlier Windows cohorts did not measure temporary archive allocation peaks. No automatic protected-order deletion or paid fallback.

Official sources: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [Firebase pricing](https://firebase.google.com/pricing). Real account eligibility/commercial acceptance/payment settings, shared-account quota and secure FCM sender are unverified. Background push is unsupported for release; D1 remains the online order authority, with no Firebase order mirror. See phase4h-final-cost-model.json and the full Phase 4H report.

<!-- END_PHASE4H_FINAL_GATE -->

## Phase 4H quota verification — 20 October 2026

Local final-schema capacity measurement is pending; prior failed 500-order cohorts remain FAILED. Rechecked official limits: D1 Free 5 million rows read and 100,000 rows written/account/day ([pricing](https://developers.cloudflare.com/d1/platform/pricing/)); 500 MB/database, 5 GB/account and 50 queries/invocation ([limits](https://developers.cloudflare.com/d1/platform/limits/)). Workers Free 100,000 requests/day and 10 ms CPU/HTTP invocation ([limits](https://developers.cloudflare.com/workers/platform/limits/)); local Node aggregate CPU does not verify provider CPU. Pages Free has finite build/file limits ([limits](https://developers.cloudflare.com/pages/platform/limits/)). FCM is listed no-cost ([Firebase pricing](https://firebase.google.com/pricing)); HTTP v1 still needs a protected trusted sender ([authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api)). No secure sender/provider/account eligibility/shared-account usage has been verified. Background push remains unsupported for release; foreground/manual synchronization is the local fallback. No billing activation, extra accounts or paid fallback.

New archive prototype deliberately accounts for temporary immutable snapshot rows and indexes, paginated reads, encrypted copies, isolated restore and explicit owner-reviewed snapshot cleanup. Permanent receipts and protected orders are not deleted. Finite storage, local media capacity and the unchanged 450,000,000-byte cutoff still apply. New 30/60/90-day projections must use final measured cohorts, not old Phase 4G scaling.

# HardwareShopPOS — ₹0 infrastructure proof audit

<!-- PHASE4G_FINAL_COST -->
## Phase 4G final cost decision — 20 October 2026

**500/day is NOT verified.** Both attempts fail, including a final repeat with long-delay authorization denials and two retained sticky claims. Local identity-safe recovery proofs pass, but no security window, timeout or concurrency was relaxed. See the Phase 4G report and raw failure/copy inspections.

The 1,000-order stress cohort passes locally, but its known combined busy-day model is **104,564 writes**, exceeding the published Free allowance before unknown sender/shared-account costs. This is a workload-specific limit, not a universal order-count limit. Actual provider CPU, account eligibility/consumption and real FCM sender/outbox remain unverified.

500/day storage sensitivity is modeled from one half of the completed 1,000-order cohort, **not a completed 500 test**: retained 30/60/90-day allocation 121,503,744 / 241,741,824 / 361,979,904 bytes; without cleanup, 90-day allocation 620,765,184 bytes. Receipt-only security export growth fits approximately 14 full modeled 500-order days before the following cohort exceeds the private 16 MiB plaintext bound; other growth can reach it sooner. Bounded tenant-hosted export/independent media and sustained renewal are unaccepted. No automatic deletion of active/uncollected/unresolved records, raised 450 MB cutoff, paid fallback or extra account was introduced.

Comparable synthetic post-archive allocations increase from Phase 4F to 4G: 200 orders 2,064,384 → 2,629,632 bytes; 300 orders 2,465,792 → 3,305,472; 400 orders 3,362,816 → 4,435,968. New durable receipt/security data is preserved. These are different-schema/retry cohorts, not a controlled provider benchmark. No production capacity credit is earned; progress remains **83.5%**. All load databases are local synthetic fixtures, not additional provider accounts or production sharding.

## Final local capacity evidence

Both 500-order attempts failed; sustained 500/day remains unverified. Completed cohorts below are local D1-compatible proofs, not provider capacity acceptance. Original failed databases and all claim identities are retained.

| Cohort / lines / concurrency / devices | Outcome | API + source archive reads | Writes | Requests | Max API SQL | Hot / post-archive bytes |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 200/5/20/5 | ✅ Local PASS | 693,889 | 23,952 | 8,706 | 46 | 2,957,312 / 2,629,632 |
| 300/5/20/5 | ✅ Local PASS | 1,031,642 | 34,032 | 10,557 | 46 | 3,788,800 / 3,305,472 |
| 400/20/20/5 | ✅ Local PASS | 1,376,364 | 44,075 | 12,409 | 46 | 6,770,688 / 4,435,968 |
| 500/20/100/10 repeat (2 planned; 0 complete) | ❌ FAIL | 1,647,503 API partial | 46,308 API partial | 6,348 partial | 46 | 8,146,944 partial / not verified |
| 500/20/100/10 (2 planned; 1 complete) | ❌ FAIL | Unverified total | Unverified total | Unverified total | Unverified | Unverified / not verified |
| 1000/20/100/10 | ✅ Local PASS | 3,499,503 | 104,564 | 34,103 | 46 | 15,032,320 / 9,281,536 |

Counts cover actual local API metadata and source archive work, including indexes/security. Separate isolated restore databases, private fixture seed/provisioning, aborted/rolled-back provider work, real renewal/sender/outbox and account sharing are not accepted production costs. Full stress workload totals must not be presented as a free daily allowance.

| Completed cohort | Conditional retained allocation: 30 / 60 / 90 days | No-cleanup allocation: 90 days | Receipt-only whole days below 16 MiB |
| --- | --- | ---: | ---: |
| 200/day (5 lines) | 42,184,704 / 83,103,744 / 124,022,784 | 153,513,984 | 35 |
| 300/day (5 lines) | 62,459,904 / 123,654,144 / 184,848,384 | 228,347,904 | 23 |
| 400/day (20 lines) | 96,374,784 / 191,483,904 / 286,593,024 | 496,717,824 | 17 |
| 1000/day (20 lines) | 241,741,824 / 482,217,984 / 722,694,144 | 1,240,264,704 | 7 |

**500/day sensitivity only — not verified capacity:** scaling the completed 1,000-order cohort by one half gives conditional retained allocation 121,503,744 / 241,741,824 / 361,979,904 bytes at 30/60/90 days. Without cleanup, 90 days is 620,765,184 bytes. The model allows approximately 111 full days before the 450,000,000-byte cutoff with the modeled terminal cleanup, or 65 without cleanup. Both actual 500 attempts failed; these figures cannot establish safe headroom.

Permanent receipts alone consume the private export's 16 MiB budget after about 14 such full 500-order days; the following cohort exceeds that receipt-only budget, and other growth can reach it sooner. This finite whole-ledger archive is not a production retention solution. No receipt/protected-order deletion, raised cutoff, paid fallback or extra account was introduced.

These linear cohort models are not 30/60/90-day execution. Future complete-ledger archive scans, restart replay and uncollected/max-size input growth remain sensitivity risks. The 450 MB operational cutoff remains enforced; ordinary offline POS billing is independent.

<!-- END PHASE4G_FINAL_COST -->

## Phase 4G current verification — 20 October 2026

Phase 4G is local/synthetic only; final paired-schema workload evidence belongs in [WEBSITE_PHASE4G_RESULTS.md](WEBSITE_PHASE4G_RESULTS.md). Earlier Phase 4F storage/write figures below are historical and cannot establish capacity for the new permanent operation-receipt tables. Current verified progress remains 83.5%.

Official documentation rechecked 20 October: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) lists 5 million reads and 100,000 writes per account/day, with index writes counted and hard errors on Free quota exhaustion. [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) lists 500 MB per Free database and 50 SQL queries per invocation; total Free storage is 5 GB/account. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) lists 100,000 requests/day and 10 ms HTTP CPU on Free. [Firebase pricing](https://firebase.google.com/pricing) lists FCM as no-cost. These published allowances do not verify this owner's account eligibility, shared-account consumption or application capacity.

The pairing prototype adds persistent authority, device/grant/audit and immutable operation receipts. Read-only requests recheck server authority/revocation and signatures without persistent mutation nonces; mutations preserve atomic replay/receipt protection. No client authorization cache replaces server checks. Whole-ledger encrypted security exports grow with retained receipts; future repeated full-ledger scans and bounded export size must be included in an operating plan. Same-disk verified copies are not independent recovery media, and this private Node administrator archive is not an accepted hosted tenant-owner endpoint. No automatic cleanup removes protected orders or permanent billing identities.

Actual FCM deliveries are zero. Secure sender custody, OAuth refresh/signing, outbox/retry writes and account-wide usage remain unverified; no Blaze service or sender was activated. Aggregate local CPU is not Cloudflare's per-invocation CPU. The 450,000,000-byte stop boundary remains in force; quota failure cannot produce a successful order confirmation or trigger a paid fallback.

## Historical Phase 4F cost boundary — 19 October 2026

Final Phase 4F workload accounting belongs in [WEBSITE_PHASE4F_RESULTS.md](WEBSITE_PHASE4F_RESULTS.md) and `tools/website-proof/evidence/phase4f-capacity-analysis.json` after aggregation. Prior Phase 4D/V1 measurements below do not include the new challenge/session indexes and are not final Phase 4F figures. All account/service activation, payment, live data and deployment remain prohibited.

Published sources rechecked today: D1 Free **5,000,000 rows read and 100,000 written per account/day**, including index write amplification ([pricing](https://developers.cloudflare.com/d1/platform/pricing/)); **500 MB/database, 5 GB/account, 50 SQL queries per invocation** ([limits](https://developers.cloudflare.com/d1/platform/limits/)); Workers Free **100,000 requests/day and 10 ms HTTP CPU** ([limits](https://developers.cloudflare.com/workers/platform/limits/)); Pages Free has a finite build/file allowance ([limits](https://developers.cloudflare.com/pages/platform/limits/)). Existing account use, commercial/no-card eligibility and provider quota/CPU behavior have not been audited or activated.

Turnstile's published Free plan includes unlimited challenge/verification requests within its listed widget/hostname constraints ([plans](https://developers.cloudflare.com/turnstile/plans/)). Mandatory server validation, single-use/five-minute token semantics and hostname/action binding are documented ([validation](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/)). The new candidate adapter fails closed without configuration. Actual browser widget/provider calls, bot classification, secure public session issuance and trusted platform IP extraction are **NOT TESTED**. The local preview's synthetic issuer must never be used as production anti-bot protection. No purchased CAPTCHA or provider account was added.

FCM is listed as a no-cost service ([Firebase pricing](https://firebase.google.com/pricing)); HTTP v1 requires trusted server OAuth authorization ([sending](https://firebase.google.com/docs/cloud-messaging/send/v1-api)). A Worker-only sender is a possible design, not a verified free implementation. Sender/key custody, token refresh/signing CPU, outbox D1 writes/retries, token authorization and real delivery remain missing costs/gates. No Cloud Functions/Blaze dependency, service-account key or sender was enabled. Foreground/manual reconciliation remains the supported local fallback.

The **450,000,000-byte cutoff is unchanged**. Owner-confirmed archive cleanup removes only verified eligible terminal payloads and expired temporary metadata; active/uncollected/unresolved orders and uncertain bill claims remain protected. Metadata index writes and cleanup are included in local measurements. Failed/rolled-back or interrupted work can have provider costs not established by local counters; those are unknown, not assumed free. No unlimited storage, guaranteed ₹0 throughput or guaranteed 500/day headroom is claimed.

### Final Phase 4F measurements

**Three of six workloads complete. Both 500/day attempts and the 1,000 stress attempt fail local transport. No final 500/day free-capacity acceptance is earned.** Detailed commands, partial DB inspection and raw results are in the Phase 4F report and `phase4f-capacity-analysis.json`. Existing larger historical profiles below cannot substitute for current-schema failures.

| Completed local cohort | API + source archive reads | API + source archive writes | Worker requests | Write envelope including known setup + modeled freshness | Hot / post-archive bytes | Max SQL |
| --- | ---: | ---: | ---: | ---: | --- | ---: |
| 200 / 5 lines / 20 customers / five devices | 804,988 | 21,386 | 8,936 | 27,986 | 2,383,872 / 2,064,384 | 46 |
| 300 / 5 lines / 20 customers / five devices | 1,156,337 | 30,095 | 10,819 | 36,695 | 2,957,312 / 2,465,792 | 46 |
| 400 / 20 lines / 20 customers / five devices | 1,514,753 | 38,783 | 12,699 | 45,383 | 5,709,824 / 3,362,816 | 46 |

Each completes catalog publication/browsing, signed foreground/hint reconciliation, worker/order changes, claims/completion recovery, auth failures and verified encrypted archive/restore/eligible cleanup. API/archive totals include measured security and index writes. Envelope adds 6,360 known setup writes and **240 modeled legacy hourly-freshness writes**, not an implemented production identity synchronizer. Known catalog seed reads are zero; unmetered foundation/provisioning costs, final Version 1 authorization, real verification/sender/outbox and account sharing remain additional uncertainties. Server/client completed-response totals match and no lost submission attempt was recorded in these three completed cases. Failed-run totals are unavailable; nothing is assumed zero-cost.

| Conditional allocated-storage cohort model | 30 days | 60 days | 90 days |
| --- | ---: | ---: | ---: |
| 200/day | 24,870,912 | 48,463,872 | 72,056,832 |
| 300/day | 36,913,152 | 72,548,352 | 108,183,552 |
| 400/day, 20-line | 63,823,872 | 126,369,792 | 188,915,712 |
| 500/day and 1,000 stress | UNVERIFIED | UNVERIFIED | UNVERIFIED |

These byte models repeat measured local cohorts with 85% resolved/collected/owner-archived orders, 10% NEW and 5% unresolved cancellations retained every day. No physical 90-day/provider run or hosted cleanup is verified. Actual 400-case protected 20-line documents average 5,981.2 bytes: if all orders remain unresolved, 500/day for 90 days is **269,154,000 document bytes**, while 1,000/day is **538,308,000**, before other storage. A measured maximum-input adjusted active document is 69,821 bytes; 500 such protected orders/day over 90 days is **3,141,945,000 document bytes**. A free 500 MB database cannot retain unbounded protected/large-input backlog. The operational cutoff must pause admission rather than discard records or enable charges.

Local scenario CPU is aggregate Node/workerd process time, not hosted per-invocation CPU: 200 case 41,109/91,984 ms; 300 case 51,281/112,797 ms; 400 case 67,734/168,734 ms. Real Turnstile/provider CPU, FCM token signing/delivery/outbox, final Version 1 device authorization and account eligibility are unverified. Secure free push remains unsupported for accepted integration, with foreground/manual fallback. No card, upgrade, paid fallback, extra quota-avoidance account, production service or deployment was activated.

> **Current Version 1 owner decision — 19 October 2026:** See [WEBSITE_ARCHITECTURE_V1.md](WEBSITE_ARCHITECTURE_V1.md). Normal target is 200–500 orders/day; sustained acceptance target 500/day; 1,000/day remains stress only. Preserve existing POS login; separate worker cloud login is out of scope. Earlier individual sign-in proposals below are historical. D1 remains the online order authority, with no full-order Firebase mirror. Production authorization/sender/capacity acceptance remains blocked; no security checks are relaxed.

## Phase 4D + 4E local re-verification — 19 October 2026

Final local security schema adds owner-reviewed installation/account bindings and an explicit trusted shop tag on replay nonces. No extra nonce index is introduced without measuring write amplification. Protected mutation guards also pin person/worker identity. Existing 450,000,000-byte admission cutoff, protected-order retention and stock-independent checkout remain unchanged. All cloud accounts/services/payments/deployment remain unconnected.

Official publications rechecked today: D1 Free **5M rows read/100k rows written per account/day** ([pricing](https://developers.cloudflare.com/d1/platform/pricing/)); **500 MB/database, 5 GB/account and 50 queries/invocation** ([limits](https://developers.cloudflare.com/d1/platform/limits/)); Workers Free **100k requests/day and 10 ms HTTP CPU** ([limits](https://developers.cloudflare.com/workers/platform/limits/)). These are published limits, not verified account/commercial/no-card eligibility or tested provider capacity.

Eight fresh 1,000-order signed workloads completed (final verified tables below), with five/twenty lines, twenty/one-hundred customer concurrency and five/ten authorized devices. They include approved account bindings, catalog/search inputs, polling, adjustments, claims/completion retries, archive/cleanup and protected backlogs. Private in-workerd counters record completed API work before a loopback response can be lost; direct source archive SQL is separately metered. The counters are volatile local evidence and do not prove provider quota accounting or work interrupted before completion.

The hosted-compatible tenant snapshot has actual owner authentication/read/write overhead, but is deliberately bounded and has no hosted cleanup/restore. A large security/audit history can exceed its 200-row/table bound; it fails closed. Natural snapshots after polling may succeed because the next signed mutation prunes already expired nonce metadata. The initial workload harness incorrectly required 409 even then; its failed evidence is retained, with a corrected complete-export-or-bound-rejection assertion. An oversized non-expiring audit history independently verifies fail-closed behavior without deletion. Consistent pagination/streaming remains a production capacity gate.

Phase 4C's **642,401,047-byte day-90 document-only lower bound** for 1,000 protected 20-line orders/day remains a labeled prior measured-document model, before indexes/events/security. The new account table cannot make an unlimited protected backlog fit. No guaranteed ₹0 throughput/retention, independent media, provider CPU/exhaustion or account headroom is claimed. The operational alternatives/tradeoffs are in WEBSITE_RELEASE_BLOCKERS.md; none changes the current admission/retention policy automatically.

Actual local HTTPS is blocked by Avast's substituted untrusted certificate. No TLS/security bypass, paid fallback or additional-account workaround was used. Final verified capacity/history values are recorded in the closeout section below.

---

Updated through 18 October 2026, Asia/Kolkata. **Final-schema local capacity proof executed; production ₹0/capacity remains unverified.** Local results are evidence of a prototype, not account eligibility or Cloudflare production behavior. No account, deployment, service subscription, payment method, Firebase Blaze upgrade or FCM sender was activated.

## Published limits rechecked from official sources

| Component | Published free boundary | Evidence |
| --- | --- | --- |
| Pages static hosting | Static requests are free/unlimited when no Function runs; a provider subdomain avoids a purchased domain. | [Pages pricing](https://developers.cloudflare.com/pages/functions/pricing/), [Pages limits](https://developers.cloudflare.com/pages/platform/limits/) |
| Pages builds | 500 builds/month on Free; one concurrent build. | [Pages limits](https://developers.cloudflare.com/pages/platform/limits/) |
| Workers / Pages Functions | Shared 100,000 requests/day per account; Free HTTP CPU 10 ms/request; Free Cron CPU also 10 ms/invocation. | [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [pricing](https://developers.cloudflare.com/workers/platform/pricing/) |
| D1 | 5 million rows read/day, 100,000 rows written/day, **500 MB per Free database**, 5 GB total account storage; 10 Free databases. Tables and indexes consume storage; index writes count. | [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [limits](https://developers.cloudflare.com/d1/platform/limits/) |
| D1 per-request work | Free limit is 50 queries/invocation. Bound line counts and batches; do not assume arbitrarily large carts. | [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) |
| Quota reset/failure | D1 daily limits reset 00:00 UTC (05:30 India time). Reaching free read/write/storage limits causes query failures; it does not justify silently upgrading. | [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) |

Commercial-use assessment: the [Cloudflare self-serve agreement](https://www.cloudflare.com/terms/) covers organizations as well as individuals and contains a free-services section. We did not identify a personal/noncommercial-only limitation in the reviewed general agreement. This is a documentation inference, not a legal/account-eligibility certification; applicable service-specific terms, actual Free entitlement and any account-specific payment requirement remain unverified because no account setup is authorized here. Free service can be changed or withdrawn. No perpetual-free or uptime guarantee is claimed.

Published signup guidance says Workers can be started free without a credit card: [Cloudflare Workers](https://www.cloudflare.com/products/workers/), [official account prerequisite](https://developers.cloudflare.com/labs/workers). This verifies the published statement, not a signup or the shop's account status. No payment method was entered.

No paid domain, images/image hosting, OTP, SMS, payment gateway, R2, Firebase Storage, Functions, Blaze or paid notification API is a dependency of the local proof. Existing Firebase identity/sync quotas are a separate shop workload; they were not accessed or consumed by this backend proof. Do not create extra accounts to avoid quotas.

## Measurement method

Pinned Miniflare 4.20260730.0 executes the actual local workerd D1 binding. Each case uses 3,201 public-safe synthetic products/variants and 1,000 submitted orders, with 5 or 20 lines and 20 or 100 concurrent customer tasks. The server never reads or reserves stock. Orders use a bounded JSON item snapshot plus indexed order/event/admission tables; this is not a one-write-per-order estimate.

Each case includes 100 duplicate submissions, 50 lost-response recovery checks, 100 customer edits, 50 pre-acceptance cancellations, 950 accept/prepare/ready/billing-claim/completion workflows, 100 repeated completions, and three authorized devices catching up through cursor pages with deliberate repeated pages. The local completion calls model protocol traffic; they are not 950 real POS bills or an estimate of the existing Firebase bill-sync cost.

Per-response `rows_read`/`rows_written` accumulate native local D1 metadata, including measured indexes. Schema/initial catalog setup is recorded separately; the report also models full daily catalog republishing. D1 `size_after` measures logical database size. SQLite page-count pragmas are restricted by D1, so the initial harness was corrected to use its supported metadata rather than substituting file sizes. Measurement fixture queries are local tooling overhead, not API traffic.

Latency is host wall time. CPU captures the Node driver and only its workerd child process; it includes local database/runtime costs and is **not** Workers request CPU. Native build activity on this shared Windows computer can affect wall time. No local value proves a real request remains under the Free 10 ms CPU bound. Real authentication/key verification, cold starts, OAuth/FCM, edge rate limiting and network latency are not in the fixture adapter.

## Measured local results

The first completed case (5 lines, 20 concurrent customers) passed all identity/status/count assertions:

- 1,000 unique orders: 950 completed, 50 cancelled; 950 distinct server bill identities.
- 6,352 API requests; 83,653 rows read; 42,351 rows written.
- Initial full catalog seed: 6,402 rows written, recorded separately.
- Database grew 5,283,840 bytes for the modeled day.
- Elapsed 221.909 seconds; request p95 wall time 1,478.253 ms; sync-page p95 431.404 ms.
- Local Node CPU 36,968 ms; local workerd CPU 93,406.25 ms. These are aggregate process measurements, not a Cloudflare CPU-quota pass/fail.

All four cases and the indexed/unindexed probe completed successfully. Each row below is a separate synthetic 1,000-order day, not a claim that all 4,000 fit one real account-day. All cases produced 1,000 unique orders, 950 unique server bill identities, 950 completed orders and 50 cancellations.

| Lines/order | Concurrent customers | API requests | D1 rows read | D1 rows written | Storage growth bytes | Wall seconds | Request p95 ms | Sync page p95 ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 6,352 | 83,653 | 42,351 | 5,283,840 | 221.909 | 1,478.253 | 431.404 |
| 5 | 100 | 6,352 | 83,653 | 42,351 | 5,279,744 | 192.653 | 9,712.331 | 23.447 |
| 20 | 20 | 6,352 | 100,153 | 42,351 | 9,584,640 | 124.322 | 761.114 | 44.696 |
| 20 | 100 | 6,352 | 100,153 | 42,351 | 9,572,352 | 118.420 | 4,202.393 | 42.652 |

Local aggregate Node/workerd CPU respectively: 36,968/93,406.25 ms; 26,751/68,156.25 ms; 22,828/75,062.5 ms; 22,938/73,843.75 ms. The worst observed request wall time was 21,090.286 ms. Host load differed between cases; these numbers do not establish production latency or mean that larger orders are faster. Sync figures measure page catch-up fetch time after the workload, not guaranteed real-time push or background notification delay.

The explicit native D1 index probe recorded 1,000 rows written for 1,000 plain inserts versus 2,000 with a secondary index; updating the indexed column likewise cost 1,000 versus 2,000. EXPLAIN QUERY PLAN changed from SCAN to indexed SEARCH. Thus the workload's write count is backed by actual local index accounting. Evidence: `tools/website-proof/evidence/capacity-results.json`, `capacity.log`, `index-overhead.json`. The generated text-only catalog gzip is 30,414 bytes.

## Usage and retention model

The first case's measured seven-page catalog read costs 3,201 local rows. Scaling that read to 1,000 separate customer downloads yields 3,281,452 total daily rows and 13,345 API requests for that case before additional real-world activity. This scaling is a model; the harness did not issue 1,000 whole-catalog downloads.

Serving a versioned compressed public catalog through static Pages assets is a candidate to reduce browsing database reads. The synthetic gzip artifact is generated locally; Pages routing, caching and deployment are not tested. Dynamic canonical product validation still runs on submission and rejects unpublished products.

Add at least 3,000 foreground polls/day for five devices × ten hours × one poll/minute; include actual empty-page read costs, authentication refresh/membership reads, pushes, retries, cleanup, backups and other account applications before deciding headroom. Full daily catalog republishing adds the recorded setup writes. Keep existing POS Firebase sync separate but include it in its own account budget.

Including a full catalog republish gives **48,753 modeled writes/day**, 48.753% of the published 100,000 allowance before other activity/cleanup. Direct catalog downloads by 1,000 customers give 3,281,452–3,297,952 modeled reads/day and 13,345 requests/day; adding the example polling gives 16,345 requests/day. Authentication, denial/abuse traffic, retention work, export and other applications are additional costs. These models are not an account quota measurement or a safe-headroom signoff.

Five-line linear storage projections are 159.43–159.55 MB at 30 days and 476.21–476.58 MB at 90 days. Twenty-line projections are **288.21–288.58 MB at 30 days** and **862.55–863.65 MB at 90 days**, exceeding the free per-database limit. MB here uses a conservative decimal-byte budget. These are growth projections, not retention simulations; maximum-length notes and real names/sizes may cost more than these synthetic records. Even 30 days is not guaranteed for worst-case payloads.

Active uncollected orders must not expire automatically; indefinite uncollected retention can exhaust storage. The owner must choose a policy and verify recoverable exports before cleanup; no order cleanup was enabled here. A fixed promise to retain every 20-line order for 90 days at 1,000/day cannot fit this measured schema's 500 MB free database. Report this constraint and revise approved retention/storage design without paid services; do not silently upgrade or shard accounts to evade quotas.

## Fail-safe and remaining payment/capacity risks

Local tests verify atomic admission rejection, persistence-failure injection, no false confirmation/order number, frozen cart/retry identity, and independent billing proof. Real provider account exhaustion cannot be induced without a deployment and remains untested. On real 429/5xx/lost response, preserve the original submission and recover it; never invent success, deduct stock, switch accounts or activate a paid fallback.

Actual account commercial/no-card eligibility, combined daily budgets, cold/worst-case request CPU, real JWT/membership/enrollment security, live notification sending, production quota failure, retention/export and performance on real customer networks remain launch blockers. **Safe production headroom is unverified.** If any required feature exceeds Free allowances, keep it disabled and report the blocker; do not recommend or activate a paid upgrade under this scope.


## Phase 3A update — archival/storage limit remains a production blocker

17 October 2026. This section supersedes the earlier “no cleanup proof” status; Phase 2 figures above remain historical measurements of the pre-archival/pre-event-index schema. Nothing was deployed or enabled on a real provider account.

Official documentation was rechecked during Phase 3A: [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) still publish **500 MB per Free database, 5 GB account total, ten Free databases and 50 queries per Worker invocation**. [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) publishes **5 million rows read/day and 100,000 rows written/day**, shared across the account, including index writes. These are published limits, not tested account entitlement. No additional accounts/databases were created to bypass them. Existing commercial/no-card/account/CPU uncertainties remain unchanged.

The finite design uses existing owner-controlled local disk, encrypted versioned archives, SHA-256 verification, a matching second copy, isolated native local D1 restoration, explicit collection/resolution/final-bill attestations and guarded owner cleanup. Active/uncollected/unresolved orders are never automatically deleted. Original order/submission/bill identities and a terminal synchronization checkpoint stay online; full documents/events remain in the verified archive. See [retention design](WEBSITE_RETENTION_DESIGN.md). The synthetic second copy is another file on the same disk; independent physical recovery and available production disk space are not proven. Without already-owned adequate recoverable storage, production cleanup stays disabled.

A completed three-day local workload tested **3,000 orders × 20 lines**, 3,201 catalog variants, 6 events/order, abuse counters and 1,900 verified compactions/restores. Fifty orders/day remain uncollected in full. Allocated bytes: baseline 1,040,384; day 1 hot 10,981,376; day 1 after cleanup 6,144,000; day 2 hot 16,007,168; after cleanup 11,272,192; day 3 hot 20,983,808. Native local allocation/reuse is measured; Cloudflare production reclamation was not tested.

Across the three days plus seed/export/monitoring/cleanup, source D1 recorded **156,397 reads and 135,607 writes**, not one account-day. Insert subset: 23,997 reads/114,003 writes. First cleanup added 47,950 reads/7,602 writes; second added 63,949 reads/7,600 writes. Restore databases are disposable local verification and excluded from source D1 totals. Source monitoring in this workload includes COUNT/SUM diagnostics, so use the bounded one-row owner endpoint for regular size checks. Local Node driver CPU was 25,202 ms; elapsed 156.446 seconds; these are not Cloudflare invocation CPU measurements.

The initial archive implementation scanned unrelated events, costing **11,336,652 source reads over the three-day harness**. The new `events(order_id,seq)` index fixes that measured scan; the final indexed run uses 156,397 reads. This adds storage/index-write cost. Existing Phase 2 API workload numbers must not be presented as unchanged final-schema account measurements. A transparent model adds about 5,900 writes/day for the new event index to the earlier 42,351 API writes, about 7,600 cleanup writes and 6,402 full catalog republish writes: **62,253 modeled writes/day** before production authentication, notifications, retries, other apps and maximum-text changes. The full API load harness was not rerun on this final schema; this combined figure is a model, not tested daily headroom. Local static catalog serving avoids per-customer database catalog reads, but Pages caching/routing/account integration is untested.

Maximum measured post-cleanup hot-to-hot growth is **5,025,792 bytes/day**. A conservative linear 90-day model is **458,276,864 bytes**, versus the previous approximately 863 MB unarchived model. The archive model is **111,908,700 encrypted bytes per copy at 90 days** for synthetic payloads; two copies require about 224 MB, plus working/restore space and safety margin. Larger customer text/maximum notes, disk failures and delayed owner cleanup can exceed those values.

**Exact remaining blocker:** 458,276,864 bytes leaves only 41,723,136 bytes below the nominal 500 MB cap and already exceeds the **450,000,000-byte (90%) new-order safety cutoff** by 8,276,864 bytes. The model crosses that cutoff around day 89. Thus the stated workload does not establish safe uninterrupted 90-day free headroom. Active backlog and retained idempotency/bill/checkpoint records grow without bound. Production capacity/retention implementation stops here; no guarantee of unlimited storage or permanent ₹0 capacity is made.

Warnings at 65%, critical at 80%, and stop-new-order admission at 90% are locally tested. Per-database enforcement is wired into new submissions; known submission recovery remains available. The policy function also accepts account totals, but real account telemetry/enforcement, owner alerts, disk monitoring, independent backups and native checkpoint acknowledgement are unimplemented release gates. Actual provider exhaustion and the 10 ms Workers Free HTTP CPU budget remain unverified. Failures preserve the original cart/identity and never invent confirmation, enable payment or block normal offline POS billing. No paid fallback is present.

Full evidence and exact commands: [Phase 3A results](WEBSITE_PHASE3A_TEST_RESULTS.md), `tools/website-proof/evidence/phase3a-retention-capacity.json`. The customer website can be reviewed locally independently of this unresolved production-capacity gate.

## Phase 3B update — 17 October 2026

Phase 3B did not activate any provider, deploy, enter payment details or create extra accounts. It exercised only the existing isolated local D1-compatible proof. Published quotas and prior measurements above are unchanged, but they do not certify the new native order metadata or Phase 3B event/audit synchronization overhead.

The prior day-90 model remains **458,276,864 bytes**, already **8,276,864 bytes above** the 450,000,000-byte operational admission cutoff. Phase 3B added local/native protocol metadata and adjustment/audit records whose production D1 storage/write effects were not measured in a final-schema 1,000-order workload. Therefore the prior figure must not be treated as a safe upper bound; headroom is less certain, not improved. Warning/stop behavior remains local proof only. No automatic cleanup, active-order deletion, account sharding or paid fallback was introduced.

Verified local results: 37/37 backend tests, 10/10 native integration, 1/1 Compose worker flow, final Android app/test APK/JVM test-source compilation. The 1,000-order Phase 2/3A loads are prior-schema synthetic evidence, not Phase 3B capacity acceptance. Real Cloudflare account-level quotas, CPU, disk growth, quota exhaustion and commercial/no-card eligibility remain untested. Do not enable production order intake until a reviewed retention/storage budget proves headroom and an owner-controlled independent archive/recovery process is available without paid services.

## Phase 4A update — 18 October 2026

This section supersedes the provisional Phase 3A/3B capacity estimates above. It uses the final Phase 3B D1 schema, including billing claims, completion links, customer-confirmed adjustment snapshots, event indexes and the current retention metadata. It does not replace prior evidence, which remains historical.

Cloudflare's published documentation currently lists D1 Free at 5 million rows read/day, 100,000 rows written/day, 500 MB per database, 5 GB total account storage, 10 Free databases and 50 queries per Worker invocation ([D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/)). Workers Free lists 100,000 requests/day and 10 ms CPU per HTTP invocation ([Workers limits](https://developers.cloudflare.com/workers/platform/limits/)). Those are published quotas only. Shop-account eligibility, actual quota usage and provider CPU have not been measured.

The final local API tests used 3,226 synthetic catalog variants, 1,000 orders, 5/20 lines, 20/100 concurrent customers, actual owner publication, 20/100 concurrent dynamic catalog visitors, edits, cancellations, 85 confirmed adjustments, claims, completion/recovery and multi-device sync. The 20-line/100-concurrent case also executed 3,000 refreshes and archived 850 fully attested orders. Across the four workload profiles, measured Worker API use was 6,084–9,644 requests, 131,849–410,704 D1 rows read and 48,497 rows written; the 1,000 dynamic-catalog-visitor read model was 3,293,329–3,374,549 rows and request model 12,944–15,944 requests. The archive run added 60,445 reads and 7,973 writes, for 56,470 measured/modelled writes including cleanup. These scenarios are separate synthetic days. The visitor figures are scaled from actual 20/100 visitor runs, and do not claim a real static Pages or account workload.

One verified, lossless metadata optimization was applied to the local proof schema. The abuse limiter now reuses one HMAC bucket row across time windows and resets the count when a new window begins; it keeps the current three-per-window and daily gates. Expired abuse buckets and previous-day admission totals are pruned only as part of explicit verified owner archive cleanup. For 1,000 repeat buckets across ten windows, local D1 allocation measured 843,776 bytes/10,000 rows/20,000 writes before and 155,648 bytes/1,000 rows/11,000 writes after. This isolated fixture reduced allocated bytes 81.6%, rows 90% and writes 45% while adding 9,000 conflict reads. A separate cleanup fixture removed 1,500 expired limiter buckets and one expired day; it retained the current bucket. This is a local synthetic microbenchmark, not a full-database or production guarantee.

In the full 20-line/100-concurrent final-schema run, allocated D1 size measured 11,931,648 bytes before archive and 6,361,088 bytes after the encrypted export, independent-file copy fixture, isolated restore/comparison and owner-confirmed compaction. All 1,000 online order identities remained; 850 kept archive references/bill IDs, while 50 unresolved cancellations and 100 uncollected orders retained full documents and event history. The 450,000,000-byte admission cutoff was not bypassed.

The actual cohort's 30/60/90-day model is backlog-sensitive. If every completed order can be attested and archived daily, the model gives 386,326,528 bytes at day 90. With 50 new protected orders/day it gives 415,817,728 bytes, 34,182,272 below the operational cutoff. With 250 protected orders/day it gives 533,782,528 bytes, above even the nominal 500 MB per-database limit. With no terminal archive/cleanup, it gives 976,150,528 bytes. These are 90-day linear projections from a one-day cohort, not a 90-day physical run. Protected orders are never removed automatically.

**Remaining zero-cost blocker:** storage fits the free database only under timely owner-attested archive cleanup and a sufficiently small backlog. The real backlog rate, independently recoverable owner disk, production D1 allocation/reclamation and daily account totals are unknown. At 250 unresolved/uncollected orders/day the model fails the 450 MB safety gate; without cleanup it exceeds the 500 MB published cap. Daily read/write/request quotas appear below the published limits for this single modeled app, but leave unverified account headroom and cannot prove the 10 ms Worker CPU boundary. Do not describe 1,000 orders/day as production-safe or guaranteed ₹0.

The complete measurements, commands, and model assumptions are in [Phase 4A test results](WEBSITE_PHASE4A_TEST_RESULTS.md) and `tools/website-proof/evidence/phase4a-capacity-results.json`. No account, payment method, cloud service, production database or Firebase connection was used; no deployment or paid fallback occurred.

## Phase 4B authentication overhead — 18 October 2026

The signed local identity/device protocol adds `auth_users`, `auth_memberships`, `auth_devices`, `auth_nonces`, `auth_audit` and the atomic authorization assertion table. Therefore Phase 4A storage/write projections are historical Phase 3B-schema measurements and cannot establish final security-schema headroom. No provider quota, free-account eligibility, key-fetch CPU or production token verification was measured in this phase. The published-limit references above remain provider documentation, not an account entitlement claim.

`node security-overhead.mjs` measured 100 sequential empty incremental synchronization requests through actual native local D1. Fixture mode: **100 rows read, 0 written**; signed mode: **800 read, 500 written**. Additional cost: **7 reads and 5 writes/request**, including nonce indexes and atomic assertion rows. Allocation: fixture **94,208 bytes** before/after; signed **159,744 before, 180,224 after**. Local wall p50/p95: signed **51.47/503.91 ms**, fixture **28.90/100.81 ms**, on a loaded local build host. These are wall times, not Cloudflare CPU measurements. A test-only expiration fixture then verified normal signed cleanup reduced nonce rows from 100 to 1; no orders or security audit rows were removed. Evidence: `tools/website-proof/evidence/phase4b-security-overhead.json` and `phase4b-security-overhead-rerun.log`.

For illustration only, 10 devices polling every 30 seconds for 8 hours add 9,600 requests/day and at least **48,000 auth writes plus 67,200 extra reads/day** at the measured empty-sync rate. Adding those writes to the earlier 56,470 order/archive-day writes gives **104,470**, exceeding the published 100,000 Free account-wide writes/day before protected mutation guards, other apps and authentication maintenance. This is a combined model, not a newly executed full signed-order day; it is a concrete unsafe polling configuration, not proof that every polling policy fails. A revised bounded polling/reconciliation policy and signed final-schema workload must be measured before production. Do not activate the example configuration or describe 1,000 orders/day as safe under current quotas.

Security audit/device retention, trusted membership/revocation freshness updates, production public-key cache refresh, owner-approved recovery and encrypted security archive/restore add unmeasured costs. The existing order archive does not yet cover the new security tables. Active/uncollected/unresolved orders and stable billing identities remain protected; no automatic record deletion, account sharding or paid fallback was added. The **450,000,000-byte operational cutoff is unchanged**. Synthetic signing keys need no paid provider; this does not prove real Firebase/account/commercial/no-card eligibility. No account was accessed, service activated, payment information entered or website deployed.

The 5-write microbenchmark is a fresh nonce window, not a steady-state upper bound. Subsequent expiration deletes add row/index writes, and protected mutations add further atomic authorization assertions. The separate expiry simulation is verified but excluded from the 100-request totals. A mature sustained-request budget must include those cleanup writes and potential malicious authorized-device traffic; the illustrative 104,470 total above is a lower-bound warning, not a safe capacity estimate.

## Phase 4C verified optimization — 19 October 2026

The historical Phase 4B polling write warning above is superseded for the three explicitly read-only GET routes. Signature/token binding, freshness and current server membership/device/revocation checks remain active on each invocation; bounded read replay within the same 60-second window is explicitly allowed. No client-side authorization cache exists. One hundred real local D1 signed empty syncs now measured **600 reads, zero writes, zero nonce rows and unchanged 204,800 bytes**. Fixture mode remains 100 reads/zero writes. Thus 9,600 empty polls add zero proof writes and approximately 57,600 total D1 reads at that measured rate, rather than 48,000 fresh-window proof writes. Nonempty pages cost more reads and are measured separately in the full workloads.

Successful mutation proofs now commit atomically with the unchanged business statements in one guard, saving two guard writes per ordinary successful mutation. Idempotent POSTs and valid failed mutations still consume durable nonces; failures cannot become replayable after their business preconditions change. A new settings insert measured seven D1 writes: two guard, three nonce/index and two business/primary-index, versus the prior nine. The initial assertion missed one index write, was independently checked against a native D1 insert and corrected; failed evidence is retained. Nonce expiry deletion, user/binding freshness maintenance and security archives must still be included in the final daily budget.

The signed schema adds individual identity bindings, unique worker links, bootstrap grants and owner claim-review evidence alongside all prior security/order/catalog/index tables. Security restore always quarantines imported authority. There is no automatic security-audit deletion or protected-order cleanup. The 450,000,000-byte cutoff is unchanged. Local source/Android checks preserve existing billing, stock and financial schemas.

Rechecked official published limits: **500 MB/database, 5 GB/account, 50 queries/invocation** ([D1 limits](https://developers.cloudflare.com/d1/platform/limits/)); **5M reads/100k writes per account/day** ([D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/)); **100k Worker requests/day and 10 ms CPU per HTTP invocation** ([Workers limits](https://developers.cloudflare.com/workers/platform/limits/)). These are provider publications, not account eligibility, actual account usage or CPU measurements. The local request wrapper counts every SQL statement, including authorization and error-proof finalization; the largest successful signed publication uses 46. No provider request/CPU execution has been tested.

Eight final signed profiles (5/20 lines × 20/100 concurrent customers × 5/10 authorized devices) are in progress. Each includes 3,226 catalog variants, real signed worker/enrollment/rotation/revocation operations, retries, invalid authentication, incremental/recovery reads, 4,800/9,600 compressed foreground polls, 850 owner-attested order exports/restores and preservation of 150 protected orders. Owner cleanup and security-table snapshots use actual local D1 metadata, not one-write-per-order assumptions. Tail nonce expiry deletion is measured as a clearly labeled future-window fixture after API operations; it is included to avoid ignoring next-day cleanup costs. Hourly trusted freshness updates remain a separate model because the real identity synchronizer is disabled.

The first capacity run was memory-aborted; another did not execute because automatic approval review hit an account usage limit. The resumed run failed around 200 completions on local Miniflare loopback EADDRINUSE. Installed Miniflare sets connection reset for each dispatch. The optional local keep-alive fixture uses the same native workerd entry and original signed URL, caps sockets at 100, preserves requested concurrency and passes all 65 regressions. This repairs local transport only and cannot prove Cloudflare behavior. Full signed capacity totals and storage forecasts remain pending; **do not claim safe headroom, unlimited storage or guaranteed ₹0 production operation**. No service/account/payment/deployment/paid fallback was activated.

Additional verified local monitoring repair: repeated full-history cleanup summaries would cost 8.52M reads at a 30,000-order history (60,000 rows per summary × 142). Per-batch cleanup now uses the existing allocation-policy style of one-row native size probe; detailed summaries are explicit report operations. Matching 20-line/20-customer/ten-device tests measured **310,670 → 27,030 archive reads**, with **84,110 total writes unchanged** and every archive/restore/protected-order invariant retained. The final full backend passed **66/66**, 51.730 s. This fixes the measured repeated-summary component, not every future hosted archive, authentication, backlog, account or CPU cost. One final stress profile and complete growing-history comparisons remain pending.


## Phase 4C final signed-schema closeout — 19 October 2026

This section supersedes intermediate pending statements and earlier unsigned/storage-only capacity forecasts. All eight synthetic signed profiles completed. The table explicitly distinguishes old full-summary cleanup from repaired allocation monitoring; there is no claim of eight post-repair repeats. Lost requests have unknown D1 cost.

| Lines | Customers | Devices | Archive monitor | API writes | Archive writes | Measured total writes | With modeled freshness | Modeled reads / 1,000 catalog visitors | Lost submit attempts | Max SQL |
| ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 5 | LEGACY_FULL_SUMMARY | 74179 | 9731 | 83910 | 84150 | 3723561 | 0 | 46 |
| 5 | 20 | 10 | LEGACY_FULL_SUMMARY | 73954 | 10156 | 84110 | 84590 | 3816741 | 0 | 46 |
| 5 | 100 | 5 | LEGACY_FULL_SUMMARY | 73702 | 10208 | 83910 | 84150 | 3724038 | 0 | 46 |
| 5 | 100 | 10 | LEGACY_FULL_SUMMARY | 73974 | 10136 | 84110 | 84590 | 3816721 | 0 | 46 |
| 20 | 20 | 5 | LEGACY_FULL_SUMMARY | 74879 | 9031 | 83910 | 84150 | 3740636 | 0 | 46 |
| 20 | 20 | 10 | ALLOCATED_ONLY | 75020 | 9090 | 84110 | 84590 | 3549592 | 0 | 46 |
| 20 | 100 | 5 | ALLOCATED_ONLY | 74404 | 9506 | 83910 | 84150 | 3457253 | 2 | 46 |
| 20 | 100 | 10 | ALLOCATED_ONLY | 74617 | 9493 | 84110 | 84590 | 3549995 | 2 | 46 |


Actual successful-response/source-archive writes: **83,910–84,110**. Separate hourly trusted-freshness MODEL: **84,150–84,590**, with no real synchronizer. Measured fixture seed/provision/counter setup gives **90,270–90,495 known writes** including those setups, excluding unmetered signing-fixture foundations and unknown lost-request costs. Dynamic visitor models: **3,457,253–3,816,741 reads**, **18,052–23,184 API requests**, before unknown costs/hosted archive transport. Local aggregate CPU cannot prove the published 10 ms per-invocation limit. Maximum counted signed SQL is **46/50**.

Matching monitor repair: **310,670 → 27,030 archive reads**, **84,110 writes unchanged**. At 90,000 retained order rows the repaired monitor reads **one row**, instead of 180,000 for a full summary; 142 cleanup checks cost 142 monitoring reads instead of 25.56M. No export, restore, attestation, guard, identity or protected record was removed.

| Lines | Representative history days | Order identities | Full uncollected / unresolved cancelled | Allocated bytes | Legacy summary reads/check | Repaired allocation reads/check |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 30,000 | 3,000 / 1,500 | 41,058,304 | 60,000 | 1 |
| 5 | 60 | 60,000 | 6,000 / 3,000 | 81,010,688 | 120,000 | 1 |
| 5 | 90 | 90,000 | 9,000 / 4,500 | 121,196,544 | 180,000 | 1 |
| 20 | 30 | 30,000 | 3,000 / 1,500 | 68,546,560 | 60,000 | 1 |
| 20 | 60 | 60,000 | 6,000 / 3,000 | 136,261,632 | 120,000 | 1 |
| 20 | 90 | 90,000 | 9,000 / 4,500 | 203,829,248 | 180,000 | 1 |

Physical row-shape fixtures are not a 90-day API/audit/revocation replay, independent archive media or production allocation proof. The linear first-day models differ because of page reuse/high-water effects; earlier zero-backlog/per-order-savings formulas are rejected as confounded by nonce/admission cleanup. Document-only backlog lower bounds from verified 20-line archive samples:

| Protected full orders/day | Day 30 document-only MB | Day 60 MB | Day 90 MB |
| ---: | ---: | ---: | ---: |
| 0 | 6.35 | 12.69 | 19.04 |
| 50 | 16.73 | 33.47 | 50.20 |
| 150 | 37.51 | 75.03 | 112.54 |
| 250 | 58.29 | 116.58 | 174.88 |
| 500 | 110.24 | 220.48 | 330.72 |
| 1000 | 214.13 | 428.27 | 642.40 |

Strict production capacity remains UNVERIFIED. A 1,000/day backlog of the measured representative full 20-line document mix alone models 642,401,047 bytes at day 90, excluding indexes/events/catalog/security; it cannot fit the 500 MB/database free limit. The permitted 69,821-byte adjusted-order example would require 942,583,500 document bytes for 150 protected orders/day over 90 days. Protected orders cannot be automatically removed. The unchanged 450,000,000-byte operational cutoff must stop new admission rather than lose records or switch to a paid service.

Provider quotas remain published documentation, not tested account entitlements: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/). Daily limits are shared across the account. Actual free/commercial/no-card eligibility, other workloads, provider CPU/exhaustion, hosted tenant-safe archive authentication, identity feed and independent owner media remain unverified. No paid fallback, extra account, payment method, cloud activation or deployment was introduced.

Full measurements/CPU/latency/commands: [Phase 4C results](WEBSITE_PHASE4C_TEST_RESULTS.md), phase4c-capacity-analysis.json and phase4c-history-growth.json. **No guaranteed ₹0 operation at 1,000 orders/day, unlimited retention or extra progress credit. Stop after Phase 4C at 83.5%.**

<!-- PHASE4D_FINAL -->
## Phase 4D + 4E final measured local capacity

All eight profiles completed on current signed schema and allocation monitoring. 6 profiles predate the harness maintenance-sequencing repair: their final read-only archive POST followed simulated future cleanup. Their core order workflows precede that simulation, but they are not retroactively clock-consistent full replay tests. A separate current-time-only supplement verifies actual proof expiry, removes 6 expired proofs and records all additional D1 reads/writes while unchanged order/event/audit/claim/binding hashes prove no business-evidence changes. Raw profile JSON files and their SHA-256 hashes remain preserved. 2 profiles finish all signed APIs before future maintenance. Totals below include the separately measured supplement (including its verification reads), not a claim of eight post-repair repeats. | Lines | Customers | Devices | API R/W (workerd) | Archive R/W | Total writes | + modeled freshness | Modeled reads | Requests | Max SQL | Hot/archive bytes |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 5 | 261889/75995 | 28943/7975 | 83970 | 84210 | 3452312 | 18062 | 46 | 7057408/5210112 |
| 5 | 20 | 10 | 360451/76255 | 29043/7975 | 84230 | 84710 | 3550974 | 23204 | 46 | 7069696/5222400 |
| 5 | 100 | 5 | 519969/75995 | 28943/7975 | 83970 | 84210 | 3452312 | 18062 | 46 | 7036928/5214208 |
| 5 | 100 | 10 | 618531/76255 | 29043/7975 | 84230 | 84710 | 3550974 | 23204 | 46 | 7032832/5218304 |
| 20 | 20 | 5 | 279664/75995 | 28943/7975 | 83970 | 84210 | 3470087 | 18062 | 46 | 12349440/6483968 |
| 20 | 20 | 10 | 378226/76255 | 29043/7975 | 84230 | 84710 | 3568749 | 23204 | 46 | 12349440/6492160 |
| 20 | 100 | 5 | 542165/75995 | 28945/7975 | 83970 | 84210 | 3474510 | 18062 | 46 | 12365824/6483968 |
| 20 | 100 | 10 | 640727/76255 | 29045/7975 | 84230 | 84710 | 3573172 | 23204 | 46 | 12365824/6496256 |

Measured API plus source archive writes 83,970–84,230; with separate modeled trusted hourly freshness 84,210–84,710. Setup foundations, full hosted pagination/cleanup, real provider identity feed/CPU/account usage and independent media remain unverified. Volatile in-workerd counters reconcile completed lost local responses; provider/interrupted-isolate accounting is not inferred.

| Lines | Days | Retained identities | Protected NEW / unresolved cancelled | Allocated bytes | Allocation probe reads |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 30000 | 3000/1500 | 41115648 | 1 |
| 5 | 60 | 60000 | 6000/3000 | 81227776 | 1 |
| 5 | 90 | 90000 | 9000/4500 | 121475072 | 1 |
| 20 | 30 | 30000 | 3000/1500 | 68603904 | 1 |
| 20 | 60 | 60000 | 6000/3000 | 136278016 | 1 |
| 20 | 90 | 90000 | 9000/4500 | 203849728 | 1 |

History is actual allocated final-schema row-shape evidence, not a 90-day API replay or production allocation guarantee. The unchanged 450 MB cutoff and protected-backlog limits still prevent an unlimited 1,000/day promise. Full CPU/latency, 30/60/90 models, archived-copy sizes, known setup and loss deltas are in phase4d-capacity-analysis.json and WEBSITE_PHASE4D_4E_TEST_RESULTS.md. Read official-limit references above as published limits only. No paid fallback/account/deployment was activated; 83.5%, unchanged.

<!-- V1_FINAL -->
## Version 1 measured normal-target baseline

Normal target is now 200–500/day; 1,000/day remains historical stress. Published official free limits were rechecked without account access.

| Lines / concurrent / devices | Cycle | Orders | API requests | API reads | API writes | Conservative busy-day reads / writes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5/20/5 | 1 | 500 | 14065 | 1736902 | 34104 | 1903021/54793 |
| 5/20/5 | 2 | 500 | 14200 | 1766783 | 35798 | 1932902/56487 |
| 20/100/10 | 1 | 500 | 21760 | 1827892 | 34399 | 2314842/54617 |
| 20/100/10 | 2 | 500 | 22030 | 1886419 | 36499 | 2373369/56717 |

| Lines / concurrent / devices | Hot bytes | After verified archive bytes | Day 30 / 60 / 90 modeled bytes | Max SQL |
| --- | ---: | ---: | ---: | ---: |
| 5/20/5 | 6606848 | 5201920 | 60710912 / 120184832 / 179658752 | 46 |
| 20/100/10 | 12087296 | 6496256 | 79839232 / 158420992 / 237002752 | 46 |

These are compressed local cycles using the old individual-auth proof. The busy-day envelope conservatively includes all known archive/setup overhead but excludes final device authorization, secure FCM sender/outbox, interrupted-request unknown costs and other account usage. Sustained 500/day at ₹0 remains UNVERIFIED. Finite protected backlog, 450 MB cutoff, NAT limiter and archive/provider gates remain. No upgrade, paid fallback, account or deployment was activated. See WEBSITE_V1_VERIFICATION_RESULTS.md and evidence/v1-capacity-analysis.json.




Phase 4I capacity checkpoint: **300/5-line/20-customer/five-device PASS**, one full synthetic cycle, 300 unique submissions / 255 distinct completed bill identities / 45 protected records, max 48 API queries/invocation. Recurring API + source archive/cleanup: **1,166,502 reads, 35,867 writes, 10,364 API requests**; conservative all setup/final flush charge **49,007 writes**. Separate terminal restore verification: 8,515 local writes, not silently omitted from disaster-recovery planning. Post-final-delta allocation 3,780,608 bytes. All 145 observed transport failures recover with original identity; this does not repair raw transport failures. **200-order profile FAILS during sync**, despite 200 persisted identities and 170 completions, 708 transport errors / four exhausted recoveries; original remains untouched. Remaining profiles pending. **+0% → 83.5%; Phase 5 blocked.**

Phase 4I verified milestone: 400/20-line/20-customer/five-device PASS; 400 unique submissions, 340 completed bill identities and 60 protected records. Recurring API + source archive/cleanup: 1593445 reads, 47886 writes, 12216 API requests. Including setup/final flush: 63734 writes. Post-final-delta 5017600 bytes. Separate isolated recovery costs remain in phase4i-capacity-analysis.json. +0% = 83.5%; two-cycle 500 profiles, final regressions and production gates pending.

Phase 4I five-device 500-order checkpoint: cycle 1/2 verified, 500 unique orders / 425 completions / 75 protected records. API 44802 plus source archive/cleanup 14800 = 59602 recurring writes; 2029764 reads; 14067 API requests. Cycle 2 is required; this is not a 500-profile PASS or guaranteed free-plan capacity. +0% = 83.5%.

Phase 4I verified five-device 500-order milestone: PASS, two cycles on one database, 1,000 unique submissions / 850 completed bill identities / 150 protected orders. Recurring writes 59,602 and 67,069; conservative busy-day setup/final-flush envelope 84,331 versus earlier 107,824. Reads 2,029,764 and 2,815,127. 112 observed transport failures recover, no exhausted recovery or workload proof denial; max 48 API queries. Post-final-delta 10,219,520 bytes. Conditional 90-day allocation 404,189,184 bytes, about 100 days to the 450 MB operational cutoff under this short-run mix; not a retention guarantee. Isolated offline incremental/terminal restores perform 222,392 writes over the complete cohort and must not be treated as free hosted D1 imports. Ten-device/100-customer acceptance and final regressions remain pending. +0% = 83.5%; Phase 5 blocked.

Phase 4I final capacity checkpoint: all six profiles executed. 300 and 400 PASS; five-device500 PASS two cycles. 200 FAIL during sync. Ten-device500/100-customer FAIL in cycle1: 499 unique orders, 408 completions, one retained sticky claim; 1,776 transport failures /17 exhausted recoveries, max proof age22s, zero auth denials. Stress1000 FAIL: 999 unique orders, 809 completions, two sticky claims; 3,535 transport failures /41 exhausted recoveries, max age25s, zero auth denials. All failed databases/claims retained, inspection performed only on hash-verified copies, none replayed/reassigned. Final original-driver backend/browser/release verification still running. +0% =83.5%; Phase5 NOT recommended.

CORRECTION — Phase4I setup accounting: the earlier 84,331 five-device envelope includes API publication/revocation and archive/final-flush, but excludes6,502 measured private source seed/schema writes. All counters were retained. Fully charging every measured source setup write raises the two daily conservative envelopes to83,366 and90,833 (9,167 margin before unknowns), while recurring59,602/67,069 is unchanged. Whole-cohort measured source total150,435 writes. The offline restore222,392 writes are additional local recovery work and cannot be moved into hosted D1 within this budget. Earlier milestones remain historical, superseded by this correction. No production ₹0/headroom credit;83.5% unchanged.

Setup-label correction also applies to earlier300/400 milestones: full measured source-setup envelopes are55,509 and70,236 writes (previous49,007 and63,734 excluded6,502 private seed/schema writes). Recurring cycle measurements are unchanged; raw counters and previous statements are retained. See phase4i-setup-accounting-correction.json.

## Phase4I final measured cohorts

| Orders / lines / customers / devices | Result | Cycles | Recurring known reads/day | Writes/day | API requests/day | Final allocation |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 200/5/20/5 | FAIL | 0/1 | unverified | unverified | unverified | unverified |
| 300/5/20/5 | PASS | 1/1 | 1,166,502 | 35,867 | 10,364 | 3780608 |
| 400/20/20/5 | PASS | 1/1 | 1,593,445 | 47,886 | 12,216 | 5017600 |
| 500/20/20/5 | PASS | 2/2 | 2,029,764, 2,815,127 | 59,602, 67,069 | 14,067, 14,093 | 10219520 |
| 500/20/100/10 | FAIL | 0/2 | unverified | unverified | unverified | unverified |
| 1000/20/100/10 | FAIL | 0/1 | unverified | unverified | unverified | unverified |

Detailed source/offline recovery request/write attribution and finite storage projections: WEBSITE_PHASE4I_RESULTS.md and tools/website-proof/evidence/phase4i-capacity-analysis.json. Official source recheck: tools/website-proof/evidence/phase4i-provider-notes.md. No permanent ₹0 guarantee.

## Phase 4J final cost decision — local evidence only

Final transport/raw gates fail, so full capacity cohorts are not rerun or credited. Indexed archive verification alone reduces a 1,024-receipt fixture from 50,689 to 1,026 source reads and performs 0 writes; this is not a new 500/day envelope. No backend schema/index or permanent receipt retention was changed.

The corrected historical Phase 4I busy-day figure remains 90,833 writes, not 84,331. Only 9,167 writes remain before unknown provider/challenge/notification/failure/shared-account costs. A proposed 20% planning reserve leaves 80,000 writes/day; the historical measured charge exceeds it by 10,833. That reserve is a design sensitivity, not measured provider overhead or guaranteed safety. No safe sustainable Phase 4J production workload is established.

Historical conditional 30/60/90-day models 135,573,504/269,881,344/404,189,184 bytes are not final Phase 4J measurements. Growing protected backlog, permanent receipts, temporary archive peaks, hosted restore, real account eligibility and 10 ms Worker CPU remain unverified. Local aggregate CPU does not prove production per-request CPU. Published 500 MB/database,450,000,000-byte operational cutoff, 50 queries/invocation, 5M reads/100k writes/day and 100k Worker requests/day remain unchanged. No automatic cleanup/paid fallback or provider activation.

Official limits checked 22 October 2026: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/). See WEBSITE_PHASE4J_RESULTS.md for measurements, failures and explicit remaining gates.
