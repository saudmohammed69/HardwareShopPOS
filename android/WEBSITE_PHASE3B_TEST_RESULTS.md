# HardwareShopPOS Phase 3B native integration — local test record

17 October 2026, Asia/Kolkata. Status: Phase 3B local implementation and verification completed with one repeatable pipe keyboard regression still failing. Weighted project progress: 82.5%. Production capacity/security remain blocked; no deployment authorized.

## Scope and preserved boundaries

Read the supplied Master Blueprint v2.0, initial inspection, code/cost/progress audits, Phase 2/3A results, billing/backup/notification safety and retention reports before implementation. No separate Phase 1 results file exists; the initial inspection and Phase 2 backend/safety reports contain that earlier proof. Actual source is the technical authority. This phase is explicitly local only.

No production database, Firebase membership, Cloudflare account, paid plan or deployment is connected. Fixture transport is debug-only, fixed to `127.0.0.1:4316`, and refuses arbitrary endpoints. An explicitly provisioned synthetic database marker must match the connection's fixture ID, the local user must be logged in/currently active, and a Firebase-linked database is refused. Fixture owner/worker tokens are loaded from a no-backup local test file or a test transport; no production credentials are embedded. Release builds have no enabled website connection. Actual device enrollment/authentication remains blocked, not proven by fixtures.

Existing AppDatabase remains **Room 11** and BackupManager remains **format 8**. Orders use a new independent **WebsiteOrdersDatabase version 1**, with no destructive migration fallback. Existing migration history and the accounting schema are unchanged. There is no reason to invent an accounting migration for a separate new cache. Creation/reopen/rollback and existing migration regressions are tested separately; no live schema is migrated or downgraded.

Original products, selling prices, stock, financial rows and sync identities are not rewritten by publication/synchronization. Only ordinary successful synthetic billing changes synthetic stock/payments during tests. `BillingRepository` remains the financial engine; no second bill or payment engine is added. Existing GST/Cash/UPI/Credit/Split semantics remain authoritative. In particular, split payments legitimately have two components and fully unpaid credit has no cash receipt; the one-payment assertion applies to a paid single-method bill, not an alteration of those existing rules.

## Pre-change verification

- Source checkpoint preserved **295 files**, with hashes, in the chat's `work/website-phase3b-checkpoint`.
- Fresh unchanged-app `WebsitePhase2SafetyTest`: **3/3 passed, 30.301 s**. Actual BackupManager WAL snapshot, isolated restore/reopen and actual createBill stock-once retries passed before native persistence changes.
- Verified synthetic archive: `tools/website-proof/evidence/phase3b-prechange/2501d770f2ce-synthetic-recoverable.hwpos.zip`; SHA-256 `2501d770f2cef38952064ad3a764ab705b942e53f354253c413511fab1038314`, format 8, one synthetic product and bill. Manifest distinguishes the fresh archive from older retained examples.
- First expanded backend suite: **35/35 passed, 30.508 s**. Later batch/page additions require the final rerun.
- Initial native compilation failed on missing Compose runtime imports in AppNavGraph. Imports were corrected; failed evidence is preserved in `website-phase3b-initial-build.log`. No success is claimed for that build.

## Native design under test

`WebsiteOrdersDatabase` is a device-local inbox/cursor/claim-intent/publication-map cache. Server feed revisions and cursor commit in one cache transaction; stale/replayed rows cannot overwrite newer state. Owner checkpoints from verified archives remain terminal and cannot be billed. Unread/alerted/checklist state survives restart. Logout/authorization failures hide cached private UI; corruption or a missing cache migration must not trigger a destructive reset or break normal counter billing.

Safe catalog projection uses existing POS product variants and SyncJournal global product identities, preferring an already-mapped/foreign canonical identity. Local numeric product IDs are never treated as portable IDs. Default publication includes active products; owner hide/show choices are saved locally before sending and survive retries. Batches are bounded by both 40 records and the request-byte budget. Deactivated mapped products are hidden. Owner controls publish only shop name/phone/address and explicit daily IST hours/maintenance; no private purchase cost, stock, credentials, payment or bill data is sent to the public catalog.

