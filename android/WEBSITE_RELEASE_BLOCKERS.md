<!-- CATALOG_PUBLICATION_20261022 -->
## Current publication status — 22 October 2026

**Catalog published:** https://hardwareshoppos.pages.dev/ (1,478 owner-approved rows). **QR PNG/PDF generated**, catalog-only caption; actual HTTPS/file hashes/disabled APIs verified. Account/project/catalog/deployment approval is complete for this static release. No live ordering is active. Remaining blockers are the two backend transport failures, trusted production owner/device integration and sender, quota/headroom, safe once-only billing/recovery acceptance and physical phone/printer acceptance. Tests remain owner-deferred and progress stays 83.5%. See `WEBSITE_FINAL_ENGINEERING_RESULTS.md` for the single current release record; older sections below retain historical limitations.
<!-- END_CATALOG_PUBLICATION_20261022 -->

## Local catalog preparation update — 22 October 2026

Verified completion remains **83.5%**. A standalone, fresh local site repository now exists at `../HardwareShopPOS-Website-Public`; it contains no Android app, original POS Git history, remote, credentials, stock, supplier, purchase-price, or customer records. Its static candidate has 1,478 name/size/unit/selling-price rows grouped into 805 name families. Candidate SHA-256: `6e77ff97c28bee415c6d6b78e534ee50f698b83336cb3530516b1c8f76fff1c6`.

The candidate is projected only from `app/src/main/assets/final_catalog.json`: 240 missing/invalid-price rows, 37 priced rows marked `Needs Identification`, and 7 rows with unit-review notes were excluded. The three disputed prices have no accepted master price and are excluded; the 173 store-only unmatched variants were not merged. No price or unit was inferred. This is not a fresh export from the current live Room database, so it remains pending owner review.

The separate site-only project uses only four public product fields, a static catalog, search, grouped sizes/prices, responsive CSS, strict static headers and no order API. `npm test` passed **3/3** and `npm run build -- --preview` produced a 1,478-row `REVIEW_PREVIEW` artifact. The ordinary build correctly refuses to create a deployable artifact until `public/catalog-approval.json` approves the exact catalog hash. Browser visual/mobile and physical-device checks were not executed; this host’s in-app browser inventory is disabled and Chrome headless exited before rendering.

No Cloudflare account or project was accessed; no GitHub remote was added or push made. There is no deployed URL or QR. The two backend transport failures, production owner/device trust, secure FCM sender, current-room price parity, 500/day Free-plan headroom, and native/physical POS acceptance remain open. Live ordering remains disabled.

<!-- FINAL_PUBLISHING_PREP -->
## Latest release decision — 22 October 2026

**83.5% verified; release and Phase 5 remain blocked.** Current-source Android JVM/build checks pass with a scoped Java 17 workaround, and the local 10-file maintenance package tests pass. No Cloudflare project/account was connected and no deployment occurred.

### Remaining blockers

- **GitHub state:** pre-recheck checkpoint `2180828f32a12150eeaf6c054dca727cd33d2aec` was clean but is not pushed; the working tree now contains only the five updated release/progress reports. The commit history includes purchase-price/stock data, which the current release instruction forbids publishing; GitHub authentication also failed previously. Do not connect Pages to this branch.
- **Cloudflare approval:** exact account, available Pages project, public page contents, Free-plan/payment status and exact resulting URL are not approved. The local maintenance artifact has zero products and blank shop contact/hours.
- **Backend reliability:** Phase 4J full backend 104/106 executed passed, 2 failed, 1 platform skip. Concurrent ordering and signed reads still fail. Live order submission remains disabled.
- **Android source verification:** current-source `:app:testDebugUnitTest` passes 312/312; debug, release and androidTest APK assembly pass using Gradle 8.7, Temurin 17 and the scoped Unix-domain socket directory. The release APK is unsigned; full DEX guard review remains open.
- **Native acceptance:** no device is attached; the API 36 AVD is stopped, configured for 2,048 MB, and host free RAM is 741 MiB. Zero native tests ran in this recheck. Existing 15-second cold navigation/keyboard limits remain open.
- **Catalog approval:** assets are not reconciled: only 33 name/size/unit matches, 3 selling-price conflicts, 173 unmatched store rows, and 240 rows in `final_catalog` without a positive selling price. No customer catalog was built from these data.
- **Capacity and ₹0:** sustained 500/day and provider headroom remain unverified. Historical five-device modeled busy-day writes were 90,833 before unmeasured provider/sender/shared-account costs; only 9,167 remain below the published daily D1 write allowance.
- **Trust and recovery:** no real proprietor bootstrap, device enrollment/revocation, production recovery, Turnstile, or secure FCM sender is accepted.
- **QR and HTTPS:** no stable URL or deployed HTTPS response exists. Therefore no permanent QR PNG/PDF has been generated.

