<!-- PHASE4I_FINAL -->
## Phase 4I final local gate — 21 October 2026

**83.5% unchanged; Phase 5 NOT recommended.** Actual new paired native methods pass, total native67/68 and JVM312/312; cold15-second navigation remains failed. Two-cycle five-device500 profile passes with1,000 unique orders/850 completed bill identities/150 protected orders. Conservative known busy-day source envelope90,833 writes; offline restore costs, growing archive reads and provider/shared-account limits remain separate. Ten-device500,200 and1,000 stress profiles fail; uncertain claims are preserved and never reassigned. Final backend counts: {'tests': 105, 'pass': 102, 'fail': 2, 'skipped': 1, 'cancelled': 0}; browser: 10 executed, 10 passed, 0 failed; 0 console errors; 0 external requests. Actual release scan: BLOCKED.

See [WEBSITE_PHASE4I_RESULTS.md](WEBSITE_PHASE4I_RESULTS.md) for exact files, commands, failures, costs, archive recovery and evidence gaps. No live data, deployment, production activation/enrollment, paid service or Phase5 activity. Older phase conclusions below remain historical.
<!-- END_PHASE4I_FINAL -->

<!-- PHASE4H_FINAL_GATE -->
## Phase 4H final local gate — 21 October 2026

**83.5% unchanged; Phase 5 NOT recommended.** Final APK/test build and fresh JVM 312/312 pass; website build/scan and 10/10 browser cases pass. Five-device 500/day completes two local cycles; ten-device Windows attempt fails with expired proofs and six preserved uncertain claims. Known five-device busy-day archive schedule costs 107,824 writes, above the Free 100,000/day allowance. Strict actual TCP TLS passes in Linux; raw backend transport failures remain. Native acceptance is BLOCKED before JUnit (emulator readiness timeout; no 15-second threshold change). Final focused result: FAIL. All details/attempts: [WEBSITE_PHASE4H_RESULTS.md](WEBSITE_PHASE4H_RESULTS.md). No deployment, production connection, paid plan, real enrollment, live data or automatic protected-order deletion.

**Additional native source-review gate:** successful payload-derived operation receipts need separation from later intentional identical catalog publications (for example show/hide/show). The new adapter is DEBUG/synthetic only and remains unaccepted; this inferred lifecycle issue needs a dedicated native regression and reviewed correction before integration. Existing POS billing identity/transactions were not changed.

<!-- END_PHASE4H_FINAL_GATE -->

## Phase 4H local engineering — verification in progress

20 October 2026. **83.5% unchanged.** New SHA-256 checkpoint verifies 686 files, including both failed Phase 4G databases. Nine focused pairing/recovery tests and three new engineering tests pass at their recorded revisions (1,000 signed reads at 100-customer concurrency; staged rotation; tenant chunked archive above 16 MiB with resume and quarantined restore). A content-type error and a trace-count test setup error were repaired; failed attempts remain retained. Final-source build, integrated native, regressions and capacity acceptance remain pending. Production owner trust, actual provider/CPU/account headroom, physical media/devices, real push and release remain unverified. See WEBSITE_PHASE4H_RESULTS.md; no Phase 5 entry approval is implied.

# HardwareShopPOS — Version 1 architecture and local verification

> **Phase 4F superseding local evidence, 19 October 2026:** See [WEBSITE_PHASE4F_RESULTS.md](WEBSITE_PHASE4F_RESULTS.md). New challenge/session mode passes 20/50/100 shared-IP customers and all 11 new admission tests; the old three/IP result below is historical legacy-mode behavior. Final backend repeat is **86/87**, with original TCP TLS failure preserved; browser **9/9**, JVM **312/312**, native **47+4 real methods** and **eight security methods** pass. Warm UI **3/3**, but two fresh cold attempts fail unchanged deadlines. Version 1 trusted owner/device authorization, secure free FCM sender, hosted archives, physical acceptance and provider capacity remain unverified. **83.5%, unchanged.** Do not interpret local challenge signatures or the old INDIVIDUAL identity fixtures as production Version 1 security.

