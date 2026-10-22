# HardwareShopPOS — Phase 4H local engineering results

21 October 2026, Asia/Kolkata. **Local verification closed with blockers. Verified weighted progress: 83.5%, unchanged. Phase 5 is NOT recommended.** No production service, deployment, paid plan, real device enrollment, live shop database or product data was used.

## Decision and smallest corrective scope

The five-device 500-order cohort completed two compressed daily cycles in one persistent database, with 1,000 unique submissions, 850 unique completed bill identities, 100 pending and 50 unresolved cancelled records protected. The ten-device/100-customer Windows cohort FAILED before its first full cycle. Strict actual backend TCP TLS passes in Linux; original Windows interception evidence remains failed. Final APK/test build and 312 fresh JVM tests pass. All ten browser cases pass. Native acceptance is BLOCKED before JUnit because the disposable emulator did not become ready within its existing setup bound. Implementation is not integrated-native acceptance.

The measured five-device archive schedule costs **107,824 known writes on a busy day**, exceeding the published Free 100,000 writes/account/day before unknown provider/sender/shared-account costs. Strict ₹0 sustained 500/day is therefore NOT accepted. Do not enable the affected production integration or substitute paid capacity. A bounded incremental snapshot/archive design with independently measured daily cost is the smallest cost correction; dropping permanent receipts, silently deleting protected orders or merely archiving less often does not prove safety.

The next local correction needs a stable host for the actual native paired flow and cold/warm timing, and a controlled ingress/peer-closure experiment. Preserve every failed attempt. Exact proprietor/operator root custody, privileged owner channel, account quotas/provider CPU, physical media/device/printer acceptance and any cloud/real-enrollment scope need separate owner approval. This report authorizes none of those steps.

## Recovery checkpoint and protected evidence

Before functional edits: **686 SHA-256 verified copies, 44,174,343 bytes**, in tools/website-proof/evidence/phase4h-prechange/manifest.json. Both historical failed Phase 4G database/WAL/SHM sets remain unchanged (12 files). Source verification also checks the protected billing, Room, pipe, FCM/printing and security sources. BackupManager's sole functional difference is an external pairing-quarantine call before its original restore transaction; format 8 and Room 11 remain unchanged.

The failed Phase 4H ten-device database was copied as closed files, hashes compared, and only that copy queried read-only: 500 orders/unique submissions, 397 unique bill identities, 391 COMPLETED, six BILLING_IN_PROGRESS, ten ACCEPTED, four READY_FOR_PICKUP, 64 NEW and 25 CANCELLED. SQLite integrity is OK, 2,064 permanent receipts retained. **No old or new uncertain claim was replayed or reassigned.** Evidence: phase4h-capacity-500-20-100-10-inspection.json, phase4h-failed-inspection.log. Never open the original failed fixture for recovery tests.

The independent Linux 1,000-order stress attempt also FAILED: 999 unique persisted submissions, 834 unique bill identities, 832 COMPLETED, two sticky BILLING_IN_PROGRESS claims, one ACCEPTED, three PREPARING, 111 NEW and 50 CANCELLED. A verified second copy passes SQLite integrity; 4,309 permanent receipts retained. File hashes of the original closed Linux database and retained copies match, with no original SQLite query or replay. 2,627 transport failures and 18 unrecoverable client operations are recorded; no authorization denial/expired proof. Partial APIs: 3,294,536 reads, 93,107 writes, 12,779 requests, max 46 queries/invocation; allocated 15,175,680 bytes. No full cycle, archive or stress daily cost is accepted. Evidence: phase4h-stress-inspection.json and phase4h-linux/stress.log. These two claims also remain untouched.

## Confirmed root causes and unresolved hypotheses