See the four `WEBSITE_FINAL_*` files for the safe artifact, exact owner approvals, deployment procedure and QR gate. No paid services, live data, real enrollment or production deployment were used. Phase 5 is not recommended.
<!-- END_FINAL_PUBLISHING_PREP -->

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

### Current blocking gates (supersede older phase-specific conclusions only where stated)

- ❌ High-concurrency acceptance: Windows 500/100/10 fails; 35 expired requests and six retained sticky claims. Just-in-time signing does not fix the remaining actual-send/Worker-entry delay. Final focused read test has unrecovered sockets. Do not relabel earlier failures.
- ❌ Strict ₹0: measured full archive schedule exceeds 100,000 writes/day before unmeasured provider/shared costs. Full-ledger archive growth needs a measured bounded correction.
- ❌ Integrated native acceptance: final sources compile and JVM passes, but emulator readiness blocks all new native tests. No 15-second acceptance limit change.
- ⬜ Production trust: independent proprietor/operator bootstrap, key custody/recovery, owner-admin channel and real enrollment need explicit approval and evidence. Local OWNER/shared UID is insufficient.
- ⬜ Provider/physical acceptance: account/CPU/challenge/sender, independent archive media, named devices/TLS/printer remain unverified. Windows intercepted TLS fails although actual Linux TLS passes.

Smallest next scope: stable-host ingress/native diagnosis and incremental archive-cost correction, preserving all uncertain claims and financial sources. **No Phase 5, production connection or deployment approval is implied.**

<!-- END_PHASE4H_FINAL_GATE -->

## Phase 4H local engineering — verification in progress

20 October 2026. **83.5% unchanged.** New SHA-256 checkpoint verifies 686 files, including both failed Phase 4G databases. Nine focused pairing/recovery tests and three new engineering tests pass at their recorded revisions (1,000 signed reads at 100-customer concurrency; staged rotation; tenant chunked archive above 16 MiB with resume and quarantined restore). A content-type error and a trace-count test setup error were repaired; failed attempts remain retained. Final-source build, integrated native, regressions and capacity acceptance remain pending. Production owner trust, actual provider/CPU/account headroom, physical media/devices, real push and release remain unverified. See WEBSITE_PHASE4H_RESULTS.md; no Phase 5 entry approval is implied.

# HardwareShopPOS — release gates through local Phase 4G

## Phase 4G current boundary — 20 October 2026

See [WEBSITE_PHASE4G_RESULTS.md](WEBSITE_PHASE4G_RESULTS.md) for current evidence. Local pairing tests, seven backend cases and one real emulator Keystore protocol case now pass without separate worker cloud login. This does **not** establish actual proprietor/administrator trust or accepted native production integration. Existing POS logins remain unchanged.

Production bootstrap is blocked until an independently authorized operator verifies the proprietor and installation fingerprint, owns server-side trust configuration and agrees recovery/revocation duties. No local OWNER label/shared UID/public self-approval endpoint substitutes for that trust. New pairing UI/lifecycle and local-worker permission intersection require separately approved integration; no real installation has been enrolled.

Privileged owner signing on a shared POS is not accepted: installation-key possession does not prove the current user is the proprietor. Approval needs an independently controlled owner/operator channel or accepted privileged reauthentication. The protocol emulator uses distinct synthetic aliases, not two physical devices. Durable native operation/outbox wiring, key lifecycle and owner-reviewed revoked-claim reconciliation must be accepted before real integration; server receipts alone do not implement that native lifecycle.

