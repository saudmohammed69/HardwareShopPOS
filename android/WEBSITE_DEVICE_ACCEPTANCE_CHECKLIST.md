<!-- PHASE4J_CURRENT -->
## Phase 4J final local gate — 22 October 2026

**83.5% unchanged (+0%); Phase 5 entry BLOCKED.**

- ✅ Fresh minified/shrunk release build and actual APK hash/static DEX guards pass after AGP 8.6.1 compiler repair.
- ✅ Fresh debug/test build and 312/312 JVM tests, 0 failures/errors/skips.
- ✅ Archive scaling 2/2: 1,024 receipts need 1,026 source reads versus 50,689; 0 verification writes; no schema/index/deletion change.
- ✅ Local website build/artifact scan and 10/10 browser regressions (9 main+1 delayed-hours), 7 viewports, 0 console errors/external requests.
- ❌ Final backend:104 passed, 2 failed, 1 platform skip;106 executed/107 defined. Concurrent ordering and signed-read transport still fail.
- ❌ Native setup blocked before JUnit at 181.1 s; 0 native tests executed. Historical 15 s app/keyboard acceptance remains open.
- ⬜ Focused 200 sync/full capacity gated; no new sustained 500/day, 30/60/90-day or provider-capacity credit. Historical 90,833-write busy day leaves only 9,167 before unknowns.
- ✅ Final preservation audit: 9,585 historical evidence files unchanged; 0 unexpected historical changes. Billing/stock, Room 11, Backup 8, pipes, printing and FCMsource protected.

See [Phase 4J final results](WEBSITE_PHASE4J_RESULTS.md) for exact files, commands, artifacts, failures, cost limits and required approvals. No production connection, deployment, paid activation, real enrollment, live data or Phase 5 execution. Historical sections below remain historical.
<!-- END_PHASE4J_CURRENT -->

<!-- PHASE4H_FINAL_GATE -->
## Phase 4H final local gate — 21 October 2026

**83.5% unchanged; Phase 5 NOT recommended.** Final APK/test build and fresh JVM 312/312 pass; website build/scan and 10/10 browser cases pass. Five-device 500/day completes two local cycles; ten-device Windows attempt fails with expired proofs and six preserved uncertain claims. Known five-device busy-day archive schedule costs 107,824 writes, above the Free 100,000/day allowance. Strict actual TCP TLS passes in Linux; raw backend transport failures remain. Native acceptance is BLOCKED before JUnit (emulator readiness timeout; no 15-second threshold change). Final focused result: FAIL. All details/attempts: [WEBSITE_PHASE4H_RESULTS.md](WEBSITE_PHASE4H_RESULTS.md). No deployment, production connection, paid plan, real enrollment, live data or automatic protected-order deletion.

**Additional native source-review gate:** successful payload-derived operation receipts need separation from later intentional identical catalog publications (for example show/hide/show). The new adapter is DEBUG/synthetic only and remains unaccepted; this inferred lifecycle issue needs a dedicated native regression and reviewed correction before integration. Existing POS billing identity/transactions were not changed.

### Phase 4H acceptance handoff

- ✅ Final debug APK/test APK built; 312 fresh JVM tests pass; artifact hashes in phase4h-final-manifest.json.
- ✅ Browser 10/10 and scanned local artifact; actual strict backend TLS passes in Linux.
- ❌ Disposable emulator readiness failed at 182236 ms; zero native methods executed. Actual paired BillingRepository flow, real process-death rotation, cold/warm 15-second navigation/IME, session continuity and Bluetooth permission/printing regression require execution on an approved valid environment. Test classes in an APK are not acceptance.
- ⬜ Verify one bill, one payment, one stock effect, unified history link and completion on the actual native paired path. Verify restored/reinstalled authority denial and no legacy fallback while quarantine remains.
- ⬜ Owner names synthetic devices/network/printer, approves valid TLS plan and confirms no live shop data. Do not attach/enroll physical shop devices under this phase.
- ⬜ Proprietor/operator authority, owner-admin approval/custody/recovery and independent archive media must be approved before production enrollment. Never reassign an uncertain offline claim automatically.

Phase 5 entry remains blocked by native, high-concurrency and cost gates. Prepared runners are local fixtures only.

<!-- END_PHASE4H_FINAL_GATE -->

## Phase 4H local engineering — verification in progress

