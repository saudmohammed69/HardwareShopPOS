# Phase 4B — secure authentication, enrollment and cross-device authorization

Date: 18 October 2026, Asia/Kolkata. **Local synthetic development/testing only.** Overall verified completion **83.5%**, unchanged; **16.5% remains unearned**. No Phase 4C or release work is authorized.

## Implementation and security findings

The existing local owner/worker passwords and remembered Firebase account are reusable for local POS access, but `CloudSessionStore` does not establish independent server membership. A shared Firebase UID cannot prove which locally selected worker is using it. The old bearer adapter is explicitly a synthetic fixture and must never become production authentication.

Added an isolated `SIGNED_LOCAL` adapter with trusted demo-only RS256 ID tokens, issuer/audience/expiry checks, finite verification-key and membership freshness, independent shop roles, recently authenticated owner approval, installation-bound P-256 keys, request/body/URL/token-bound ES256 proof, persisted replay nonces, shop-scoped session revocation, membership removal, dual-key rotation and audit records. Atomic D1 authorization assertions prevent writes after a concurrent revocation. No token-provided role, shop or device identity grants authority. Missing/expired trust configuration fails closed with no fixture fallback.

Enrollment starts PENDING and requires existing owner approval. Persisted request/key identity recovers lost responses; changing/reinstalling the key requires new approval. Enrollment count, approval and worker removal use transactional assertions to prevent stale/concurrent mutations. A review found that an initial shop-owner session-revocation design could revoke another shop's user sessions; the final implementation updates only that membership and tests the isolation. Provider-global disabled/revoked status is trusted server data, never set by arbitrary shop owners.

See [authentication architecture and explicit production gates](WEBSITE_AUTH_SECURITY_DESIGN.md) for exact trust, signature, enrollment, rotation and offline-revocation semantics. It includes official Firebase/Android references. The signed transport uses only fixed loopback 4318, injectable synthetic token source and the existing transport/repository interface; it is not wired to production/Hilt. Legacy printer identity, Firebase sessions and FCM/remote printing remain unchanged.

## Recovery checkpoint and compatibility

Before security-sensitive persistence work, 327 allowlisted source files were copied, SHA-256 manifested and restored to a separate isolated recovery directory. Every recovered file matched the original. Evidence: `tools/website-proof/evidence/phase4b-prechange/manifest.json`, `source/`, `isolated-recovery/`. No live database snapshot or production restore was performed. No production records were accessed.

Compared with that checkpoint, existing proof `worker.mjs`, `runtime.mjs` and the synthetic `native-server.mjs` changed. The original `WebsiteOrdersUiTest.kt` now waits for both Room READY and the actual Compose billing control within the same original 15-second deadline. All existing main application source, including `WebsiteOrdersRepository`, billing transactions, products, GST/payment/stock logic, AppDatabase Room **11**, BackupManager format **8**, Firebase and printer code is unchanged. New security classes/files are additive. D1 security tables are loaded only into fresh signed local fixtures. No destructive fallback or migration was added. Critical hashes: `evidence/phase4b-critical-source-verification.json`.

## Executed tests and preserved evidence

Unless stated otherwise, evidence filenames below are under `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS\tools\website-proof\evidence`. Build logs are under `C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations`. Commands and final APK hashes are recorded below; earlier failed evidence is retained.

