# HardwareShopPOS — Phase 2 test results

Updated 17 October 2026, Asia/Kolkata. **Approved local Phase 2 work complete.** Production feasibility/integration is not claimed. Stop before Phase 3 or deployment pending user approval.

Evidence is retained under `tools/website-proof/evidence`. Existing Android wrapper build logs remain in `../2026-10-15/fix-pipe-pricing-and-billing-flow/work/advanced-operations`.

| Executed check | Result | Evidence |
| --- | --- | --- |
| Android app/test APK and unit compilation, first build | PASS, 2m 42s | `website-phase2-build.log` |
| Revised Android/test/unit compilation | PASS, 3m 35s | `website-phase2-revision-build.log` |
| Final app/test/unit build with pipe-input fix | PASS, 7m 18s | `website-phase2-layout-build.log` |
| JVM + actual Kotlin shared pipe fixtures | PASS, latest 311 tests, 3.706s | `evidence/jvm-final-tests.log`; earlier 311 passed in 3.955s |
| Reminder UI | PASS on first Phase 2 execution, original 15s bound | `evidence/android-baseline.log` |
| WAL archive/isolated restore/repeated restore/integrity reopen | PASS | `WebsitePhase2SafetyTest`, recoverable synthetic `.hwpos.zip` pulled to evidence |
| Linked-shop restore refusal | PASS; financial snapshot unchanged | Same suite |
| Existing createBill with ten concurrent same-order checkout identities | PASS; one bill, one payment, stock 20→18 exactly once, price 12345 unchanged | Same suite, test DB only |
| First combined Android suite | FAIL overall, 4/6 pass | Navigation return and software keyboard conditions timed out; preserved in `android-baseline.log` |
| Corrected navigation rerun | PASS: five billing entries 856–11,356 ms, 17 menus 113–504 ms; original 15s threshold | `android-baseline-retry.log.logcat.txt` |
| Combined rerun | FAIL overall, 5/6 pass; remaining pipe keyboard check | `android-baseline-retry.log` |
| Isolated keyboard diagnosis | FAIL before application fix; primary fields below short visible panel, no input focus | `android-keyboard-isolated.log`, `phase2-ime.png` |
| Minimal pipe input ordering fix and final relevant Android suite | PASS, 60/60, 137.686s | `evidence/android-final-regressions.log`; includes reminder, both menu/keyboard checks, billing, backup, panels, saved receipt and migration |
| Tablet portrait panel/keyboard checks | PASS, 3/3, 85.927s | `evidence/android-tablet-portrait.log`, emulator 1200×1920 at density 240 |
| Tablet landscape panel/keyboard checks | PASS, 3/3, 38.649s | `evidence/android-tablet-landscape.log`, emulator 1920×1200 at density 240 |
| Native local D1 backend suite | PASS, expanded 22/22, 7.766s | `evidence/backend-final-tests.log`; earlier 17/17 passed |
| Capacity/index/storage workload | PASS locally: four separate 1,000-order days, all assertions passed | `evidence/capacity.log`, `capacity-results.json`, `index-overhead.json` |
| Local quota/persistence failure | PASS: CHECK-enforced admission rollback, unavailable writes, no false confirmation and retained cart | Expanded backend suite; actual Cloudflare account quota exhaustion was not run |

The 60-test suite plus six additional configuration tests are **66 passing Android test executions**, not 66 unique test methods. Phone display was 540×960 at density 240; the tablet harness restored those original overrides after testing. Screenshots were inspected: [phone keyboard](tools/website-proof/evidence/keyboard-final.png), [tablet portrait](tools/website-proof/evidence/tablet-portrait-keyboard.png), [tablet landscape](tools/website-proof/evidence/tablet-landscape-keyboard.png). All primary pipe controls and actions shown by these checks are accessible, with grid/panel separation and system/keyboard bounds asserted.

The final menu test retains its 15-second threshold. The earlier successful measured five entries were 7,262 / 11,356 / 1,487 / 8,408 / 856 ms; 17 owner-menu entries were 113–504 ms. Those detailed timings are from the pre-layout-fix navigation retry log; the final 60-test suite independently passed the same thresholds. The final end-of-suite logcat had no retained performance lines, so no new per-entry timings are invented.

## Local capacity results

Each case used 3,201 products, 1,000 unique orders, 5 or 20 lines and 20 or 100 concurrent customers. Each produced 950 completed / 50 cancelled orders and 950 distinct server bill identities, 5,900 events and three-device catch-up. This is backend protocol traffic; it is not a 950-sale production POS test.

| Lines | Concurrent | Seconds | API requests | Rows read | Rows written | Growth bytes |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 221.909 | 6,352 | 83,653 | 42,351 | 5,283,840 |
| 5 | 100 | 192.653 | 6,352 | 83,653 | 42,351 | 5,279,744 |
| 20 | 20 | 124.322 | 6,352 | 100,153 | 42,351 | 9,584,640 |
| 20 | 100 | 118.420 | 6,352 | 100,153 | 42,351 | 9,572,352 |

