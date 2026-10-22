# HardwareShopPOS Phase 4C — Local Security Integration and Capacity

Updated 19 October 2026, Asia/Kolkata. Local implementation and synthetic testing only. Local verification is closed with the explicit release/capacity gates below; no production readiness is claimed. No deployment, production Firebase/Cloudflare connection, payment activation, real device enrollment or live shop data operation occurred.

## Result and boundaries

The local proof now maps individual provider UIDs to trusted people, shop memberships, worker records and enrolled keys. Shared identities fail closed. Initial owner creation requires an out-of-band UID/key-bound grant; an authenticated owner separately approves individual workers and their installations. Native staged-key recovery survives a lost response and actual app process death. Restored security records are quarantined, never imported as live authority. Safe read-only synchronization costs zero persistent nonce writes; successful mutation proof and existing business statements share one atomic guard, while valid failed mutations still consume their proof before an error response.

Existing POS financial transactions, stock/prices, payment/GST/pipe behavior, screens/navigation, Bluetooth printing, unified Bill History, Room 11 and BackupManager format 8 remain unchanged. Real provider authentication is disabled. The signed native adapter remains DEBUG-only, fixed to loopback and outside Hilt. A 353-file pre-change source/test/report checkpoint was copied and SHA-256 verified. Evidence is `tools/website-proof/evidence/phase4c-prechange/manifest.json` and `source/`.

**Affected integration stopped for review:** revoked sticky billing claims accept owner evidence but return REVIEW_REQUIRED. No endpoint transfers, cancels, completes or creates another bill for an uncertain claim. Final bill verification/completion after device revocation needs a separately reviewed protocol. Original ordinary offline billing remains independent.

## Executed verification

Counts below describe separate runs; repeated methods are not added together as unique coverage.

| Verification | Actual result | Evidence |
| --- | --- | --- |
| Complete backend before pooled transport | 64/64 passed, 111.338 s | `phase4c-backend-accepted.log` |
| Final focused security cases | 10/10 passed, 11.637 s | `phase4c-security-final.log` |
| Local keep-alive transport, real workerd/D1, 100 concurrent signed reads | 1/1 passed, 2.624 s | `phase4c-pooled-transport.log` |
| Complete backend with capacity's pooled transport | 65/65 passed, 49.405 s | `phase4c-backend-pooled.log` |
| Bootstrap grant replacement, one-use recovery proofs and focused security repeat | 11/11 passed, 17.486 s | `phase4c-security-bootstrap-final.log` |
| Full backend after bootstrap repair, before final monitor acceptance repeat | 66/66 passed, 53.699 s | `phase4c-backend-final.log`; superseded by the post-monitor repeat below |
| Final backend after allocation-monitor repair and bounded idle/request-lifetime pool | 66/66 passed, 51.730 s | `phase4c-backend-final-after-monitor.log` |
| App/test APK and JVM test compilation | Initial build passed, 10m 5s; final incremental build passed, 2m 17s, 79 tasks (7 executed, 72 up-to-date) | `work/advanced-operations/website-phase4c-build-first.log`, `website-phase4c-build-accepted.log` in the sibling original chat workspace |
| Direct complete JVM JUnit regression | 312/312 passed, 3.167 s | `phase4c-jvm-direct.log` |
| Normal Gradle `:app:testDebugUnitTest` | Environment-blocked before valid application-test execution: loopback/PipeImpl SocketException; failing task stopped, not counted as passed | sibling `work/advanced-operations/website-phase4c-gradle-worker.log` |
| Native Keystore, enrollment metadata/reinstall, durable staged-key uncertainty | 3/3 passed, 0.538 s | `phase4c-signed-native-final.log.keys.log` |
| Signed worker rotation/revocation and existing actual billing/payment/stock-once/history/offline billing | 2/2 passed, 5.899 s | `phase4c-signed-native-final.log` |
| Separate process: begin committed rotation with lost response | 1/1 passed, 0.553 s | `phase4c-signed-native-final.log.beginLostRotationForSeparateProcessRecovery.log` |
| New instrumentation process after app force-stop: recover staged key | 1/1 passed, 0.332 s | `phase4c-signed-native-final.log.finishLostRotationFromPreviousInstrumentationProcess.log` |
| Broad native billing/payment/stock-once, Orders UI/workflow, notification fixtures, backup/restore, pipes, Bluetooth permission and session regression | 51/51 passed, 71.166 s | `phase4c-native-regressions.log` |
| Orders UI/reminder/navigation/IME baseline | 2/4 passed, 128.766 s: Orders UI/navigation passed; reminder and IME failed | `phase4c-ui-baseline.log` |
| Intermediate UI recovery attempt | Interrupted after reminder focus failure; instrumentation stopped for emulator restart, no successful suite claimed | `phase4c-ui-recovery.log`, `phase4c-ui-recovery-before-restart.logcat.txt` |
| Fresh read-only emulator reminder/navigation/IME | 2/3 passed, 182.407 s: reminder and IME passed; navigation header failed the original 15-second wait | `phase4c-ui-fresh-emulator.log` |
| Eight signed-schema 1,000-order profiles | All eight completed: 5/20 lines × 20/100 customers × five/ten devices; each 850 completed, 50 unresolved cancelled, 100 uncollected | `phase4c-capacity-results.json`, per-profile JSON/isolated logs; earlier failed runs retained, two final profiles include unknown-cost lost submissions |

