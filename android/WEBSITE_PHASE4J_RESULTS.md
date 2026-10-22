# HardwareShopPOS — Phase 4J final local results

22 October 2026, Asia/Kolkata. **Phase 5 entry: BLOCKED. Verified weighted completion: 83.5% (+0%).**

The release compiler failure is repaired, and archive identity verification now uses substantially fewer reads. Fresh Android builds, all 312 JVM tests, the website artifact scan and all ten browser regressions pass. Concurrent backend transport remains unreliable, and the disposable emulator did not become ready for native execution. Required local acceptance is therefore incomplete. This report supersedes the intermediate Phase 4J status notes; their original text is retained in `tools/website-proof/evidence/phase4j-intermediate-report.md`.

## Verified gate ledger

| Check | Actual result | Evidence under tools/website-proof/evidence |
| --- | --- | --- |
| Original clean release reproduction | FAIL: mapped R8 ConcurrentModificationException, 30 m 41 s, 41 tasks executed | External `website-phase4j-release-baseline-clean.log`; retraced frame |
| Patched minified/shrunk/obfuscated release | PASS: 34 m 29 s, 57 tasks executed | `website-phase4j-release-patched-windowsroots-1.log` and result JSON |
| Actual unsigned release APK inspection | PASS: hash, non-debuggable manifest, marker scan and manual compiled DEX guard review | `phase4j-release-artifact/`, `phase4j-release-scan.json`, `phase4j-release-guard-reviewed.json` |
| Fresh debug/test assembly | PASS: combined build/JVM run 15 m 44 s, 85 tasks executed | `website-phase4j-debug-jvm-1.log` |
| Complete JVM regression | PASS: 312 executed, 312 passed, 0 failures/errors/skips; 39 suites | `phase4j-android-build-results.json`, `phase4j-jvm-xml/` |
| Native cold-navigation setup | BLOCKED BEFORE JUnit: boot readiness 181,123 ms, unchanged 180 s setup bound; 0 native tests executed | `phase4j-ui-cold-1-*` |
| Full backend, final HTTP/default-off experiments | FAIL: 107 defined, 106 executed, 104 passed, 2 failed, 1 platform skip, 0 cancelled; 306.803 s | `phase4j-linux/final-http-1/backend.log`, `backend-result.json`, closed `state/` |
| Focused Windows raw/shared-IP | FAIL: 12 executed, 10 passed, 2 failed; 106.081 s | `phase4j-raw-sharedip-windows-1.log` |
| Focused archive identity/scaling | PASS: 2/2, 12.605 s; also passes in full backend | `phase4j-archive-focused-windows-4.log` |
| Website build and static artifact scan | PASS: 3,249 variants, 31,041-byte gzip catalog; no detected private catalog fields or credential/issuer markers | `phase4j-web-build.json`, `phase4j-artifact-scan.json` |
| Browser regression | PASS: 9 main cases + 1 historical delayed-hours case, 0 console errors/external requests | `phase4j-browser-browser-results.json`, `phase4j-delay-browser-browser-results.json` |
| 200/300/400 and both 500 two-cycle profiles | NOT RUN in Phase 4J: foundational transport and native gates fail | Historical Phase 4I results retained, not transferred |
| 1,000-order stress | NOT RUN in Phase 4J; gated, stress-only | Historical failure retained |

No production account or physical-device acceptance is inferred from these results. Backend component tests do not replace native billing/stock/printing integration tests.

## Root causes and repairs

### Release compiler — repaired and verified

The clean AGP 8.6.0 / R8 8.6.17 app reproduction reaches `Enqueuer.processDeferredAnnotations:2368–2373` and fails with ConcurrentModificationException. This matches Google's issue 359385828 fix. The sole toolchain change is **AGP 8.6.0 → 8.6.1**, which bundles R8 8.6.27. The actual patched app release succeeds with existing minification, obfuscation, resource shrinking and security rules retained. Small annotation probes pass on both versions, so they are not presented as defect reproductions.

