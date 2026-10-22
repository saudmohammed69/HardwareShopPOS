# HardwareShopPOS Phase 3A local results

17 October 2026, Asia/Kolkata. Requirements: supplied Website Master Blueprint v2.0 and the user's local-only Phase 3A approval. No deployment, production POS/Firebase/Cloudflare connection, paid service, payment information, live database modification or destructive migration occurred.

## Outcome

The customer website and finite archival/restore prototype are implemented locally. Production retention capacity remains **unverified/blocked**: the measured indexed three-day workload projects **458,276,864 bytes at day 90**, over the deliberately conservative 450 MB admission cutoff. It is below the nominal 500 MB limit but does not establish safe headroom. This part stops at the local proof; no production cleanup or promise of continuous 90-day ₹0 operation is enabled. No later phase is authorized.

The website uses only local synthetic sample products/prices. Safe branding mapping follows actual POS keys `shop_name`, `shop_phone`, `shop_address`; absent an approved public settings export, the preview explicitly says HardwareShopPOS/sample data. No live shop settings or private owner credentials were accessed. Default preview hours are all-day fixture hours; actual shop hours, holidays and production settings synchronization require the approved adapter. Server-enforced daily window, maintenance and pickup validation are tested locally.

## Tests and builds actually executed

| Verification | Result | Evidence |
| --- | --- | --- |
| Existing Android app/test APK and JVM test compilation | BUILD SUCCESSFUL, 1m 26s; 79 cached tasks up-to-date | `work/advanced-operations/website-phase3a-build.log` in the chat workspace |
| Existing JVM suite, including actual Kotlin pipe fixtures | 311/311 passed, 5.6 seconds | `tools/website-proof/evidence/phase3a-jvm.log` |
| Relevant Android regressions, final sequential run | 6/6 passed, 48.366 seconds | `evidence/phase3a-android-sequential.log` and `.logcat.txt` |
| Final backend/shared model/archive safety tests | 32/32 passed, 24.887 seconds | Native local Miniflare/workerd D1, not mocked Cloudflare production |
| Final browser customer acceptance | 9/9 scenarios passed; seven viewport sizes | Installed headless Chrome; responsive/mobile/network emulation |
| Local website build | Static HTML/CSS/ESM, catalog/config fixture and SHA-256 manifest generated successfully | `evidence/phase3a-web-build.json`, `local-customer-build/manifest.json` |
| Retention workload | 3,000 orders, 20 lines/order, 3,201 catalog variants; 1,900 guarded owner compactions and isolated restores | `evidence/phase3a-retention-capacity.json` |

The APK remains SHA-256 `b85f44b19b4be888f8789a7bbf8be1630725166af754209fde879f757312cbec`, identical to Phase 2. No Android application or test source was changed in Phase 3A. Byte comparisons confirm AppDatabase.kt, BackupManager.kt and BillingAndStockRepositories.kt match the preserved source checkpoint. Room remains 11, backup format 8, and normal billing/stock transactions are unchanged.

The Android safety tests use named synthetic Room databases and the disposable emulator `emulator-5598`, never a shop device. They verify WAL-consistent backup/repeated isolated restore, linked-shop restore rejection, and concurrent same-origin billing retry with one bill/payment/stock deduction. Reminder ledger behavior, five billing entries, seventeen owner menus and numeric pipe keyboard/pinned cart are covered. Final billing times: **6,864 / 1,090 / 2,213 / 1,330 / 1,649 ms**; owner menus **99–676 ms**. The original **15,000 ms** limits were retained.

## Website behavior verified

