# HardwareShopPOS — Phase 4I results

**Local verification finished with blockers. Verified weighted progress: 83.5%, unchanged. Phase 5 is NOT recommended.**

The native operation-journal repair is verified in actual Android execution. Incremental encrypted archives reduce the measured five-device local busy-day source-write envelope to 90,833 from the historical Phase4H 107,824 model. Sustained ten-device/100-customer reliability is **not verified**: its required two-cycle workload fails during cycle one. The 200-order and 1,000-order stress runs also fail. This phase is not a production readiness or permanent ₹0 claim.

No deployment, cloud activation, real owner/device enrollment, live shop data access or paid service was performed. Existing POS accounting, products, prices, stock, pipe calculations, payments/GST, login, Firebase synchronization, printing, Room11 and BackupManager format8 were preserved. All fixtures are synthetic and local; website checkout remains independent of stock checks/reservations.

## Verified results

| Check | Actual result |
| --- | --- |
| Final Android debug app and test assembly | PASS, 6m19s; hashes in `tools/website-proof/evidence/phase4i-final-jvm.json` |
| Full final-source JVM | **312/312 PASS**, 39 suites, zero errors/failures/skips |
| Actual Android native | **67 PASS / 68 executions**, one cold-navigation failure |
| New paired native methods | Integration and restore **2/2**; two separate process-recovery steps **1/1 each**; dedicated Keystore pairing **1/1** |
| Broad native / existing signed security | **51/51** and **8/8 PASS** |
| Warm reminder/navigation/numeric keyboard | **3/3 PASS**; historical IME intermittency is not declared universally resolved |
| Cold navigation | **FAIL**, unchanged 15-second catalog-ready wait; full method 46.750s |
| Packed incremental archive focused suite | **4/4 PASS**, 19.625s at its recorded candidate source; final default-backend log supplies final-source case results |
| First valid-CA Linux full backend | 105 defined, 102 PASS, 2 FAIL, 1 platform skip; 104 executed, 171.274s |
| Final original-driver Linux backend | 105 defined, 102 PASS, 2 FAIL, 1 skip, 0 cancelled; see exact final log |
| Rejected alternate HTTP-driver backend | 79 PASS, 25 FAIL, 1 platform skip; experiment is preserved, not a repair |
| Website browser | 10 executed, 10 passed, 0 failed; 0 console errors; 0 external requests. |
| Final standalone release assembly | **FAIL**, 13m31s; repeated R8 8.6.17 `ConcurrentModificationException` at `:app:minifyReleaseWithR8`; release lint passed |
| Actual final release artifact scan | **BLOCKED**; `Final release build did not succeed; no old/debug APK substituted.` |

Build/check process results: prepare: exit 0; backend-final: exit 1; ingress: exit 0; website-build: exit 0; website-static-scan: exit 0; website-browser: exit 0; release-assembly-with-stacktrace: exit 1. A nonzero build or test exit is not converted into a pass.

## Capacity acceptance

| Orders / lines / customers / devices | Result | Cycles | Recurring known reads/day | Writes/day | API requests/day | Final allocation |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 200/5/20/5 | FAIL | 0/1 | unverified | unverified | unverified | unverified |
| 300/5/20/5 | PASS | 1/1 | 1,166,502 | 35,867 | 10,364 | 3780608 |
| 400/20/20/5 | PASS | 1/1 | 1,593,445 | 47,886 | 12,216 | 5017600 |
| 500/20/20/5 | PASS | 2/2 | 2,029,764, 2,815,127 | 59,602, 67,069 | 14,067, 14,093 | 10219520 |
| 500/20/100/10 | FAIL | 0/2 | unverified | unverified | unverified | unverified |
| 1000/20/100/10 | FAIL | 0/1 | unverified | unverified | unverified | unverified |

Both 500 profiles require two complete daily cycles on one database. The five-device profile meets that local requirement: **1,000 unique submissions, 850 distinct completed bill identities and 150 protected orders** survive all archive/restore/cleanup comparisons. Its 112 observed transport failures recover with the original business identities; zero exhausted recoveries or workload authorization denials. Max API queries/invocation: **48/50**, private source SQL batch max40. The failed ten-device profile does not earn partial acceptance and is not repeated against its uncertain database.

The failed 200 profile stores 200 unique submissions, 170 completed bill identities, 20 NEW and 10 unresolved cancellations, 958 permanent receipts; 708 transport failures/four exhausted recoveries occur during synchronization. The retained database has no unfinished sticky claim. It still fails the workload.