The 51 native methods comprise ten website repository cases, one Compose worker-to-existing-billing case, three WAL/backup/identity safety cases, 23 billing integrity cases, four advanced billing/payment cases, four pipe/catalog cases, two Bluetooth permission cases and four cloud-session permission cases. Seven separate native security methods passed on the accepted APK. Real Bluetooth receipt output, real FCM delivery, physical-device/Safari acceptance and provider behavior were not tested.

## Failure evidence and root causes

- First full backend run: 61/62; a restart test disposed its local server twice. Fixed test cleanup; complete 62/62 rerun passed in 137.993 s. Logs: `phase4c-backend-first.log`, `phase4c-backend-rerun.log`.
- Strengthened all-table archive fixture initially failed because no audit row had been created. Added a real enrollment audit event; now every one of the nine archived tables contains data, and quarantined restore comparison passes. Failed log: `phase4c-backend-archive-fixture-failure.log`.
- Atomic write-cost assertion initially omitted the public-config primary-key index: actual seven writes, expected six. A direct local D1 insertion independently measured two business/index writes. Correct accounting is two guard + three nonce/index + two business/index; previously nine. Failed log: `phase4c-backend-index-cost-failure.log`. No business implementation was changed to alter this measurement.
- Bootstrap review found that the SQL guard needed to compare the exact administrator grant again, rather than relying on a prior lookup. The guard now compares UID, key fingerprint, secret hash, expiry and use state atomically; a real D1 adversarial test replaces the grant between lookup and transaction and confirms denial. Idempotent bootstrap recovery also consumes a fresh one-use proof; replaying the original or recovery request is denied. The focused final 11-case repeat passed.
- Initial direct JVM invocation ran before compilation finished and found zero tests. It is NOT a pass. Preserved `phase4c-jvm-before-compilation.log`; the post-compilation 312-test execution is the accepted result.
- The initial newly added process-recovery method inferred a non-void Kotlin return. `javap` inspection caught it before native execution; final test source ends in a Unit assertion and the test APK was rebuilt.
- First signed Android attempt: key/metadata 3/3 passed; both network tests rejected enrollment with HTTP 403. Emulator time was 107 seconds behind the host. `adb root`/direct date setting were denied by the emulator system image; supported `cmd alarm set-time` aligned it to zero skew. The 60-second signature window was never widened. The next seven security methods passed. Evidence: initial `phase4c-signed-native-accepted.log`, `phase4c-native-clock-repair.json`, `phase4c-native-clock-alarm-repair.json`.
- Initial UI baseline: a SystemUI ANR dialog held current window focus; Gboard startup ANRs also appear in event logs. Reminder Espresso close-keyboard failed its normal root-focus check, and pipe IME visibility failed its unchanged 15-second wait. A fresh emulator passed reminder and IME, but navigation failed at `MenuKeyboardRegressionTest.kt:76` waiting for `billing-header-cart`. Navigation had passed the earlier run. **UI acceptance remains intermittent and unresolved; no application cause was independently repaired.** Evidence: `phase4c-ui-focus-systemui-anr.txt`, `phase4c-systemui-events.txt`, fresh UI logs. Test harness now applies existing guest-offline/wake settings only after services are ready; no UI timeout was increased.
- Normal Gradle worker again fails inside `sun.nio.ch.PipeImpl` / `UnixDomainSockets.connect` with `Unable to establish loopback connection` / `SocketException: Invalid argument: connect`. Production logic was not changed to work around it; direct JUnit does not imply that Gradle worker issue is fixed.
- First capacity attempt was stopped for host memory contention with compilation; no completed profile claimed. Earlier observed free memory was about 478 MB; the actual stop snapshot is 624,064 KB in `phase4c-capacity-first-aborted.json`.
- A later capacity launch did not execute because automatic approval review could not run after an account usage limit. It was retried only after the user's continuation.
- Resumed capacity run reached approximately 200 completed orders, then failed with loopback `connect EADDRINUSE`. Installed Miniflare sets `reset=true` for every dispatch. The optional Node-only pooled fixture uses the same workerd entry/original-URL headers and a maximum 100 keep-alive connections; full 65-case regression and exact signed-URL/replay/revocation checks pass with it. No dependency, Worker business statement, signature rule or requested customer concurrency was changed. Failure: `phase4c-capacity-socket-failure.log`.
- The pooled multi-profile run then completed its first two profiles but failed during a 100-customer edit on `UND_ERR_SOCKET: other side closed`. Evidence: `phase4c-capacity-pooled-socket-failure.log`; the abandoned partial database is retained. The same 100-customer profile passed in a fresh isolated process, without changing concurrency or application code. Remaining profiles run in separate processes. The exact cause of that closed connection is not independently established; a successful rerun does not erase the failed evidence or prove provider behavior.
- Both first 20-line/100-customer profiles lost a connection during submission. Bounded same-submission/cart retry recovered the five-device profile; the ten-device repeat later lost a connection at signed worker acceptance after ~500 completions and stopped without blindly retrying it. Its ECONNRESET messages include other in-flight submissions being torn down after that failure. Final local pool bounds one-second idle reuse and 16 requests per connection, still up to 100 connections/customers; the next ten-device profile passed. Exact closure cause and repeatability are not proven. Final successful 100-customer cases each contain two lost submission attempts whose D1 cost is unknown.
- Intermediate collection/history/analysis commands exited 1 because the required ten-device/100-customer JSON did not yet exist. The first 5-line history probe reached 90,000 orders, but no complete combined history result was claimed. Those logs/partial data remain; required-input validation now fails before allocating another history if a source is absent. Final collection/probes/analysis execute only after all eight profile artifacts exist. These missing-input attempts are not counted as successful capacity verification.