- Text-only catalog, no images/placeholders, external fonts, analytics or paid UI dependencies. No login/OTP, delivery, payment, stock display, stock checks or reservations.
- 3,249 published sample variants initially; names/categories/type/unit define grouped product families. Four PVC sizes and a 36-size bolt family verify multi-size selection and vertical scrolling; ordinary one-size products are also tested.
- Unicode/Kannada aliases, categories, sizes, prominent search, bounded initial rendering (36 product families) and explicit load-more. Owner hide/show is tested through the local fixture API and rebuilt static snapshot, with canonical submission rejecting unpublished items. Native POS publishing controls are pending.
- Shared pure pricing module is used in both browser/backend; its pipe semantics were already checked against actual Kotlin fixtures. Full Length and Extra Feet have separate rates/inputs, integer-paisa totals and bounded validation. Tested PVC cart: one half-inch length + 3.5 ft = ₹250, two three-quarter-inch lengths = ₹520; total ₹770.
- Cart edit/remove/item/order notes/missing-item request, reload persistence, name/Indian mobile, optional pickup, explicit contact-memory opt-in and removal. Private detail responses are not persisted as receipt contact data when opted out.
- Server recalculates prices, validates shop hours/pickup/maintenance, and confirms only after storage. Customer editing/cancellation require a private device capability and expected revision while NEW. No phone-only lookup or capability URL is exposed.
- Lost response after committed create, browser reload, same-identity recovery, accepted-order editing lockout, failed persistence/quota response, and malformed HTTP 200 receipt keep the correct identity/cart without false confirmation. Cart revision after recovery 404 retains the same submission identity to protect against a delayed original commit. Order-edit mode persists across reload.
- Strict same-origin customer proxy; staff/owner/internal SQL fixture routes are not forwarded. CSP disallows remote assets, images and embedding. Local fixture identities are not production authentication.
- Seven viewports: **320×640, 360×800, 390×844, 412×915, 768×1024, 1024×768, 1440×900**. No horizontal page overflow; phone panels fill width, larger screens align a single panel right. Modal focus containment, input labels, keyboard navigation, reduced motion CSS and reachable sticky actions at 400 px viewport height are checked. Screenshots are in `evidence/phase3a/`.

Final throttled Chrome run (150 kbps download, 150 ms latency, 4× CPU throttling): catalog-ready 10,006.941 ms; category-scoped Kannada search 1,861.396 ms; gzip catalog 30,956 bytes after hiding one sample variant. Both retained budgets (15 s load / 2 s search) pass. There were zero external asset requests and zero page-script errors. Earlier runs were faster; these are the final measured values, not guaranteed device performance.

Viewport shrinking simulates a reduced visual area; it does **not** prove physical Android/iOS keyboard behavior. Safari/WebKit, VoiceOver/TalkBack, real budget phones and actual mobile-data/QR connectivity were not executed. No public URL exists, so hosted QR end-to-end acceptance is pending.

## Storage, export and recovery measurements

The indexed workload inserts 1,000 synthetic orders per day, each with 20 line snapshots and six status events; 950 completed and 50 resolved cancellations. The first 50 terminal orders/day simulate **uncollected** orders and are never compacted. Owner attestations, encrypted archives, matching second file, isolated restore comparisons and explicit cleanup confirmation precede every compaction. Raw documents/full events are recoverable from both encrypted files; online order identities/bill links and terminal cursor checkpoints remain. Two files on one synthetic disk are not independent physical disaster recovery.

| Stage | Native local D1 allocated bytes |
| --- | ---: |
| Catalog baseline | 1,040,384 |
| Day 1 before cleanup | 10,981,376 |
| Day 1 after verified owner cleanup | 6,144,000 |
| Day 2 before cleanup | 16,007,168 |
| Day 2 after verified owner cleanup | 11,272,192 |
| Day 3 before cleanup | 20,983,808 |

Final source D1 totals across all three days, seed, monitoring/export/compaction: **156,397 rows read, 135,607 rows written, 39,795 statements**. This spans three synthetic days, not one account-day. Insert subset: 23,997 reads/114,003 writes. Driver CPU: 25,202 ms; elapsed: 156,445.593 ms. CPU is local Node driver aggregate, not per-invocation Workers CPU. Disposable restoration databases are separate local verification work and are not included as source-account D1 writes.

Maximum measured day-to-day allocated growth after cleanup: **5,025,792 bytes**. Linear 90-day model: **458,276,864 bytes**, compared with about 863 MB before retention. Estimated encrypted archive bytes at 90 days: **111,908,700 per copy** for this synthetic payload. Real text/maximum notes, owner cadence, retained uncollected backlog and existing account use can increase costs. Three measured days are not a 90-day capacity test.

