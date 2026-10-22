# HardwareShopPOS — Phase 4D + 4E Test Results

> **Historical phase report:** The later owner-confirmed [Version 1 requirements](WEBSITE_ARCHITECTURE_V1.md) supersede the separate worker sign-in proposal and change normal traffic to 200–500 orders/day. Preserve the existing login. These Phase 4D/4E test results remain historical evidence; current Version 1 results are tracked separately.

**Local verification closed with release blockers. Verified weighted completion: 83.5%, unchanged.**

Original verification date: 19 October 2026. This report was reconstructed from the preserved closeout generator, measurement files and test evidence after its contents were found replaced by the six-character text PdkqVZ. That replaced content is preserved separately in the evidence directory. Reconstruction does not rerun tests or claim new acceptance.

## Verified results

| Check | Recorded result |
| --- | --- |
| Android application and instrumentation APK build | Passed; build plus JVM run completed in 9m 17s |
| Complete Gradle JVM suite | 312 passed; 39 XML suites; zero failures, errors or skipped tests |
| Broad Android regression suite | 51 passed in 80.123 seconds |
| Native security | Eight unique methods passed, including signed identity/billing/rotation and separate-process recovery |
| Focused final backend security | 34 passed in 41.713 seconds |
| Complete final backend suite | 74 passed / 75 total; one TLS failure; zero skipped |
| Website browser suite | Nine passed; seven viewport sizes; no recorded console errors or external requests |
| Local signed capacity workloads | Eight completed profiles; caveats and lower-bound accounting below |
| API 36 reminder/navigation/keyboard suite | First run 2/3; two warm repeats 3/3; cold navigation failure retained |
| Legacy encrypted security archive restore | Passed in isolated quarantined environment; no authority reactivated |
| Production and physical acceptance | Not performed; not approved by local test results |

## Implementation and preservation

- Added synthetic server-authorized individual UID/person/shop membership/device/local-worker bindings. The native DEBUG identity gate is an isolated prototype outside existing login, Hilt and billing wiring. Shared Firebase identity is not individual worker proof.
- Added transactional person/worker-reference checks and explicit tenant tags for replay evidence. These affect only the disposable local D1 proof; no live migration occurred.
- Added owner-only, signed, HTTPS-required tenant archive snapshots and local AES-256-GCM encryption, copy verification and isolated quarantined restore. Bounded exports fail closed rather than truncate. Scalable consistent pagination remains pending.
- Preserved sticky billing claims for uncertain revoked/offline devices. Owner evidence intake remains REVIEW_REQUIRED and cannot transfer, finalize or rebill an uncertain claim.
- Added capacity/history/transport measurements and a process-scoped Gradle socket-directory runner. No existing billing transaction, product price, stock, payment method, GST, pipe calculation, Bluetooth printing, Firebase session, Room 11 schema or backup format 8 was changed.

## Diagnosed failures and limits

**Gradle test-worker networking:** standalone Java Selector/AF_UNIX probes fail in both default TEMP path spellings and succeed in a project-local socket directory. The scoped runner sets jdk.net.unixdomain.tmpdir for its process tree and restores the prior environment. The actual Gradle suite passes through this runner. The underlying Windows TEMP cause is not independently established; default unconfigured launches can still fail.

**TLS:** the generated test certificate chain verifies independently, but Avast Web/Mail Shield substitutes an untrusted certificate on the actual loopback connection. Node and OpenSSL reject it with verification enabled. The failing test remains enabled. No antivirus disabling, untrusted root installation or TLS bypass was performed.

**Android UI:** cold Settings navigation measured 16,335 ms against the unchanged 15,000 ms acceptance limit. Two warm repeat suites passed. Low host memory and emulator service ANRs were observed but do not establish a complete root cause. Historical API 36 IME/focus failures remain visible; physical keyboard and cold-start stability remain open.