- `phase4i-capacity-500-20-100-10-linux.json`: {'orders': 499, 'submissions': 499, 'billIdentities': 409, 'completed': 408, 'new': 63, 'cancelled': 25, 'unfinishedStickyClaims': 1}; statuses {'ACCEPTED': 1, 'BILLING_IN_PROGRESS': 1, 'CANCELLED': 25, 'COMPLETED': 408, 'NEW': 63, 'READY_FOR_PICKUP': 1}; 2197 permanent receipts. Read-only source hashes checked; only a copy was queried; no claim replay.
- `phase4i-capacity-1000-20-100-10-linux.json`: {'orders': 999, 'submissions': 999, 'billIdentities': 811, 'completed': 809, 'new': 119, 'cancelled': 50, 'unfinishedStickyClaims': 2}; statuses {'ACCEPTED': 8, 'BILLING_IN_PROGRESS': 2, 'CANCELLED': 50, 'COMPLETED': 809, 'NEW': 119, 'PREPARING': 5, 'READY_FOR_PICKUP': 6}; 4304 permanent receipts. Read-only source hashes checked; only a copy was queried; no claim replay.

The ten-device 500 attempt records **1,776 transport errors /17 exhausted recoveries**, maximum proof age22s and no authorization denial. The 1,000 stress attempt records **3,535 errors /41 exhausted recoveries**, maximum proof age25s and no authorization denial. No 60-second signature relaxation or unsafe retry was used. Completion/claim failures remain recoverable evidence, not replacement bill IDs.

## Cost and retention conclusion

For five devices and 500 twenty-line orders/day, measured recurring API + source archive/cleanup writes are **59,602 and 67,069**. Conservatively charging every measured source setup/seed/schema/publication/revocation write and final flush to each daily envelope yields **83,366 and 90,833**. The largest known margin to 100,000 is therefore **9,167 writes before unknown shared-account/provider/sender/challenge/failure costs**. An earlier Phase4I milestone called84,331 all setup; it omitted6,502 retained private seed/schema source writes. This correction preserves that earlier evidence and charges the omitted counters. The measured whole-cohort source total is150,435 writes; offline recovery is additional. The earlier107,824 reference has different recorded retry/schema context; this is a final local workload comparison, not an isolated algorithm benchmark.

Daily recurring reads are **2,029,764 and 2,815,127**. Archive/verification reads grow from331,667 to1,111,170 between cycles. Two compressed days do not establish long-term read headroom. The source comparison cache, retained identities, append-only receipts and uncollected backlog continue to grow. The 450,000,000-byte cutoff remains enforced; no active/uncollected/unresolved order is automatically removed.

Post-final-delta allocation is **10,219,520 bytes**; observed temporary source peak is25,526,272 bytes. Short-run linear 30/60/90-day projections are **135,573,504 /269,881,344 /404,189,184 bytes**, approximately100 days to the operational cutoff under this specific 15%-protected mix. This is not a measured 90-day final-schema run, max-input/backlog guarantee, unlimited capacity or permanent ₹0 claim. Owner-controlled finite archive media and backlog resolution are mandatory; independent physical media have not been verified.

Measured isolated recovery over the five-device cohort costs **194,037 incremental-chain writes plus28,355 terminal-restore writes** and includes schema/field-verification/import SQL. These run offline in fresh local workerd databases. Moving this verification into hosted D1 would consume real quotas and could exceed the Free allowance. Hosted disaster-recovery import is not accepted. Detailed per-archive read/write/request/statement counters, source costs, API table attribution and conditional projections are in `tools/website-proof/evidence/phase4i-capacity-analysis.json`.

The five-device cohort measures28,353 normal API requests,6,605 private source SQL data calls,7,055 incremental restore data calls and1,076 terminal restore data calls:43,089 total local data calls across the whole cohort. Fixture meter/control/trace calls are excluded and are not production APIs. Charging all cohort source SQL requests conservatively to each daily envelope gives20,865/20,891 potential Worker data requests, before real notification/challenge/shared usage. Actual Cloudflare requests and CPU are not measured.

Official documents rechecked21 October2026: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) publishes5M reads/100k writes per account/day and5GB account storage; [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) sets500MB/database and50 queries/invocation. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) lists100k requests/day and10ms HTTP CPU. [Firebase plans](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) lists FCM as no-cost on Spark. No account eligibility, actual provider CPU, shared quota, production challenge or secure free sender/delivery is verified. Local aggregate CPU/wall latency proves none of these. No paid fallback is introduced.