| Check | Actual result | Evidence |
| --- | --- | --- |
| Initial signed adversarial suite | **13/13 pass**, 37.054 s | `tools/website-proof/evidence/phase4b-security-first.log` |
| Full backend/model/archive/security suite before body hardening | **53/53 pass**, 147.223 s, includes 14 security tests | `tools/website-proof/evidence/phase4b-backend-final.log` |
| Final backend after bounded streaming proof hashing | **54/54 pass**, 93.644 s, includes 15 security tests | `tools/website-proof/evidence/phase4b-backend-verified.log` |
| Security overhead/nonce-expiry harness | **Pass**: 100 signed + 100 fixture sync requests, 100 accepted proof rows reduced to 1 after test-only expiry and normal request cleanup | `phase4b-security-overhead.json`, `phase4b-security-overhead-final.log` |
| Initial overhead harness | **Harness failed**, D1 rejects `PRAGMA page_count` with SQLITE_AUTH; corrected to supported query `meta.size_after`, then rerun successfully | `phase4b-security-overhead.log` retained |
| JS syntax checks | **Pass**, security, fixture, secure bridge, overhead harness | `node --check` commands below |
| Complete direct JVM JUnit regression, final compiled source | **312/312 pass**, 2.699 s; initial same-phase run also passed 312/312 in 7.112 s | `phase4b-jvm-direct-final.log`, `phase4b-jvm-direct.log` |
| Initial Android build | App APK assembled; test compilation **failed** on an incorrect new test import (`util.Result` instead of `domain.model.Result`), corrected without app logic changes | `website-phase4b-build.log`, 24m 38s, preserved |
| Corrected Android app/test APK and JVM test-source assembly | All three requested assembly/compilation tasks passed; combined invocation reached the known Gradle worker transport failure | `website-phase4b-build-rerun.log` |
| Normal `:app:testDebugUnitTest` | **Environment blocked**: repeated `Unable to establish loopback connection` / `SocketException: Invalid argument: connect` in `PipeImpl`/Gradle TestWorker. Stopped the failing launch loop; no passing app-test count claimed from this path | Same combined build log, retained |
| Initial native Android Keystore and metadata recovery | **2/2 pass**, 0.322 s | `phase4b-keystore-recovery-initial.log` |
| Signed Android enrollment→existing once-only billing→response loss/restart→revocation→ordinary offline counter billing | **1/1 pass**, 21.818 s | `phase4b-signed-native.log` and diagnostics |
| Initial existing native regressions | **41/51 pass, 10 fail**, 35.682 s; stale pre-4A synthetic admission schema | `phase4b-native-regressions.log` and diagnostics retained |
| Current-schema native rerun | **50/51 pass, 1 fail**, 44.628 s; UI test observed Room READY before Compose rendered its billing action | `phase4b-native-regressions-rerun.log` and diagnostics retained |
| First clean app/test APK and JVM compilation after UI readiness wait | **BUILD SUCCESSFUL**, 1m 32s; 79 tasks, 6 executed/73 up-to-date | `website-phase4b-build-verified.log`; APK hashes retained in `phase4b-ui-first-apks.json` |
| First current-schema native suite with READY-control wait | **51/51 pass**, 67.547 s; subsequent repeat below exposed a second timing race, so this alone did not establish UI stability | `phase4b-native-regressions-final.log`, diagnostics, synthetic backup and cart screenshot |
| Repeat UI plus reminder/navigation/IME baseline | **3/4 pass, 1 fail**, 74.195 s. Reminder, navigation and pipe IME pass; Orders UI times out waiting for a checklist change after it clicks during the previous busy action | `phase4b-ui-baseline-final.log` and diagnostics retained; failed source revision preserved as `phase4b-ui-first-sync-test.kt` |
| Accepted final app/test APK and JVM compilation after enabled-control waits | **BUILD SUCCESSFUL**, 1m 27s; 79 tasks, 6 executed/73 up-to-date | `website-phase4b-build-accepted.log`; `phase4b-final-apks.json` |
| Accepted final native regression suite | **51/51 pass**, 87.129 s | `phase4b-native-regressions-accepted.log` and diagnostics |
| Accepted final repeat Orders UI + reminder/navigation/IME | **4/4 pass**, 62.786 s; Orders flow repeats successfully after the final synchronization repair | `phase4b-ui-baseline-accepted.log`; billing-navigation entries **971–7,996 ms**, original 15-second limits retained |
| Accepted-APK Android Keystore and enrollment metadata tests | **2/2 pass**, 0.223 s | `phase4b-keystore-recovery.log` |
| Accepted-APK signed native approval/billing/recovery/revocation/offline-counter test | **1/1 pass**, 4.373 s | `phase4b-signed-native-accepted.log` and diagnostics |

The 15 security tests cover forged/expired/wrong-issuer/audience/key/algorithm/timing tokens; stale trust/revocation data; no signed-mode bearer fallback; independent membership; ignored role/shop/device claims; unauthorized owner settings/catalog; cross-shop access; wrong device keys; raw body/URL/token substitution; replay/stale proofs; oversized-body rejection before nonce writes; pending/expired/idempotently recovered enrollment; recent owner auth; revoked/reinstalled installations; dual-key rotation and lost-response reconciliation with the new key; shop-scoped revocation; all-device membership removal; stale removal CAS; revocation between initial verification and atomic write; two-device sticky claim competition; repeated/lost-response completion/cursor recovery; customer capability isolation and accepted-order edit denial; token/key/capability absence in tested session/audit responses. These are actual local D1/crypto tests, not real Firebase or Cloudflare delivery tests.

Initial emulator cold boot remained ADB offline and was stopped to free build resources; emulator logs are retained. No device tests are counted from that startup. The known API 36 IME instability and normal Gradle test-worker loopback transport failure remain unresolved unless explicitly verified otherwise below. Acceptance timeouts are unchanged.

The second isolated emulator startup, after compilation, completed in 63.735 seconds. Host free physical memory was only about 532 MB during the overlapping first startup/build; only our test servers/emulator were stopped to free memory. No unrelated apps were closed. Guest Wi-Fi/mobile data were disabled; ADB reverse connects loopback fixtures only.

