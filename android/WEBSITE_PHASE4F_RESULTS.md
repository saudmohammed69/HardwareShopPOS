# HardwareShopPOS — Phase 4F local verification

19 October 2026. **LOCAL PHASE CLOSED WITH BLOCKERS. Verified project progress remains 83.5%. Release/production and complete physical Version 1 acceptance are not approved.**

Scope: local development and synthetic tests only. No production deployment, real Cloudflare/Firebase account activation, real enrollment, live shop data, paid service or billing activation is authorized.

## Inspection and checkpoint

Read the owner request, Version 1 architecture/results, release gates, Phase 4D/4E results and evidence, and inspected Android navigation/settings, existing billing/Orders permissions, backup schema, FCM handling, website submission/recovery, worker API, signed identity guards, local gateway, D1 schema, archive/restore and capacity harnesses. Inventory spans the existing Android app, tests and local website/backend tools. Actual source is authoritative; prototype claims are not treated as production functionality.

Before code edits, 390 source/report files were copied and SHA-256 verified in `tools/website-proof/evidence/phase4f-prechange/manifest.json`. This is a source recovery checkpoint, not a production database backup. Room 11, BackupManager format 8 and financial transactions are unchanged.

## Implementation versus proof

| Area | Current technical classification |
| --- | --- |
| Customer catalog/cart/pipe UI | Implemented local website, synthetic settings/catalog; no production POS publication or hosted QR accepted |
| Existing native Orders/billing integration | Implemented DEBUG fixture/cache integration with ordinary Room billing; still explicitly refuses real Firebase-linked fixture databases |
| Individual signed identity/enrollment | Tested older local prototype; incompatible with Version 1 no-worker-cloud-login policy; not production authorization |
| Owner-approved device principal | Production wiring STOPPED: trustworthy remote owner/bootstrap authority is not established by a local OWNER label/shared Firebase UID |
| New shared-IP abuse controls | Local anonymous-session/challenge protocol plus strict optional Siteverify adapter; real widget/account/provider bot detection untested |
| FCM | Existing receiver and reconciliation retained; secure free sender not activated or accepted; foreground/manual fallback remains |
| Archives | Bounded tenant snapshot and local encrypted export/restore; scalable hosted consistent export/cleanup and independent physical copies remain unverified |

## Current executed evidence

- Final backend repeat: **87 executed, 86 passed, one failed, zero skipped**, 88.294 seconds. All **11 new admission tests passed**. The original full run was **85/87**, 137.118 seconds: retained TCP failure plus a test assumption that a shared-IP counter could not cross a real ten-minute boundary. All 50 orders persisted; only the current bucket had reset to 32. The test now requires the full count within one window and bounded current-window counts across a real boundary. No server window, clock or limit changed.
- Named-pipe strict HTTPS diagnostic: **1/1 passed**, expected generated certificate fingerprint matched; unknown issuer, wrong hostname and signed replay rejected. This test uses an explicitly verified local fixture CA in memory, changes no trust store/antivirus and is NOT proof that intercepted TCP is fixed. Original TCP test remains enabled.
- Initial Gradle invocation: **BUILD SUCCESSFUL, JVM tests UP-TO-DATE**; not counted as newly executed tests. Task-only `--rerun`: **312/312**, 39 fresh XML results, no failures/errors/skips, build successful in 4m 38s. Process-scoped AF_UNIX runner workaround retained; default environment root cause remains historical.
- Native regression: **51 unique real methods passed** across 47 billing/Orders/backup/pipe/Bluetooth methods and four corrected cloud-session methods. First command additionally reported one initialization error because a nonexistent class name was selected; that log is retained. It is not a clean single 51-test batch.
- Signed native regression: **8/8 methods passed**, comprising 3 offline Keystore/store, 3 signed enrollment/billing/rotation and 2 separate-instrumentation process-death recovery methods. Guest/host clock skew measured zero; 60-second signature window unchanged. Conditional older INDIVIDUAL fixtures only, not Version 1 device-principal acceptance.
- Cold navigation: **two failures retained**. First billing navigation 23,070 ms against 15,000 ms with approximately 427 MB free host RAM/concurrent Gradle. Second fresh isolated emulator with 1,445,188 KB free host RAM timed out at the existing 15-second Compose readiness condition. Emulator service ANR evidence exists, but root cause is not conclusively isolated to app or host. Warm reminder/navigation/numeric-IME suite **3/3**, 74.506 seconds. Historical 16,335 ms Settings and IME intermittency remain open. No timeout increased or app UI changed.
- Local website build: **PASS** after authorized loopback access. Initial sandbox EACCES is retained. No deployment. Browser/capacity results are recorded below.

