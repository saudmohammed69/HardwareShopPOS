# HardwareShopPOS website — initial compatibility and infrastructure inspection

Date: 17 October 2026, Asia/Kolkata. Scope: read-only source/configuration inspection and review of existing build/test evidence. Application implementation is awaiting explicit user approval.

## Requirements authority

The primary requirements document is [HardwareShopPOS Website Master Blueprint v2.0](<../2026-10-16/referenced-chatgpt-conversation-this-is-an/outputs/HardwareShopPOS_Website_Master_Blueprint_v2.md>), supplied by the user in this conversation. The user's progress-tracking instructions govern the dashboard and approval gate.

Two other documents exist: the project-local `HardwareShopPOS_Shopping_Website_Blueprint.md` (v1.0.0) and the separate `HardwareShopPOS_Shopping_Website_Blueprint_v2.md`. Neither supersedes the supplied **Website Master** document. In particular, the earlier image-based, owner-selected static catalog/export draft is not the requested website and earns no website implementation credit.

The current requirement is in-shop QR ordering on customer mobile data: text only, no product images or placeholders, approximately 2,900+ products, eligible products published by default with owner hide/show, multiple sizes, exact POS pipe semantics, customer edit/cancel only before acceptance, and a measured capacity target of 1,000 orders/day. No customer account, OTP, online payment, delivery, public tracking page, paid service or paid domain is required. A private capability for submission recovery/edit/cancel is distinct from a public tracking page.

## Inspection result

The existing POS has usable integration points. There is no implemented hosted website/order service or native web-order workflow in the inspected application. The main compatibility risks are migration numbering, the difference between local worker roles and cloud identities, multi-device bill duplication, strict sync allowlists, and free-tier capacity. These are findings and planned changes, not changes made during this inspection.

The source inventory contains 183 main Kotlin files, 37 JVM-test Kotlin files, 42 Android-test Kotlin files, and eight exported Room schema JSON files. The inspection covered the build structure and relevant data, billing, stock, pipe, search, authentication, device, notification, navigation, sync and backup boundaries. This is an architectural inspection, not a claim that every line has passed a security audit.

No applicable AGENTS.md was found in the project/source search or checked ancestor locations. This checkout has no project Git metadata available for a reliable working-tree diff. Existing changes were therefore preserved, and an in-memory SHA-256 inventory of 300 source/schema/build/rules files was taken for an end-of-inspection comparison. No application code, products, prices, database, cloud configuration or credentials were changed.

## Verified compatibility map

Paths in this table are relative to the existing project.