The legacy native bridge had hardcoded `.local-state/phase3b-native`, which retained the old `(shop,bucket,window)` admission primary key. The current Phase 4A upsert targets `(shop,bucket)`. Read-only inspection and read-only SQLite `EXPLAIN` independently confirmed `ON CONFLICT clause does not match any PRIMARY KEY or UNIQUE constraint`; no old rows were changed. Evidence: `phase4b-stale-native-schema.log`, `phase4b-stale-native-conflict.log`. The bridge now defaults to a new isolated UUID directory, while an explicit UUID can preserve a current-schema fixture for restart tests. Old fixtures are retained, not deleted or silently migrated.

After that fixture correction, all ten native repository tests and all forty billing/backup/printing/session safety tests passed. The remaining Compose test failed at `WebsiteOrdersUiTest.kt:66`: it waited for Room READY, then immediately searched for `website-open-billing`. Room commits before its asynchronous Flow is rendered in Compose. Adding actual READY-control readiness produced one full 51/51 pass, but a repeat exposed the same race earlier: the preparation checkbox click occurred before the previous ViewModel action finished, and the checklist never changed. `WebsiteOrdersViewModel.task` correctly rejects another action while busy; the UI disables its controls during that interval. The final test retains each existing **15,000 ms deadline**, checks the original Room status plus the rendered/enabled control and idle ViewModel, and then performs its original clicks and financial assertions. It does not retry clicks, alter application flags or increase timeouts. Neither menu nor IME acceptance limits, application screens or working billing logic changed.

The first baseline keyboard execution passes its existing 15-second IME assertion, but no IME implementation/environment repair was made; the historical API 36 intermittent failure remains open. Its billing-navigation entries were **758–13,175 ms**, all below the unchanged 15,000 ms limit. This is local emulator performance, not physical-device acceptance.

The accepted artifact executes **57 distinct Android test methods successfully** across the 51-test suite, the four-test baseline/repeat and the three security tests (**58 executions**, because the Orders UI method appears twice). The two earlier full/UI runs and the earlier four-test repeat failures remain recorded above; they are not counted as successful final-source verification. Both accepted Orders UI executions pass with rendered/enabled-control and idle-ViewModel predicates. The historical API 36 IME problem remains unresolved despite two Phase 4B baseline passes; this phase did not independently repair its cause. The Gradle worker transport failure also remains unresolved despite successful direct JUnit execution.

The signed Android integration uses a synthetic OWNER principal. Signed WORKER authorization/cross-shop/escalation cases are tested against the real local Worker/D1 backend; existing native worker permissions and UI are tested separately. Real Firebase owner/worker identity mapping is not claimed.

All accepted runs use the app SHA-256 `0b59099eb4bd46802c77c513f8d035befbc5f0c7ccea6b3d74456b8ab1573ead` and test APK SHA-256 `1161a255f789f3aa1f4f1f9c86458a6a13556c009c0c8e9d76cebbf5696a920e`. The final repair changes only the test APK; the app APK remained unchanged through both UI synchronization repairs. The synthetic servers and disposable read-only emulator were stopped after verification. Synthetic control values were removed from host and emulator; no deployment or background production task remains active from this phase.

## Capacity and security gates

The signed empty-sync measurement adds **7 reads and 5 writes per request** over fixture mode. Allocated signed D1 size grew from 159,744 to 180,224 bytes for 100 proof nonces; fixture remained 94,208. The earlier Phase 4A full-order projections exclude this schema. An illustrative 10-device/30-second/8-hour poll schedule adds 48,000 auth writes/day; combined with the previous 56,470 order/archive-day writes it exceeds the published 100,000 Free account write limit. This is a model, not a full signed-order capacity run. See updated `WEBSITE_ZERO_COST_AUDIT.md`; the 450,000,000-byte cutoff remains intact.

Production remains blocked on independently verified worker UID/membership mapping, real trusted key rotation/freshness and revocation synchronization, initial owner bootstrap, production owner enrollment/rotation interface and durable staged-key recovery, physical reinstall/Keystore assurance, secure TLS deployment, orphaned billing-claim reconciliation, security-table archive/restore and final signed-schema read/write/CPU/storage capacity. No real FCM delivery, physical Bluetooth printer, live account eligibility or production quota failure is claimed.

Revocation prevents new online claims and server completion; an already disconnected device with a previously authorized sticky claim can still finish the existing local once-only billing transaction. It cannot synchronize completion after revocation. The claim must stay locked until owner reconciliation; silently reallocating it could duplicate stock/bills. Ordinary offline counter billing must continue. This limitation is documented rather than weakening the existing transaction or pretending instantaneous offline revocation is possible.