## Root causes and implemented repairs

1. **Intent identity:** payload-derived identities previously conflated uncertain retries with all future identical actions. Immutable per-intent UUID files plus a durable active pointer now retain PENDING identity, close confirmed receipts, and permit later equal new intents. AtomicFile reads recover `.bak`; unknown remnants fail closed. Actual show/hide/show, repeated publication, concurrent intents, lost responses, repository/process restart, staged-key rotation, restore quarantine and revoked-device uncertainty pass. Sticky billing claims retain the original bill identity.
2. **Missing catalog/configuration receipts:** the signed local backend now commits configuration/catalog updates with permanent route/body/device/database-bound receipts, looks them up only after current authority checks, rejects conflicting reuse and recovers lost responses safely. Existing Room accounting transactions are unchanged.
3. **Full-ledger archive duplication:** a verified baseline and encrypted revision-aware linked deltas replace daily full permanent-ledger staging in this local prototype. Enforced revision/append-only contracts support compact comparison markers; up to8 changes/staging row, bounded pages, exact field restoration, authenticated manifests, interruption/resume and verified owner-only checkpoint advancement preserve recovery. No source protected order/permanent receipt is deleted.
4. **Index cost:** measured100 inserts cost200 writes with the ordinary composite key and100 with WITHOUT ROWID; deletion costs100 in both. Only insertion savings are credited. Failed hypotheses and a premature PASS measurement label remain retained with an explicit correction.
5. **Transport:** original-driver raw shared-IP and sustained-load socket resets persist on Linux; isolated Windows/Linux first-attempt write probes each store300/300 without a failure. Read diagnostics trace admission, signature/header emission, Worker entry, proof age, memory and event-loop delay. There is no kernel packet capture or proven single OS/Node/workerd/AV cause. The alternate Node HTTP experiment fails and has different adapter capability detection, so it cannot cleanly attribute the fault. It is removed from final source and retained as evidence. No signature/timeouts/assertions are weakened.
6. **Cold navigation:** ViewModel-created appears, but catalog-ready is absent before15s. Warm UI passes; a later pipe catalog takes13.557s. Host pressure and binder-freeze errors are observed, not proven sole cause. The cold fixture's unconditional `@After` cleanup means a complete pre-teardown failing DB was not retained. Runner snapshots only preserve surviving post-teardown state; that gap stays open. No financial claim existed in that cold UI case.
7. **Gradle test worker:** the existing scoped JDK Unix-domain temp-path workaround restores full Gradle JVM execution on this host; it is process-local and does not modify production business logic or global environment. Historical loopback failure remains documented as environment-dependent.
8. **Release R8:** earlier combined build fails at minifyReleaseWithR8 with ConcurrentModificationException before JVM. The final standalone repeat fails after13m31s with the same exception inside R8 8.6.17 shaking/optimization; release lint passes. The stacktrace identifies an internal compiler failure but does not establish its trigger or a verified compiler repair. No R8/minification/security check or test is disabled, and no stale APK substitutes for a failed final build.

## Exact implementation changes

- Android main: `app/src/main/java/com/hardwareshop/pos/data/website/PairedLocalWebsiteTransport.kt`, `PairedWebsiteKeyLifecycle.kt`, `WebsiteRestoreQuarantine.kt`; `app/src/main/java/com/hardwareshop/pos/di/WebsiteOrdersModule.kt`.
- Actual native coverage: `WebsitePairedIntegrationTest.kt`, `WebsitePairedProcessRecoveryTest.kt`, `WebsitePairedRestoreTest.kt`, `WebsiteNativeFixture.kt` in `app/src/androidTest/java/com/hardwareshop/pos/`. Dedicated existing WebsiteShopPairingTest executes unchanged.
- Local backend: `tools/website-proof/pairing.mjs`, `worker.mjs`, `paired-recovery.mjs`, `d1-fixture.mjs`, `runtime.mjs`, new `proof-cost-meter.mjs`.
- Local archives/capacity/diagnostics: new `phase4i-archive.mjs`, `phase4i-capacity.mjs`, `phase4i-ingress.mjs`, `phase4i-write-ingress.mjs`; `phase4h-archive.mjs`, `retention.mjs`, `test/phase4i.test.mjs`, `test/phase4d-tls.test.mjs`. The final pooled-fixture.mjs matches the prechange SHA; rejected comparison source is retained in evidence.
- Test runners: `run-android.ps1`, `run-native.ps1` and `evidence/phase4i-*` runners/analysis/TLS fixture/report helpers. No test timeout/financial schema migration. Reports updated as requested; final manifest lists exact changed/new source and evidence hashes.