1. Client signing occurred before transport admission in the earlier proof path. The repaired path acquires bounded admission before signing and holds it through body consumption. Exact method/URL/body/device/database/epoch/nonce/operation signatures and the **60-second proof bound remain unchanged**. This fixes the measured client queue placement, not every ingress delay.
2. Miniflare's reset dispatch and the former forced 16-request connection recycling create connection churn. The local pool now uses at most 100 connections, pipelining one and four-second idle reuse. No mutation retry lives in the transport. The existing alternative Node HTTP driver also had ECONNRESET historically, and Linux raw tests still fail: blaming only Windows, Undici, antivirus or stale keep-alive is unsupported.
3. In the failed ten-device cohort, 35 distinct requests expired (70 denial events include the sanitized outer denial), maximum age 104 seconds. Sign-to-actual-send maximum was under one second; Worker entry occurred tens of seconds after actual transmission. One retained example has an 86,809 ms wall-clock send/entry gap. This localizes the remaining delay boundary but does not identify OS, intermediary, workerd scheduling or paging as the sole cause. Host samples observed 94% committed memory, 595 MB available and 1,873 pages/sec. These are observations, not causal proof. No retry on 403 or widened freshness was added.
4. Node client performance.now is monotonic. Workers uses an epoch-origin, I/O-sensitive runtime timer; the earlier field named serverMonoMs was misleading. New traces label it serverRuntimeMs/WORKER_RUNTIME_EPOCH. Analysis interprets historical fields accordingly, keeps client/server domains separate and never claims runtime duration is provider CPU. See official [Workers timers](https://developers.cloudflare.com/workers/runtime-apis/performance/).
5. TLS isolation reached the exact fixture certificate, exposing a test-client parser that tried to decode HTTP chunk framing as JSON. The test now parses and validates chunked framing, still enforces CA/hostname and additionally asserts the exact leaf fingerprint. Linux actual signed archive request and replay-denial pass. Windows Avast-substituted certificate remains a real failed environment path; no antivirus/trust-store/certificate verification change occurred.
6. Linux source setup initially omitted fixtures/pipe.json; two pricing tests correctly failed ENOENT. The fixture copy and SHA manifest were corrected. A later signed-read trace assertion incorrectly expected exactly 1,000 attempts despite safe identity-preserving retries. It now requires exactly 1,000 logical sent/verified identities, all logical operations successful, zero authorization denials, and actual sends equal 1,000 plus recorded recovery attempts. Earlier failed assertions are retained.
7. Native Hilt selection could fall back to the old DEBUG bearer fixture when main pairing metadata was absent while a quarantine/rotation fence remained. It now selects a disabled transport in that state. The added native assertion compiled but is **not executed** because emulator setup is blocked.
8. Final source review identifies an additional **unaccepted native intent-lifecycle risk**: the journal derives an operation identity from device/database/route/body and keeps using it after a successful receipt. That is appropriate for uncertain retry, but an intentional later identical catalog publication (for example show, hide, show) could recover the old receipt rather than apply a new intent. This is a source-based inference, not an executed native result. Do not approve the new adapter for production or award it complete lifecycle credit. A reviewed new-intent versus uncertain-retry boundary that preserves previous receipts needs a dedicated native regression before integration. Existing POS billing transactions were not changed to work around it.

## Exact implementation changes

Changed Android main sources:

- app/src/main/java/com/hardwareshop/pos/data/website/WebsiteOrdersRepository.kt — current active worker permission intersection and post-I/O session/account continuity checks.
- app/src/main/java/com/hardwareshop/pos/di/WebsiteOrdersModule.kt — explicit paired DEBUG adapter and fail-closed quarantine/rotation selection.
- app/src/main/java/com/hardwareshop/pos/presentation/worker/product/ProductSelectionViewModel.kt — DEBUG stage/count/elapsed traces, no names or customer/price data.
- app/src/main/java/com/hardwareshop/pos/util/BackupManager.kt — one external authority fence call before restore.

Added Android main sources in data/website/: PairedLocalWebsiteTransport.kt (installation signatures, per-device mutex, segmented no-backup AtomicFile operation/receipt journal), PairedWebsiteKeyLifecycle.kt (durable uncertainty fence, staged dual-key rotation and fresh-session recovery), WebsiteRestoreQuarantine.kt (restore authority fence). Operation identity survives restart/key rotation; journal files are finite and fail closed at their per-record bound, with no automatic evidence deletion.

Added Android tests: WebsitePairedIntegrationTest.kt (actual paired backend + existing Room/BillingRepository/bill/payment/stock/history recovery), WebsitePairedRestoreTest.kt (format8 product snapshot and fallback denial), WebsitePairedProcessRecoveryTest.kt (two instrumentation processes for committed rotation recovery). MenuKeyboardRegressionTest.kt adds Orders/session preservation to existing navigation. **All compile; none executed in this Phase 4H attempt.** Existing 15,000 ms keyboard/navigation thresholds are unchanged.

Changed proof sources: d1-fixture.mjs, runtime.mjs, worker.mjs, pairing.mjs, paired-recovery.mjs, pooled-fixture.mjs, build-web.mjs, test/phase4d-tls.test.mjs and run-android.ps1. They add sanitized lifecycle/commit/denial evidence, just-in-time signing, safe receipt retry, full local schema setup, dual-key rotation and evidence-preserving build/test output. Added lifecycle-trace.mjs, phase4h-archive.mjs, phase4h-capacity.mjs, phase4h-shared-ip.mjs, phase4h-pairing-native-server.mjs and test/phase4h.test.mjs. Evidence runners/scripts and exact SHA changes are enumerated in phase4h-final-manifest.json. build-web's optional evidence argument prevents overwriting Phase 3A evidence. run-android uses Phase 4H-specific backup artifacts and refuses to overwrite an existing Phase 4H attempt. No public bootstrap/production gateway was added.

## PASS / FAIL / BLOCKED / NOT TESTED matrix

| Verification | Result | Evidence / limits |
| --- | --- | --- |
| Final frozen Android app/test assembly | PASS | website-phase4h-frozen-build-jvm.log; BUILD SUCCESSFUL in 28m32s |
| Fresh JVM | PASS | 312/312, 39 suites; 0 failures, 0 errors, 0 skips |
| Intermediate app/JVM revision | PASS | 312/312, separate earlier log; not substituted for final sources |
| Initial Android build | FAIL | Test compile source changed during build; no JVM pass claimed |
| Initial Windows full backend | FAIL | 101 tests: 97 pass, four fail, zero skip; TLS substitution + three raw socket failures |
| First isolated Linux full backend | FAIL | 101 defined: 97 pass, three fail, one platform-specific named-pipe skip; fixture missing twice + HTTP framing parser |
| Corrected Linux full backend | FAIL | 101 defined: 97 pass, three fail, one platform-specific skip; raw 50/100 sockets + retry-count assertion |
| Final focused pairing/recovery/archive | FAIL | 14 defined: 13 pass, one unrecovered concurrent-read transport failure; phase4h-linux/focused-final.log |
| Actual strict backend TCP TLS | PASS in Linux | Corrected full backend log; wrong issuer/name rejected, exact leaf asserted, signed mutation replay denied; Windows FAIL retained |
| Python isolated TLS proof | PASS, limited | Independent HMAC fixture; not a substitute for actual backend TLS |
| Website artifact build / scan | PASS | 3249 public variants; no scanned credential/private-field/issuer markers; release BuildConfig DEBUG=false |
| Browser | PASS | 10/10; seven viewports; zero console errors/external requests |
| Physical Android/iPhone/Safari/IME/a11y | NOT TESTED | Browser reduced viewport is a simulation, not physical keyboard acceptance |
| Native cold/warm, billing/Orders, Bluetooth permission, paired integrated/process recovery | BLOCKED | Zero JUnit methods executed; emulator ready=false after 1,82,236 ms; existing 180-second setup bound; starts with 278 MB available |
| Native prepared runners / test APK | COMPILED ONLY | Existing financial/native suites and new paired path not credited as passed |
| Historical 15-second API36 failures | OPEN | Not erased by build or historical warm passes |
| Default host Gradle loopback issue | SCOPED WORKAROUND VERIFIED | Process-only jdk.net.unixdomain.tmpdir; no claim all host defaults repaired |
| Linux backend | FAIL | phase4h-linux/backend.log; exit 1 |
| Linux backend-final | FAIL | phase4h-linux/backend-final.log; exit 1 |
| Linux focused-final | FAIL | phase4h-linux/focused-final.log; exit 1 |
| Linux shared-ip | PASS | phase4h-linux/shared-ip.log; exit 0 |
| Linux capacity-high | NOT EXECUTED / no completed evidence | Not counted as a completed test |
| Linux stress | FAIL | phase4h-linux/stress.log; exit 1 |
| Separate shared-IP identity-recovery scenarios | PASS | 20: PASS; 50: PASS; 100: PASS; raw full-suite failures remain failed |
| Real FCM delivery, sender/outbox, production enrollment/account/CPU | NOT TESTED / BLOCKED | Explicitly disabled; foreground/manual local fallback only |

Browser measured throttled load 6,365 ms, search 229 ms, gzip catalog 30,956 bytes. Screenshots visually inspected: catalog-mobile.png and many-sizes-mobile.png. No design changes made in this phase.

## All capacity attempts and measured quota envelope

Profiles below use final paired local D1 schema, 3,201 catalog variants, full catalog browsing per customer, edits/confirmed adjustments, claims, completion/duplicates, device sync/polls, revocation probes, encrypted archives/restores and protected cleanup. 500 profiles require TWO compressed cycles in one DB. They are not provider or real-day tests. 1,000 is stress only.

| Orders/lines/customers/devices | Functional outcome | Cycles | Known busy-day reads | Writes | Requests | Post-archive bytes |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| Linux 1000/20/100/10 | FAIL | 0/1 | partial 32,94,536 | partial 93,107 | partial 12,779 | unverified |
| Windows 200/5/20/5 | PASS | 1/1 | 7,10,423 | 41,364 | 8,707 | 26,46,016 |
| Windows 300/5/20/5 | PASS | 1/1 | 10,51,599 | 55,308 | 10,557 | 32,97,280 |
| Windows 400/20/20/5 | PASS | 1/1 | 13,99,739 | 69,206 | 12,409 | 44,48,256 |
| Windows 500/20/100/10 | FAIL | 0/2 | partial 16,48,226 | partial 47,183 | partial 6,368 | unverified |
| Windows 500/20/20/5 | PASS | 2/2 | 18,03,302 | 1,07,824 | 14,395 | 92,28,288 |

The scheduling-held Windows high-profile log contains only paused-before-fixture and no DB; it is not a failed or completed cohort. Its explicitly launched attempt is phase4h-capacity-500-100-10-final.log and remains FAIL. Earlier Phase 4F/4G attempts also remain FAIL; no silent relabel or source replay occurred.

The five-device two-cycle API totals are 3,425,399 reads, 95,491 writes, 28,462 requests and maximum 46 queries/invocation. Per-cycle APIs: 1,695,988/44,929/14,067 then 1,724,693/45,993/14,202 (reads/writes/requests). Source archive adds 73,891 reads and 57,262 writes, including snapshot/index/cleanup costs. The modeled busy day charges all cohort setup/denial overhead and that entire archive to one day: 1,803,302 reads, **107,824 writes**, 14,395 requests. This is a conservative schedule model, not a measured provider calendar day. Raw local counters include committed responses before outer-response loss. Private fixture seed/schema/provisioning, independent restore imports, provider failed-query billing, real challenge/sender/renewal and shared-account costs remain excluded/unverified.

148 signed/read transport failures were recovered in that five-device cohort with original immutable identities; zero unrecoverable client failures or workload authorization denial. All 150 protected orders are field-compared after cleanup. The failed ten-device attempt has only partial cost/storage metadata and no verified archive total. No production CPU claim: aggregate Node CPU includes client/KDF, excludes separate workerd CPU, and cannot establish Cloudflare's 10 ms allowance.

## Archive scalability, storage and finite retention

The local archive snapshots all 15 tenant catalog/order/event/admission/security/receipt tables in one D1 transaction, then exports deterministic keyset pages of 32 rows, bounded plaintext and encrypted chunk inputs. AES-GCM chunks bind shop/snapshot/table/index/previous hash; an authenticated manifest tracks committed pages and supports resume. Two file copies are hash checked, restored fields are compared in an isolated DB, and restored authority/devices/grants are quarantined/revoked/consumed. Nonces, audit, bill links and permanent operation receipts survive. Owner-confirmed cleanup removes only the temporary snapshot after verified restoration; normal terminal compaction separately requires collected/resolved bill evidence. No hosted destructive cleanup or live restore exists.

Focused synthetic receipts produced a **19,726,789-byte plaintext archive in 180 chunks**, exceeding 16 MiB, with resume, post-snapshot source mutation isolation, corrupted-copy rejection, cutoff denial and synthetic quota-trigger atomic rollback. Process RSS in retained Linux test samples was 251–314 MB; chunked bounds do not mean constant zero memory. The manifest itself remains bounded (8 MiB); owner disk capacity is finite. The tested copies are independent files on one PC, **not independently accepted physical media**. The private prototype is not an accepted hosted tenant/owner export UI.

Five-device 500/day measured base/hot/post-cleanup sizes: 1,265,664 / 15,056,896 / 9,228,288 bytes after two cycles. Conditional linear retained allocation: **30d 120,705,024; 60d 240,144,384; 90d 359,583,744 bytes**. No-terminal-cleanup sensitivity: 208,134,144 / 415,002,624 / 621,871,104 bytes. An additional modeled 30% of all orders staying uncollected gives 90d 452,155,754 bytes, above the unchanged 450,000,000-byte admission cutoff. These interpolations are not executed multimonth/max-input workloads. Temporary full-ledger snapshot allocation was not measured in the completed Windows cohorts; later harness instrumentation records it but earns no retroactive result.

Full snapshot writes grow with the permanent ledger, so 500/day long-term archival headroom remains blocked even when retained allocation looks smaller than 500 MB. No unlimited storage or permanent ₹0 guarantee. Preserve every active, uncollected and unresolved record. On cutoff/quota failure, no invented confirmation, retain cart/recovery identity, and keep offline POS independent.

## Published limits and release gates

Official sources rechecked: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) — 5M reads, 100k writes/account/day, indexes counted; [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) — 500 MB/database, 5 GB/account, 50 queries/invocation. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) — 100k requests/day, 10 ms CPU/HTTP. [Pages limits](https://developers.cloudflare.com/pages/platform/limits/) — finite Free build/file limits. [Firebase pricing](https://firebase.google.com/pricing) lists FCM no-cost; a secure [HTTP v1 sender](https://firebase.google.com/docs/cloud-messaging/send/v1-api) still needs trusted credentials and has not been implemented/approved. Existing Firebase/printing remains unchanged; no order mirror.

Actual owner account eligibility/commercial-use acceptance, plan/payment settings, shared quota consumption and provider CPU are unverified. No paid upgrade/card/domain/fallback/extra accounts. Background push is unsupported for release; local foreground/manual reconciliation is preserved. A synthetic challenge issuer is never a production artifact. The scanned customer build has no issuer/server/private credential code, but remains explicitly localOnly=true/productionReady=false. Static DEBUG guards are not a release APK penetration test.

## Weighted completion and stop

Earned target weights remain **10 + 4 + 14 + 10 + 10 + 11.5 + 10 + 10 + 3 + 1 = 83.5%**. Earlier accepted local billing features retain their historical credit; the new native lifecycle is not counted as tested. No production-authentication/provider/physical readiness credit is awarded from synthetic data.

Exact executed commands and retained failures: tools/website-proof/evidence/phase4h-commands.md; per-mode logs/results and phase4h-final-manifest.json identify revisions, files and artifacts. Source hashes and readability are checked before handoff.

**STOP after Phase 4H. Do not begin Phase 5, enroll real devices, connect production Firebase/Cloudflare, deploy, enable paid services or access live data.** Owner review should approve only a concrete next corrective scope, then separately approve any physical/account/trust validation. No Phase 5 approval is requested or inferred here.