## Exact source/report changes

Existing proof files: `tools/website-proof/worker.mjs`, `runtime.mjs`, `native-server.mjs`; existing test: `app/src/androidTest/java/com/hardwareshop/pos/WebsiteOrdersUiTest.kt` (same-deadline UI synchronization only).

New proof files: `security.mjs`, `security-schema.sql`, `security-fixture.mjs`, `secure-native-server.mjs`, `security-overhead.mjs`, `test/phase4b.test.mjs`, `run-signed-native.ps1`, all under `tools/website-proof/`.

New Android files: `app/src/main/java/com/hardwareshop/pos/data/website/WebsiteDeviceKey.kt`, `WebsiteEnrollmentStore.kt`, `SignedLocalWebsiteTransport.kt`; `app/src/androidTest/java/com/hardwareshop/pos/WebsiteDeviceSecurityTest.kt`.

Reports: `WEBSITE_AUTH_SECURITY_DESIGN.md`, `WEBSITE_PHASE4B_TEST_RESULTS.md`, `WEBSITE_PROGRESS.md`, `WEBSITE_CODE_AUDIT.md`, `WEBSITE_ZERO_COST_AUDIT.md`. Evidence: phase4b checkpoint/recovery, backend/security logs, overhead JSON/logs, build/native/JVM results and source-compatibility comparisons. No dependencies or lockfiles changed.

The 51 final native cases comprise ten website repository/integration tests; one Compose worker-to-existing-billing flow; three WAL/backup/identity safety tests; 23 billing integrity tests; four advanced payment/draft tests; four pipe/catalog migration tests; two Bluetooth permission tests; and four local cloud-session/worker-permission tests. They use synthetic Room databases and local D1 fixtures. Physical Bluetooth receipt output, real provider identity/token delivery and FCM notification delivery remain untested.

## Commands

Backend commands, from `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS\tools\website-proof` with approved loopback access:

```powershell
node --test --test-concurrency=1 test/phase4b.test.mjs *> evidence/phase4b-security-first.log
npm test *> evidence/phase4b-backend-final.log
npm test *> evidence/phase4b-backend-verified.log
node security-overhead.mjs *> evidence/phase4b-security-overhead-final.log
node security-overhead.mjs *> evidence/phase4b-security-overhead-rerun.log
node --check security.mjs
node --check security-fixture.mjs
node --check secure-native-server.mjs
node --check security-overhead.mjs
node secure-native-server.mjs
node native-server.mjs
```

Build command, from the HardwareShopPOS project, using existing offline dependencies:

```powershell
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase4b-build.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin',':app:testDebugUnitTest' -Log 'website-phase4b-build-rerun.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase4b-build-verified.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations\jvm_tests.ps1' *> tools/website-proof/evidence/phase4b-jvm-direct-final.log
& tools/website-proof/run-signed-native.ps1
$taskClasses='com.hardwareshop.pos.WebsiteNativeIntegrationTest,com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.BillingIntegrityTest,com.hardwareshop.pos.AdvancedBillingIntegrationTest,com.hardwareshop.pos.PipeBillingIntegrationTest,com.hardwareshop.pos.BluetoothPermissionTest,com.hardwareshop.pos.data.remote.CloudSessionInstrumentedTest'
& tools/website-proof/run-native.ps1 -Classes $taskClasses -Log 'phase4b-native-regressions.log'
& tools/website-proof/run-native.ps1 -Classes $taskClasses -Log 'phase4b-native-regressions-rerun.log'
& tools/website-proof/run-native.ps1 -Classes $taskClasses -Log 'phase4b-native-regressions-final.log'
& tools/website-proof/run-native.ps1 -Classes 'com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest' -Log 'phase4b-ui-baseline-final.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase4b-build-accepted.log'
& tools/website-proof/run-native.ps1 -Classes $taskClasses -Log 'phase4b-native-regressions-accepted.log'
& tools/website-proof/run-native.ps1 -Classes 'com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest' -Log 'phase4b-ui-baseline-accepted.log'
& tools/website-proof/run-signed-native.ps1 -Log 'phase4b-signed-native-accepted.log'
```

## Progress and next authorized decision

**83.5% verified, 16.5% remaining, no new progress awarded.** Local synthetic security proof does not satisfy the ledger's real-identity/provider/sender gates. Stop after Phase 4B. Recommend a separately approved Phase 4C review of identity mapping/bootstrap, offline revoked-claim reconciliation, staged-key recovery, security archive/restore and bounded signed-schema quota/CPU feasibility. Production connection or deployment needs explicit fresh approval; no Phase 4C work was started.