CPU/latency details, direct-catalog/polling models and retention projections are recorded in [zero-cost audit](WEBSITE_ZERO_COST_AUDIT.md). The index probe measured 1,000 plain versus 2,000 indexed writes for both the insert and indexed-column-update workloads. Local account-quota/CPU behavior is not Cloudflare production verification. Retaining 90 days of 20-line orders at this modeled rate exceeds 500 MB; safe production headroom remains **unverified**.

## Database and billing recovery evidence

The actual BackupManager exported committed WAL data into a recoverable format-8 ZIP, restored it into another named synthetic Room DB, repeated restore without duplication and passed a read-only integrity reopen. Products/prices/bills/payments/stock matched the original financial snapshot. Linked-shop restore was rejected without financial changes. No live shop database was opened or restored.

The selected recovery artifact is `tools/website-proof/evidence/verified-synthetic-backup.hwpos.zip`. It contains one synthetic product, bill and payment; price 12,345 paisa and stock 18 after the synthetic two-unit sale. Ten concurrent retries through real createBill yielded one bill/payment/stock effect. The separate server/SQLite proof also covers simultaneous device claims, sticky ownership, persisted pre-send claim intent, response loss, rollback before commit, restart after commit and completion/cursor retries.

AppDatabase.kt, BackupManager.kt and BillingAndStockRepositories.kt match the preserved Phase 3 checkpoint byte-for-byte. Room remains 11, backup format remains 8. New uniqueness/cache/outbox tables are isolated prototype/test structures, not a live migration. Normal billing and existing Bill History use the same repository; real native website-origin wiring remains future work.

## Commands executed

From the existing project directory:

```powershell
& '..\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:compileDebugAndroidTestKotlin',':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase2-build.log'
& '..\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase2-revision-build.log'
& '..\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase2-layout-build.log'
& '.\tools\website-proof\run-android.ps1'
& '.\tools\website-proof\run-android.ps1' -Log 'android-baseline-retry.log'
& '..\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations\jvm_tests.ps1'
& '.\tools\website-proof\run-android.ps1' -Classes 'com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.ReminderUiIntegrationTest,com.hardwareshop.pos.MenuKeyboardRegressionTest,com.hardwareshop.pos.BillingIntegrityTest,com.hardwareshop.pos.ProductSizeSelectionTest,com.hardwareshop.pos.ResponsiveBillingPanelTest,com.hardwareshop.pos.CreditDocumentsIntegrationTest,com.hardwareshop.pos.ReceiptFooterUiIntegrationTest,com.hardwareshop.pos.AdvancedBillingUiTest,com.hardwareshop.pos.DiscountedUpiReceiptTest,com.hardwareshop.pos.RegionalAliasMigrationTest' -Log 'android-final-regressions.log'
& '.\tools\website-proof\run-tablet-panels.ps1'
node --test --test-concurrency=1 --test-reporter=spec tools/website-proof/test/*.test.mjs
```

From `tools/website-proof`:

```powershell
$env:NODE_OPTIONS='--use-system-ca'
npm install --ignore-scripts --no-audit --no-fund --fetch-retries=0 --fetch-timeout=30000
node --test --test-concurrency=1 --test-reporter=spec test/backend.test.mjs
node capacity.mjs
```

TLS certificate validation remained enabled; the Windows trusted store resolved an initial npm certificate-chain error. Miniflare's synchronous Node D1 proxy initially hung before tests could run; the separately keyed local fixture driver resolved that tooling issue. No mock or local-runtime result is reported as a real Cloudflare quota/CPU measurement.

The expanded backend suite initially passed 20/22: the disabled-mode test incorrectly tried initializing the intentionally blocked fixture SQL endpoint, and the no-phone-lookup test expected 404 instead of the existing invalid-UUID 400 response. Corrected those test assumptions; no backend behavior was relaxed. Final 22/22 passed. Initial restricted SQLite page-count probing was replaced by supported D1 size_after metadata before the successful full capacity runs.

## Build artifact and phase boundary

Final local build: `app/build/outputs/apk/debug/app-debug.apk`, 31,432,919 bytes, package `com.hardwareshop.pos.debug`, version `1.4.21-DEBUG` (26). SHA-256: `b85f44b19b4be888f8789a7bbf8be1630725166af754209fde879f757312cbec`. It is a debug verification artifact, not a production release/install authorization. No version/schema bump or release signing change was made.

No production database or live Firebase connection was used by the backend, no paid service/account/payment method was activated, and no website deployment occurred. Notification/billing designs are in `WEBSITE_NOTIFICATION_DESIGN.md` and `WEBSITE_BILLING_SAFETY_PROOF.md`; production-backup steps are in `WEBSITE_BACKUP_SAFETY.md`. Checkout is stock-independent and has no automatic reservations. All Phase 2 local tasks are complete; Phase 3 and production remain unapproved.

Not run or unverified: real Cloudflare Free CPU/account quotas/production load, account-specific eligibility, Firebase membership/enrollment integration, live FCM delivery, physical-device/printer checks, production migration/restore, full customer website/native Orders integration and Android lint. These are not reported as passing.