The pre-index run used 11,336,652 source reads because per-order exports scanned unrelated events. The added `events(order_id,seq)` index brings the final proof to 156,397 source reads, at additional index storage/write cost. Query-plan regression checks verify indexed lookup. Complete accounting includes order/index changes, events/indexes, abuse counters and cleanup; no one-write-per-order estimate is used.

Read `WEBSITE_RETENTION_DESIGN.md` for exact safeguards, the 65/80/90% warnings/admission thresholds, retained identities/checkpoints and deployment gates. The production-capacity part stops here: **safe 90-day headroom is not established**. No active/uncollected/unresolved order was automatically deleted. No paid or multi-account workaround was added.

## Failures found and retained

The first browser attempts exposed the mobile field's accessible-name/help association and a template-escaped invalid phone pattern; both were fixed. A later slow-data assertion incorrectly expected only fixture matches although the sample elbow shared the Kannada alias/size; the test now scopes that measurement to the known fixture category. No timeouts were enlarged.

An initial Android run during simultaneous memory-heavy local workloads passed four tests and timed out on navigation/loading and IME visibility. The unchanged source/APK passed all six when run sequentially, with the same thresholds. This supports host contention as the cause, rather than proving performance under arbitrary host load. Failed evidence remains `phase3a-android-loaded-host-failed.log`; passing evidence is distinct. No Android behavior was changed to hide those failures.

The first retention attempt preserving every online event still modeled about 502 MB. Verified offline event archival with retained terminal checkpoints reduced it; adding the required lookup index slightly increased storage. The final 458 MB still breaches the 450 MB operational cutoff. All intermediate evidence is preserved. The earlier interrupted three-day run was incomplete; only the subsequently completed JSON is used for final conclusions.

## Exact commands

Project root: `C:\Users\mohammed saud\Documents\Codex\HardwareShopPOS`. Node: `C:\Program Files\nodejs\node.exe` (v24.18.0). No deployment CLI/configuration is used.

```powershell
# Project root; offline cached build
& 'C:/Users/mohammed saud/Documents/Codex/2026-10-15/fix-pipe-pricing-and-billing-flow/work/performance/build.ps1' -Tasks ':app:assembleDebug',':app:assembleDebugAndroidTest',':app:compileDebugUnitTestKotlin' -Log 'website-phase3a-build.log'
& 'C:/Users/mohammed saud/Documents/Codex/2026-10-15/fix-pipe-pricing-and-billing-flow/work/advanced-operations/jvm_tests.ps1'
& './tools/website-proof/run-android.ps1' -Log 'phase3a-android-sequential.log'

# tools/website-proof; run workloads sequentially on this memory-limited host
& 'C:/Program Files/nodejs/node.exe' --test --test-concurrency=1 test/*.test.mjs
& 'C:/Program Files/nodejs/node.exe' browser-tests.mjs
& 'C:/Program Files/nodejs/node.exe' build-web.mjs
& 'C:/Program Files/nodejs/node.exe' retention-capacity.mjs

# Optional local-only synthetic preview; loopback, no production connections
& 'C:/Program Files/nodejs/node.exe' web-server.mjs
```

Browser tests use the bundled Playwright package with installed Chrome, not an external CDN. Tests bind local loopback and required sandbox approval; they do not contact production providers. The isolated emulator was paused during build/capacity to reduce memory contention, then resumed for Android verification. No other user's emulator was stopped.

## Remaining gates and stop

Overall weighted completion is **49.5%**, not 100%. Actual POS public catalog/settings adapter, native Orders/notification/manual preparation, once-only real multi-device billing bridge/history integration, real account/CPU/quota measurements, finite retention policy with headroom, authenticated production owner/device protocols, independent archive recovery and real mobile/Safari/assistive acceptance remain pending. Phase 3A stops after final reports; later work and any production deployment require fresh approval.
