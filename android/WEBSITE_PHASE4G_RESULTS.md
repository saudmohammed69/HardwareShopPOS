# HardwareShopPOS — Phase 4G local implementation and verification

20 October 2026 (Asia/Kolkata). **Final local verification; open release blockers. Verified progress 83.5%, unchanged.** Local/synthetic testing only. No real device enrollment, live data, production Cloudflare/Firebase, paid services, deployment or Phase 5/6.

**Outcome:** local device-pairing and identity-safe recovery proofs are implemented, and the checkout configuration race is repaired. **500/day remains unverified:** both measured attempts failed; the final failed database retains two uncertain claims without reassignment. Android assembly, 312 JVM tests, 63 native executions and ten browser cases pass; one cold native execution and the Windows TLS test fail. Final backend is 95/96. Production and integrated physical acceptance remain blocked. No weighted acceptance increase is earned.

## Inspection and recovery checkpoint

Read the complete Phase 4F report and current release/progress/source evidence before editing. Source inventory covers 376 source/tool entries; inspected request authentication, billing claim transitions, Room/Keystore boundaries, operation identity, HTTP clients, admission and archive paths. Before changes **402 source/report/evidence-runner copies were SHA-256 verified** in `tools/website-proof/evidence/phase4g-prechange/manifest.json`. No live database backup or restore was attempted.

Existing app logins, billing transactions, pricing/pipe logic, stock, GST, payment methods, printing, Room 11 and BackupManager format 8 are preserved. Functional changes are confined to local backend/client proof modules and the minimal customer-site timing repair. The only added Android file is the emulator-only pairing test listed below. Historical Phase 4F failures and uncertain claims remain unchanged.

## Pairing authority and explicit production blocker

New isolated `PAIRING_LOCAL` adapter authenticates **installation keys**, not named workers. It never accepts Firebase tokens, local OWNER labels or client worker IDs as ownership. Existing individual-sign-in adapter remains intact. The prototype's administrator is a Node-only synthetic signing authority with a server-pinned public key/freshness and durable shop authority epoch. The fixture directly provisions a five-minute, one-use, shop/key/device/database-epoch/permission-bound grant, signed by the administrator. This direct private provisioning is not a public approval endpoint.

For real bootstrap, the operator controlling the backend trust configuration must be independently authorized by the actual shop proprietor: verify shop ownership/authority through an independently obtained record/contact and in-person or equivalent verified ceremony; compare the owner installation's full key fingerprint and shop ID over that channel; document approver/evidence without putting identity documents/secrets into customer data; pin the administrator public key and recovery authority. Local POS labels/shared UID cannot satisfy this. The administrator must protect signing credentials, record grants/revocations, prevent grant reuse, verify authority again for lost-owner recovery, rotate epochs/keys and quarantine restored data. Sole-owner loss/revocation must never silently issue a replacement that can steal uncertain billing claims.

**No real administrator, proprietor identity or trust custody has been demonstrated. Production/native Version 1 wiring remains blocked.** Local synthetic grant proofs demonstrate protocol behavior only; they cannot establish the human trust root. No worker cloud login was added. Existing Keystore P-256 signing is reusable, but accepted app pairing UI/lifecycle/local-worker capability intersection is not connected to production.

Owner-key use is also a release boundary: the fixture assumes an independently controlled owner installation. An installation-wide key cannot identify the person using a shared owner/worker POS. Production approval must use an accepted independent owner/operator channel or owner-reauthenticated privileged signing, never a mutable local OWNER label. That ceremony and durable native operation/outbox integration remain unaccepted. The emulator uses separate synthetic test aliases; it does not prove two physical installations or hardware attestation.