## Shared-IP design

No rate limit was disabled. New mode retains three orders per anonymous session/ten minutes, adds a 200-order shared-IP emergency circuit breaker and preserves the 1,200-order shop daily cap. Single-use challenge hashes and counters commit atomically with the order/event. Same-submission capability recovery occurs before a new admission challenge and never creates another order. Only server-HMAC opaque identifiers/buckets are stored; no fingerprint or plaintext IP table is introduced. A private loopback gateway attests test client IP; customer-supplied forwarding headers confer no trust.

Synthetic challenges test protocol/replay/error behavior only. The actual Turnstile Siteverify adapter checks success, hostname, action, cart/submission/session binding and freshness; it fails closed without credentials. No real widget/provider was activated or called. The loopback preview issuer must not be deployed as bot detection. Production widget/gateway provenance/CPU/privacy acceptance still requires separately scoped verification.

## Device authorization stop and decision

Existing Android Keystore enrollment/rotation/revocation components remain available as local proofs. They do not establish a trustworthy first owner for Version 1. An existing local OWNER session can be created on-device and a shared Firebase account does not distinguish workers or prove an owner. Reusing those labels or removing INDIVIDUAL guards would weaken security and is prohibited.

Before production or native Version 1 security wiring, the owner must select and review a trusted bootstrap: independently authenticated existing owner authority with server-verified shop ownership, or out-of-band administrator provisioning of the first owner installation key with a one-use, shop/key-bound approval ceremony and recovery policy. No separate worker cloud sign-in is required by either design. Existing worker permissions, Firebase session and offline billing stay unchanged. Do not claim that a device signature proves a named worker.

The reviewed Version 1 design should bind an Android Keystore installation public key to one server-authorized shop/device principal, short-lived grants and explicit Orders capabilities. Owner approval must bind the exact shop/key/request and expiry; self-selected roles, worker IDs and a shared Firebase UID grant no authority. Server revocation/permission freshness is checked online for every operation; mutations retain durable signed replay protection and sticky claim ownership. App-local permissions and account/database epoch remain pinned through handoff. Audit distinguishes verified device identity from local operator attribution. Reinstall/restore starts unauthorized; key rotation needs staged old/new-key proof and recoverable server acknowledgement. These are design requirements and reused component proofs, **not an implemented accepted Version 1 adapter**. No production identity guard was removed.

## Browser, permitted inputs and preservation

Browser regression **PASS 9/9**, seven viewports from 320×640 to 1440×900, zero console errors or external requests. Verified full mobile checkout, grouped pipe sizes, cart editing/removal, private order edit/cancel, reload/recovery, failed storage/malformed receipts, shop closure, hide/show, many-size scrolling and reduced-height keyboard simulation. Actual iPhone Safari/physical keyboards remain NOT TESTED. Mobile and tablet cart screenshots were visually reviewed: footer/actions fit and scrollable content remains separate.

Fresh maximum-input/index probes **PASS**: 32,768-byte customer request accepted, 32,769 rejected; 25 lines; 100-character customer name; 1,000-character order/missing-item notes; 300-character line notes; maximum existing pipe inputs preserve shared pricing semantics. Maximum legal adjusted order reached 69,821 bytes; another adjustment failed closed with the original order preserved under the existing 96,000-byte bound. Plain/indexed insert+update wrote 2,000/4,000 rows, demonstrating real index amplification rather than assuming one write per order. These probes use synthetic verification and existing limits, not live prices/data.