20 October 2026. **83.5% unchanged.** New SHA-256 checkpoint verifies 686 files, including both failed Phase 4G databases. Nine focused pairing/recovery tests and three new engineering tests pass at their recorded revisions (1,000 signed reads at 100-customer concurrency; staged rotation; tenant chunked archive above 16 MiB with resume and quarantined restore). A content-type error and a trace-count test setup error were repaired; failed attempts remain retained. Final-source build, integrated native, regressions and capacity acceptance remain pending. Production owner trust, actual provider/CPU/account headroom, physical media/devices, real push and release remain unverified. See WEBSITE_PHASE4H_RESULTS.md; no Phase 5 entry approval is implied.

# Phase 4E — Physical-device acceptance plan

19 October 2026. **PREPARED, NOT EXECUTED.** All physical rows below are pending. Local browser/emulator fixtures do not count as physical acceptance. No permanent production QR exists yet.

## Approval and evidence prerequisites

Before touching a physical device, obtain explicit permission naming the devices, test accounts, data set, printer and allowed network. Separately approve any real-provider account access, enrollment or deployment. Do not run destructive emulator fixture scripts on a phone. Take a verified owner backup before any later live-data phase; use disposable products/orders for acceptance. Keep device/build/browser versions, APK SHA-256, timestamps, test IDs, screenshots and sanitized logs. Do not record passwords, tokens, capability URLs or customer phone numbers in shared evidence.

Version 1 owner decision supersedes the earlier individual cloud sign-in proposal: preserve the existing POS login and do not add separate worker cloud login. Physical acceptance must wait for a reviewed/tested shop-device authorization protocol. Shared Firebase UID and a local worker selector cannot prove individual cloud identity; do not relax the old individual guard to force compatibility. See WEBSITE_ARCHITECTURE_V1.md.

| Device/surface | Required conditions | Actual evidence | Status |
| --- | --- | --- | --- |
| Budget Android customer phone, current Chrome | Ordinary mobile data; 320–412 CSS px; software keyboard; slow connection | Device/model/OS/browser, network, recording, request/receipt correlation | ⬜ |
| iPhone Safari | Actual iPhone, small and large text, keyboard, safe-area insets, rotation | Model/iOS/Safari, screen recording and focus/scroll results | ⬜ |
| Tablet customer browser | Portrait and landscape, touch, 768+ CSS px | Complete multi-size selector and checkout visibility | ⬜ |
| Shop POS phone/tablet | Separately approved installation, existing POS login and local permissions | OS/build/key/enrollment approval, test order and bill IDs | ⬜ |
| Owner personal phone | Existing owner login plus reviewed owner-device authority; no Firebase session switch | Owner-only settings/export/enrollment actions and denied worker actions | ⬜ |
| Two authorized worker devices | Distinct approved installations and existing local worker accounts | Concurrent accept/claim/retry logs and one finalized bill | ⬜ |
| Real 58 mm Bluetooth ESC/POS printer | Authorized paired test printer and paper | Printed receipt photo, disconnect/reconnect and permission evidence | ⬜ |

## Customer flow cases

Local preparation uses `tools/website-proof/test/phase4d.test.mjs` (identity/tenant/replay/restore/reconciliation), `WebsiteDeviceSecurityTest.kt` (Keystore, worker gate and real process-death fixtures), `phase4d-browser-tests.mjs` (Chromium responsive/customer-flow fixtures) and `phase4d-capacity.mjs` (synthetic signed workload). Exact executed results and failures belong in WEBSITE_PHASE4D_4E_TEST_RESULTS.md. These fixtures prepare the following physical cases; they do not check them off.

- [ ] Scan a permanent HTTPS QR over mobile data without shop Wi-Fi. Verify its exact approved public URL and durable redirect policy; do not print the loopback preview as a permanent QR.
- [ ] Browse outside shop hours and in maintenance mode; submission must stop while the cart remains intact. Test preferred pickup inside/outside operating hours.
- [ ] Search normal names, aliases, Kannada, sizes and common spelling variations in a 2,900+ product catalog. Confirm text-only cards, no image placeholders, no public stock/cost/supplier/private settings.
- [ ] Select multiple ordinary sizes together; edit, remove, reopen and reload the cart. Check accessible names, contrast, enlarged text, keyboard focus and 44+ px touch targets.
- [ ] For a known 20-ft synthetic pipe at ₹400/full length and ₹35/extra foot, 1 length + 2.5 ft estimates ₹487.50. Separately test foot-stock and length-stock POS parity, zero/decimal quantities and boundary errors against actual saved variant configuration.
- [ ] Complete name/mobile/notes/pickup fields with Android/iPhone keyboards; submit and edit/remove controls stay reachable. Rotate and return without losing the cart.
- [ ] Rapid double tap, disconnect before send, disconnect after server commit, retry after restart: preserve the same submission identity and show one stored order number only after confirmed persistence.
- [ ] Before acceptance, edit/cancel with the private capability; wrong/lost/cross-order capability cannot read or mutate. Once accepted, customer mutation must fail clearly.
- [ ] Simulate full/quota-unavailable backend: no invented number/success, cart retained, retry explained; ordinary POS remains usable.
- [ ] Check legitimate concurrent customers behind a shared carrier/shop public IP. Current synthetic capacity uses fixture IP identities; verify false-positive abuse limits and trustworthy provider client-IP handling before release.