Approved device keys are globally unique in the prototype ledger and immutable to one shop; enrollment binds a separate database epoch. Server capabilities, expiry, root epoch and revocation are checked each request and again atomically before protected writes. Worker grants permit only Orders/Billing capabilities, not owner/device administration. Redemption verifies key possession and grant hash, owner/admin authority, five-minute expiry and one-use consumption atomically. Restores always quarantine authority, revoke devices, consume grants and preserve operation/replay evidence; no overwrite/live restore exists.

## Transport and recovery investigation

Phase 4F failures remain: both 500 attempts lost signed prepare/ready or billing-claim responses, and 1,000 stress lost a catalog connection. Their 13/3 uncertain claims are not replayed, reassigned or altered. Peer closure is verified; Windows/workerd/antivirus/resource root cause is not conclusively isolated.

An alternate Node HTTP keep-alive driver also produced ECONNRESET in a small pairing test, so blaming Undici alone is unsupported. The existing pooled driver forcibly recycles connections after 16 requests; installed Undici source confirms this reset behavior, but causation of every historical closure is unproven. New diagnostic driver is optional, without hidden retries or timeout changes.

New paired mutations use a stable signed operation ID and exact request hash. The order transition and immutable operation receipt commit in one guarded D1 transaction. A retry uses a fresh nonce/signature with the same operation ID/body; committed response recovery returns the original receipt. Same ID/different body fails, exact nonce replay fails, and current revocation is checked before recovery. This is backend protocol/client-proof work, not a replacement billing engine or permission to move sticky claims. The load client retries only safe reads, durable-identity submissions and paired order mutations with receipts, at most twice. It never assumes an unobserved action failed or creates a new billing identity to recover it.

## Current executed evidence

- Pairing suite initial attempt: prototype request stream was consumed before signature hashing; fixed by parsing a clone. Retained failed evidence. A later diagnostic run had one actual socket reset and one cross-shop test status mismatch; corrected the assertion to require signature denial on the configured URL, without relaxing isolation.
- Pairing core repeat: **4/4 passed**. The subsequent 94-test suite includes all seven then-existing pairing cases passing; the final 96-test suite includes all nine new cases, including encrypted security restore, stale/revoked authority, racing claims and lost-after-commit response recovery.
- First complete backend run: **94 executed, 92 passed, two failed**, 146.104 seconds. Original Windows TCP TLS failure remains; new revoked-owner-grant case denied authority but incorrectly returned generic 503 because a failed pre-enrollment proof was flushed again. Fixed the new redemption cleanup so the atomic authorization denial stays 403, without granting authority or relaxing its assertion. The 94- and 96-case repeats are recorded below. New archive/restore and injected-after-commit recovery cases passed in that first full run.
- Isolated Ubuntu TCP TLS: **PASS**, exact generated peer certificate verified; unknown issuer and wrong hostname both rejected. Ephemeral certificate/private files removed; no system trust or antivirus changes. This validates an isolated Linux TCP environment only, not the Windows intercepted path or production transport.
- Final backend repeat: **94 executed, 93 passed, one failed, zero skipped**, 195.749 seconds (`tools/website-proof/evidence/phase4g-backend-final.log`). The sole failure remains the Windows TCP TLS test with substituted peer certificate; the isolated Linux TCP result does not reclassify that failure. All seven pairing and eleven shared-IP abuse cases pass.
- Cold API 36 navigation: **one executed, one failed** at the unchanged 15,000 ms Compose assertion, line 78. Preserve `phase4g-ui-cold.log`. Emulator boot took 177,521 ms; host free memory was approximately 1.74 GB before boot and 281 MB during boot. These are observed constraints, not proof that resource contention explains the application failure. Warm/native repeats below do not erase the cold failure; contention-independent acceptance remains unverified.
- Warm API 36 reminder/navigation/numeric-IME: **3/3 passed**, 120.546 seconds; billing/owner-menu timing and 15-second limits unchanged. Broad native regression: **51/51 passed**, 143.418 seconds, covering ten native Orders methods, Orders UI, three backup/safety cases, 23 billing-integrity cases, four advanced billing, four pipe, two Bluetooth permission and four cloud-session notification/printing isolation cases. These synthetic tests do not verify a physical Bluetooth receipt or real FCM delivery.
- Existing signed native security: **8/8 passed** (three key/metadata cases, three signed integration cases and two separate-process rotation methods). Guest/host skew was zero; the 60-second signature freshness window is unchanged. New Android Keystore shop-pairing test: first instrumentation could not attach its process and executed **zero** JUnit methods; log and ActivityManager evidence retained. Unchanged APK repeat after force-stop with a fresh private fixture: **1/1 passed**, 1.078 seconds. This proves non-exportable installation signing, one-use owner/worker approval, revoked-device denial and reinstalled-key rejection on the disposable emulator, not real owner enrollment. Native total: 63 passing executions plus one failed cold execution, with the separate pre-test attachment failure retained. Task-owned emulator stopped before browser/load testing.
- First browser run: two cases passed; the third edit-after-reload case failed with Submit disabled; remaining six were not executed. Inspection found checkout captured unresolved shop configuration and never refreshed its controls when the asynchronous response arrived. A minimal website-only refresh preserves entered details and closed-hours blocking. The deterministic delayed-configuration case and final repeat pass below. No POS design change.
- Final website repeat: **10/10 passed**, seven viewport sizes (320–1440 px), including deterministic delayed config, edit/reload identity, pipe/multiple variants, secure cancellation, lost-response/restart recovery, persistence/malformed-success failures, closed/maintenance blocking and mobile data budget. Zero console errors/external requests. `phase4g-repeat-browser-results.json` retains timings/screenshots; website artifact build passes (`phase4g-web-build.log`). The reduced-height viewport is a keyboard simulation, not physical iOS Safari or Android IME acceptance.
- Android test APK build and complete fresh JVM execution: **PASS**, 4m26s, 312 tests/39 XML suites, zero failures/errors/skips. App APK SHA-256 remains `F9D42EBD2598C617454ECC25173B5256C6425B94B8F14E7E6C73CD38511059DB`; test APK (one added emulator-only pairing class) is `17953FF39AFF53B930784BA1216A68B393B2C5D7B71FC662951D7E7152B78FA3`. Initial wrong-directory invocation failed before compilation on the relative init-script path; successful root invocation retained separately.