## Security and storage details

Trusted `auth_identity_bindings` maps one UID to one person; SHARED/absent/stale bindings deny access. WORKER additionally requires a unique shop worker link; role/shop claims in JWT and local owner flags cannot grant authority. Production needs separately authenticated individuals and explicit worker switching/reauthentication. The existing default Firebase session used by sync/remote printing must not be silently switched; an isolated Orders identity session and trusted provisioning/revocation source need separate integration approval and tests.

The owner bootstrap is administrator-provisioned, short-lived, bound to individual UID and proposed key, and cannot overwrite an existing owner. Removed memberships/revoked keys cannot self-reactivate. Loss of the sole owner installation requires separately reviewed trusted recovery; there is no automatic restore-to-owner shortcut.

Actual `WorkerEntity` has a local Long ID/username and no provider UID or globally stable worker identity. The design therefore requires an owner-reviewed mapping from a server-issued stable `worker_ref` to each installation's existing local account; names/phones/shared accounts/local IDs alone cannot establish it. Agreement between the separate Orders identity and existing billing's selected worker must be verified before any production handoff. That provider/native mapping is not implemented or credited by synthetic fixtures.

Security export snapshots all nine tables atomically in native local D1, encrypts AES-256-GCM/scrypt, verifies checksum plus a second copy and restores only into a new isolated store. Every imported user/member/device is disabled/inactive/revoked; bootstrap grants expire and nonce/audit/claim evidence survives. Fresh independent trust review is required before any new approval. Room 11 and BackupManager 8 are unchanged.