Phase 4F capacity closeout: **200/300/400 cohorts pass; both 500 attempts and 1,000 stress fail local sockets.** The 500 failures preserve 13 and three uncertain sticky claims without replay or reassignment; the 1,000 catalog failure precedes order creation. Sustained 500/day is unverified. Current 400-case known write envelope is 45,383/day and its conditional 90-day allocation model is 188,915,712 bytes under explicit owner cleanup/protected-backlog assumptions. No release/physical-readiness credit is awarded. Earlier two-cycle V1 results below precede the new admission schema and remain historical only.

19 October 2026. **Progress: 83.5%, unchanged. Local verification closed with blockers; release not approved.**

The owner-confirmed contract is [WEBSITE_ARCHITECTURE_V1.md](WEBSITE_ARCHITECTURE_V1.md). Existing POS login stays unchanged; no separate worker cloud login is authorized. D1 is the online order authority, Firebase keeps its existing POS role, and FCM is conditional on a verified secure free sender. Normal target is 200–500/day, sustained acceptance target 500/day, and 1,000/day remains stress-only.

## Executed current checks

| Check | Actual result |
| --- | --- |
| Before-edit source/report checkpoint | 386 files copied and SHA-256 verified; not a shop database backup |
| Full backend regression | 75 executed: 74 passed, one failed, zero skipped; 70.033 seconds |
| Customer browser regression | Nine passed; seven viewport sizes; zero recorded console errors/external requests |
| Shared-IP admission probe | 20 concurrent carts: three stored, 17 rate-limited; no false confirmations; three accepted retries recover without duplicates |
| Two consecutive 500-order cycles, 5 lines / 20 concurrent / five devices | Both cycles and verified encrypted archives/isolated restores passed; conditional old-auth baseline |
| Two consecutive 500-order cycles, 20 lines / 100 concurrent / ten devices | Both cycles and verified encrypted archives/isolated restores passed; conditional old-auth baseline |
| Current Android build/JVM/native re-execution | Not run for these documentation/load-harness changes; existing application, tests and configuration are preserved and checked against the checkpoint |
| Sustained production 500/day, real FCM, provider CPU/account eligibility | Not verified; not authorized by local tests |

The actual backend failure is certificate/hostname validation in `test/phase4d-tls.test.mjs`. The current run reports an untrusted issuer. Prior preserved diagnosis identified Avast certificate substitution; that historic diagnosis is not silently reclassified as a pass or a newly fixed issue. Verification is enabled; no antivirus change, root installation, TLS bypass or test disabling occurred.

The browser tests cover text-only catalog, multiple variants, full-length/extra-feet pipes, cart edit/remove, details, submission, successful durable receipt, secure edit/cancel, lost-response/restart recovery, malformed receipts, persistence failures, closed hours/maintenance, bounded rendering, slow-network simulation and mobile panel layout. Installed headless Chromium and shortened viewport simulation do not prove iPhone Safari, physical keyboard or mobile-network acceptance.

## Security compatibility — affected integration stopped

The existing signed local API joins only INDIVIDUAL identity bindings and trusted memberships, and its transactional guards pin person/worker references. The DEBUG native gate expects that individual to match the local worker. This cannot be wired unchanged under the new no-worker-cloud-login scope. It remains an isolated proof; no shared identity is promoted into a person, no role is taken from a client claim and no fixture bearer fallback is enabled.

The Version 1 design direction is a reviewed owner-approved shop/device principal, signed installation requests, current revocation, replay-safe mutations, restored-key quarantine and unchanged local worker permissions. Bootstrap/approval authority and permission handoff still require implementation/security review. This is not a production-ready protocol and does not provide server-verifiable per-worker identity. The existing POS/Firebase login is untouched.

## Shared carrier IP blocker

Current local admission allows three orders per IP per ten-minute window. The new 20-customer probe verifies three durable orders, 17 HTTP 429 rejections, no invented confirmation, and idempotent retry of those three accepted carts. This is correct operation of the present limiter and a failed product acceptance condition for a larger legitimate shared-IP group.