Final backend **95/96**, JVM 312/312, broad native 51/51, signed security 8/8, warm UI 3/3, new pairing repeat 1/1 and website 10/10 are current local evidence. App assembly succeeds with unchanged tested app/test APK hashes. Cold navigation still fails 15 seconds; Windows substituted-certificate TCP remains failed. Isolated Linux TCP proves an alternate valid trust environment only. Initial pairing process attachment and browser reload failures are retained, with unchanged native repeat and website repair verification recorded separately.

Final paired-schema tests: 200/300/400 and 1,000 stress pass locally; **both 500 attempts fail**. The first has an unrecovered duplicate-response client path (now repaired/tested); the fresh repeat fails on long-delay authorization denials consistent with the unchanged 60-second freshness boundary. It retains two sticky uncertain claims, untouched. Stable 500/day capacity is unverified; do not widen freshness or reassign claims to obtain a pass. The complete stress busy-day model exceeds the published 100,000 write allowance. A 500/day storage sensitivity is modeled only, and the private 16 MiB whole-ledger export reaches its receipt-only bound after approximately 14 full modeled days. No production archive plan is accepted.

Actual free account/shared usage, per-invocation CPU, real Turnstile/platform-IP handling, secure free FCM sender/delivery, tenant-bounded hosted archives, independent owner recovery media and physical mobile/printer acceptance remain unverified. Do not start Phase 5/6 or deployment from these local passes. The 450,000,000-byte cutoff and all protected/uncertain order retention remain unchanged.

> **Current Version 1 owner decision — 19 October 2026:** See [WEBSITE_ARCHITECTURE_V1.md](WEBSITE_ARCHITECTURE_V1.md). Normal target is 200–500 orders/day; sustained acceptance target 500/day; 1,000/day remains stress only. Preserve existing POS login; separate worker cloud login is out of scope. Earlier individual sign-in proposals below are historical. D1 remains the online order authority, with no full-order Firebase mirror. Production authorization/sender/capacity acceptance remains blocked; no security checks are relaxed.

19 October 2026. This is a living local verification record. **Release/deployment is not approved. Progress remains 83.5%.** Current Phase 4F evidence is in [WEBSITE_PHASE4F_RESULTS.md](WEBSITE_PHASE4F_RESULTS.md); older phase results remain historical.