The security snapshot is a local database-administrator operation covering all fixture rows, not a tenant-scoped owner HTTP download. No such hosted route exists. Before production, an owner-facing export needs separately reviewed tenant scoping or distinct database-administrator authority; a shop membership alone must never authorize the whole-database snapshot. The 16 MB plaintext export bound fails closed rather than truncating security evidence.

Only exact GET session/events/storage routes skip durable nonce writes; bounded read replay is allowed inside the existing signed timestamp window, with proof/token/URL verification and fresh server membership/device checks each time. Mutations, including valid failed requests, retain durable nonce/revocation defenses. There is no client authorization cache. The one-row owner storage warnings and 450,000,000-byte cutoff remain unchanged; no active/uncollected/unresolved order is automatically deleted.

The read microbenchmark measured 100 signed empty syncs at 600 reads, zero writes and unchanged 204,800 bytes, versus Phase 4B's 800/500. Evidence: `phase4c-security-overhead.json`. This is not a full-day budget. Largest signed catalog batch uses 46 counted SQL statements, below the conservative 50 safety budget. Owner archive tooling remains local; a hosted owner cleanup protocol is not implemented or credited.

The separate physical-history probe found a genuine read-growth problem in local owner cleanup: `compactVerified` called a full COUNT/document-size summary twice in each of 71 daily batches. At 30,000 retained order rows, these summaries alone model 8,520,000 D1 reads/day. Cleanup now uses `measureAllocatedStorage`, a fresh one-row native allocation probe with the same policy thresholds; the explicit detailed summary remains available. All current callers used only the returned byte/policy fields; per-batch results are tagged ALLOCATED_ONLY. No encryption, archive, restore, owner attestation or guarded data statement changed. A matching 20-line/20-customer/ten-device workload measured archive reads 310,670 → 27,030 and unchanged total writes 84,110, with all 1,000 identities/850 archive links/150 full protected records verified. Before files remain in `evidence/phase4c-before-monitor-optimization/`. The complete 66-test post-change repeat passed.

## Exact source changes

Existing Android: `WebsiteDeviceKey.kt` (alias metadata), `SignedLocalWebsiteTransport.kt` (dual-key rotation and typed status errors); `WebsiteDeviceSecurityTest.kt` (four new native recovery tests, total seven). New Android: `WebsiteKeyRotationStore.kt` (atomic noBackup lifecycle). All paths are under `app/src/main/java/com/hardwareshop/pos/data/website/` or the corresponding `app/src/androidTest/java/com/hardwareshop/pos/` test path.

Existing proof: `worker.mjs`, `runtime.mjs`, `security.mjs`, `security-schema.sql`, `security-fixture.mjs`, `retention.mjs`, `test/phase4b.test.mjs`, `run-android.ps1`, `run-signed-native.ps1`. New proof: `security-archive.mjs`, `phase4c-capacity.mjs`, `phase4c-history-growth.mjs`, `phase4c-summarize.mjs`, `phase4c-security-overhead.mjs`, `pooled-fixture.mjs`, `test/phase4c.test.mjs`, `test/phase4c-transport.test.mjs`. All are under `tools/website-proof/`. Dependencies/lockfile are unchanged; Undici was already installed through Miniflare.