**Capacity:** local synthetic results do not prove real provider CPU, account eligibility, sustained capacity, billing correctness on deployed multi-device hardware or unlimited protected-order storage. Preserve the unchanged 450,000,000-byte admission cutoff and all active, uncollected and unresolved records.

## Primary evidence and reproducibility

Evidence paths below are relative to tools/website-proof/evidence/:

- phase4d-gradle-jvm-counts.json and the external build log website-phase4d-build-jvm-first.log under the existing work/advanced-operations directory.
- phase4d-native-regressions.log; phase4d-signed-native-final.log and its keys, begin-recovery and finish-recovery logs.
- phase4d-ui-repeat1.log, phase4d-ui-repeat2.log and phase4d-ui-repeat3.log with associated diagnostics.
- phase4d-security-final.log; phase4d-backend-final.log; phase4d-browser-results.json; phase4d-web-build.json.
- phase4d-capacity-analysis.json, eight per-profile measurement JSON files, phase4d-tail-reconciliation.json and the history-growth JSON files.
- phase4d-legacy-security-restore.json, phase4d-final-cleanup.json and phase4d-final-source-manifest.json.

Executed project-root build command:

```powershell
& ./tools/website-proof/run-local-gradle.ps1 -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:testDebugUnitTest' -Log 'website-phase4d-build-jvm-first.log'
```

Executed local proof commands (from tools/website-proof):

```powershell
$env:PROOF_POOLED_TRANSPORT='1'
node --test --test-concurrency=1 test/phase4b.test.mjs test/phase4c.test.mjs test/phase4d.test.mjs
& ./evidence/phase4d-run-capacity.ps1
& ./evidence/phase4d-finish-local.ps1
& ./evidence/phase4d-final-native.ps1
```

The preserved runner scripts and logs contain the individual instrumentation and workload invocations. Commands listed here are historical executions; report recovery did not execute them again.


<!-- PHASE4D_FINAL -->
## Phase 4D + 4E final local closeout

Local verification is closed with explicit release blockers. **83.5%, unchanged.** No cloud/account activation, real shop data, physical enrollment, deployment, paid service, production migration or Phase 5/6 occurred.

Final source: **16 existing files changed, 15 source/report files added**; all 371 recovery copies, six protected POS files and tested APK hashes verify unchanged from their stated baselines. Every existing main Android source remains unchanged; the new DEBUG identity gate is outside production wiring.

Final full backend: **74/75 passed, one actual TLS acceptance failure, zero skipped**. The sole failure remains Avast untrusted-certificate substitution; it is not hidden or counted as a pass. Focused final security is 34/34. Website Chromium browser regression **9/9** passes with no console errors/external requests; seven viewport layouts and shortened-keyboard viewport simulation are synthetic, not physical Safari/keyboard acceptance. Local website artifact builds with 3249 variants/31041 gzip catalog bytes and productionReady=false.

The real Gradle JVM suite executes **312/312**, no errors/failures/skips. Existing broad native **51/51** and final-source signed native **eight methods** pass (3 key/store + 3 signed identity/billing/rotation + two separate-process recovery). Initial and final signed runs are separate executions, not 16 unique security methods. UI runs remain **2/3, 3/3, 3/3** with one retained cold Settings 16,335 ms failure; all 15-second limits remain unchanged. Historical IME/focus failures are not erased. Original nine-table encrypted security archive restores under the new schema with every device revoked and zero invented account bindings.

### Final-schema capacity

All eight fresh synthetic 1,000-order profiles complete: 850 completed/bill-linked identities verified through 71 encrypted order archive/restore batches, 50 unresolved cancellations and 100 uncollected NEW records kept complete. Each includes 3,226 catalog variants, individual account bindings, 100 duplicates, 50 lost-response identity recoveries, 100 customer edits, 85 confirmed adjustments, 17 claim retries, completion retries, rotation/revocation, multiple device synchronization and compressed 4,800/9,600 foreground polls. Backend bill IDs in this workload are synthetic completion fixtures; actual existing Room bill/payment/stock/history behavior is exercised separately by native tests.