The existing owner/worker navigation gains Orders and unread counts. Active/Completed/Cancelled lists show customer/pickup/item details. NEW acceptance requires a manual availability acknowledgement. Preparation uses a persisted per-size checklist; every line must be checked before Ready. Adjustments require a reason and customer confirmation, are server-priced/revision-checked and retain before/after/actor audit data. Confirmed changes return to Accepted for preparation. Nothing automatically checks, reserves or decrements stock at website checkout.

Native handoff requires Ready, acquires a sticky installation claim online, and persists its random token **before** the request. A lost response reuses that token. Another installation cannot take the claim. There is no automatic reassignment/expiry. Existing cart contents are kept until an explicit park/replace choice, and customer/current prices are reviewed before loading the original billing screen. The website estimate is not an invoice. Item/quantity changes at save time are rejected until confirmed through Orders; normal POS price/discount/GST/payment permissions still apply.

The website wrapper joins the **same Room transaction** as `BillingRepository.createBill`. A stable `web-<order UUID>` checkout key, one non-secret order→local bill origin and a pending-completion record are committed with bill/items/payment/stock. Failures before origin commit roll back all effects. A crash after commit is recovered from the main DB's origin/outbox, even if the separate Orders cache update was interrupted. Server claim UUID and actual SyncJournal bill UUID are retained as distinct identities; the existing SyncJournal identity is never overwritten. The completion feed carries the actual bill sync ID so another installation can resolve its normal synchronized Bill History record, or clearly wait for it without creating another bill.

The existing logical backup already transports non-secret Settings records, so origin metadata fits format 8. Local fixture provisioning and device claim tokens are excluded. Backup import validates origin IDs against the archive's actual bill rows. An existing database with website origins cannot be replaced by an older snapshot without owner reconciliation: that could erase a saved-bill/outbox fence while a server claim is still live. Isolated fresh restores remain the recovery test path. This is not a production cloud-safe restore/enrollment procedure.

Foreground reconciliation runs only while the app lifecycle is started and logged in, with manual Refresh Orders and visible stale/error state. A separate notification channel uses generic text, persisted deduplication and batched catch-up. Android permission/mute/DND remain authoritative. WEB_ORDER_EVENT branches before print-only fields and schedules independent bounded WorkManager reconciliation; logged-out processes defer to foreground authorization. PRINT_JOB retains its original authorization and Bluetooth worker path. No FCM sender is deployed, and real FCM/audio/background delivery is not claimed.

## Storage and affected integration stop

The **450,000,000-byte operational cutoff is retained**. New website submissions and bulk native catalog publication stop at that gate. Active/claimed/completion-recovery operations retain the reserved headroom and never delete protected orders. Owner-visible diagnostics show warning/critical/stop thresholds; production account telemetry, disk monitoring and independent archive recovery remain unverified. There is no automatic cleanup or paid fallback.

The prior 90-day estimate **458,276,864 bytes** already exceeded the operational cutoff. Native order adjustments/audits, public config and synchronization add costs; the old capacity figures cannot certify this new schema/protocol. Adjustment history and response pages are bounded; an over-budget adjustment is rejected with the original active order preserved for owner reconciliation. No new 90-day production headroom is claimed. The **production capacity/security integration remains stopped**. This does not prevent isolated local native testing.

## Planned coverage (execution results below)

- Real loopback HTTP publication, owner/active-worker permissions, private-field projection and hide/show retry.
- Incremental cursor rollback, replay, cache/checklist reopen, offline reads/retry, archive checkpoint protection.
- Two independent named accounting/cache databases and installation tokens racing for one server claim.
- Ten native concurrent createBill retries; one paid bill/payment/stock decrement/history link.
- Lost claim response; injected rollback immediately before origin/outbox; offline commit only after a verified claim; lost completion acknowledgement and restart recovery.
- Native pipe full lengths/extra feet with current POS rates/GST/UPI; ordinary counter billing remains independent while transport is offline.
- Backup origin preservation, exclusion of claim tokens, isolated restore and refusal to erase existing website bill origins.
- Local notification channel/permission/generic-content/deduplication and unchanged print notification state.
- Compose worker acceptance→checklist→Ready→review→park existing cart→existing CartScreen→actual save.
- Existing reminder/navigation/billing/pipe/payment/migration/print-related regression tests where available.