Reports: this file, WEBSITE_PROGRESS.md, WEBSITE_CODE_AUDIT.md, WEBSITE_AUTH_SECURITY_DESIGN.md and WEBSITE_ZERO_COST_AUDIT.md. Exact existing-file comparisons and final source hashes are kept in evidence. Critical comparison covers AppDatabase, BackupManager, BillingAndStockRepositories, WebsiteOrdersRepository, CloudSessionStore and HardwareShopFcmService; all unchanged.

Accepted app SHA-256: `d6ea7063e93cf80d929f036816c699ceaabae407c9f556cceeb6e97c199da80d`; test APK: `e2194f9bec1f59798db2f01696ad9100c1fe047d6ec2dff7f65c85a4462c6a5c`. See `phase4c-apk-hashes.json`.

## Exact test commands

Project: `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS`. Backend commands run from its `tools\website-proof` directory with authorized local loopback:

```powershell
node --test --test-concurrency=1 test/phase4b.test.mjs test/phase4c.test.mjs *> evidence/phase4c-security-first.log
npm test *> evidence/phase4c-backend-first.log
npm test *> evidence/phase4c-backend-rerun.log
npm test *> evidence/phase4c-backend-accepted.log
node --test --test-concurrency=1 test/phase4c.test.mjs *> evidence/phase4c-security-final.log
node phase4c-security-overhead.mjs *> evidence/phase4c-security-overhead.log
node --test test/phase4c-transport.test.mjs *> evidence/phase4c-pooled-transport.log
$env:PROOF_POOLED_TRANSPORT='1'
npm test *> evidence/phase4c-backend-pooled.log
node phase4c-capacity.mjs *> evidence/phase4c-capacity-final.log
node --test --test-concurrency=1 test/phase4c.test.mjs *> evidence/phase4c-security-bootstrap-final.log
node phase4c-capacity.mjs --profile=5,100,5 *> evidence/phase4c-capacity-5-100-5-rerun.log
& ./evidence/phase4c-run-remaining.ps1
& ./evidence/phase4c-finish-capacity.ps1
& ./evidence/phase4c-last-stress.ps1
node evidence/phase4c-close-reports.mjs
node evidence/phase4c-source-check.mjs
```

The remaining-run script records each exit and executes the other five `--profile=lines,customers,devices` combinations in separate processes, then `node phase4c-capacity.mjs --collect`, `node phase4c-history-growth.mjs`, `node phase4c-summarize.mjs`, and a pooled `npm test` into `phase4c-backend-final.log`. The history probe copies representative rows only into new, authority-disabled allocation fixtures. Its repeated archive references are byte-shape fixtures, not additional verified customer orders/bills/archives. It stops below the 450 MB application cutoff. First two raw profile files retain an earlier confounded backlog formula; their zero-backlog/per-order-savings estimates are rejected and superseded by `phase4c-capacity-analysis.json`.

The retained archive/index-failure logs were earlier `npm test` attempts using the same command and distinct evidence names. The first memory-aborted capacity used `phase4c-capacity-first.log`; the reset-socket attempt was renamed `phase4c-capacity-socket-failure.log`. No completed result is claimed from either.

From the project root:

```powershell
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase4c-build-first.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase4c-build-accepted.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:testDebugUnitTest' -Log 'website-phase4c-gradle-worker.log'
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations\jvm_tests.ps1' *> tools/website-proof/evidence/phase4c-jvm-direct.log
```

Loopback servers were `node secure-native-server.mjs` then `node native-server.mjs`, never both for capacity testing. Android commands from `tools/website-proof`:

```powershell
& ./run-signed-native.ps1 -Log 'phase4c-signed-native-final.log'
$taskClasses='com.hardwareshop.pos.WebsiteNativeIntegrationTest,com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.BillingIntegrityTest,com.hardwareshop.pos.AdvancedBillingIntegrationTest,com.hardwareshop.pos.PipeBillingIntegrationTest,com.hardwareshop.pos.BluetoothPermissionTest,com.hardwareshop.pos.data.remote.CloudSessionInstrumentedTest'
& ./run-native.ps1 -Classes $taskClasses -Log 'phase4c-native-regressions.log'
& ./run-native.ps1 -Classes 'com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest' -Log 'phase4c-ui-baseline.log'
& ./run-android.ps1 -Classes 'com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest' -Log 'phase4c-ui-fresh-emulator.log'
```