## Capacity milestone: 200 orders

200/5-line/20-customer/five-device final-schema cohort: **PASS**, 237.894 seconds including final checks. All 200 submission identities unique, 170 finalized bill identities, 20 NEW and ten unresolved cancelled records preserved byte-for-byte through approved terminal cleanup. API + source archive: **693,889 reads, 23,952 writes**, 8,706 Worker requests, maximum 46 SQL queries/invocation. Hot allocation 2,957,312 bytes; post-archive allocation 2,629,632 bytes. No transport failure/retry in this cohort. These are actual local D1 metadata, not provider throughput/CPU acceptance.

Archive verification also exposes a finite export boundary: this cohort's full pairing/security export has 476,246 plaintext bytes, including 466,604 bytes for 877 permanent operation receipts. The private whole-ledger exporter rejects plaintext beyond 16 MiB. Receipt-only linear growth would reach that bound after roughly 35 such 200-order days, sooner after other growth. This is an explicit production archive blocker, not permission to delete receipts or raise bounds blindly. Final 500-order measurements will refine it; paginated/tenant-bounded hosted archive and independently verified media remain unaccepted. Fixture root freshness (one hour) and device lifetime (one day) are fail-closed test policy, not a tested sustained production renewal process.

300/5-line/20-customer/five-device cohort: **PASS**; 255 completed identities, 30 NEW and 15 unresolved cancellations preserved. API + source archive: **1,031,642 reads, 34,032 writes**, 10,557 requests, max 46 SQL/invocation; hot 3,788,800 bytes and post-archive 3,305,472 bytes. **13 real transport failures were recovered with stable identities; zero unrecoverable failures.** This cohort's initial metrics did not record routes/codes individually, so it cannot prove which failures occurred after a claim committed. The separate injected-after-commit test supplies that evidence. Added diagnostic-only route/code/operation metadata for subsequent profiles; retry policy, assertions, concurrency and timeouts are unchanged. Final regression repeat will include that instrumentation.