## Preservation, build and execution commands

Checkpoint: **1,167 SHA-256 verified copies,170,917,368 bytes**. Final protected-source check: 1167 checkpoint copies verified; 24 historical failed database files unchanged; protected financial/Firebase/pipe/backup/printing source hashes match. **4,615 capacity-fixture files across all six cohorts** match their retained SHA-256 copies. Fresh failed capacity originals are retained with WAL/SHM where present; inspection uses copies only. Two archive paths on one computer are not independent physical media.

Commands below run from `C:/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof` unless stated:

```powershell
node --test --test-concurrency=1 test/phase4i.test.mjs
node phase4i-ingress.mjs --label=windows
node phase4i-write-ingress.mjs --label=windows
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4i-linux.py' prepare
# Modes executed separately; each refuses to overwrite a previous log:
# backend, write-ingress, 200, backend-final-node (rejected experiment),
# 300, 400, 500-five, 500-ten, 1000, backend-final, ingress
& ./evidence/phase4i-remaining-capacity.ps1
& ./evidence/phase4i-native-session.ps1
& ./evidence/phase4i-final-checks.ps1
node evidence/phase4i-analyze.mjs
node evidence/phase4i-ingress-analyze.mjs
node build-web.mjs --evidence=phase4i-web-build.json
node evidence/phase4i-artifact-scan.mjs
node evidence/phase4i-browser-runner.mjs
# From project root:
./tools/website-proof/run-local-gradle.ps1 -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest','--rerun' -Log website-phase4i-debug-final-jvm.log
./tools/website-proof/run-local-gradle.ps1 -Tasks ':app:assembleRelease','--stacktrace' -Log website-phase4i-release-stacktrace.log
```

Exact per-profile CLI arguments/source hashes/fixture UUIDs are in `evidence/phase4i-linux.py`, the labeled logs, `source-manifest*.json` and capacity results. The native runner records every instrumentation command and actual method count. Logs retain failed archive hypotheses, corrected expectations, the failed combined release/JVM invocation, every failed load and the rejected transport experiment. Compilation is never counted as a native execution.

## Remaining gates and stop decision

- ❌ Sustained ten-device/100-customer reliability and raw full-suite transport; failed200/stress evidence remains.
- ❌ Stable cold navigation within15s; historic IME intermittency and full pre-teardown failure evidence remain open.
- ❌ Guaranteed long-term read/write/storage headroom: increasing archive comparisons, finite permanent receipts/protected backlog, actual max-input final-schema90-day behavior, providerCPU/account/shared usage and hosted recovery remain unverified. Immutable debug-native intent journals also grow; no production journal retention/storage acceptance is claimed, and uncertain entries must not be discarded.
- ❌ Final release assembly fails twice in R8; actual release artifact exclusion is blocked. Source guards alone are insufficient. Marker scanning is narrower than penetration testing.
- ⬜ Real proprietor/operator trust and recovery/key custody, secure free FCM/challenge sender, explicit device enrollment, provider plan/account eligibility, independent media restore and physical device/printing acceptance require separate evidence and approval.

Weighted calculation remains `10 +4 +14 +10 +10 +11.5 +10 +10 +3 +1 =83.5%`. No new production-authentication/capacity/final-release credit is awarded. **Stop after Phase4I. Do not begin Phase5 or deploy.** The next proposed scope is isolated repair/reverification of the unresolved local gates, then an owner-reviewed acceptance plan; it requires the owner's next explicit instruction.

---
Historical execution ledger follows. Candidate passes below retain their original source scope; the final decision above supersedes stale pending statements only.

# HardwareShopPOS — Phase 4I local verification

21 October 2026. **IN PROGRESS — 83.5% verified weighted completion, unchanged. Phase 5 remains blocked.** Local synthetic engineering only. This is an execution ledger, not a completion or deployment approval.

## Preservation

Before functional edits, `tools/website-proof/evidence/phase4i-prechange/manifest.json` records **1,167 SHA-256 verified copies, 170,917,368 bytes**, including current source/reports and closed historical failed fixtures/logs. Prior Phase 4G/4H uncertain billing claims are not replayed or reassigned. The initial checkpoint enumeration was stopped before functional changes and replaced with an explicit bounded file inventory; the completed verified checkpoint is the authoritative one.

## Current implementation and evidence