## POS, identity and worker cases

- [ ] Under the reviewed Version 1 protocol, owner approves each shop-scoped installation and capability policy. Wrong shop, inactive local worker, revoked/expired enrollment, replay, stale authorization and unreviewed restore/key replacement must deny online handoff. Client labels/shared Firebase UID must not confer owner authority or claim individual identity. No real enrollment is authorized by this checklist.
- [ ] Switching workers uses the existing POS login and local permission checks; no separate cloud login or Firebase sync/printing session switch is introduced. Verify existing owner approval/discount permissions and that a worker/session change during network/billing handoff cannot inherit another local worker permission. Server audit identifies the authorized device; local worker labels are not server-verified person proof.
- [ ] New order appears with unread count, customer contact, sizes, quantities, prices, pickup and notes. Two concurrent accept attempts have exactly one winner. Staff manually check availability; website checkout never checks, reserves or deducts stock.
- [ ] Review → Accept → Prepare/checklist → Ready → existing billing. Confirm substitutions/removals with the customer and retain before/after audit. Active Orders persists until successful billing.
- [ ] Cash, UPI, Credit/Udhaar, GST and non-GST use existing calculations and permissions. Record before/after synthetic stock, payment and ledger counts.
- [ ] Successful website billing produces one bill, one payment, one stock effect, one Completed Order and one unified Bill History entry with the original bill link.
- [ ] Crash/restart before claim, after claim, during local commit, after commit, before completion response and after lost completion response: recover without rebilling or duplicate deduction.
- [ ] Revoke the claiming device while offline. It cannot start new online operations. Keep uncertain claim sticky and REVIEW_REQUIRED; owner gathers original Bill History/origin/outbox evidence. Never transfer/rebill on a timeout or unverified “not billed” assertion.
- [ ] Ordinary offline counter billing, existing Firebase reconciliation and remote printing continue independently of the Orders service.

## Notification and printer cases

- [ ] Foreground new-order alert, unread count, manual Refresh Orders and cursor recovery work. Duplicate events produce one alert.
- [ ] Separately authorized real FCM sender/delivery: foreground/background/process killed/reboot, notification permission denied/granted, DND and OEM battery restrictions. Record actual latency and sound. Never promise guaranteed background sound.
- [ ] Remote-print FCM payload still routes correctly; order alert cannot intercept it. Missed notifications recover from independent synchronization.
- [ ] Print a synthetic 58 mm Cash/UPI/Udhaar/GST/pipe receipt; verify characters, totals, wrapping, feed/cut where supported and unified history reprint. Test Bluetooth permission denial, printer disconnect, reconnect and retry without another bill.

## Archive, storage and release gates

- [ ] On approved independently owned physical media, save two encrypted copies with matching SHA-256. Keep passphrase separately. Remove access to one copy and restore from the other into a fresh isolated environment; compare all fields/events/bill links.
- [ ] On a newly enrolled test installation without the original local origin record, open a compacted Completed Order and resolve its original POS bill sync ID from verified authorized archive/link data. The link must open the existing unified Bill History entry without creating a bill/payment/stock effect.
- [ ] Confirm owner-only tenant isolation and HTTPS certificate/hostname validation on the actual endpoint. Older security restore remains quarantined; no revoked key/member revives and replay evidence remains retained.
- [ ] Large security/audit snapshots require a reviewed consistent paginated/streaming export. Bounded export failure is a blocker, never permission to truncate or delete.
- [ ] Verify actual database/account usage, backlog, owner-disk capacity, archive age and warnings. At 450,000,000 allocated bytes stop new website admission; existing identity recovery and ordinary billing remain independent.
- [ ] Record new Version 1 pass/failure/blockers in WEBSITE_V1_VERIFICATION_RESULTS.md and WEBSITE_RELEASE_BLOCKERS.md. Keep earlier phase results historical. Only verified weighted criteria may earn progress. No Phase 5/6 or deployment without fresh approval.