400/20-line/20-customer/five-device cohort: **PASS**; 340 archived completions, 40 NEW and 20 unresolved cancellations preserved. API/source archive **1,376,364 reads, 44,075 writes**, post-archive allocation 4,435,968 bytes; max 46 API SQL/invocation, zero transport failures.

Initial 500/20-line/100-customer/ten-device cohort: **FAIL**, retained. First compressed cycle completed (1,746,570 reads/45,207 writes); cycle two lost the response to an intentional duplicate submission at the proof driver's old line 34. That path used a direct call without identity recovery, unlike normal submission and paired order mutations. The recorded paired/read client recovered **88 UND_ERR_SOCKET attempts**, including billing-claim and completion routes, with no exhausted paired retry; its zero-unrecoverable counter does not include the final raw duplicate call and must not be read as workload success. Underlying Windows/workerd peer-close origin remains unidentified.

Hash-verified closed-database copy inspection (`phase4g-capacity-500-20-100-10-inspection.json`) reports integrity OK, 1,000 unique submissions, 848 unique bill identities/claim events/completion events, 848 COMPLETED, 102 NEW and 50 CANCELLED; **zero BILLING_IN_PROGRESS**. Original database/WAL/SHM hashes unchanged. No uncertain claim was replayed/reassigned. The initial drain retained only its first thrown exception; two remaining NEW orders are visible, but the second exception's route cannot be reconstructed conclusively. Later diagnostics retain every rejected pool reason.

Added Node-only customer recovery proof: immutable submission body/identity for exact duplicate retry; after an uncertain edit, capability-protected customer-view must prove matching revision and full intended selection/customer/notes before accepting it, or prove the original revision before a CAS retry. Unexpected newer state remains uncertain; no blind edit overwrite. Application/backend billing rules, retry maximum and timeouts are unchanged. Injected lost-after-commit submission/duplicate/edit tests and a labeled fresh cohort repeat are required before acceptance; failures are not overwritten.

Focused final recovery suite: **9/9 passed**, 13.549 seconds, including the injected customer response losses, actual paired claim-response loss, two concurrent paired claimants and revoked winner retaining its sticky claim. `phase4g-recovery-focused.log` retains all results. New recovery module changes only the local load/client proof; it is not a claim of new native or customer UI production wiring.

Fresh labeled 500 repeat (`phase4g-capacity-500-20-100-10-repeat1.json`): **FAIL before completing cycle one**. Forty-one recorded worker failures were HTTP 403 `Shop device authorization failed`, with request wall times approximately 65–82 seconds, consistent with exceeding the unchanged 60-second signature freshness window under request/verification delay. No proof-age bypass, widened window, authorization-error retry or reduced concurrency was added. Individual server denial reasons/request ages were not logged, so timestamp expiry is a supported diagnosis, not a packet-level proof of the underlying Windows/workerd/host delay. Root/device policy remained the same; this is not evidence of provider behavior.

The repeat recovered 113 transport failures but did not recover those authorization denials. Its observed partial API totals are 1,647,503 reads, 46,308 writes and 6,348 requests (max 46 SQL); no final archive or full-day total exists. Hash-verified copy inspection preserves integrity OK, 500 unique submissions, 388 unique claimed bill identities, 386 completed events, 69 NEW, 25 CANCELLED, four ACCEPTED, 14 PREPARING and **two BILLING_IN_PROGRESS claims**. Both uncertain claims remain sticky and untouched; no replacement bill, replay or reassignment. Original database/WAL/SHM hashes are unchanged. **500/day reliability and final 500 capacity remain unverified; stop the affected production integration.**