- `PairedLocalWebsiteTransport.kt`: immutable per-intent journal records and a per-payload active pointer. PENDING retries keep their operation UUID across transport restart/key rotation. A confirmed RECEIPT closes the intent; later identical requests create new intent UUIDs. Old pending journals migrate with their original UUID; previous evidence is retained. Restore/revocation/rotation fences remain. These changes require actual native acceptance.
- `WebsitePairedIntegrationTest.kt`: added real signed show/hide/show, repeated publication, response-loss/reopened transport and concurrent identical confirmed intent assertions, alongside existing actual Room/bill/payment/stock/history checks.
- `WebsitePairedProcessRecoveryTest.kt`: added an uncertain catalog mutation across the existing two-process rotation test, requiring recovery of the original receipt and then a new identical intent. Compilation is not execution.
- `phase4i-archive.mjs`: private local incremental prototype. Atomic cross-table capture compares exact persisted fields against the last owner-verified checkpoint. Authenticated manifests link the baseline and ordered deltas. Resumable cache advancement follows two-copy verification and isolated restore. This maintains a source-side comparison cache: its storage/write/read overhead must be measured, not assumed free. No protected source order or permanent receipt is deleted.
- `phase4h-archive.mjs`: shared encrypted chunk tooling accepts authenticated incremental chain metadata; historical full-snapshot format remains supported.
- `d1-fixture.mjs` and `runtime.mjs`: private fixture SQL meter includes schema/restore/verification overhead; it is separate from API accounting and is not a production quota meter.

## Tests executed so far

| Command (from tools/website-proof) | Actual result |
| --- | --- |
| `node --test --test-concurrency=1 test/phase4i.test.mjs` — first | Failure retained; test fixture cleanup leaked a runtime, process stopped. `evidence/phase4i-archive-first.log`. |
| Same command — second | 2 executed, 1 passed, 1 failed: test incorrectly expected one nonce despite three existing fixture nonces. `evidence/phase4i-archive-second.log`. |
| Same command — corrected third | **2/2 passed**, 20.009 s, zero skipped/cancelled. `evidence/phase4i-archive-third.log`. Baseline + incrementals, interruption/resume, changed-only catalog exclusion, field comparisons, tenant isolation, replay retention, quarantine, corruption rejection, missing-baseline rejection, owner-confirmation denial and atomic quota rollback. |

Fresh Android app/test build and full JVM invocation started using `run-local-gradle.ps1`, log `website-phase4i-build-jvm.log`. No result is claimed until completion. Native, full backend/TLS, browser, shared-IP, transport isolation and capacity reruns are pending. The 2/2 archive result predates subsequent keyset/`WITHOUT ROWID` optimizations and catalog receipt changes; final-source archive acceptance still requires a fresh run.

Further source review found that prior permanent receipts covered order routes only. The local backend now records catalog/configuration receipts atomically with their mutations, checks current authority before receipt recovery, binds route/body/device/database to the original ID, and permits bounded identity-preserving transport recovery for these routes. There is no Room schema or POS transaction change. API write attribution is being added as sanitized table aggregates. New archive metadata uses composite primary keys without duplicated rowid indexes; support is described in Cloudflare's [D1 return objects](https://developers.cloudflare.com/d1/worker-api/return-object/), but local metadata and provider capacity remain distinct.

### Subsequent verified milestone and retained failures

- Intermediate Android assembly and complete Gradle JVM suite: **BUILD SUCCESSFUL, 15m18s; 312/312 tests, 39 suites, no failure/error/skip**. `evidence/phase4i-intermediate-jvm.json` records APK hashes. This predates the AtomicFile follow-up and is not final native acceptance.
- Final source review found journal reads checked base-file existence before `AtomicFile.openRead`, bypassing backup-file recovery after a crash. Reads now use AtomicFile recovery, unknown remnants fail closed, pairing/restore/rotation remnants prevent legacy bearer fallback, and native assertions simulate `.bak` recovery. A fresh corrected build, including a local release artifact, is running in `website-phase4i-final-build-jvm.log`.
- `phase4i-focused-final-candidate.log`: **3/3 pass**, 22.266s, at its candidate revision.
- `phase4i-focused-final.log`: **3/4 pass**, failed deletion-cost assumption. `phase4i-focused-index-corrected.log`: **3/4 pass**, actual insertion/deletion counts retained. In this local D1 runtime, 100 inserts cost 200 writes with an ordinary composite key versus 100 without rowid; both whole-table and tenant-filtered deletion cost 100 in each layout. **No deletion saving is claimed.** An intermediate measurement file prematurely said PASS before the failed assertion; its `.CORRECTION.json` explicitly marks the attempt FAIL. Original evidence is preserved.
- `phase4i-focused-measured-final.log`: **4/4 pass**, 22.685s, zero skipped/cancelled. Tests verify combined insertion/cleanup costs, actual post-commit response loss, immutable receipt recovery, distinct later intent, denied worker publication, changed-only encrypted chains, corruption rejection, guarded revision/append-only contracts and quota rollback. A subsequently added fresh-runtime checkpoint-restart assertion awaits the final rerun.