Only read-only Operations/API 36 `emulator-5598` with `ro.kernel.qemu=1` was used; guest Wi-Fi/mobile data were disabled before app tests. Final fresh boot readiness warnings occurred before Android services existed; the runner waited for services within its unchanged three-minute fixture-readiness deadline. Test acceptance deadlines remain 15 seconds. The emulator and synthetic Android bridges were stopped; synthetic control files were removed. The user's separate preview on 4317 was not stopped.

## Capacity results and remaining gates

All eight separate synthetic 1,000-order profiles completed in native local Miniflare 4.20260730.0/workerd D1: 5/20 lines × 20/100 customers × five/ten authorized shop devices. Each published 3,226 catalog variants, preserved 1,000 unique submission identities, completed 850 unique bill-linked orders, retained 50 unresolved cancellations and 100 uncollected NEW orders, verified 71 encrypted export/restore batches, and retained every archived identity/bill link. Each exercised 85 confirmed adjustments, 100 duplicate submissions, 50 lost-response identity recoveries, 17 claim retries, completion retries, enrollment/rotation/revocation, multi-device incremental/full/recovery sync and 4,800/9,600 compressed foreground polls. These are separate workload days, not 8,000 orders in one day or eight hours of actual Android background execution.

### Quota measurements and models

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

Five profile artifacts retain the legacy full-summary cleanup monitor; three use ALLOCATED_ONLY. The schema and signed API/order behavior are the same. The matching 20-line/20-customer/ten-device before/after run isolates the monitor repair. Do not present these as eight complete repeats after that repair. First two profiles predate only the bootstrap grant-guard tightening; bootstrap is not exercised by their daily workload, and the final 66-case suite separately verifies the repaired bootstrap.

Measured totals include successful-response API D1 metadata and actual source archive/cleanup SQL, including a clearly labeled 11-minute future-window nonce-deletion fixture after all API calls. The two final 20-line/100-customer cases each have two lost submission attempts: their exact D1 cost is unknown, so measured read/write totals are LOWER BOUNDS for those cases. Submission retry preserves the exact cart/capability/submission ID and is limited to two transport retries; no application error, edit or signed worker mutation is blindly retried. Earlier abandoned/failed profiles are not silently added to a successful day or discarded.

Hourly trusted identity refresh adds a separate MODEL of 240/480 writes/day (two non-indexed freshness updates per individual/hour), producing 84,150–84,590 before unknown costs. There is no real identity synchronizer. Separately measured fixture catalog seed is 6,340 writes, trusted additional UID provisioning 15/40 and archive-counter setup five: known local totals including these setups are 90,270–90,495. Initial signing-fixture identities/devices/schema creation are not metered as a complete production onboarding budget.

Dynamic-catalog read/request models scale the actually measured 20/100 visitors to 1,000; production Pages caching/routing is not tested or credited. Modeled reads range 3,457,253–3,816,741 and API requests 18,052–23,184, before unknown lost-attempt costs and hosted archive transport/authentication. Local archive SQL statements are measured separately; one HTTP request per statement is only an illustrative request model, not an implemented hosted protocol. Isolated restore SQL runs locally and is not claimed as Cloudflare account traffic.

Published limits rechecked 19 October 2026: D1 Free 5M reads/100k writes per account/day, 500 MB/database and 5 GB/account; conservative 50 SQL statements/invocation. Workers Free 100k requests/day and 10 ms HTTP CPU. Sources: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/). The maximum counted signed request is 46 statements. Published limits are not account eligibility or tested provider capacity.