| Lines | Customers | Devices | API R/W (workerd) | Archive R/W | Total writes | + modeled freshness | Modeled reads | Requests | Max SQL | Hot/archive bytes |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 20 | 5 | 261889/75995 | 28943/7975 | 83970 | 84210 | 3452312 | 18062 | 46 | 7057408/5210112 |
| 5 | 20 | 10 | 360451/76255 | 29043/7975 | 84230 | 84710 | 3550974 | 23204 | 46 | 7069696/5222400 |
| 5 | 100 | 5 | 519969/75995 | 28943/7975 | 83970 | 84210 | 3452312 | 18062 | 46 | 7036928/5214208 |
| 5 | 100 | 10 | 618531/76255 | 29043/7975 | 84230 | 84710 | 3550974 | 23204 | 46 | 7032832/5218304 |
| 20 | 20 | 5 | 279664/75995 | 28943/7975 | 83970 | 84210 | 3470087 | 18062 | 46 | 12349440/6483968 |
| 20 | 20 | 10 | 378226/76255 | 29043/7975 | 84230 | 84710 | 3568749 | 23204 | 46 | 12349440/6492160 |
| 20 | 100 | 5 | 542165/75995 | 28945/7975 | 83970 | 84210 | 3474510 | 18062 | 46 | 12365824/6483968 |
| 20 | 100 | 10 | 640727/76255 | 29045/7975 | 84230 | 84710 | 3573172 | 23204 | 46 | 12365824/6496256 |

Workerd API counters capture completed response metadata before outer loopback loss; source archive SQL is separate. 6 profiles predate the harness maintenance-sequencing repair: their final read-only archive POST followed simulated future cleanup. Their core order workflows precede that simulation, but they are not retroactively clock-consistent full replay tests. A separate current-time-only supplement verifies actual proof expiry, removes 6 expired proofs and records all additional D1 reads/writes while unchanged order/event/audit/claim/binding hashes prove no business-evidence changes. Raw profile JSON files and their SHA-256 hashes remain preserved. 2 profiles finish all signed APIs before future maintenance. Totals below include the separately measured supplement (including its verification reads), not a claim of eight post-repair repeats.

Known successful API plus local archive/supplement writes are **83,970–84,230**; with separately modeled hourly trusted freshness, **84,210–84,710**. The real provider identity feed, full hosted archive/cleanup protocol, initial identity foundations and account-wide other workloads are not verified/budgeted. Separately known setup writes are in the JSON. Models with 1,000 catalog visitors use measured 20/100-visitor dynamic browsing, not credited production Pages cache savings.

Lost-response accounting by profile (volatile local isolate evidence; not provider accounting). Any unmatched transport attempt can conceal a commit and keeps quota totals LOWER BOUNDS; no zero-cost assumption is made:

```json
[
  {
    "profile": "5/20/5",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "5/20/10",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "5/100/5",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "5/100/10",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "20/20/5",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "20/20/10",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 0,
    "quotaTotalsAreLowerBounds": false,
    "transportRetries": 0
  },
  {
    "profile": "20/100/5",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 2,
    "quotaTotalsAreLowerBounds": true,
    "transportRetries": 2
  },
  {
    "profile": "20/100/10",
    "completedResponsesMissingAtClient": 0,
    "rowsRead": 0,
    "rowsWritten": 0,
    "transportAttemptsWithoutCompletedResponseMetadata": 3,
    "quotaTotalsAreLowerBounds": true,
    "transportRetries": 3
  }
]
```

All source invocations remain at most 46/50 SQL statements. Pre-cleanup tenant snapshots returned complete bounded 200 or explicit 409 according to retained security history; no truncation or cleanup is authorized by either. Oversized non-expiring audit snapshots separately require 409 and preserve every row. Each profile encrypts/copies/compares a quarantined tenant restore of 12 selected order identities plus bounded tenant security rows; the flags above distinguish earlier post-maintenance stub snapshots from corrected pre-maintenance full-order snapshots. The first aborted profile's 200-versus-409 harness assertion and database are retained; it is not counted as a completed capacity run.