Revision markers now have enforced contracts: catalog updates advance revision; order changes advance revision except the existing terminal compaction marker; receipts/audit/events cannot be updated, permanent receipts/audit cannot be deleted. Source-side cache stores compact markers for these large tables, exact values for small unversioned tables, and full changed records in encrypted archives. Source schema/contracts are local proof additions only; no live D1 or Room migration occurred. Cache, trigger, temporary snapshot, index, restore and verification costs remain subject to capacity measurement.

### Latest execution update

`phase4i-focused-restart.log`: **4/4 pass**, 22.558s, no skipped/cancelled tests. This includes stopping workerd after partial archive checkpoint advancement, reopening the same disposable database in a fresh runtime, reconstructing the pending boundary, repeating isolated restore verification and safely finishing the checkpoint. No runtime-only attestation was trusted after restart.

`website-phase4i-final-build-jvm.log`: **FAILED in 28m28s** at `:app:minifyReleaseWithR8`, `java.util.ConcurrentModificationException`. Debug app/test assembly completed first; this combined invocation did **not** reach JVM tests and earns no final JVM/release pass. Its full log is retained. No compiler/security checks were disabled. A separate final debug/JVM invocation is running in `website-phase4i-debug-final-jvm.log`. Release artifact exclusion remains blocked until an actual release artifact can be inspected.

A separate Phase 4I synthetic TLS fixture was generated and locally verified: valid 21–28 October 2026; SHA-256 leaf fingerprint `5D:6E:D1:C0:23:44:A5:3D:EC:F7:0B:78:F0:9A:8D:4E:4B:36:AA:0B:FE:63:23:86:97:B2:65:80:BA:84:73:BF`. Old certificates are preserved. The explicit fixture selector retains CA/hostname and exact-leaf checks; no system trust or verification setting changed.

Final debug-only verification: **BUILD SUCCESSFUL in 6m19s**, 312 JVM tests / 39 suites, zero failures/errors/skips. App and test APK SHA-256 hashes are in `evidence/phase4i-final-jvm.json`; log `website-phase4i-debug-final-jvm.log`. Actual native execution has started through `evidence/phase4i-native-session.ps1`; no result is credited yet. Release R8 failure remains open.

The incremental prototype subsequently packs up to eight changed records per temporary snapshot row, exports at most four packed rows per chunk page and advances no more than 32 record markers per checkpoint batch. Restores still compare every field. This reduces temporary staging writes in the design; new focused and capacity measurements are required before claiming savings or final acceptance. Earlier passing focused tests remain tied to their un-packed revision.

Native environment milestone: the read-only Operations API36 emulator became ready in **142,589 ms**, within the unchanged three-minute setup bound, with 2,100,524 KB free RAM before boot and 743,548 KB after. Final app/test APKs installed. `evidence/phase4i-native-boot.json` records `ready:true`. Native methods are executing; readiness/installation alone earns no UI or billing acceptance credit. Physical devices were not used.

### Actual native results received

- **Cold navigation FAIL, 1 executed / 1 failed**, 46.750s method run; original internal 15,000ms catalog-loading wait at `MenuKeyboardRegressionTest.kt:78`. Logs `phase4i-ui-cold.log` and `phase4i-ui-cold-app-traces.txt`. The trace shows ViewModel creation and no catalog-ready event before timeout. Host pressure is observed, not proven as the sole cause. No timeout increased. The original runner preserved logs but did not retain the failing catalog workload before the test fixture's unconditional @After cleanup; this evidence gap is explicit. Later runner-level snapshots capture surviving post-teardown state only. Complete pre-teardown UI-failure capture remains unverified and needs a failure-aware fixture before another acceptance run. Historical backend failed DB/WAL/SHM/claims remain untouched.
- **Warm reminder/navigation/numeric keyboard PASS, 3/3**, 62.761s. `phase4i-ui-warm.log`. This does not erase the cold failure or prove historical IME intermittency universally resolved.
- **Existing native broad regressions PASS, 51/51**, 58.800s. Existing Orders workflow, billing/payment/stock integrity, advanced billing, pipe inputs/calculations, backup recovery, Bluetooth permission and notification/session tests. `phase4i-native-broad.log`.
- **Existing signed security PASS, 8/8**, runner `phase4i-signed-native.log` plus its per-method logs.
- **Actual new paired integration and restore PASS, 2/2**, 5.584s. This executes signed publication show/hide/show, repeated identical publications, concurrent intents, lost publication/claim responses, AtomicFile backup recovery, reopened repositories, actual existing Room billing with exactly one bill/payment/stock deduction/history link, key rotation, revoked-device pending evidence and format8 restore quarantine/fallback denial. `phase4i-native-integrated.log`. Correlated backend: **one order, one bill identity, one completed order, 17 permanent receipts**, max 12 API queries/invocation; `phase4i-native-integrated-backend-results.json`. No production authentication or real-device credit.