## Reproducible local commands

From the existing project root, with only the disposable debug emulator `emulator-5598`:

```powershell
& './tools/website-proof/run-android.ps1' -Classes 'com.hardwareshop.pos.WebsitePhase2SafetyTest' -Log 'phase3b-prechange-backup.log'
& 'C:/Users/mohammed saud/Documents/Codex/2026-10-15/fix-pipe-pricing-and-billing-flow/work/performance/build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase3b-test-build.log'

# tools/website-proof: local bridge creates only isolated synthetic D1 data
& 'C:/Program Files/nodejs/node.exe' native-server.mjs
# Separate terminal; only this emulator gets the loopback reverse mapping
& 'C:/Users/mohammed saud/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5598 reverse tcp:4316 tcp:4316
& 'C:/Users/mohammed saud/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s emulator-5598 reverse tcp:4320 tcp:4320
```

The customer test site is `http://127.0.0.1:4320`; the staff bridge is separate at port 4316 and never forwarded by the customer proxy. The staff bridge's owner-authenticated `/fixture/orders` is synthetic test tooling that gives test cases distinct admission buckets; it is not a customer API or production abuse bypass. Standard customer submissions retain normal abuse controls. Run build/browser/capacity/emulator workloads sequentially on this host. Final exact executed commands/results will be recorded below.

## Final gate

Partial Phase 3B credit reflects only locally verified implementation and test milestones; final verification remains failed until the repeatable pipe IME regression is resolved or validly reverified. Deployment, real Firebase enrollment/sender integration, production account capacity, full physical printer/phone/Safari behavior and any later phase require separate approval. Stop after Phase 3B reports and verification.

## Final execution results — 17 October 2026

| Check | Result | Evidence and limits |
| --- | --- | --- |
| Final Android app + test APK + JVM test-source compile | **PASS** — `BUILD SUCCESSFUL in 14m 11s`; 79 tasks, 13 executed / 66 up-to-date | Build log `work/advanced-operations/website-phase3b-menu-fix-build.log`. `compileDebugUnitTestKotlin` is compilation only; this turn did not execute all JVM tests. |
| Local backend regression | **PASS — 37/37** | `tools/website-proof/evidence/phase3b-backend.log`; Miniflare/native SQLite local synthetic test only. |
| Android native integration | **PASS — 10/10**, 96.841 s | `phase3b-native-integration-rerun.log`; synthetic catalog, worker roles/claims, synchronization, actual billing transaction, backup guard, pipe calculation and notification fixtures. No production identity or service. |
| Compose worker→billing flow | **PASS — 1/1**, 65.545 s | `phase3b-orders-ui.log`; checklist, Ready, review/park, original billing path. |
| Large-catalog billing navigation after fixes | **PASS — 1/1**, 57.336 s overall | `phase3b-menu-navigation-final.log`; the test's internal 15-second billing navigation threshold passes. Repeated standalone after final APK build. |
| Pipe numeric keyboard visibility | **FAIL — 1 test**; IME did not become visible within the existing 15-second assertion at line 153 | Repeated standalone after the final build; `phase3b-pipe-keyboard-final-failure.log`. A prior run passed it, so behavior is inconsistent; final regression gate remains open. No timeout was raised. |
| Existing selected Android regression suite | **39/40 passed**, 282.971 s; menu test was sole failure before navigation fix | `phase3b-existing-pos-regressions.log`. Its other tests passed: reminder, backup, billing performance, advanced billing, integrity, pipe calculations and Bluetooth permission. The exact menu method now passes. The separate IME test later failed twice on API 36. |
| Pre-change WAL backup + isolated restore + actual billing stock-once | **PASS — 3/3**, 30.301 s | `phase3b-prechange-backup.log`; synthetic named databases only. |
| Android test startup first attempt | **BLOCKED before execution** during cold emulator start | Package service was absent and app startup ANR'd. No test result counted from that attempt. The Samsung handset present in ADB was not used; all later tests used only `emulator-5598`. |