| Lines/customers/devices | Local p95 wall ms | Node CPU ms | Source workerd CPU ms |
| --- | ---: | ---: | ---: |
| 5/20/5 | 558 | 69359 | 162766 |
| 5/20/10 | 608 | 91578 | 210031 |
| 5/100/5 | 7056 | 70297 | 152672 |
| 5/100/10 | 6729 | 89828 | 206203 |
| 20/20/5 | 1303 | 71609 | 189891 |
| 20/20/10 | 929 | 97735 | 243031 |
| 20/100/5 | 5895 | 76406 | 197547 |
| 20/100/10 | 9795 | 98656 | 251266 |

CPU figures are loaded-host Node/source-workerd aggregate measurements, omit terminated restore processes and later tail-reconciliation CPU, and cannot prove 10 ms production per-invocation compliance. Latencies are local wall time.

### Storage and backlog

| Lines | Days | Retained identities | Protected NEW / unresolved cancelled | Allocated bytes | Allocation probe reads |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 30 | 30000 | 3000/1500 | 41115648 | 1 |
| 5 | 60 | 60000 | 6000/3000 | 81227776 | 1 |
| 5 | 90 | 90000 | 9000/4500 | 121475072 | 1 |
| 20 | 30 | 30000 | 3000/1500 | 68603904 | 1 |
| 20 | 60 | 60000 | 6000/3000 | 136278016 | 1 |
| 20 | 90 | 90000 | 9000/4500 | 203849728 | 1 |

These are real local D1 allocation/index probes of final-schema representative row shapes, not 90 days of authenticated production workflow. Copied authorities are disabled; repeated archive references are byte-shape fixtures, not new verified customer exports/bills. Every allocation probe stays bounded and matches the full summary's bytes.

For comparison only, repeating the first-day allocated growth linearly gives:

| Lines/customers/devices | Day 30 bytes | Day 60 bytes | Day 90 bytes |
| --- | ---: | ---: | ---: |
| 5/20/5 | 120430592 | 239624192 | 358817792 |
| 5/20/10 | 120205312 | 239153152 | 358100992 |
| 5/100/5 | 120553472 | 239869952 | 359186432 |
| 5/100/10 | 120082432 | 238907392 | 357732352 |
| 20/20/5 | 158646272 | 316055552 | 473464832 |
| 20/20/10 | 158298112 | 315338752 | 472379392 |
| 20/100/5 | 158646272 | 316055552 | 473464832 |
| 20/100/10 | 158420992 | 315584512 | 472748032 |

This is a model, not physical prediction; page reuse/high-water/security-history/payload/backlog change actual growth. The labeled Phase 4C measured-document backlog model remains **642,401,047 bytes at day 90** for 1,000 protected 20-line documents/day, before indexes/events/security. A permitted 69,821-byte adjusted document at 150 protected/day models 942,583,500 document bytes. Finite free storage cannot guarantee this unbounded workload. The 450,000,000-byte admission cutoff is unchanged; active/uncollected/unresolved orders are never automatically deleted. Remaining updates can still consume headroom after admission stops.

### Final exact source changes

Paths below are project-relative; final hashes are in phase4d-final-source-manifest.json. Generated APK/build/evidence/checkpoint/disposable database artifacts are separately recorded, not application source edits.