| Gate | Evidence / exact limitation | Required next action |
| --- | --- | --- |
| Version 1 shared carrier IP admission | Historical legacy mode still admits three/IP. New explicitly configured local challenge/session mode passes 20/50/100 same-IP customers, three/session limit, 200/IP emergency ceiling, 1,200/shop daily ceiling and durable retry/restart tests. All 11 new cases pass in final 86/87 backend repeat. Synthetic preview tokens do not classify bots. | Approve/review real free Turnstile widget and server validation, secure session issuance, trusted platform IP provenance, privacy and production CPU/abuse/quota tests. No public challenge issuer or production gateway is enabled. Do not deploy the synthetic issuer. |
| Version 1 shop/device authorization | Owner excludes separate worker cloud login and preserves existing POS login. The existing individual-identity proof is incompatible; it remains disabled for production. | Review and test an owner-approved device-principal protocol, bootstrap, capability policy, revocation/restore and local permission handoff. Do not remove individual checks from the old adapter or claim a shared UID identifies workers. |
| Native account/database lifecycle | Existing WorkerEntity has local ID/username, no provider UID. Fixture binds account SHA-256 and database epoch with owner approval. Production restore/replacement/credential and permission-change lifecycle is not wired. | Review noBackup epoch issuance/invalidation, account restore/replacement and local permission/session pinning through handoff under the Version 1 device protocol. Preserve existing local login; do not infer cloud person identities from names/phones/IDs. |
| Real identity/revocation trust | All issuers, users, enrollments and membership freshness are synthetic. | Explicit approval for real account read-only verification first, then separately scoped enrollment/integration. Verify trusted provider key rotation, disabled/revoked-user feed, memberships, bootstrap and sole-owner recovery. |
| Revoked offline billing uncertainty | Owner evidence intake is idempotent across restart and retains original device/bill/claim. REVIEW_REQUIRED cannot transfer, complete or rebill. A disconnected original device may already have committed. | Owner compares original Bill History, persisted website origin, local bill sync ID, payment/stock effects and completion outbox against exact server claim. Preserve verified backup/evidence. If unavailable or inconsistent, keep locked. Any automated finalization/transfer needs separately approved original-bill verification architecture. |
| Hosted archive scalability | Signed owner-only HTTPS-required tenant snapshot has consistent bounded reads, local encryption and quarantined isolated restore. Maximum 200 rows/security table, 12 selected orders, 1,000 events and 2 MB; larger snapshots fail closed. No hosted cleanup/live restore exists. | Review consistent paginated/streaming owner export and copy/restore attestation protocol before hosted use. Do not truncate evidence, expand bounds blindly, or delete active/uncollected/unresolved orders. |
| Archived bill links on a new installation | Compacted online records retain website/server bill identities and archive checksums; the original finalized POS bill sync ID remains in the verified full encrypted order archive. Click-through on a newly enrolled/synchronized installation without the original local origin record has not been verified. The original POS origin/history data is preserved. | Verify an authorized archive/link-resolution path on a new installation before production cleanup. Resolve the original existing Bill History entry; never create another bill or stock movement to reconstruct a link. |
| Actual HTTPS transport on this host | Final Phase 4F TCP test again receives Avast's substituted certificate and fails mandatory validation. Separate strict named-pipe TLS verifies the intended CA, hostname, exact peer fingerprint and signed replay rejection. | TCP remains blocked; named-pipe diagnostic is not a production fallback or resolution. Obtain a separately approved path/environment with valid intended TLS. No antivirus disabling, trust-store change or verification bypass occurred. |
| Independent archives | Local encrypted files, second-file checksums and isolated restore are synthetic same-disk proofs. | Approve named independently owned media/devices and prove recovery with one copy inaccessible, owner capacity and passphrase custody. Do not enable hosted cleanup until proven. |
| Sustained ₹0 capacity | Phase 4F: 200/300/400 cohorts pass. Both 500 attempts fail local signed-operation sockets; 1,000 stress fails catalog transport before orders. No complete current-schema 500/1,000 quota/retention result. Measured 400/20-line case models 188,915,712 allocated bytes at day 90 under 85% owner-archived/15% protected assumptions. All-unresolved 1,000/day document-only sensitivity is 538,308,000 bytes before overhead. | Resolve/reproduce local transport with evidence, then repeat the exact failed workloads. Preserve failed DBs and sticky claims. Keep 450,000,000-byte cutoff, bounded operator/backlog policy and verified archive cadence. No guaranteed sustained 500/day or unlimited storage claim. |
| Provider/account headroom | Published free limits are not actual commercial/no-card eligibility, shared-account usage, CPU compliance or provider exhaustion evidence. | Separate approval for read-only real-account audit and a bounded provider test. Include hosted archive/auth/retry traffic, other workloads, backup costs and 10 ms per-invocation CPU. Never enable a paid fallback. |
| Android repeatability | Phase 4F fresh cold attempts fail at billing 23,070 ms and the unchanged 15-second Compose readiness condition. Warm reminder/navigation/IME passes 3/3. Host memory/service ANRs are evidence, not conclusive root cause. Historical API 36 IME and 16,335 ms Settings failures remain. | Repeated stable cold and warm runs with unchanged deadlines, then named physical acceptance. No application/UI change or timeout increase was made to hide the failures. |
| Local Node/Windows transport | Historical loopback socket loss remains recorded. New private workerd meter can count completed requests before an outer response is lost; it is volatile and not a durable provider quota meter. | Reconcile server/client local counters; retain failures. Independently test real routing, restart and quota failures before any production claim. |
| Physical/mobile/printing/notification acceptance | No actual iPhone Safari, physical multi-device enrollment, real FCM/sound/background delivery, mobile-data permanent QR or real 58 mm receipt is accepted. | Obtain explicit named-device/printer/account/network approval and execute WEBSITE_DEVICE_ACCEPTANCE_CHECKLIST.md. |