Official references: [AGP 8.6 compatibility and 8.6.1 fixes](https://developer.android.com/build/releases/agp-8-6-0-release-notes), [R8 deferred-annotation fix](https://r8.googlesource.com/r8/+/d6058fde773d135ade972df5e70cb9226537fe11).

Two dependency-resolution attempts fail before compilation with the default JBR certificate-chain error. A separate Java HTTPS probe then succeeds using the existing Windows ROOT trust store for this build JVM only. TLS certificate validation remains enabled. No trust store, app transport or backend trust policy was changed. The original JVM-worker loopback problem did not recur in this fresh 312-test run using the scoped local runner. Historical intermittent failures remain historical evidence, not erased.

Release APK: `tools/website-proof/evidence/phase4j-release-artifact/app-release-unsigned.apk`, **12,537,868 bytes**, SHA256:

`f9e781557a2e6722ce2b6b2b1d132919f4af5797bab2fc3919732e820e2791cf`

The compiled local-request coroutine unconditionally throws before networking; the paired transport is an abstract class-init stub; signed fixture transport and debug navigation diagnostics are removed. The initial automatic guard parser could not follow R8 inlining and correctly required manual review. Its failure is retained. Static DEX inspection is not runtime penetration testing, production authentication approval or a signed installable release.

### Archive verification — repaired and measured

The old primary-key pagination filters by shop through a competing index, repeatedly scanning/sorting historical receipts. The first tuple candidate still rescans shared prefixes. The final verifier explicitly traverses the existing `(shop, order_id, rowid)` index by equality within each order prefix, then seeks the next prefix. Restored records are matched by logical identity; physical rowids may differ. Every field is compared, and missing, extra, corrupt or foreign-shop identities are rejected.

| Same 1,024-receipt synthetic fixture | Actual source D1 rows read | Actual restore rows read | Source writes |
| --- | ---: | ---: | ---: |
| Preserved Phase 4I verifier | 50,689 | 50,689 | 0 |
| First tuple candidate | 17,920 | 2,048 | 0 |
| Final explicit prefix cursor | 1,026 | Not separately logged in this fixture | 0 |

The independent one-order/90-receipt final fixture measures **96 source reads + 182 restore reads, zero writes on either side**. Two focused regressions pass, including multiple shops and different physical insert layouts. No schema/index/cleanup authority is changed. This optimizes the local archive verification harness; it does not establish a new hosted archive budget or sustained 500/day capacity.

The separate WITHOUT ROWID probe reduces measured writes per 100 inserts from 300→200 for receipts/nonces and 200→100 for audit records. **This schema candidate is not applied.** Existing archive/recovery helpers depend on rowid; production-compatible migration and restore work would be required. No speculative write/storage saving is credited.

### Transport — remains unresolved

The final backend fails:

1. `100 verified anonymous customers behind one IP persist all orders; double submits remain unique`: UND_ERR_SOCKET / peer closed, 7.924 s.
2. `100 concurrent signed operations trace actual send, fresh authentication and complete response without forced recycle`: six rejected customer tasks with fetch failures, 52.119 s. This test requires 1,000 logical signed reads across 100 concurrent tasks and exhausts its existing recovery path; it is not a six-error total for all network attempts.

Per-attempt UUIDs now distinguish actual sends from retried business operations. Controlled comparisons preserve signatures, URL identities, concurrency and timeouts:

| Experiment | Actual outcome |
| --- | --- |
| Windows pooled HTTP probe | 600/600 pass, 37.886 s; one passing control is not reliability acceptance |
| Direct native workerd proxy socket | 10 socket failures +10 subsequent duplicate 403 responses, 23.050 s |
| Fresh sockets/no reuse | 12 socket failures +12 subsequent duplicate 403 responses, 46.502 s |
| Minimal native-workerd control, no auth/order/D1 | 600/600 pass |
| Same control, 20 D 1 inserts per POST | 600/600 pass;100 concurrentGET/50 POST, not 100 concurrent customer mutations |
| No Node test-process isolation, two targeted bodies | 1 pass/1 fail, 36.712 s |
| Strict native TLS on Windows | Initialization rejects chain; no workload executed and no trust bypass |
| Same pinned Node/dependencies in isolated Ubuntu, strict native TLS | Probe 600/600 pass, 19.221 s; subsequent raw suite 10/12 pass,124.160 s |

On failing fresh sockets, zero response bytes were read, 1,767 bytes were written, and no matching Worker-entry event was recorded. Stale keep-alive reuse is therefore not the sole cause. The direct experiment and Linux failures also prevent attributing the problem solely to the intermediate proxy, Windows, Node process isolation or HTTPS. Kernel packet capture/peer FIN ownership and a single workerd/OS/antivirus/resource root cause remain unverified. Historical trace join estimates are explicitly diagnostic because earlier operation IDs were shared across attempts. No sole-component root cause or production-compatible transport fix is claimed.

Experimental direct/no-reuse/native-TLS switches remain default-off and unaccepted. No retries, acceptance timeouts or authorization rules were relaxed. The operationally safe alternative is to keep website-to-POS production integration disabled, retain uncertain identities/claims and continue ordinary offline POS billing independently. Resume controlled transport diagnostics on a stable isolated host before capacity acceptance; do not reassign sticky claims or multiply retries indefinitely.

### Cold Android navigation — application root cause still unverified

Debug instrumentation covers activity creation, ViewModel/draft recovery, catalog query/emission/indexing and Compose readiness without logging product/customer/cart contents. The unchanged 15 s assertion now requires actual catalog readiness; spinner absence alone could previously pass before catalog completion. Failure evidence is captured before teardown, with main-thread probes, semantics/screenshots and a WAL-consistent synthetic `VACUUM INTO` snapshot when possible. These additions compile but have no fresh native runtime acceptance.

The disposable read-only Operations emulator did not reach package/settings/IME readiness within the unchanged 180 s setup limit. **Zero native tests executed.** At start the host had 504,832 KiB free; startup reached 312 MB free and 20,523 pages/sec. A later host check still had 497,148 KiB free. Resource pressure is observed, not proven to explain the historical app 15 s failure. No app performance repair or numeric-keyboard pass is claimed. Native billing/Orders/Keystore/restore/process recovery/Bluetooth permission and cold/warm acceptance remain blocked.

Only the owned emulator and its identified cleanup child were stopped. ADB logcat had waited after the device disappeared; future owned-child capture is now bounded at 10 s. User applications, BlueStacks, physical devices and the existing preview remain untouched. Original setup/failure evidence and the first runner revision are retained.

## Customer website verification

All existing browser regressions execute: nine main cases plus the independently retained delayed-shop-hours case. Seven viewport sizes cover 320,360,390,412,768, 1024 and 1440 pixel widths. Tests cover text-only catalog, many-size scrolling/footer access, pipe full-length/extra-feet totals, ordinary products, cart editing, checkout details, opt-in contact deletion, secure order edits/cancellation, restart/lost-response recovery, false/malformed receipt rejection, closed-hours browsing and same-origin boundaries.

The catalog initially has 3,249 synthetic variants. The owner-hide test removes one variant; the final slow-network snapshot has 3,248 variants and 30,956 gzip bytes. Emulated mobile load **6,008 ms**, search **353 ms**, bounded 36-card DOM, 0 browser errors and 0 external requests. These are installed Chrome headless/network/CPU/viewport simulations, not physical Android/iPhone keyboard or real mobile-data acceptance. The many-size screenshot was visually inspected. No website/product redesign was performed in Phase 4J.

## Conservative ₹0 envelope and finite storage

Published limits checked 22 October 2026: D1 Free **5 M rows read/day,100,000 rows written/day,500 MB/database, 5 GB/account, 50 queries/invocation**; Workers Free **100,000 requests/day, 10 msCPU/request**. [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/).

No final Phase 4J 500-order envelope is accepted: raw transport/native gates block the costly cohorts. Historical Phase 4I five-device 500 two-cycle results remain conditional evidence only: recurring 59,602/67,069 writes, 2,029,764/2,815,127 reads and 14,067/14,093 APIrequests per compressed cycle; 48 SQL/invocation maximum. Fully charging measured source setup yields 83,366/90,833 writes, leaving **9,167 writes** on the busier day before unknown provider, challenge, notification, failures and shared-account costs. The earlier 84,331 figure omits 6,502 setup writes and must not be reused.

For an explicit planning sensitivity, reserving 20% of published daily allowances would leave 80,000 writes, 4 Mreads and 80,000 Worker requests for modeled shop activity. **This reserve is a proposed design allowance, not a provider charge measurement or proven sufficient margin.** The historical 90,833-write day already exceeds that 80,000-write planning ceiling by 10,833; the archive read-only repair does not fix this write deficit. Actual missing costs must be measured, then the reserve approved. No sustainable Phase 4J 200–500/day production rate or permanent₹0 promise is established. Aggregate local CPU cannot prove 10 msproductionCPU compliance.

Historical conditional 30/60/90-day allocations are 135,573,504 /269,881,344 /404,189,184 bytes for the earlier short-run mix. They are not new Phase 4J measurements, maximum-input/backlog acceptance or a retention guarantee. Permanent receipts and growing uncollected/unresolved orders remain finite-storage liabilities. Protected orders must remain intact even when growth prevents new admission. The 450,000,000-byte operational cutoff, owner warnings, encrypted archives/quarantined restore and owner-controlled cleanup remain in force; no automatic deletion or paid fallback was added. Snapshot peaks, long archive-chain growth, heavier protected backlogs, real account eligibility/shared usage, hosted restore cost and notification/challenge overhead remain production blockers. Historical isolated recovery 222,392 writes cannot be silently moved to free hosted D1 within the daily budget.

## Preservation and regression limits

All 10,023 Phase 4I manifest entries were verified before edits, with 441 source/configuration/report copies retained. Final audit: **10,003 entries unchanged, including 9,585 historical evidence files; 20 expected working-source/report/rebuilt-APK changes; 0 unexpected changes or missing checkpoint files.** Old failed DBs, sticky claims, receipts and logs remain unchanged. `phase4j-final-audit.json` distinguishes immutable evidence from current working sources and rebuilt APK paths. It does not claim every old manifest entry is byte-identical after authorized source/build changes.

Ten critical source/configuration files were rechecked against the prechange checkpoint: billing/stock repository, Room database, BackupManager, pipe calculator, Bluetooth printer, FCM service, app build/ProGuard configuration and backend worker/schema. All match. Room 11, BackupManager 8, financial transactions, product assets/prices/stock and existing payment/printing/Firebase behavior were not edited. No production database was opened/restored/modified; all generated DBs and orders are synthetic. Backend tests verify local once-only claims/receipts, pairing, revocation, tenant isolation, replay, restore/quarantine, status and retry components. They cannot certify physical printing or final native financial integration while native execution is blocked.

The final source/artifact/evidence manifest records current hashes. Source diagnostics and tests are the only Android changes beyond the compatible build-tool patch. No destructive migration, schema/index change, real Firebase/Cloudflare connection, deployment, paid activation, real enrollment or Phase 5 execution occurred.

## Exact source and report changes

Existing files modified, relative to the project root:

- `app/src/androidTest/java/com/hardwareshop/pos/MenuKeyboardRegressionTest.kt`
- `app/src/main/java/com/hardwareshop/pos/data/repository/Repositories.kt`
- `app/src/main/java/com/hardwareshop/pos/FinalProductCatalog.kt`
- `app/src/main/java/com/hardwareshop/pos/MainActivity.kt`
- `app/src/main/java/com/hardwareshop/pos/presentation/worker/billing/BillingViewModel.kt`
- `app/src/main/java/com/hardwareshop/pos/presentation/worker/billing/WorkerScreens.kt`
- `gradle/libs.versions.toml`
- `tools/website-proof/lifecycle-trace.mjs`
- `tools/website-proof/phase4i-capacity.mjs`
- `tools/website-proof/pooled-fixture.mjs`
- `tools/website-proof/run-android.ps1`
- `tools/website-proof/runtime.mjs`
- `WEBSITE_AUTH_SECURITY_DESIGN.md`
- `WEBSITE_CODE_AUDIT.md`
- `WEBSITE_DEVICE_ACCEPTANCE_CHECKLIST.md`
- `WEBSITE_PROGRESS.md`
- `WEBSITE_RELEASE_BLOCKERS.md`
- `WEBSITE_RETENTION_DESIGN.md`
- `WEBSITE_ZERO_COST_AUDIT.md`

New source/report files:

- `app/src/androidTest/java/com/hardwareshop/pos/NavigationFailureEvidence.kt`
- `app/src/main/java/com/hardwareshop/pos/util/BillingLoadDiagnostics.kt`
- `tools/website-proof/archive-identity-verification.mjs`
- `tools/website-proof/fixtures/phase4j/archive-phase4i-verifier.mjs`
- `tools/website-proof/fixtures/phase4j/archive-tuple-verifier.mjs`
- `tools/website-proof/fixtures/phase4j-r8/AnnotationProbe.java`
- `tools/website-proof/fixtures/phase4j-r8/ArtifactTlsProbe.java`
- `tools/website-proof/fixtures/phase4j-r8/probe.pro`
- `tools/website-proof/phase4j-browser-tests.mjs`
- `tools/website-proof/phase4j-build-web.mjs`
- `tools/website-proof/phase4j-index-probe.mjs`
- `tools/website-proof/phase4j-pairing-native-server.mjs`
- `tools/website-proof/phase4j-transport-probe.mjs`
- `tools/website-proof/phase4j-wire-control.mjs`
- `tools/website-proof/test/phase4j.test.mjs`
- `WEBSITE_PHASE4J_RESULTS.md`

`gradle/libs.versions.toml` contains the compiler repair. Android production edits are debug timing/readiness diagnostics; the new AndroidTest helper preserves pre-teardown failure evidence. `archive-identity-verification.mjs` and its capacity-harness call site implement/test the indexed verifier. Transport files add per-attempt diagnostics and default-off comparison switches. Native/browser/build/probe files isolate local synthetic checks. `tools/website-proof/evidence/phase4j*` contains checkpoint, analysis, runner, artifact and report helpers; every retained output/helper is enumerated by the final manifest. Prepared native integrated runners were not executed and earn no credit.

## Remaining gates and owner handoff

1. Resolve raw concurrent transport/peer closure with attributable evidence; rerun unchanged raw/shared-IP/recovery tests and focused 200 sync.
2. Obtain a stable disposable emulator environment, then run cold/warm 15 s, numeric keyboard, full native billing/Orders/backup/security/process-death/printing-permission regressions on these actual APKs. Investigate application timing using the new traces if it fails.
3. After focused gates pass, run final 200/300/400 profiles, both 500 two-cycle profiles and separately classified 1,000 stress. Reconcile every bill/order/claim and account for all reads/writes/queries/requests, snapshots and growing backlog.
4. Establish a measured conservative write/CPU/storage envelope and owner archive responsibilities. Reverify physical archive media, proprietor/administrator trust and production credentials separately.
5. **No Phase 5, physical acceptance, real pairing or provider activation is authorized by this report.** First close local gates and obtain fresh explicit owner approval for a precisely scoped next phase. Production enrollment, Firebase/FCM sender, Turnstile, Cloudflare deployment/account checks and physical-device/live-data use each remain outside the current authorization.

Weighted progress stays 83.5% because no additional target's full acceptance criteria are met. Local failures are preserved; Phase 5 entry is **BLOCKED**, not READY FOR OWNER REVIEW. Phase 4J stops here for owner review of evidence and unresolved engineering gates.

## Exact local commands used

All commands run from `tools/website-proof` unless an absolute path is shown. Each output label is unique; original failures are retained.

```powershell
# Baseline, at project root via existing runner:
./tools/website-proof/run-local-gradle.ps1 -Tasks ':app:assembleRelease','--rerun-tasks','--no-build-cache','--stacktrace' -Log website-phase4j-release-baseline-clean.log
# Patched release and actual artifact inspection:
./evidence/phase4j-build.ps1 -Tasks ':app:assembleRelease','--no-build-cache','--stacktrace' -Log website-phase4j-release-patched-windowsroots-1.log -Online -WindowsRoots
python evidence/phase4j-release-scan.py
# Initial guard parser required manual inspection because R8 inlined connection():
python evidence/phase4j-release-guard.py
# SDK dexdump -d on each actual APK classes*.dex; extracted C3/a,b,c bodies
# retained in phase4j-release-artifact/local-transport-dex.txt.
# Controlled transport (PROOF_4J_SOCKET_TRACE=1):
node phase4j-transport-probe.mjs --label=windows-reuse-1
# PROOF_4J_DIRECT=1 / PROOF_4J_NO_REUSE=1 for the respective preserved probes:
node phase4j-transport-probe.mjs --label=windows-direct-1
node phase4j-transport-probe.mjs --label=windows-close-1
node phase4j-wire-control.mjs --label=windows-reuse-control-1
# PROOF_4J_CONTROL_D1=1, PROOF_4J_CONTROL_LINES=20:
node phase4j-wire-control.mjs --label=windows-d1-20-control-1
# PROOF_POOLED_TRANSPORT=1, default HTTP, no direct/no-reuse/native-TLS switches:
node --test --test-concurrency=1 test/phase4c-transport.test.mjs test/phase4f.test.mjs
node --test --test-isolation=none --test-concurrency=1 '--test-name-pattern=100 verified anonymous|shared-IP emergency' test/phase4f.test.mjs
node --test --test-concurrency=1 test/phase4j.test.mjs
node phase4j-index-probe.mjs --label=windows-1
# Strict native TLS experiment only, PROOF_4J_NATIVE_TLS=1:
node phase4j-transport-probe.mjs --label=windows-native-tls-1
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4j-linux.py' native-tls-1 prepare
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4j-linux.py' native-tls-1 transport
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4j-linux.py' native-tls-1 raw-1
# Fresh final Android, original default HTTP backend:
./evidence/phase4j-build.ps1 -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest','--no-build-cache','--stacktrace' -Log website-phase4j-debug-jvm-1.log -Online -WindowsRoots
python evidence/phase4j-android-build-results.py
./evidence/phase4j-native-cold.ps1 -Label phase4j-ui-cold-1
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4j-linux.py' final-http-1 prepare
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4j-linux.py' final-http-1 backend
node phase4j-build-web.mjs
node evidence/phase4j-artifact-scan.mjs
node phase4j-browser-tests.mjs
node evidence/phase4j-browser-delay-runner.mjs
python evidence/phase4j-final-audit.py
python evidence/phase4j-finalize-reports.py
python evidence/phase4j-final-manifest.py
```

The Windows Python executable is `C:/Users/mohammed saud/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`; `python` above abbreviates that exact executable. Linux runner uses the pinned 24.18.0 Node and unchanged lockfile/dependencies. Per-attempt source snapshots/logs capture revisions and detailed environment switches. No production request or cloud deployment command is included.

Final evidence freeze: `tools/website-proof/evidence/phase4j-final-manifest-reviewed.json`. Earlier manifests and copyedit attempts are retained.
