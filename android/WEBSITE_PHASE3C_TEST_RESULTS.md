# HardwareShopPOS Phase 3C — Regression Repair and Final Local Verification

Date: 18 October 2026, Asia/Kolkata. Status: local regression audit stopped with the pipe keyboard acceptance test unresolved. Weighted progress remains **82.5%**. No Phase 4 work, deployment or production integration was started.

## Scope and preserved boundaries

Read the Website Master Blueprint v2.0, project progress, Phase 3B test record and code audit before testing. This phase made no production Android, billing, database, website, Firebase or Cloudflare changes. The only source edits were diagnostic instrumentation-test and local test-runner improvements. Room remains version 11, BackupManager remains format 8, and no production shop data, prices, stock or bills were accessed. No paid service was activated and nothing was deployed.

The final app APK was already built successfully in Phase 3B. In Phase 3C only the Android instrumentation-test APK was rebuilt to include the focus assertion; the app APK remained unchanged. No billing transaction architecture or security identity change was attempted.

Files changed in Phase 3C:

- `app/src/androidTest/java/com/hardwareshop/pos/MenuKeyboardRegressionTest.kt` — assert the pipe-feet field is focused before the existing IME visibility wait.
- `tools/website-proof/run-android.ps1` — wait for Android package/settings/IME readiness and verify the emulator's software-keyboard setting.
- `WEBSITE_PHASE3C_TEST_RESULTS.md` — this test report.
- `WEBSITE_PROGRESS.md` — verified status and weighted progress.
- `WEBSITE_CODE_AUDIT.md` — Phase 3C findings.

Test logs, screenshots and diagnostic traces were added under `tools/website-proof/evidence/phase3c-*`; they are evidence artifacts, not application source changes.

## Pipe keyboard regression

The focused API 36 test performs a Compose click, asserts the pipe-feet field is focused, and waits the original **15 seconds** for `WindowInsetsCompat.Type.ime()` to become visible. The test still checks numeric input, pipe-foot text, offcut hint, pinned-cart geometry, search and cart access. The time limit was not increased.

Evidence separates field focus from system keyboard visibility:

- In the two successful Phase 3C executions (`phase3c-pipe-keyboard-baseline.log`, `phase3c-pipe-keyboard-run2.log`), the entire method passed in **39.697 s** and **50.301 s**. The accompanying IME trace (`phase3c-ime-logcat.txt`) shows Gboard received `inputType=2002` (`Number`); the method entered numeric text, verified the offcut hint and pinned cart, and completed search/cart actions.
- Run 5 (`phase3c-pipe-keyboard-run5.log`) failed at the unchanged 15-second IME-visible wait, line 154. The preceding `assertIsFocused()` passed. The emulator reported `show_ime_with_hard_keyboard=1` and had Gboard selected. Its trace (`phase3c-run5-full-logcat.txt`) records `ImeTracker ... onRequestShow ... SHOW_SOFT_INPUT_BY_INSETS_API`, followed immediately by `onFailed at PHASE_SERVER_UPDATE_CLIENT_VISIBILITY`; Gboard did not receive the numeric editor on this failed run.
- Runs 3, 4 and 6 ended with instrumentation process crash or device disconnection before producing a test result (`phase3c-pipe-keyboard-run3.log`, `phase3c-pipe-keyboard-run4.log`, `phase3c-pipe-keyboard-run6.log`). They are preserved as failures, not passes. An immediate inspection after run 3 showed an app-start ANR; its short instrumentation output is retained, but that run's full logcat was not saved. The full run 5 and run 6 emulator traces are retained as `phase3c-run5-full-logcat.txt` and `phase3c-run6-full-logcat.txt`. A fresh boot and service-readiness checks did not prevent the later IME failure or process disconnect.
- Phase 3B had two further 15-second keyboard failures after the final application build. Its earlier selected suite was 39/40, and the billing-navigation method subsequently passed separately.

The verified cause of the current failed assertion is that Android's API 36 IME service rejected the visibility request even though Compose focus and the emulator's software-keyboard preference were set. Successful runs prove the field's numeric editor configuration and full UI flow can work. The evidence does **not** establish a deterministic application defect or prove the behavior is exclusive to the emulator. The runner now waits for Android package/settings/IME services and verifies the software-keyboard setting; readiness alone did not fix the system rejection. Explicitly selecting Gboard for another attempt also did not yield a recorded pass. No production UI or keyboard behavior was changed, and this acceptance item remains **blocked/unresolved** pending a repeatable run on a stable API 36 device or emulator.

## JVM test-worker failure

The Gradle task `:app:testDebugUnitTest` still fails before it reports test discovery. Its worker throws `Unable to establish loopback connection`, caused by Java NIO `PipeImpl` failing in `LoopbackConnector` with `java.net.SocketException: Invalid argument: connect`. The error occurs while Gradle starts its isolated `TestWorker`, not in an application assertion. It occurred in the prior sandboxed and elevated attempts; the preserved log is `C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations\website-phase3b-jvm-tests.log`.