- `WEBSITE_AUTH_SECURITY_DESIGN.md`
- `WEBSITE_CODE_AUDIT.md`
- `WEBSITE_DEVICE_ACCEPTANCE_CHECKLIST.md`
- `WEBSITE_PHASE4D_4E_TEST_RESULTS.md`
- `WEBSITE_PROGRESS.md`
- `WEBSITE_RELEASE_BLOCKERS.md`
- `WEBSITE_RETENTION_DESIGN.md`
- `WEBSITE_ZERO_COST_AUDIT.md`
- `app/src/androidTest/java/com/hardwareshop/pos/WebsiteDeviceSecurityTest.kt`
- `app/src/main/java/com/hardwareshop/pos/data/website/WebsiteWorkerIdentityGate.kt`
- `tools/website-proof/d1-fixture.mjs`
- `tools/website-proof/phase4d-analysis.mjs`
- `tools/website-proof/phase4d-browser-tests.mjs`
- `tools/website-proof/phase4d-build-web.mjs`
- `tools/website-proof/phase4d-capacity.mjs`
- `tools/website-proof/phase4d-history-growth.mjs`
- `tools/website-proof/phase4d-tail-reconcile.mjs`
- `tools/website-proof/run-local-gradle.ps1`
- `tools/website-proof/run-signed-native.ps1`
- `tools/website-proof/runtime.mjs`
- `tools/website-proof/secure-native-server.mjs`
- `tools/website-proof/security-archive.mjs`
- `tools/website-proof/security-schema.sql`
- `tools/website-proof/security.mjs`
- `tools/website-proof/tenant-archive-client.mjs`
- `tools/website-proof/tenant-archive.mjs`
- `tools/website-proof/test/phase4b.test.mjs`
- `tools/website-proof/test/phase4c.test.mjs`
- `tools/website-proof/test/phase4d-tls.test.mjs`
- `tools/website-proof/test/phase4d.test.mjs`
- `tools/website-proof/worker.mjs`

Local reproducibility/evidence helpers:

- `tools/website-proof/evidence/Phase4dSelectorProbe.java`
- `tools/website-proof/evidence/Phase4dUnixProbe.java`
- `tools/website-proof/evidence/phase4d-close-reports.mjs`
- `tools/website-proof/evidence/phase4d-create-tls-fixture.ps1`
- `tools/website-proof/evidence/phase4d-final-native.ps1`
- `tools/website-proof/evidence/phase4d-finish-local.ps1`
- `tools/website-proof/evidence/phase4d-legacy-security-restore.mjs`
- `tools/website-proof/evidence/phase4d-run-capacity.ps1`
- `tools/website-proof/evidence/phase4d-source-check.mjs`
- `tools/website-proof/evidence/phase4d-start-emulator.ps1`

### Additional executed commands

```powershell
node phase4d-capacity.mjs --collect
node phase4d-history-growth.mjs
node phase4d-analysis.mjs
node evidence/phase4d-legacy-security-restore.mjs *> evidence/phase4d-legacy-security-restore-final.log
node phase4d-build-web.mjs *> evidence/phase4d-web-build.log
node phase4d-browser-tests.mjs *> evidence/phase4d-browser.log
npm test *> evidence/phase4d-backend-final.log
& ./run-signed-native.ps1 -Log 'phase4d-signed-native-final.log'
node evidence/phase4d-source-check.mjs
node evidence/phase4d-close-reports.mjs
node evidence/phase4d-source-check.mjs
```

The full backend exit is 1 due solely to the retained TLS failure. All other command exits are preserved in their logs/controller JSON. Cleanup verifies no owned synthetic servers/emulator/workerd and no host control fixture; it does not target or claim reachability of the user's 4317 preview. Physical/provider/independent-copy claims remain pending.

### Approval needed next

Review WEBSITE_RELEASE_BLOCKERS.md and WEBSITE_DEVICE_ACCEPTANCE_CHECKLIST.md. Choose the isolated individual Orders sign-in/explicit switch-reauthentication workflow before its user-facing wiring. Separately approve a named clean TLS test environment and scalable archive/epoch lifecycle review. Any real cloud account audit, physical device/printer testing/enrollment, live data/migration, deployment or Phase 5/6 needs its own explicit scope. **Stop here; no automatic next phase or release.**