Distinct synthetic per-submission IPs in capacity profiles cannot prove safety behind carrier NAT. A reviewed NAT-tolerant abuse policy and trusted production IP extraction remain required. The existing limiter was not raised, bypassed or disabled. Local rollback/error metadata does not prove the provider quota cost of all rejected requests.

## Official free-plan documentation rechecked

Only public documentation was accessed; no Cloudflare/Firebase account, credentials, billing console or real registration token was accessed.

| Service | Published boundary / implication |
| --- | --- |
| D1 Free | 5,000,000 rows read and 100,000 rows written per account/day; tables and indexes consume storage; index changes contribute writes. Daily quota exhaustion makes queries fail. [Pricing](https://developers.cloudflare.com/d1/platform/pricing/) |
| D1 Free storage/query limit | 500 MB/database, 5 GB/account and 50 queries per Worker invocation. Keep the existing 450,000,000-byte admission cutoff. [Limits](https://developers.cloudflare.com/d1/platform/limits/) |
| Workers Free | 100,000 requests/day and 10 ms CPU/HTTP invocation. Local aggregate CPU cannot verify that per-invocation limit. [Limits](https://developers.cloudflare.com/workers/platform/limits/) |
| Pages Free | 500 builds/month and 20,000 files/site. A provided `pages.dev` address avoids a domain purchase. [Limits](https://developers.cloudflare.com/pages/platform/limits/), [provided subdomain](https://developers.cloudflare.com/pages/configuration/custom-domains/) |
| FCM / Spark | FCM is listed no-cost; Spark supports no-cost products without payment information. Cloud Functions is not available on Spark and is not an approved sender dependency. [Firebase pricing](https://firebase.google.com/pricing), [plan requirements](https://firebase.google.com/docs/projects/billing/firebase-pricing-plans) |
| FCM sender | Requires a trusted environment, protected server authorization/recipient tokens and resend/backoff. A Workers HTTP v1 candidate still needs secure token handling, CPU and durable retry-cost verification. [Server requirements](https://firebase.google.com/docs/cloud-messaging/server-environment), [HTTP v1 authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api) |

These published limits do not verify this owner's account eligibility, commercial terms/account status, shared quota use, payment settings, real CPU usage or operational ₹0/month. No free-plan upgrade or paid fallback is permitted. Actual account checks remain separately scoped and unperformed.

## Capacity methodology and limits

The new harness runs two compressed consecutive 500-order service cycles per profile on one persistent disposable D1 database. Each cycle measures 500 complete catalog browses across 3,226 published variants, creates 500 unique orders, leaves 50 NEW/uncollected and 25 unresolved cancellations, and completes 425 synthetic bill-linked orders. It includes edits, confirmed adjustments, stable-identity retries, multiple devices, eight hours' worth of compressed foreground polls and an additional signed refresh per new-order hint plus 10% duplicate hints per device.

These hint refreshes are real API requests; **zero FCM messages are sent**. Actual FCM sender/outbox/token-refresh costs are unverified. Existing INDIVIDUAL synthetic identity fixtures are deliberately retained to measure the known signed baseline; they do not implement the approved Version 1 device principal and do not add a worker cloud login.

All signed operations finish before test-only future maintenance. Resolved/collected synthetic completions require encrypted export, matching second file, isolated restore and owner-confirmed CAS compaction. Active, uncollected and unresolved records remain protected. Both copies are on one test disk; independent owner media remains unverified. No live restore or production cleanup exists.

A two-cycle result is not 48 hours elapsed, a daily UTC-reset test, real Android background scheduling, real FCM, a multi-month API replay or provider capacity acceptance. Legacy 1,000/day forecasts inherited by raw harness output are not used as Version 1 projections; separate analysis uses actual `v1.dailyEvidence` and explicitly two-cycle storage models. The earlier eight 1,000/day stress profiles remain preserved historical evidence, including their sequencing and unknown-transport-cost caveats.

## Commands and evidence

From `tools/website-proof`:

```powershell
$env:PROOF_POOLED_TRANSPORT='1'
npm.cmd test *> evidence/v1-backend-regression.log
node evidence/v1-browser-runner.mjs *> evidence/v1-browser.log
node evidence/v1-shared-ip-probe.mjs *> evidence/v1-shared-ip.log
node v1-capacity.mjs --profile=5,20,5 *> evidence/v1-capacity-5-20-5.log
node v1-capacity.mjs --profile=20,100,10 *> evidence/v1-capacity-20-100-10.log
```

Command results and original failure logs are retained in `evidence/v1-*`. The browser runner redirects artifacts to new Version 1 paths without replacing older Phase 4D results. The source checkpoint is `evidence/v1-prechange/manifest.json`; every copied file was verified before edits.

## Remaining acceptance gates

- Compatible, reviewed and tested shop/device authorization without separate worker cloud sign-in; secure owner bootstrap, revocation/restore and existing worker permission handoff.
- NAT-tolerant admission with safe abuse controls and trusted production client-IP extraction.
- Scalable consistent tenant archive export and verified independent owner copies; no cleanup of protected orders and no unbounded retention guarantee.
- A safe free FCM sender if push is required; real recipient authorization, notification deduplication and physical background delivery remain unverified.
- Final-schema sustained 500/day/account headroom after those protocols exist; unknown interrupted-request, sender and production authorization costs remain explicit.
- Clean actual TLS acceptance; historical cold navigation/IME and physical/mobile/printing acceptance remain open.

Existing Room 11, backup format 8, billing/stock/payment/GST/pipe transactions, Firebase sync and printing are preserved. No deployment, paid activation, payment information, real enrollment or live shop data operation occurred. Stop affected integration; these local results cannot authorize release.

<!-- V1_FINAL -->
## Final Version 1 local result

**83.5%, unchanged. Local verification closed with blockers; sustained 500/day acceptance remains UNVERIFIED.**

Both successful profiles finish two consecutive 500-order cycles on their own persistent disposable database: **2,000 distinct synthetic orders across four completed cycles**, plus the separately preserved failed attempt below. Each successful profile preserves all 1,000 identities, 850 finalized synthetic backend bill links, 100 uncollected NEW and 50 unresolved cancelled records. All 850 completed payloads per successful profile pass encrypted export/copy/isolated restore before owner-attested compaction. Actual Room billing is not simulated into production; native billing evidence remains the earlier Phase 4D suite.

**High-concurrency repeatability remains blocked.** The first 20-line/100-customer/ten-device attempt failed on UND_ERR_SOCKET during an idempotent completion retry after initial submission socket losses. Finally-closing the fixture caused subsequent in-flight ECONNRESET failures; these are not independently counted as different root causes. The original failed log and DB are retained. Read-only remount verifies 144 unique orders/submissions and 93 unique claims: 87 COMPLETED, six BILLING_IN_PROGRESS, 44 NEW, one PREPARING and six READY_FOR_PICKUP. No uncertain claim was transferred, finalized or retried by the inspection. Original failed-attempt API quota metadata is unavailable after isolate closure. The unchanged fresh repeat kept 100-customer concurrency and passed; it does not erase the failure or establish stable sustained acceptance.

| Lines / concurrent / devices | Cycle | Orders | API requests | API reads | API writes | Conservative busy-day reads / writes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 5/20/5 | 1 | 500 | 14065 | 1736902 | 34104 | 1903021/54793 |
| 5/20/5 | 2 | 500 | 14200 | 1766783 | 35798 | 1932902/56487 |
| 20/100/10 | 1 | 500 | 21760 | 1827892 | 34399 | 2314842/54617 |
| 20/100/10 | 2 | 500 | 22030 | 1886419 | 36499 | 2373369/56717 |

Each cycle includes 500 real full catalog browses, edits, retries, customer-confirmed adjustments, incremental/catch-up synchronization, 960 foreground polls per device and 550 hint/duplicate-triggered signed refreshes per device. No FCM delivery, production identity provider or cloud service was used.

The conservative busy-day envelope adds ALL measured one-off API overhead, the ENTIRE two-cycle final archive workload, known fixture setup writes and the explicitly modeled legacy hourly freshness writes to each single cycle. This deliberately overcounts known overhead; it still excludes unmetered identity foundations, final device-principal/sender/outbox implementation, other account traffic and unmatched interrupted attempts. It is not verified free headroom. FCM send-attempt volume is only modeled (two attempts/new order/device); real sender CPU/storage/authentication is unknown.

| Lines / concurrent / devices | Hot bytes | After verified archive bytes | Day 30 / 60 / 90 modeled bytes | Max SQL |
| --- | ---: | ---: | ---: | ---: |
| 5/20/5 | 6606848 | 5201920 | 60710912 / 120184832 / 179658752 | 46 |
| 20/100/10 | 12087296 | 6496256 | 79839232 / 158420992 / 237002752 | 46 |

Storage forecasts linearly repeat the measured TWO-cycle allocation growth at 500/day, including 50 NEW and 25 unresolved cancellations/day. They are models, not 30/60/90-day physical allocation or API runs. The prior 90,000-identity probes remain historical row-shape evidence. Maximum-size or indefinitely protected backlogs can exceed finite free storage even at 500/day. The 450,000,000-byte admission cutoff is unchanged; no active/uncollected/unresolved order was cleaned up.

Measured transport accounting and local aggregate CPU (not provider per-invocation compliance):

```json
[
  {
    "profile": [
      5,
      20,
      5
    ],
    "totalMeasuredApiAndArchiveReads": 3669804,
    "totalMeasuredApiAndArchiveWrites": 83991,
    "accounting": {
      "completedResponsesMissingAtClient": 0,
      "additionalRowsRead": 0,
      "additionalRowsWritten": 0,
      "recordedTransportRetries": 0,
      "unmatchedTransportAttempts": 0
    },
    "quotaTotalsAreLowerBounds": false,
    "localP95WallMs": 2666.6622999999963,
    "localNodeCpuMs": 140422,
    "localWorkerdCpuMs": 321218.75,
    "elapsedMs": 538463.8722000001
  },
  {
    "profile": [
      20,
      100,
      10
    ],
    "totalMeasuredApiAndArchiveReads": 4201261,
    "totalMeasuredApiAndArchiveWrites": 84251,
    "accounting": {
      "completedResponsesMissingAtClient": 0,
      "additionalRowsRead": 0,
      "additionalRowsWritten": 0,
      "recordedTransportRetries": 8,
      "unmatchedTransportAttempts": 8
    },
    "quotaTotalsAreLowerBounds": true,
    "localP95WallMs": 4096.53760000004,
    "localNodeCpuMs": 221656,
    "localWorkerdCpuMs": 530796.875,
    "elapsedMs": 797047.0026
  }
]
```

Private workerd counters cover completed responses only. Interrupted requests without response metadata may conceal a commit and unknown quota use; totals are never treated as zero-cost for those attempts. Source archive SQL is counted separately. Local source-workerd CPU snapshots omit terminated isolated restore workers and cannot prove Cloudflare per-invocation compliance. Test-only next-window nonce/admission expiry runs only after all signed APIs; it removes expired security/admission metadata, not protected order evidence.

### Final files and preservation

The 386 recovery copies, every existing application/backend/test/configuration source and recorded APK hashes verify. Existing source changes are reports only. No Android build/JVM/native run was repeated for this report/load-harness change; prior 312 JVM, 51 broad native and eight security methods remain historical, with cold navigation/IME limitations retained. Capacity commands are one initial pass, one retained failure and one unchanged fresh repeat pass; backend exits one solely for TLS; browser and shared-IP probe exit zero, while shared-IP PRODUCT acceptance remains blocked. The final read-only process/port check finds no named Version 1 test process or listener on test ports 4318/4319. It neither targets nor claims reachability of the user's 4317 preview.

Changed/added project source and reports:

- `WEBSITE_ARCHITECTURE_V1.md`
- `WEBSITE_AUTH_SECURITY_DESIGN.md`
- `WEBSITE_CODE_AUDIT.md`
- `WEBSITE_DEVICE_ACCEPTANCE_CHECKLIST.md`
- `WEBSITE_NOTIFICATION_DESIGN.md`
- `WEBSITE_PHASE4D_4E_TEST_RESULTS.md`
- `WEBSITE_PROGRESS.md`
- `WEBSITE_RELEASE_BLOCKERS.md`
- `WEBSITE_RETENTION_DESIGN.md`
- `WEBSITE_V1_VERIFICATION_RESULTS.md`
- `WEBSITE_ZERO_COST_AUDIT.md`
- `tools/website-proof/v1-analysis.mjs`
- `tools/website-proof/v1-capacity.mjs`

Local evidence helpers:

- `tools/website-proof/evidence/v1-browser-runner.mjs`
- `tools/website-proof/evidence/v1-close-reports.mjs`
- `tools/website-proof/evidence/v1-failed-capacity-inspection.mjs`
- `tools/website-proof/evidence/v1-shared-ip-probe.mjs`
- `tools/website-proof/evidence/v1-source-check.mjs`

Final analysis and verification commands, from tools/website-proof:

```powershell
node v1-analysis.mjs *> evidence/v1-capacity-analysis.log
node v1-capacity.mjs --profile=20,100,10 *> evidence/v1-capacity-20-100-10-repeat.log
node evidence/v1-failed-capacity-inspection.mjs *> evidence/v1-capacity-first-failure-inspection.log
node evidence/v1-source-check.mjs
node evidence/v1-close-reports.mjs
node evidence/v1-source-check.mjs
```

### Stop conditions

Do not wire the incompatible individual-identity adapter, introduce separate worker cloud sign-in, weaken authentication/abuse controls, activate a sender, deploy or enroll real devices. Stop affected integration pending the documented device-principal, shared-IP, archive and free-sender reviews. No production data, payment information, paid upgrade, Firebase order mirror or deployment was introduced. Final requirements are recorded; this is not release approval.


Phase 4I final capacity checkpoint: all six profiles executed. 300 and 400 PASS; five-device500 PASS two cycles. 200 FAIL during sync. Ten-device500/100-customer FAIL in cycle1: 499 unique orders, 408 completions, one retained sticky claim; 1,776 transport failures /17 exhausted recoveries, max proof age22s, zero auth denials. Stress1000 FAIL: 999 unique orders, 809 completions, two sticky claims; 3,535 transport failures /41 exhausted recoveries, max age25s, zero auth denials. All failed databases/claims retained, inspection performed only on hash-verified copies, none replayed/reassigned. Final original-driver backend/browser/release verification still running. +0% =83.5%; Phase5 NOT recommended.

Phase 4I final original-driver backend: 105 defined /104 executed, 102 PASS, two FAIL, one platform SKIP, zero cancelled, 634.411s. Failure cases: bounded keep-alive regression (phase4c-transport) and raw50 shared-IP (phase4f). Raw20/100 shared-IP, strict actual TCP TLS, all new intent/archive cases, pairing/revocation/claim recovery, corrupt/unauthorized restore and quota/cutoff rollback pass. Both earlier backend attempts remain retained. No full-suite acceptance or progress increase;83.5%. Final ingress/browser/release checks continue.

Phase 4I website final verification: local build and static scan PASS;10/10 browser cases, seven viewports, no browser console errors or external requests. New catalog/cart/confirmation/many-size screenshots visually inspected. Simulated slow-mobile load6,284ms, search236ms, gzip30,956 bytes at final browser catalog snapshot. These are emulated network/CPU/keyboard dimensions, not physical-phone acceptance. Both OS read diagnostics complete3,000/3,000 with no retry/failure; Linux max proof ages2/2/6s versus Windows1/1/2s. Actual sustained ten-device order acceptance remains FAIL. Release verification pending;83.5% unchanged.

CORRECTION — Phase4I setup accounting: the earlier 84,331 five-device envelope includes API publication/revocation and archive/final-flush, but excludes6,502 measured private source seed/schema writes. All counters were retained. Fully charging every measured source setup write raises the two daily conservative envelopes to83,366 and90,833 (9,167 margin before unknowns), while recurring59,602/67,069 is unchanged. Whole-cohort measured source total150,435 writes. The offline restore222,392 writes are additional local recovery work and cannot be moved into hosted D1 within this budget. Earlier milestones remain historical, superseded by this correction. No production ₹0/headroom credit;83.5% unchanged.