The complete compiled JVM regression suite was executed independently through the project's direct JUnitCore runner, avoiding Gradle's failing worker transport: **312 tests passed, 0 failed**, in 2.76 seconds. Output: `tools/website-proof/evidence/phase3c-jvm-direct-rerun.log`. This restores a real JVM test count for the compiled final Phase 3B application/test classes. The Phase 3C source change is Android-test-only; the Gradle task itself remains environment-blocked and is not reported as passing.

## Regression results

| Area | Result | Evidence / limitation |
| --- | --- | --- |
| Android instrumentation source build | **PASS** | `:app:compileDebugAndroidTestKotlin` and `:app:assembleDebugAndroidTest`; **BUILD SUCCESSFUL in 5m 57s**. Only the test APK was rebuilt. |
| Complete JVM suite | **PASS — 312/312** | Direct JUnitCore run; Gradle worker startup still blocked as described above. |
| Website backend and local D1 | **PASS — 37/37** | `npm test`, local Miniflare/D1 loopback runner; **17.406 s**, `tools/website-proof/evidence/phase3c-backend-tests.log`. The default sandbox attempt failed to connect with `EACCES` and is not counted. |
| Pipe numeric keyboard / pinned cart | **UNRESOLVED** | Two complete Phase 3C passes; one 15-second IME assertion failure; three instrumentation crash/disconnect attempts. No timeout change. |
| Billing navigation | **PASS — 1/1 in Phase 3B** | Standalone final-build method passed in 57.336 s overall; its internal 15-second acceptance limit passed (`phase3b-menu-navigation-final.log`). |
| Billing, payment methods, pipe calculation, reminder, backup, Bluetooth permission | **PASS in Phase 3B selected run** | 39/40 overall; the sole failure was the menu-navigation method later passed standalone. The subsequent keyboard issue remains separate. |
| Orders, worker acceptance/preparation, once-only bill/stock, history, notification fixtures | **PASS in Phase 3B** | Backend 37/37, native integration 10/10, Compose worker flow 1/1. The 10-test integration covers synthetic order synchronization, worker roles, billing recovery, backup guards and notification fixtures. No real FCM delivery is claimed. |
| WAL backup/recovery and stock-once protections | **PASS in Phase 3B** | Synthetic isolated backup/restore and billing stock-once tests 3/3; Room/backup formats were not changed. |
| Android full selected suite in Phase 3C | **NOT COMPLETED** | Repeated API 36 instrumentation instability prevented a stable broad rerun. Prior Phase 3B evidence remains identified as prior-phase evidence, not a fresh Phase 3C run. |

No physical Bluetooth printer, shop phone, real FCM, production account, final-schema capacity test or production database was used. No test result is claimed for those environments.

## Exact Phase 3C commands

Run from `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS` unless stated otherwise:

```powershell
# Build only the modified Android instrumentation-test source/APK
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\performance\build.ps1' -Tasks ':app:compileDebugAndroidTestKotlin',':app:assembleDebugAndroidTest' -Log 'website-phase3c-test-apk-build.log'

# Complete compiled JVM unit suite, using JUnitCore without a Gradle TestWorker
& 'C:\Users\mohammed saud\Documents\Codex\2026-10-15\fix-pipe-pricing-and-billing-flow\work\advanced-operations\jvm_tests.ps1'

# Local backend regression suite (run from tools/website-proof with loopback access for Miniflare)
npm test

# Focused API 36 keyboard test; repeated with distinct evidence logs
& './tools/website-proof/run-android.ps1' -Classes 'com.hardwareshop.pos.MenuKeyboardRegressionTest#pipeFeetAndPinnedCartRemainVisibleWithNumericKeyboard' -Log 'phase3c-pipe-keyboard-run5.log'
& './tools/website-proof/run-android.ps1' -Classes 'com.hardwareshop.pos.MenuKeyboardRegressionTest#pipeFeetAndPinnedCartRemainVisibleWithNumericKeyboard' -Log 'phase3c-pipe-keyboard-run6.log'
```

The focused method was also run for earlier Phase 3C attempts using the same command with `phase3c-pipe-keyboard-baseline.log`, `phase3c-pipe-keyboard-run2.log`, `phase3c-pipe-keyboard-run3.log` and `phase3c-pipe-keyboard-run4.log`. Run logs and IME diagnostics are retained in `tools/website-proof/evidence/`. The keyboard runner's readiness changes do not increase test timeouts.

## Remaining blockers and next phase

1. Obtain stable API 36 IME visibility and repeat the keyboard/pinned-cart flow without loosening its 15-second bound; retain the current system-rejection and crash traces.
2. Restore Gradle `:app:testDebugUnitTest` worker startup on a suitable local environment, then compare the Gradle run with the direct 312/312 result.
3. Rerun the complete selected Android regression suite on a stable disposable emulator, including the already passing billing, pipe, Orders, backup and Bluetooth cases.
4. Production capacity/authentication, provider quotas, physical printer/device checks, real FCM, final-schema capacity and release review remain separate blockers. The 90-day storage model still exceeds the 450 MB operational cutoff.

Recommended next step: continue only after approval, on a stable API 36 test environment, to resolve the IME visibility gate and rerun the complete Android regression suite. Phase 4 and deployment have not begun; no production action was taken.