### Exact commands used

From `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS` unless a working directory is specified:

```powershell
# Local backend suite; working directory tools/website-proof
npm test

# Final app, Android test APK and JVM test-source compilation; cwd must be the project root
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase3b-menu-fix-build.log'

# Full native integration + Compose flow fixture runner
& './tools/website-proof/run-native.ps1' -Classes 'com.hardwareshop.pos.WebsiteNativeIntegrationTest,com.hardwareshop.pos.WebsiteOrdersUiTest' -Log 'phase3b-native-integration-rerun.log'

# Existing selected Android regressions
& './tools/website-proof/run-android.ps1' -Classes 'com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest,com.hardwareshop.pos.BillingPerformanceInvestigationTest,com.hardwareshop.pos.AdvancedBillingIntegrationTest,com.hardwareshop.pos.BillingIntegrityTest,com.hardwareshop.pos.PipeBillingIntegrationTest,com.hardwareshop.pos.BluetoothPermissionTest' -Log 'phase3b-existing-pos-regressions.log'

# Final standalone menu navigation success (run against disposable emulator only)
& 'C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5598 shell am instrument -w -r -e class 'com.hardwareshop.pos.MenuKeyboardRegressionTest#largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus' com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner

# Reproduced keyboard/IME failure (same emulator, test APK from final build)
& 'C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5598 shell settings put secure show_ime_with_hard_keyboard 1
& 'C:\Users\mohammed saud\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s emulator-5598 shell am instrument -w -r -e class 'com.hardwareshop.pos.MenuKeyboardRegressionTest#pipeFeetAndPinnedCartRemainVisibleWithNumericKeyboard' com.hardwareshop.pos.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The build helper writes its log in the approved work checkout. Backend, native, and Android regression evidence files are retained in `tools/website-proof/evidence/`. The initial failing bridge response and earlier navigation timeout evidence are preserved. The bridge fix serializes embedded Miniflare SQLite access and buffers responses; backend concurrency/race assertions run in the independent Node suite.

## Verified Phase 3B scope and remaining blockers

Verified locally: POS catalog projection and hide/show; shop public settings fixtures; Orders menu and unread state; manual worker availability confirmation; checklist/Ready and customer-confirmed adjustment audit; incremental synchronization and retry; device-bound sticky claim; once-only existing bill/payment/stock transaction and Bill History origin; recovery after lost acknowledgement/restart; backup origin guards; notification permission/channel/dedup fixture; ordinary billing remains independent. Website checkout still does not query, reserve or deduct stock.

No production data or app database was modified. AppDatabase remains Room 11 and BackupManager remains format 8; WebsiteOrdersDatabase is the separate local integration database version 1. No destructive migration fallback was added. No production Firebase, FCM sender, Cloudflare, payment service, deployment or paid plan was activated.

Unresolved gates: (1) repeatable pipe IME visibility regression on API 36, despite the earlier passing observation; (2) 90-day storage estimate 458,276,864 bytes exceeds the 450,000,000-byte operational cutoff, and this integration added new metadata that was not re-capacity-tested; (3) real authentication/tenant/installation enrollment and production provider quota behavior are unverified; (4) no physical Bluetooth printing, physical phone, Safari, actual FCM delivery or final-schema capacity workload was run. Stop here and await approval.


### JVM test-task attempt

The command `:app:testDebugUnitTest` was attempted twice on the final source (once in the default sandbox and once with elevated local execution). In both cases Gradle failed to establish its loopback socket for the isolated test worker before reporting any test results (`Unable to establish loopback connection` / `SocketException: Invalid argument: connect`). These are environment/runner failures, not a test pass or an assertion failure. The Android JVM test sources compile successfully, and historical Phase 3A JVM 311/311 remains documented, but the complete JVM suite is **not verified for the final Phase 3B source**. Log: `work/advanced-operations/website-phase3b-jvm-tests.log`. No timeout was extended.