The two separate instrumentation processes both **PASS (1/1 each)**, 0.735s and 0.488s: committed catalog mutation and staged-key rotation survive force-stop, recover under the new key with the original receipt, and allow a later identical new intent. Dedicated `WebsiteShopPairingTest` **PASS (1/1)**, 0.592s, actual Android Keystore approval/revocation/reinstall proof. Full native total: **68 executions, 67 pass, 1 cold-navigation failure** (navigation method runs both cold and warm). The runner exits nonzero because it preserves that failure; no skipped method is counted. Emulator was stopped after the run. No physical device was used.

Final packed archive focused suite: **4/4 PASS**, 19.625s (`phase4i-packed-focused.log`). This includes actual runtime restart and all field-level comparisons after packing. Windows ingress diagnostic: **3 × 1,000 signed reads, zero failures** at concurrency 1/20/100, no retry layer. Maximum proof ages 1/1/2s and maximum header-emission-to-Worker-entry gaps 9/789/1,803ms respectively. Header emission is not packet capture, elapsed time is not provider CPU, and these reads are not 500-order acceptance. `phase4i-ingress-analysis.json` retains memory/event-loop and clock-domain details. Linux full backend verification is running from the hashed final source with a valid explicit CA; no pass claimed until completion.

## Remaining gates

The five-device 500-order Phase 4H budget remains **107,824 known writes** until a final-schema workload measures the incremental replacement. Native acceptance remains blocked by the previous emulator environment until actual tests execute. Ten-device/100-customer reliability and certificate-valid full backend acceptance remain unresolved. Preserve the 15-second UI and 60-second signature bounds.

Real proprietor/operator trust, provider account/shared quotas and CPU, secure free FCM sender, real challenge provider, physical devices/printer and independent archive media remain separate unverified gates. No production or Phase 5 scope is authorized.

### Backend and write-ingress checkpoint

The first Linux full backend child completes **105 defined / 104 executed / 102 passed / 2 failed / 1 platform skip**, 171.274s. Failures are first-attempt 50/100 shared-IP fetches with ECONNRESET. Original failed evidence remains. Strict actual TCP TLS passes with the new valid explicit CA and exact leaf checks. All four Phase4I focused tests pass, as do the large encrypted archive, signed 100-concurrent read recovery and pairing cases. This run precedes the additional isolated terminal-restore SQL accounting field; a final-source run is still required. `evidence/phase4i-linux/backend.log`.

`node phase4i-write-ingress.mjs --label=windows` and the Linux runner's `write-ingress` mode each complete **300/300 stored 20-line orders, zero failures**, at concurrency 1/20/100. All clients settle before evidence collection/teardown; no retries or uncertain disposition occur. The first full-suite socket failure is not reproduced here. A first failed Promise.all followed by fixture teardown can amplify subsequent closures but does not establish the initial failure's root cause. No OS/network/provider cure is claimed.

Phase 4I capacity checkpoint: **300/5-line/20-customer/five-device PASS**, one full synthetic cycle, 300 unique submissions / 255 distinct completed bill identities / 45 protected records, max 48 API queries/invocation. Recurring API + source archive/cleanup: **1,166,502 reads, 35,867 writes, 10,364 API requests**; conservative all setup/final flush charge **49,007 writes**. Separate terminal restore verification: 8,515 local writes, not silently omitted from disaster-recovery planning. Post-final-delta allocation 3,780,608 bytes. All 145 observed transport failures recover with original identity; this does not repair raw transport failures. **200-order profile FAILS during sync**, despite 200 persisted identities and 170 completions, 708 transport errors / four exhausted recoveries; original remains untouched. Remaining profiles pending. **+0% → 83.5%; Phase 5 blocked.**