Current source comparison verifies all **390 recovery copies**, unchanged Android application/test/configuration/assets and unchanged recorded app/test APK hashes. No Android rebuild is necessary for these backend/tool/report-only changes. JVM build/test task is successful; a new assemble/lint invocation is NOT EXECUTED in Phase 4F. Existing Room 11 and BackupManager 8 are unchanged. Isolated native WAL/backup tests passed; no production database backup/restore was attempted.

## FCM and archive review

FCM itself is listed as no-cost, but a sender still needs trusted server OAuth credentials ([Firebase pricing](https://firebase.google.com/pricing), [HTTP v1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api)). A possible architecture is Workers server-only credentials → short-lived OAuth token → FCM HTTP v1, with shop/device-authorized registration and a durable deduplicated retry outbox. This is a design inference, not tested free deployment. Secret custody, token rotation/refresh CPU, cold signing, outbox D1 writes, provider/account eligibility and real delivery remain unverified. No key was obtained and no sender was activated. Cloud Functions/Blaze is not introduced. **Secure free push is unsupported for accepted Version 1 integration until those gates pass**; foreground reconciliation/manual refresh remain available without promises of immediate background alerts. Existing PRINT_JOB routing and Firebase sync source hashes are unchanged.

Hosted archives still require a reviewed tenant-bound snapshot watermark, stable paginated chunks with a complete ordered manifest/checksum chain, owner-local authenticated encryption, two independently recoverable copies and an isolated full-field restore before owner-approved CAS cleanup. Every chunk/commit must remain within the 50-query boundary, with measured retries and quota exhaustion. Source revisions/status/bill identities must match at compaction, and all active/uncollected/unresolved records stay protected. Current bounded snapshot rejects oversize exports rather than truncating them; there is no hosted cleanup or destructive live restore. Admission counters/challenge hashes are temporary metadata, not exported production authority; any future restore/re-enable protocol must preserve replay/rate policy or issue a fresh reviewed epoch. Current security restore quarantines credentials and cannot authorize a restored installation. Independent media and new-installation Bill History link resolution remain unverified. Uncertain offline claims stay **REVIEW_REQUIRED**; evidence intake never transfers, rebills or finalizes them.

## Verification status ledger

Counts are per suite/run, not summed across repeated or overlapping cases.

| Check | Classification | Actual evidence |
| --- | --- | --- |
| Before-edit checkpoint | PASS | 390 verified source/report recovery copies |
| JVM complete suite | PASS | 312 tests, zero failures/errors/skips; cached first invocation excluded |
| Native broad regression | PASS methods / FAILED initial runner selection | 47 real methods pass; one nonexistent-class initialization error retained |
| Corrected cloud-session suite | PASS | Four real methods, 2.113 s; 51 unique broad native methods in total |
| Native signed security | PASS | Eight unique methods; original identity protocol only |
| Native isolated WAL/backup/restore | PASS | Three safety methods within broad native suite; disposable data only |
| Warm reminder/navigation/numeric IME | PASS | Three methods, 74.506 s; initial optional-artifact pull error retained separately |
| Cold navigation, first fresh emulator | FAIL | Billing 23,070 ms > 15,000 ms; 94 s test run |
| Cold navigation, isolated fresh emulator | FAIL | Existing 15-second Compose readiness wait; 58.469 s test run |
| Full backend initial final-source attempt | FAIL | 85/87, 137.118 s; TCP TLS plus window-assumption test error |
| Full backend repeat after test correction | FAIL | 86/87, 88.294 s; TCP TLS only, zero skips |
| Admission/security additions | PASS | All 11 cases within final full backend repeat |
| Strict named-pipe TLS diagnostic | PASS | Within full backend repeat; also standalone 1/1 first run |
| Website artifact | PASS | Authorized loopback build; initial sandbox EACCES retained |
| Browser responsive/customer regression | PASS | 9/9, seven viewport sizes; synthetic Chrome mobile/keyboard simulation |
| Maximum input/adjustment and index cost | PASS | Three executed probe groups; measured D1 results in capacity-probes JSON |
| Current-schema load commands | PASS 3 / FAIL 3 | 200/300/400 complete; both 500 attempts and 1,000 stress fail, preserved without acceptance credit |
| Real QR → secure Version 1 device → existing billing | BLOCKED | Trusted owner bootstrap/device adapter not accepted. Separate browser/native fixture flows pass; they are not one approved secure production flow |
| Production FCM/sound/background receipt | NOT TESTED / unsupported integration | No sender or real enrollment; foreground/manual fallback only |
| Hosted 500/day, provider CPU/quota/account | NOT TESTED | Compressed local cohorts cannot verify provider execution or sustained real days |
| Physical Safari/Android/printer/independent archive media | NOT TESTED | No physical devices, printers or real shop data authorized |
| New Android assemble/lint | NOT EXECUTED | No app/test/build-source changes; recorded app/test APK hashes unchanged, real JVM build succeeds |

## Final measured capacity — three complete, three failed workloads

Six workload commands executed. **200/300/400 completed successfully; both 500 attempts and the 1,000 stress attempt failed.** The additional 500/20-customer run failed at a signed billing claim before finishing its first cycle. Neither 500 run completed the requested two-cycle model. Sustained 500/day and 1,000 stress are **UNVERIFIED**, not passed or extrapolated into acceptance. No production capacity/account eligibility/10 ms CPU credit is earned.

| Requested cohort | Lines / concurrent customers / signed devices | Result | Retained state |
| --- | --- | --- | --- |
| 200 | 5 / 20 / 5 | PASS | 170 archived/restored server completions; 20 NEW and 10 unresolved cancellations |
| 300 | 5 / 20 / 5 | PASS | 255 archived/restored server completions; 30 NEW and 15 unresolved cancellations |
| 400 | 20 / 20 / 5 | PASS | 340 archived/restored server completions; 40 NEW and 20 unresolved cancellations |
| 500, first attempt | 20 / 100 / 10 | FAIL: local socket at signed prepare/ready | Read-only inspection: 244 unique orders, 184 distinct bill identities, 171 completed, **13 sticky uncertain claims** |
| 1,000 stress | 20 / 100 / 10 | FAIL: local catalog-read socket | Zero persisted orders; order workload never started |
| 500, separate normal-concurrency attempt | 20 / 20 / 10 | FAIL: local socket at signed billing claim | Read-only inspection: 356 unique orders, 343 distinct bill identities, 340 completed, **3 sticky uncertain claims** |

Failed databases were inspected read-only and kept. No claim was resumed, transferred or rebilled. `UND_ERR_SOCKET`/peer closure establishes a transport failure; the underlying Windows/workerd/network cause is not conclusively isolated. The first 500 log contains 66 submission-retry messages, the second five; entries after fixture disposal include cascading teardown errors, **not independent initial faults**. Incomplete-run quota totals are unavailable, not zero. No further load retries were used to obtain a pass.

The completed cases each published **3,226 synthetic variants**, performed one full dynamic-catalog browse per customer plus setup browses, 4,800 signed foreground polls, approximately 10% duplicate-hint refreshes (1,105/1,650/2,205 actual refresh calls), edits/cancellations/confirmed adjustments, multiple-device claims/completion recovery, rotation/revocation and 100 rejected authentication requests. All completed order exports were encrypted, copied and restored before explicit eligible cleanup; all protected orders and stable identities were retained. Restores/copies use disposable local databases and same-disk files, not independent disaster recovery media.

| Completed cohort | API + source archive rows read | API + source archive rows written | Inbound Worker requests | Busy-day write envelope* | Max SQL/invocation |
| --- | ---: | ---: | ---: | ---: | ---: |
| 200 | 804,988 | 21,386 | 8,936 | 27,986 | 46 |
| 300 | 1,156,337 | 30,095 | 10,819 | 36,695 | 46 |
| 400 | 1,514,753 | 38,783 | 12,699 | 45,383 | 46 |

\* Adds 6,360 measured fixture/seed/archive-setup writes and 240 **modeled**, unimplemented legacy trusted-freshness writes/day (five devices × 48). Source archive writes are 1,803/2,701/3,597 and reads 5,442/8,151/10,869, already included above. Completed cases have zero recorded lost submissions and matching server/client completed-response meters. Catalog seed reads are zero; other unmetered foundation/provisioning costs remain unknown. These are known local workload totals, not guaranteed account headroom. New Version 1 device authorization, real Turnstile verification CPU, FCM sender/outbox, other account usage and provider failure accounting are missing costs. No complete 500/1,000 final-schema quota total exists.

Published D1 Free allowance is 5M rows read/100k written per account/day, with index amplification ([pricing](https://developers.cloudflare.com/d1/platform/pricing/)); per-database limit is 500 MB, account storage 5 GB and invocation boundary 50 queries ([limits](https://developers.cloudflare.com/d1/platform/limits/)). Workers Free publishes 100k daily requests and 10 ms HTTP CPU ([limits](https://developers.cloudflare.com/workers/platform/limits/)). The known completed 400-order envelope uses 30.3% of reads, 45.4% of writes and 12.7% of requests before missing production costs. This cannot establish the failed 500 target. Account eligibility/shared use and real provider exhaustion are NOT TESTED.

Local measurements: total scenario wall time **135.114/184.451/300.937 seconds**; client request p95 **1,335/1,393/2,154 ms**; maximum measured reconciliation-page wall time **84.9/59.5/325.5 ms**, respectively. Node process CPU **41,109/51,281/67,734 ms** and local workerd CPU **91,984/112,797/168,734 ms** are aggregate local process measurements, including fixture work. They neither measure customer notification delay nor prove per-invocation Cloudflare CPU. FCM actually delivered **zero** messages.

| Completed cohort | Hot allocation after final APIs | Post-archive allocation | 30-day model | 60-day model | 90-day model |
| --- | ---: | ---: | ---: | ---: | ---: |
| 200 / 5 lines | 2,383,872 | 2,064,384 | 24,870,912 | 48,463,872 | 72,056,832 |
| 300 / 5 lines | 2,957,312 | 2,465,792 | 36,913,152 | 72,548,352 | 108,183,552 |
| 400 / 20 lines | 5,709,824 | 3,362,816 | 63,823,872 | 126,369,792 | 188,915,712 |

All sizes are bytes. Models repeat the measured cohort above the 1,277,952-byte catalog/security baseline, assuming 85% of orders are completed/collected/resolved and owner-archived daily while 10% NEW and 5% unresolved cancellations accumulate. They are **not physical multi-day allocation or 500/day measurements**. No archive reduces protected data. Encrypted full-order archive bytes per copy are 122,875/184,174/363,281 for these cohorts; security/tenant exports are additional. Independent media/capacity, hosted cleanup and provider reclamation are unverified.

Backlog sensitivity: actual retained 20-line protected documents average **5,981.2 bytes** in the completed 400 case. If all 500/day remain unresolved for 90 days, documents alone model **269,154,000 bytes**; at 1,000/day, **538,308,000 bytes**, already above 500 MB before catalog/index/events/security. The permitted-input probe produced a **69,821-byte** adjusted active order: 500 such protected orders/day for 90 days would require **3,141,945,000 document bytes**. These are labeled document-only models, not allocated database measurements or a promise that every order reaches that size. The exact finite-storage blocker remains: unbounded protected backlog/large legal inputs cannot fit a free 500 MB database. Keep the **450,000,000-byte operational cutoff**, owner-visible warnings and safe cart-preserving admission failure; no deletion/paid fallback/account circumvention is introduced.

Machine-readable reconciliation, failed database identities, raw hashes and assumptions: `tools/website-proof/evidence/phase4f-capacity-analysis.json`. Both historical Phase 4D/V1 successful cohorts and current failures remain evidence; older schemas/results cannot replace these failed current-schema acceptance runs.

Capacity completions use synthetic server bill IDs/POS sync references. They verify backend identity/claim/completion/retention behavior, not hundreds of actual Android Room transactions. Actual local bill/payment/stock/history behavior is exercised by the separate native suites. Browser and native fixture flows do not establish the blocked production Version 1 authenticated end-to-end connection.

## Exact implementation scope and next approval

Existing proof files changed: `worker.mjs`, `runtime.mjs`, `web-server.mjs`, `native-server.mjs`, `retention.mjs`, `run-android.ps1` under `tools/website-proof/`. New proof files: `customer-abuse.mjs`, `customer-abuse-schema.sql`, `customer-abuse-fixture.mjs`, `test/phase4f.test.mjs`, `test/phase4f-tls-ipc.test.mjs`, `phase4f-capacity.mjs`, `phase4f-analysis.mjs`. These changes add local admission, fixtures, temporary metadata expiry, diagnosis/load tests and runner artifact handling only. No application source, dependency or original pricing function changed.

Reports: new `WEBSITE_PHASE4F_RESULTS.md`; updated `WEBSITE_PROGRESS.md`, `WEBSITE_RELEASE_BLOCKERS.md`, `WEBSITE_ZERO_COST_AUDIT.md`, `WEBSITE_V1_VERIFICATION_RESULTS.md` and `WEBSITE_CODE_AUDIT.md`. Supporting evidence runners are `evidence/phase4f-start-emulator.ps1`, `evidence/phase4f-browser-runner.mjs`, `evidence/phase4f-source-check.mjs` and `evidence/phase4f-test-summary.mjs`. Generated logs/JSON/screenshots/encrypted synthetic archives remain as evidence; exact source/report hashes are in `evidence/phase4f-final-source-manifest.json`.

**Version 1 is not ready for complete secure physical-device acceptance or release.** Browser and existing native component workflows have local evidence, but accepted owner/device trust and the end-to-end secure connection are absent; TCP/cold UI/archive/provider gates remain. No real shop device should be enrolled based on this report.

Next approval should select the trusted owner/bootstrap/recovery authority and authorize a narrowly scoped local Version 1 device-principal implementation/review, preserving current POS login. Separately name an approved valid-TLS test environment and stable emulator/physical test scope. Real account eligibility/CPU/Turnstile/FCM verification would require an explicit **read-only/bounded test** authorization naming accounts, permitted operations and a no-payment/no-deployment boundary. Any later physical acceptance must name synthetic data, devices, printer, network and allowed enrollment/actions. Independent archive media/custody and consistent hosted export/cleanup need their own reviewed proof. None of those approvals is implied by Phase 4F or this report. **Do not start Phase 5/6, deploy, activate production services, enter payment information or modify live data. Stop for owner review after this local closeout.**

## Final cleanup and stop

Verified `phase4f-final-local-check.json`: no listeners on owned test ports 4316/4318/4319/4320/5598, no owned fixture/load/browser/emulator processes, and no host synthetic enrollment-control file. Guest control files were removed by the signed helper. The user's 4317 preview was left untouched. All 390 recovery-copy hashes and unchanged Android/APK hashes pass final verification. Structured counts are in `phase4f-test-summary.json`.

No production database was modified, no real shop device was enrolled, no Cloudflare/Firebase service/account or paid infrastructure was activated, and no deployment occurred. **Local Phase 4F is closed with blockers at 83.5%. Stop for owner review; Phase 5/6 and production actions remain unauthorized.**

## Reproduction commands and retained evidence

Working directory: `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS\tools\website-proof`. Node 24.18.0, installed Chrome, Miniflare 4.20260730.0/local workerd D1. Native commands use only read-only synthetic Operations/API 36.1 `emulator-5598`, never a physical device. Local fixture servers were started hidden; no guest Internet/data connection was enabled for production accounts.

```powershell
# First cached invocation is not counted as fresh tests.
./run-local-gradle.ps1 -Tasks ':app:testDebugUnitTest' -Log 'website-phase4f-jvm.log'
./run-local-gradle.ps1 -Tasks ':app:testDebugUnitTest','--rerun' -Log 'website-phase4f-jvm-executed.log'

# Each cold run used a separately started disposable emulator.
./evidence/phase4f-start-emulator.ps1
./run-android.ps1 -Classes 'com.hardwareshop.pos.MenuKeyboardRegressionTest#largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus' -Log 'phase4f-ui-cold.log'
./run-android.ps1 -Classes 'com.hardwareshop.pos.MenuKeyboardRegressionTest#largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus' -Log 'phase4f-ui-isolated-cold.log'
./run-android.ps1 -Classes 'com.hardwareshop.pos.ReminderUiIntegrationTest#ownerReviewsAnOverdueReminderWithoutSendingOrChangingTheLedger,com.hardwareshop.pos.MenuKeyboardRegressionTest#largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus,com.hardwareshop.pos.MenuKeyboardRegressionTest#pipeFeetAndPinnedCartRemainVisibleWithNumericKeyboard' -Log 'phase4f-ui-warm.log'

# With the private native fixture server running on 4316/4320:
node native-server.mjs
./run-native.ps1 -Classes 'com.hardwareshop.pos.WebsiteNativeIntegrationTest,com.hardwareshop.pos.WebsiteOrdersUiTest,com.hardwareshop.pos.WebsitePhase2SafetyTest,com.hardwareshop.pos.BillingIntegrityTest,com.hardwareshop.pos.AdvancedBillingIntegrationTest,com.hardwareshop.pos.PipeBillingIntegrationTest,com.hardwareshop.pos.BluetoothPermissionTest,com.hardwareshop.pos.CloudSessionStoreTest' -Log 'phase4f-native-regressions.log'
# The nonexistent final class above caused the retained initialization error.
./run-android.ps1 -Classes 'com.hardwareshop.pos.data.remote.CloudSessionInstrumentedTest' -Log 'phase4f-native-cloud-session.log'

# With private signed fixture running on 4318; helper removes guest control files:
node secure-native-server.mjs
./run-signed-native.ps1 -Log 'phase4f-signed-native.log'

npm.cmd test # logs: phase4f-backend-final.log, then phase4f-backend-repeat.log
node build-web.mjs # phase4f-web-build.log sandbox denied; phase4f-web-build-authorized.log PASS
node evidence/phase4f-browser-runner.mjs
node phase4f-capacity.mjs --orders=200 --profile=5,20,5 --probes-only
node phase4f-capacity.mjs --orders=200 --profile=5,20,5
node phase4f-capacity.mjs --orders=300 --profile=5,20,5
node phase4f-capacity.mjs --orders=400 --profile=20,20,5
node phase4f-capacity.mjs --orders=500 --profile=20,100,10
node phase4f-capacity.mjs --orders=1000 --profile=20,100,10
node phase4f-capacity.mjs --orders=500 --profile=20,20,10 # separately labeled normal-concurrency model
# Final aggregation/checks are recorded only after execution:
node phase4f-analysis.mjs
node evidence/phase4f-source-check.mjs
node evidence/phase4f-test-summary.mjs
```

Full stdout/stderr and diagnostics live under `tools/website-proof/evidence/phase4f-*`; JVM build logs are in the existing `2026-10-15/fix-pipe-pricing-and-billing-flow/work/advanced-operations` folder. Android counts are kept separate from runner errors: the initial warm command returned nonzero only when pulling a nonexistent optional backup folder, after `OK (3 tests)`. `run-android.ps1` now distinguishes optional artifacts from required safety-test output and returns success only after actual instrumentation success; no test was disabled. The later signed/native commands verified this helper behavior. Automatic approval review initially prevented the signed command from starting because of a temporary usage limit; after its stated retry time, the authorized command ran and all eight methods passed. The unexecuted review attempt is not counted as a test failure or pass.