| Lines/customers/devices | Measured API responses | Modeled API requests / 1,000 visitors | Local request p95 ms | Node CPU ms | Source workerd CPU ms | Hot / archived MB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5/20/5 | 11,192 | 18,052 | 658 | 61,453 | 139,531 | 7.04 / 5.19 |
| 5/20/10 | 16,324 | 23,184 | 654 | 80,234 | 197,125 | 7.07 / 5.22 |
| 5/100/5 | 11,752 | 18,052 | 2,594 | 70,063 | 158,766 | 7.05 / 5.20 |
| 5/100/10 | 16,884 | 23,184 | 2,510 | 82,797 | 198,266 | 7.07 / 5.22 |
| 20/20/5 | 11,192 | 18,052 | 1,009 | 68,375 | 187,719 | 12.32 / 6.47 |
| 20/20/10 | 16,324 | 23,184 | 1,460 | 90,453 | 238,766 | 12.31 / 6.47 |
| 20/100/5 | 11,752 | 18,052 | 5,043 | 72,313 | 185,422 | 12.39 / 6.45 |
| 20/100/10 | 16,884 | 23,184 | 5,787 | 97,046 | 246,016 | 12.38 / 6.50 |

CPU is local Node plus the surviving source workerd process; terminated isolated-restore workerd CPU is absent from the end snapshot. Request timings are loaded-host wall times, including the order/security request stage, not per-invocation CPU. No aggregate or mean here proves Cloudflare's 10 ms requirement. Actual storage reclamation, provider error metering and repeatable production concurrency remain unverified. The final ten-device stress pass does not erase its earlier intermittent loopback failures.

### History growth, monitoring repair and protected backlog

The matching workload reduced source archive reads **310,670 → 27,030**, with writes **84,110 → 84,110**. Cleanup still verifies copies, encryption, isolated restoration, owner attestation and unchanged row guards. Detailed full summaries remain explicit; cleanup probes fresh allocated size without a cache. At 90,000 rows, the full summary reads 180,000 rows/check while the repaired allocation probe reads one; 142 daily cleanup checks therefore cost 142 monitor reads instead of 25,560,000. Other operations are not assumed free.

| Lines | Representative history days | Order identities | Full uncollected / unresolved cancelled | Allocated bytes | Legacy summary reads/check | Repaired allocation reads/check |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 30,000 | 3,000 / 1,500 | 41,058,304 | 60,000 | 1 |
| 5 | 60 | 60,000 | 6,000 / 3,000 | 81,010,688 | 120,000 | 1 |
| 5 | 90 | 90,000 | 9,000 / 4,500 | 121,196,544 | 180,000 | 1 |
| 20 | 30 | 30,000 | 3,000 / 1,500 | 68,546,560 | 60,000 | 1 |
| 20 | 60 | 60,000 | 6,000 / 3,000 | 136,261,632 | 120,000 | 1 |
| 20 | 90 | 90,000 | 9,000 / 4,500 | 203,829,248 | 180,000 | 1 |

These are actual allocated D1 fixtures using the final schema/indexes and representative retained row shapes, not 90 days of authenticated workflow. Each day adds 850 compacted identities, 100 full NEW and 50 full unresolved cancellations plus events. Repeated byte-shape archive references are not additional verified exports/bills. Copied authority is disabled and no trusted JWT configuration is installed. No existing source is restored/overwritten, and all growth stays below the operational cutoff. Security history is copied from the measured cohort, not a full 90-day device/audit/revocation workload.

Day-90 allocation: 5-line: 121,196,544 bytes; 20-line: 203,829,248 bytes. This is materially different from simply repeating first-day allocated growth, which includes transient high-water/page-allocation effects:

| Representative profile | Linear day 30 MB | Day 60 MB | Day 90 MB |
| --- | ---: | ---: | ---: |
| 5 lines, 100 new uncollected + 50 unresolved cancelled/day | 120.56 | 239.87 | 359.19 |
| 20 lines, 100 new uncollected + 50 unresolved cancelled/day | 158.90 | 316.55 | 474.21 |

The first two raw profile files also retain an earlier per-order savings/zero-backlog formula. It confounds expiry metadata cleanup and order compaction; those estimates are REJECTED and superseded by phase4c-capacity-analysis.json. Neither linear projection nor the physical row-shape probe establishes production headroom.