Phase 4I verified milestone: 400/20-line/20-customer/five-device PASS; 400 unique submissions, 340 completed bill identities and 60 protected records. Recurring API + source archive/cleanup: 1593445 reads, 47886 writes, 12216 API requests. Including setup/final flush: 63734 writes. Post-final-delta 5017600 bytes. Separate isolated recovery costs remain in phase4i-capacity-analysis.json. +0% = 83.5%; two-cycle 500 profiles, final regressions and production gates pending.

Phase 4I five-device 500-order checkpoint: cycle 1/2 verified, 500 unique orders / 425 completions / 75 protected records. API 44802 plus source archive/cleanup 14800 = 59602 recurring writes; 2029764 reads; 14067 API requests. Cycle 2 is required; this is not a 500-profile PASS or guaranteed free-plan capacity. +0% = 83.5%.

Phase 4I verified five-device 500-order milestone: PASS, two cycles on one database, 1,000 unique submissions / 850 completed bill identities / 150 protected orders. Recurring writes 59,602 and 67,069; conservative busy-day setup/final-flush envelope 84,331 versus earlier 107,824. Reads 2,029,764 and 2,815,127. 112 observed transport failures recover, no exhausted recovery or workload proof denial; max 48 API queries. Post-final-delta 10,219,520 bytes. Conditional 90-day allocation 404,189,184 bytes, about 100 days to the 450 MB operational cutoff under this short-run mix; not a retention guarantee. Isolated offline incremental/terminal restores perform 222,392 writes over the complete cohort and must not be treated as free hosted D1 imports. Ten-device/100-customer acceptance and final regressions remain pending. +0% = 83.5%; Phase 5 blocked.

Phase 4I final capacity checkpoint: all six profiles executed. 300 and 400 PASS; five-device500 PASS two cycles. 200 FAIL during sync. Ten-device500/100-customer FAIL in cycle1: 499 unique orders, 408 completions, one retained sticky claim; 1,776 transport failures /17 exhausted recoveries, max proof age22s, zero auth denials. Stress1000 FAIL: 999 unique orders, 809 completions, two sticky claims; 3,535 transport failures /41 exhausted recoveries, max age25s, zero auth denials. All failed databases/claims retained, inspection performed only on hash-verified copies, none replayed/reassigned. Final original-driver backend/browser/release verification still running. +0% =83.5%; Phase5 NOT recommended.

Phase 4I final original-driver backend: 105 defined /104 executed, 102 PASS, two FAIL, one platform SKIP, zero cancelled, 634.411s. Failure cases: bounded keep-alive regression (phase4c-transport) and raw50 shared-IP (phase4f). Raw20/100 shared-IP, strict actual TCP TLS, all new intent/archive cases, pairing/revocation/claim recovery, corrupt/unauthorized restore and quota/cutoff rollback pass. Both earlier backend attempts remain retained. No full-suite acceptance or progress increase;83.5%. Final ingress/browser/release checks continue.

Phase 4I website final verification: local build and static scan PASS;10/10 browser cases, seven viewports, no browser console errors or external requests. New catalog/cart/confirmation/many-size screenshots visually inspected. Simulated slow-mobile load6,284ms, search236ms, gzip30,956 bytes at final browser catalog snapshot. These are emulated network/CPU/keyboard dimensions, not physical-phone acceptance. Both OS read diagnostics complete3,000/3,000 with no retry/failure; Linux max proof ages2/2/6s versus Windows1/1/2s. Actual sustained ten-device order acceptance remains FAIL. Release verification pending;83.5% unchanged.

CORRECTION — Phase4I setup accounting: the earlier 84,331 five-device envelope includes API publication/revocation and archive/final-flush, but excludes6,502 measured private source seed/schema writes. All counters were retained. Fully charging every measured source setup write raises the two daily conservative envelopes to83,366 and90,833 (9,167 margin before unknowns), while recurring59,602/67,069 is unchanged. Whole-cohort measured source total150,435 writes. The offline restore222,392 writes are additional local recovery work and cannot be moved into hosted D1 within this budget. Earlier milestones remain historical, superseded by this correction. No production ₹0/headroom credit;83.5% unchanged.

Setup-label correction also applies to earlier300/400 milestones: full measured source-setup envelopes are55,509 and70,236 writes (previous49,007 and63,734 excluded6,502 private seed/schema writes). Recurring cycle measurements are unchanged; raw counters and previous statements are retained. See phase4i-setup-accounting-correction.json.