| Area | Inspected evidence | Finding and implementation constraint |
| --- | --- | --- |
| Build and DI | `settings.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `di/Modules.kt` | One Android app module; Kotlin 2.0.20, Compose, Hilt, Room 2.6.1, WorkManager and Firebase. Java/Kotlin target 17. No need to replace the POS or introduce another Android architecture. |
| Package/version | `app/build.gradle.kts`, existing APK metadata | Namespace/release application ID `com.hardwareshop.pos`; current version code 26, version 1.4.21. Debug uses `com.hardwareshop.pos.debug` / `1.4.21-DEBUG`. Release signing credentials are not configured in source. |
| Database | `data/local/database/AppDatabase.kt`, migrations, schemas | Room **11**, WAL, migrations through 10→11, alias-index and sync triggers. The old proposal to add orders with migration 10→11 is incompatible. Derive the next migration from the version at implementation time; if it remains 11, the next migration is 11→12. No destructive fallback. |
| Existing records | `data/local/entity/*`, `data/local/dao/*` | Products, variants, bills/items, payments, credits, stock, returns, workers and remote printing already have established stores. No web-order/cache/reservation/billing-link entities were found. Add bounded, indexed order structures without deleting existing records. |
| Billing transaction | `data/repository/BillingAndStockRepositories.kt`, `createBill` | A Room transaction validates the cart/current configuration and worker permissions, calculates totals, saves bill/items, decrements stock and records payment/credit/audit effects. `checkout:<checkoutId>` makes supported local save retries reuse the saved bill. Extend this path; keep printing/network outside its transaction. |
| Website bill identity | Same repository, `SyncJournal.kt`, `SyncIdentityLookup.kt` | No website-order origin/unique order→bill link exists. The local checkout marker alone cannot prevent independent devices billing the same order. Add a stable order/bill identity, device-bound server billing claim and local uniqueness/commit outbox before enabling website billing. Never blindly reassign a possibly committed offline claim. |
| Stock and offcuts | Billing repository, `PipeOffcutRepository.kt` | Existing sale decrements physical stock once. Reservations must be separate and must not decrement physical stock. Registered offcuts remain included in normal stock, as the user confirmed earlier. Preserve that rule. Negative available-to-promise warns; it does not automatically reject acceptance. |
| Pipe amounts | `util/PipeBilling.kt`, `CalculationEngine.kt` | Full lengths and extra feet price independently in integer paisa with overflow checks. Inventory conversion uses the actual configured `pipeFeetPerLength` and unit; piece/length conversion rejects unsupported precision instead of silently rounding. Port fixtures, not guesses from product names. |
| Catalog/search | `BillingCatalogue.kt`, `ProductSearch.kt`, `ProductSizeGroups.kt`, alias entity/DAO | Existing product sizes and alias search are reusable references. Build a separate public-safe projection. Existing member sync DTOs include non-public fields and must not be exposed as a public catalog. Default publication/hide-show must match the Master, not the earlier selected-product export design. |
| Identity and permissions | `FirebaseManager.kt`, `CloudAccountRepository.kt`, `CloudSessionStore.kt`, `SecurityManager.kt` | Firebase has both existing-session/anonymous handling and email account paths; local owner/worker sessions are a separate authority. Do not assume one Firebase UID per worker. Preserve local worker restrictions and audit actor IDs; validate cloud membership and server-side tenant/roles. |
| Device proof | `PrinterDeviceIdentity.kt`, existing shop-device registration | Current printer identity uses Android ID and existing Firebase membership. That ID is not a secret credential. Secure, revocable installation enrollment/proof is needed for website device-sensitive operations; do not repurpose or break printer registration. |
| Firestore/sync | `firestore.sync-v2.rules`, `SyncWireSchema.kt`, `SyncJournalSchema.kt`, `SyncFinancialProjector.kt`, `SyncFinancialReceiver.kt` | `sync_v2_public` means shop-member access, not internet access. Financial bundles already carry bill graphs through strict schemas. Preserve the boundary; assess new origin fields against sender/receiver/rules compatibility. Do not independently sync website state through two authorities. |
| Orders UI/history | `presentation/navigation/AppNavGraph.kt`, `owner/misc/RemainingScreens.kt` | Unified Bill History already queries the existing billing repository. No dedicated website Orders route was found. Add Active/Completed/Cancelled views and link completed orders to the one existing bill; do not make a parallel invoice store. |
| FCM/printing | `service/HardwareShopFcmService.kt`, print workers/service | Current incoming handling expects `PRINT_JOB`, `jobId` and `shopId`. A web-order event with no print job ID would be discarded by that early parsing. Add explicit event routing while preserving printing. A comment mentioning Functions does not establish a deployed secure sender. |
| Backup/restore | `util/BackupManager.kt` | Backup format is version **8**. Safety backup precedes restore; linked-shop restore is deliberately refused. Extend versioning/order completeness and recovery without removing these protections or restoring device secrets. |
| Website tooling | Project file inventory | No application website package, Wrangler configuration or D1 migration set was found. The Node package under `tools/rules-tests` is a Firestore rules-test harness, not the website. Prior staged catalog code outside the app is uninstalled preparation. |

## Infrastructure and capacity findings

Use the Master’s preferred candidate for proof: **Cloudflare Pages static site + Workers trusted API + D1**. Turnstile remains an evaluated option, not an assumed hard requirement. Existing Firebase identity/membership and FCM may be reused after verification. No account was provisioned, no service deployed and no plan upgraded.

Official documentation was rechecked during this inspection. These are provider-published limits, not verification of the shop's actual account configuration:

| Component | Documentation evidence | Remaining proof |
| --- | --- | --- |
| Pages | Free-plan build and asset limits are published; static text/catalog assets suit the candidate. [Pages limits](https://developers.cloudflare.com/pages/platform/limits/) | Actual account eligibility, commercial terms, free-plan/no-card setup, deployment/rollback and asset bandwidth behavior. |
| Workers Free | 100,000 requests/day; 10 ms CPU per HTTP request and per Cron invocation. Network wait is distinct from CPU. [Workers limits](https://developers.cloudflare.com/workers/platform/limits/) | Cold/warm authentication, schema validation, server calculations, push signing and cleanup must fit measured limits. No paid fallback. |
| D1 Free | 5 million rows read/day; 100,000 rows written/day; 5 GB account storage. Index maintenance can add written rows. [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/) | Measure actual SQL/index usage, query plans, retention and headroom under the target workload. |
| D1 database | Free databases have a 500 MB per-database limit. [D1 limits](https://developers.cloudflare.com/d1/platform/limits/) | Measure stored bytes/order including events, indexes, idempotency data and retention. |
| Transactional operations | D1 batches provide rollback on statement failure. A successful UPDATE matching zero rows is not a failure; dependent claim/reservation writes need explicit guards. [D1 database API](https://developers.cloudflare.com/d1/worker-api/d1-database/) | Real concurrent claim/edit/cancel/billing tests against the chosen SQL. |
| Turnstile option | Free plan includes small/medium businesses. Tokens require server-side verification. [Plans](https://developers.cloudflare.com/turnstile/plans/), [validation](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/) | Actual hostname/action/test-key behavior, abuse model, accessibility and no false order success. |
| Existing Firestore | Free quotas include 50,000 reads/day, 20,000 writes/day, 20,000 deletes/day and 1 GiB storage. [Firestore quotas](https://firebase.google.com/docs/firestore/quotas) | Existing independent POS sync usage plus added identity/device operations; deployed rules and actual plan were not accessed. |
| FCM/identity | HTTP v1 requires trusted sending credentials; ID tokens require verification. [FCM server authorization](https://firebase.google.com/docs/cloud-messaging/send/v1-api), [ID-token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens) | Secure free sender, least privilege, revocation/tenant checks and real device delivery. Keep server secrets out of APK/browser. Polling fallback must state Android latency limits. |

**₹0 at 1,000 orders/day is unproven.** An illustrative risk calculation, not a benchmark: 1,000 orders × 20 lines = 20,000 line inserts/day before headers, edits, statuses, events, admission counters, reservations, device acknowledgements, cleanup and index writes. A design that independently inserts and releases 20 reservation rows/order adds 40,000 base writes; indexed changes may consume the remaining 100,000-write allowance. Reserving locally may reduce cloud writes but needs a safe multi-device reconciliation design. No percentage is awarded for a complete capacity model until an actual schema and measured operations establish it.

The proof must measure 5- and 20-line workloads, 2,900+ products, 20–100 concurrent customers, worker updates/retries, existing POS bill traffic, requests, rows scanned/written, stored bytes, CPU, bandwidth, reset behavior and retention. Use versioned compressed public catalog assets and incremental refresh as candidates to reduce read traffic. Stop accepting orders when durable persistence is unavailable; keep ordinary offline POS billing available.

## Baseline build and test evidence

These results were executed earlier in the ongoing POS work and their actual logs/artifact were reread during this inspection. No new build, application test run, emulator mutation or production database access was performed for this documentation-only request. They establish baseline evidence, not website acceptance.

Evidence directory: [advanced-operations](<../2026-10-15/fix-pipe-pricing-and-billing-flow/work/advanced-operations>).

| Check | Actual result | Evidence |
| --- | --- | --- |
| Debug app, Android-test APK and JVM-test compilation | ✅ `BUILD SUCCESSFUL in 22m 59s`; log modified 17 October 2026 11:18:43 +05:30 | `advanced-receipts-reminders-build.log`; prior wrapper tasks `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:compileDebugUnitTestKotlin` |
| JVM suite | ✅ 310 tests passed in 6.265 s | `receipts-reminders-jvm-tests.log`; `jvm_tests.ps1` runs compiled tests through JUnitCore |
| Latest Android suite | ❌ 11/12 passed; `ReminderUiIntegrationTest.ownerReviewsAnOverdueReminderWithoutSendingOrChangingTheLedger` timed out after 15,000 ms | `receipts-reminders-android-tests.log`; `verify_receipts_reminders.ps1` uses AndroidJUnitRunner on disposable emulator-5598 |
| Alias migration suite | ✅ 2/2 passed | `phase3-alias-precompiled-tests.log`; proves existing aliases migration only |
| Earlier menu performance retry | ❌ Billing navigation 15,997 ms exceeded 15,000 ms threshold | `phase3-menu-retry-tests.log`; unresolved baseline regression |
| New website/backend/Orders acceptance | ⬜ Not run; corresponding implementation is absent | No substitute credit from unrelated POS tests or staged JavaScript smoke tests |
| Production DB backup/recovery rehearsal | ⬜ Not performed in this inspection | Required before database implementation; do not copy only an open SQLite DB without its WAL-consistent backup procedure |
| Physical Bluetooth, real push, customer mobile-data QR | ⬜ Not verified here | Must remain separate release gates |

Existing debug APK: `app/build/outputs/apk/debug/app-debug.apk`; 31,432,919 bytes; modified 17 October 2026 11:10:56 +05:30. SHA-256: `8844ba712f82cb3e66ce74d665bfaab3d91c2eebf86151ac4e2a14d15dc02c62`. This is a baseline APK, not a website-integrated release.

## Blockers and next approved scope

1. **Baseline gate:** diagnose the reminder timeout and navigation performance failure, run relevant checks, and take a safe pre-change database backup. Initial inspection target remains partially complete until those gates pass.
2. **Infrastructure gate:** account/free-plan eligibility, commercial use and no-card requirements, actual 1,000-order load/CPU/storage/index budgets, retention and quota-failure behavior remain unverified. Do not claim ₹0 is proven.
3. **Security gate:** implement verified identity/membership and device enrollment; customer NEW-only edit/cancel capability; owner publication/admin controls; tenant isolation and malicious-request tests. Separate web admin also needs authentication/MFA feasibility within ₹0 before it can be enabled.
4. **Billing consistency gate:** establish single server acceptance/billing ownership plus stable identity and atomic local order→bill completion/outbox. Local unique constraints alone do not cover independent offline devices. Failed billing retains an active recoverable order.
5. **Delivery gate:** verify real Android notification permissions/sound/deduplication, missed-event reconciliation, normal offline billing, Bluetooth regression, Room migration, backup/export/restore, unified history and final website/API/APK builds.

After explicit user approval, first resolve the baseline/backup gate and build a local secure backend proof behind disabled/unconfigured website integration: bounded schema, canonical catalog lookup, same-key retry, NEW-only customer edit/cancel, race-safe acceptance, device-bound billing identity and a measured capacity harness. Use explicit local test adapters when credentials are unavailable; never report those as real account or push verification. Then implement the text-only website, native Orders/reservations, existing billing bridge and owner/recovery controls in the Master’s phase order.

Before each milestone starts, define its acceptance checks. After each milestone finishes, update `WEBSITE_PROGRESS.md` with only verified weighted subtasks, exact test results, remaining failures and the next target, and show a concise conversation update. Public deployment and credential setup remain separate authorized actions.

**Approval checkpoint:** the user's latest instructions explicitly require waiting after inspection. Only the dashboard and this report were created; application implementation is not authorized by this report or blueprint alone.

## Inspection closeout verification

The final SHA-256 comparison found **300 protected source/schema/build/rules files unchanged, zero changed and zero removed**. Dashboard checks found exactly ten targets, weights totaling 100%, earned credit totaling 10%, the correct supplied Master reference and the explicit approval hold. This verifies the documentation update and source preservation; it does not resolve the recorded application-test or production-readiness failures.