## Resolved local test-runner problem

Current Phase 4F failed load databases retain **13** and **3** uncertain sticky billing claims. Read-only inspection found 244/356 unique orders and 171/340 server completions respectively; no repair replay, reassignment or new bill was attempted. The 1,000 attempt stored zero orders. These are synthetic server claims, not real shop bills. Current load gates remain failed even though smaller cohorts and native stock-once regression tests pass.

Gradle test-worker loopback failure is reproduced in standalone Java Selector/AF_UNIX probes using both TEMP path spellings. Direct AF_UNIX connection and 20 selector creations succeed in a project-local socket directory. `tools/website-proof/run-local-gradle.ps1` sets `jdk.net.unixdomain.tmpdir` only for its process tree and restores the prior environment. The actual Gradle build/JVM invocation passes **312 tests, 0 failures/errors/skips**, with both APKs built (9m 17s). Windows TEMP's underlying access/virtualization cause is not established. Default unconfigured launches can still fail; use the scoped runner. No application business logic, global environment or security setting changed.

## Strictly ₹0 operating alternatives and tradeoffs

1. Keep the approved architecture and finite 450 MB cutoff; owner resolves/collects completed orders, verifies independent encrypted archives and explicitly cleans eligible terminal payloads. Tradeoff: operator effort, finite local media and admission pauses if protected backlog grows. This cannot guarantee unlimited 1,000/day retention.
2. Reduce accepted daily workload or shorten retention only for actually resolved/collected, verified archived orders, with an explicit owner decision. Tradeoff: lower availability/operator burden; active/uncollected/unresolved records remain protected. No policy change has been silently enabled.
3. When safe capacity is unavailable, stop new website admission with cart-preserving retry/contact guidance and keep ordinary POS billing operational. Tradeoff: customers may need counter ordering. This meets the zero-payment boundary but not an unconditional website throughput promise.

No additional accounts, purchased domain, paid storage/API, Firebase Blaze or automatic upgrade was introduced. A different storage service is not presented as a verified replacement. Any architectural alternative requires its own free eligibility, security, recovery and capacity proof.

## Approval boundaries

Local Phase 4D/4E work does not authorize real cloud access, live shop data, physical enrollment, website deployment, paid features, production migrations or Phase 5/6. The final local report supplies the concrete reviewable artifact. Any next approval must name the desired workflow/scope, devices/accounts/data and allowed actions; one approval must not be interpreted as blanket release permission.

Phase 4I final capacity checkpoint: all six profiles executed. 300 and 400 PASS; five-device500 PASS two cycles. 200 FAIL during sync. Ten-device500/100-customer FAIL in cycle1: 499 unique orders, 408 completions, one retained sticky claim; 1,776 transport failures /17 exhausted recoveries, max proof age22s, zero auth denials. Stress1000 FAIL: 999 unique orders, 809 completions, two sticky claims; 3,535 transport failures /41 exhausted recoveries, max age25s, zero auth denials. All failed databases/claims retained, inspection performed only on hash-verified copies, none replayed/reassigned. Final original-driver backend/browser/release verification still running. +0% =83.5%; Phase5 NOT recommended.
## Current setup status — owner-deferred testing

22 October 2026: shared synthetic customer/Android services started at loopback 4317/4318; separate 1,478-row catalog-only preview started at 4310. Preview build and allowlisted public-source export completed. No test suite or real-device acceptance ran. These are local setup results, not release verification; progress remains 83.5%. The owner confirmed no Cloudflare account exists yet. Signup/email verification must precede exact Free-account/project/catalog/deployment approval. Backend peer closures, production trust/device adapter and sender, provider headroom and physical acceptance remain blockers. No publication, real customer ordering or QR occurred. Older sections below retain their historical evidence.