The following sensitivity uses verified 20-line archive documents, removes only finalizedPosBillSyncId to represent the pre-billing document mix, and models full protected documents versus compacted summaries. It excludes every index, identity column, event, catalog/security row and fragmentation: these are document-only LOWER BOUNDS. Zero protected orders is hypothetical only after actual owner resolution/collection and verified archives, never automatic removal.

| Protected full orders/day | Day 30 document-only MB | Day 60 MB | Day 90 MB |
| ---: | ---: | ---: | ---: |
| 0 | 6.35 | 12.69 | 19.04 |
| 50 | 16.73 | 33.47 | 50.20 |
| 150 | 37.51 | 75.03 | 112.54 |
| 250 | 58.29 | 116.58 | 174.88 |
| 500 | 110.24 | 220.48 | 330.72 |
| 1000 | 214.13 | 428.27 | 642.40 |

Strict production capacity remains UNVERIFIED. A 1,000/day backlog of the measured representative full 20-line document mix alone models 642,401,047 bytes at day 90, excluding indexes/events/catalog/security; it cannot fit the 500 MB/database free limit. The permitted 69,821-byte adjusted-order example would require 942,583,500 document bytes for 150 protected orders/day over 90 days. Protected orders cannot be automatically removed. The unchanged 450,000,000-byte operational cutoff must stop new admission rather than lose records or switch to a paid service.

Maximum-input probes accepted exactly 32,768 bytes, rejected 32,769 with no extra order, preserved same-ID retry, and stored 25 lines with maximum text bounds. The max pipe fixture (999999999 lengths + 999999999.99 extra feet) stored 43499999959965 paisa. A 25-line confirmed-adjustment case retained a 69,821-byte document and rejected the next growth beyond the 96,000-byte limit without truncating the order. These are single bound cases, not 1,000 maximum-payload orders. The actual index probe measured 1,000 insert + 1,000 update writes without the secondary index versus 2,000 + 2,000 with it: 2,000 additional indexed writes, with indexed query plan verified.

Encrypted order/security files use two matching files on one synthetic disk. Independent physical copies, actual owner disk capacity and a production backup procedure are not demonstrated by that fixture. Security export is a local database-administrator snapshot, not a tenant owner download, with a fail-closed 16 MB plaintext bound. No hosted cleanup route or automatic protected-record deletion was added.

### Final local status and handoff

- Real individual Firebase identity, trustworthy installation-to-existing-worker binding, isolated Orders sign-in/reauthentication and trusted freshness/revocation feed remain disabled/unverified.
- Uncertain revoked/offline billing claims remain sticky; evidence intake cannot finalize or transfer them. A separately approved original-bill verification/completion protocol is required.
- Hosted owner archive authorization/tenant scoping/transport, actual account-wide usage/free/commercial/no-card eligibility, provider quota exhaustion and 10 ms Worker CPU compliance are unverified.
- Independent physical archive media/recovery, physical multi-device/mobile/Safari/printing and real FCM/sound/background delivery remain unverified.
- API 36 UI timing remains intermittent at the original 15-second limits; normal Gradle test-worker loopback execution remains blocked. Successful direct JVM/other Android suites do not resolve those failures.

Source checkpoint/APK hashes, exact changed files and commands are preserved in evidence and the sections above. Final source verification rechecks 353 checkpoint files, six protected POS files and unchanged dependencies. No live data, production Firebase/Cloudflare account, real device enrollment, paid activation or deployment was used. Final cleanup found no synthetic test workers/emulator/Android bridges and no temporary host control file. Cleanup did not target the user's separate 4317 preview; its current reachability is not claimed. Evidence: phase4c-final-cleanup.json.

**Weighted progress: 83.5%, unchanged.** Local Phase 4C stops with these explicit release/capacity gates. Recommended next work is a separately approved local blocker-resolution/design-review phase; do not start it, production integration or release without fresh approval.