Final complete backend after the client-recovery diagnostics/tests: **96 executed, 95 passed, one failed, zero cancelled/skipped**, 110.217 seconds (`phase4g-backend-final-source.log`). All nine new cases pass. The sole failure is still actual Windows TCP certificate/hostname acceptance with substituted peer certificate. An uncaught Windows workerd `WSARecv #64` diagnostic also appears during the intentional committed-response-loss test, which passes; it does not identify the origin of the earlier spontaneous peer closures. Earlier 94-test failures/repeats remain preserved.

1,000/20-line/100-customer/ten-device **stress** cohort: **PASS locally**, including 850 finalized identities archived/restored and all 100 NEW/50 unresolved cancelled records unchanged. API/source-archive **3,499,503 reads and 104,564 writes**, 34,103 requests, max 46 API SQL/invocation. All 18 recorded transport failures recovered; traced events include actual claim/complete/accept/ready UND_ERR_SOCKET failures, while initial customer-create counter entries were not individually traced by that earlier driver. Hot/post-archive figures and raw evidence are in `phase4g-capacity-analysis.json`.

This combined stress workload's known busy-day write model exceeds the published 100,000/day Free limit **before** unknown production sender/shared-account costs; no ₹0 1,000/day guarantee is possible from it. Conditional post-archive 30/60/90-day allocations are 241,741,824 / 482,217,984 / 722,694,144 bytes; protected backlog/permanent identities reach the unchanged cutoff in the model. It remains stress-only. Published limits are linked in the zero-cost audit; local success is not permission to exceed provider quotas or delete pending records.

## Implementation and reproducibility

## Changed implementation files and test commands

Existing backend files: `tools/website-proof/worker.mjs` (pairing dispatch, guarded atomic operation receipts/recovery), `runtime.mjs` (new local schema/optional diagnostic transport), `web/app.mjs` (asynchronous checkout availability). Added proof files: `pairing-schema.sql`, `pairing.mjs`, `pairing-fixture.mjs`, `paired-recovery.mjs`, `customer-recovery.mjs`, `pairing-archive.mjs`, `node-http-fixture.mjs`, `pairing-native-server.mjs`, `phase4g-capacity.mjs`, and `test/phase4g-pairing.test.mjs`, all under `tools/website-proof/`. Added Android **test only**: `app/src/androidTest/java/com/hardwareshop/pos/WebsiteShopPairingTest.kt`. Existing Android source, schema and financial transactions are unchanged.

Evidence runners added under `tools/website-proof/evidence/`: `phase4g-checkpoint.mjs`, `phase4g-start-emulator.ps1`, `phase4g-run-native.ps1`, `phase4g-pair-native-repeat.ps1`, `phase4g-browser-runner.mjs`, `phase4g-browser-repeat-runner.mjs`, `phase4g-browser-delay-case.txt`, `phase4g-isolated-tls.py`, `phase4g-run-capacity.ps1`, `phase4g-capacity-analyze.mjs` and `phase4g-source-verify.mjs`. Logs/results are retained alongside these; no failed log was replaced by a repeat.

Closeout/recovery evidence helpers: `phase4g-repeat-capacity.ps1`, `phase4g-inspect-failure.py`, `phase4g-final-checks.ps1`, `phase4g-capacity-tables.mjs`, and report/manifest verification helpers under the same evidence directory. The inspectors copy closed synthetic DB/WAL/SHM, verify hashes and query only the copies; they never replay or repair source claims.

Commands executed from project root unless the directory is explicitly `tools/website-proof`:

```powershell
& ./tools/website-proof/run-local-gradle.ps1 -Tasks ':app:assembleDebugAndroidTest',':app:testDebugUnitTest','--rerun' -Log website-phase4g-build-jvm-root.log
# tools/website-proof:
npm test # first and final backend logs retained separately
wsl.exe -d Ubuntu -- python3 '/mnt/c/Users/mohammed saud/Documents/Codex/HardwareShopPOS/tools/website-proof/evidence/phase4g-isolated-tls.py'
& ./evidence/phase4g-start-emulator.ps1 -Label phase4g-cold
& ./run-android.ps1 -Classes 'com.hardwareshop.pos.MenuKeyboardRegressionTest#largeCatalogueCanRepeatedlyOpenBillingAndOwnerMenus' -Log phase4g-ui-cold.log
& ./evidence/phase4g-run-native.ps1
& ./evidence/phase4g-pair-native-repeat.ps1
node evidence/phase4g-browser-runner.mjs
node evidence/phase4g-browser-repeat-runner.mjs
npm run build:web
& ./evidence/phase4g-run-capacity.ps1
& ./evidence/phase4g-repeat-capacity.ps1
python evidence/phase4g-inspect-failure.py evidence/phase4g-capacity-500-20-100-10.json
python evidence/phase4g-inspect-failure.py evidence/phase4g-capacity-500-20-100-10-repeat1.json
& ./evidence/phase4g-final-checks.ps1
node evidence/phase4g-capacity-analyze.mjs
node evidence/phase4g-capacity-tables.mjs
```

Capacity runner executes serially: `node phase4g-capacity.mjs --orders=200 --lines=5 --concurrent=20 --devices=5`, then 300 with the same shape, 400/20/20/5, 500/20/100/10 (two compressed daily cycles on one database), and 1000/20/100/10 stress. Detailed subprocess/native method selections are preserved in the runners. Compressed cycles are not two actual operating days, and 1,000 is stress only.

## Physical acceptance and approval gates

**Integrated physical acceptance cannot begin from this evidence.** Real owner/admin trust and privileged signing control, native pairing/operation persistence/revoked-claim recovery, independent bounded archives and provider/sender acceptance remain unapproved. A separately approved UI-only preview on named disposable phones with synthetic data is a narrower test; it would not accept device authorization, financial integration, FCM or Bluetooth printing.

Required next approvals are explicit and separate: (1) owner review of this Phase 4G report and the next local closure scope; (2) accepted proprietor/operator verification and owner-admin device/control/recovery policy, without treating shared UID/local roles as ownership; (3) named isolated physical devices/network/printer test plan with synthetic data and valid TLS, excluding live shop data; (4) account-specific free-plan/shared-quota and secure sender/provider testing scope. Production cloud activation, real enrollment, live catalog/data access and deployment each remain prohibited until expressly authorized. No Phase 5/6 starts here.

Measured local Node CPU includes client signing/parsing and private archive encryption and excludes the separate workerd process; it is not per-invocation server CPU. The load driver's P95 records its signed/read `send` calls, not every anonymous customer call. `maxSyncWallMs` is event-fetch request wall time, not notification-to-device end-to-end delay. Whole-ledger exports and restores use private local SQL operations; the max-query figure applies to measured API invocations, not an accepted hosted archive implementation. Failed/rolled-back provider queries, fixture seed/provisioning, real freshness/sender/outbox and shared-account costs remain additional/unknown. No production capacity, permanent ₹0 or progress increase is earned by these synthetic results alone.

Final `:app:assembleDebug` check: **BUILD SUCCESSFUL**, 2m03s, all 43 tasks up-to-date. Fresh JVM/test-build execution earlier passed 312/312. Final app SHA-256 remains `F9D42EBD2598C617454ECC25173B5256C6425B94B8F14E7E6C73CD38511059DB`, test APK remains `17953FF39AFF53B930784BA1216A68B393B2C5D7B71FC662951D7E7152B78FA3`. Checkpoint verification reports all 402 recovery copies valid, no missing files and no changed protected Android files. Website source matches its locally built artifact (`phase4g-web-artifact-verification.json`). No real product/price/stock/bill, Room 11, BackupManager format 8 or existing financial behavior was changed.

**Phase 4G stops for owner review.** Recommended handoff is an explicitly approved local closure scope for stable-host request-delay/TLS/cold-UI diagnosis, owner/admin control and durable native identity lifecycle, and bounded tenant archives/independent media. Actual free-account, CPU, sender/provider and named-device acceptance need separate approval and evidence. Do not start Phase 5/6 or deploy from this report.


<!-- PHASE4G_CAPACITY -->
## Final local capacity evidence

Both 500-order attempts failed; sustained 500/day remains unverified. Completed cohorts below are local D1-compatible proofs, not provider capacity acceptance. Original failed databases and all claim identities are retained.

| Cohort / lines / concurrency / devices | Outcome | API + source archive reads | Writes | Requests | Max API SQL | Hot / post-archive bytes |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| 200/5/20/5 | ✅ Local PASS | 693,889 | 23,952 | 8,706 | 46 | 2,957,312 / 2,629,632 |
| 300/5/20/5 | ✅ Local PASS | 1,031,642 | 34,032 | 10,557 | 46 | 3,788,800 / 3,305,472 |
| 400/20/20/5 | ✅ Local PASS | 1,376,364 | 44,075 | 12,409 | 46 | 6,770,688 / 4,435,968 |
| 500/20/100/10 repeat (2 planned; 0 complete) | ❌ FAIL | 1,647,503 API partial | 46,308 API partial | 6,348 partial | 46 | 8,146,944 partial / not verified |
| 500/20/100/10 (2 planned; 1 complete) | ❌ FAIL | Unverified total | Unverified total | Unverified total | Unverified | Unverified / not verified |
| 1000/20/100/10 | ✅ Local PASS | 3,499,503 | 104,564 | 34,103 | 46 | 15,032,320 / 9,281,536 |

Counts cover actual local API metadata and source archive work, including indexes/security. Separate isolated restore databases, private fixture seed/provisioning, aborted/rolled-back provider work, real renewal/sender/outbox and account sharing are not accepted production costs. Full stress workload totals must not be presented as a free daily allowance.

| Completed cohort | Conditional retained allocation: 30 / 60 / 90 days | No-cleanup allocation: 90 days | Receipt-only whole days below 16 MiB |
| --- | --- | ---: | ---: |
| 200/day (5 lines) | 42,184,704 / 83,103,744 / 124,022,784 | 153,513,984 | 35 |
| 300/day (5 lines) | 62,459,904 / 123,654,144 / 184,848,384 | 228,347,904 | 23 |
| 400/day (20 lines) | 96,374,784 / 191,483,904 / 286,593,024 | 496,717,824 | 17 |
| 1000/day (20 lines) | 241,741,824 / 482,217,984 / 722,694,144 | 1,240,264,704 | 7 |

**500/day sensitivity only — not verified capacity:** scaling the completed 1,000-order cohort by one half gives conditional retained allocation 121,503,744 / 241,741,824 / 361,979,904 bytes at 30/60/90 days. Without cleanup, 90 days is 620,765,184 bytes. The model allows approximately 111 full days before the 450,000,000-byte cutoff with the modeled terminal cleanup, or 65 without cleanup. Both actual 500 attempts failed; these figures cannot establish safe headroom.

Permanent receipts alone consume the private export's 16 MiB budget after about 14 such full 500-order days; the following cohort exceeds that receipt-only budget, and other growth can reach it sooner. This finite whole-ledger archive is not a production retention solution. No receipt/protected-order deletion, raised cutoff, paid fallback or extra account was introduced.

These linear cohort models are not 30/60/90-day execution. Future complete-ledger archive scans, restart replay and uncollected/max-size input growth remain sensitivity risks. The 450 MB operational cutoff remains enforced; ordinary offline POS billing is independent.

<!-- END PHASE4G_CAPACITY -->
