# Cloud sync work in progress — 3 October 2026

**Not ready for installation or production cloud sync.** The unsafe snapshot prototype has been removed from this working source; the draft rules close its old paths. `SyncReleaseGate.READY` remains false. In this source, startup cancels prototype jobs and a queued job returns without accessing Firebase. No Firebase rules have been deployed and no phone database has been touched by this work. The latest changes are in the workspace copy, not installed on the phones or copied into the live Android Studio project.

## What this continuation actually implements

- The user confirmed **both phones contain separate records that must be combined**. The new first-link planner preserves independent history even when bill numbers match. It proposes catalogue matches only for matching category/type/name/size/unit; stock requires an explicit reviewed physical quantity. This is a tested planning component, not a completed merge screen or an executed merge.
- Structured Firestore transport with authenticated server membership checks, optimistic versions, immutable operation receipts, bounded pages and server-timestamp cursors. A resumable engine now connects the catalogue and financial components behind the disabled release gate. No rules have been deployed.
- A Room 7→8 migration adds durable outbox, inbox, cursor, conflict and projection tables. Each upload freezes its contents before network access. Product metadata and private buying costs must both acknowledge before its journal revision is cleared.
- An outbox projector reads rows and global references in the same Room transaction. Local primary keys, absolute stock, credentials and printer state are excluded from portable catalogue records.
- A catalogue receiver applies categories, types, products, customers, suppliers, portable settings, worker metadata and private product costs. New workers receive no password material and cannot log in until the local owner sets a password. New records receive local keys; duplicate keys and simultaneous local edits wait for review.
- Financial bundles freeze and import complete bills/purchases, lines, payments, credits and returns in one Room transaction. Missing parents roll back the whole import. Duplicate bill numbers receive a local identity suffix, preserving both sales. Receiving history does not deduct stock or print again.
- Standalone customer-credit payments, stock-adjustment history, audit and day-closing records have a receiver. Credit entries preserve signed amounts and verify exact paisa arithmetic; balances remain derived from entries. Same-day closing collisions require review, not replacement.
- Durable scanning prevents a page of waiting records from permanently starving later records. Missing bundle receipts wait for retry; invalid financial totals are held for review.
- First-link snapshots capture product metadata, stock revisions, record counts and a change token in one transaction. A sale followed by a return to the same quantity invalidates an older stock review. Approved foreign product/customer references can resolve to a local record without replacing its metadata.
- A Room 6→7 migration that adds a local journal without deleting or rewriting business records. Version 5 upgrades through the existing 5→6 migration.
- Stable random 128-bit sync identities separate from local Room primary keys.
- SQLite triggers recording insert/update/deletion changes in the same transaction as the business operation. Deletions retain tombstones.
- Revision-aware acknowledgement: an old upload response cannot erase a later pending edit.
- Separate immutable stock movements, including opening quantities and signed deltas. These are created atomically with stock updates.
- Stock-only changes no longer mark product metadata as edited. Local print markers, product photos and worker passwords do not cause metadata uploads. Changed buying prices still enter the private projection.
- A transactional stock receiver with delivery deduplication, local-echo detection, prerequisite checks, and tracking suppression so received changes do not become new outbound sales.
- Foreign identity mapping without using another phone's auto-increment ID as a local ID.
- A one-shop binding guard that refuses to mix two stores in one database.
- An explicit store-setting allowlist. Printer addresses, bill sequences, carts, request IDs, catalogue installer state and credentials stay local.
- Cancellation-aware WorkManager handling and exponential retry configuration, held behind the release gate.
- Revised draft printing rules: owner creates/retries jobs; an explicitly authorized printer can acknowledge them without getting owner access.
- Generated, emulator-tested v2 rules enforce schema/privacy, device membership roles, atomic head/receipt writes, immutable financial records, optimistic versions, server timestamps and typed financial manifests. They are candidates, not deployed rules.
- Old snapshot backup restore is refused on a linked database, including a second check inside the restore transaction. Cloud-safe restore preserving identities is still outstanding. Standalone backup/restore remains available.

## Architecture inspected

| Business area | Room tables / entities | DAO / repository | Presentation |
|---|---|---|---|
| Catalogue | categories, product_types, products; products_fts is derived | CategoryDao, ProductTypeDao, ProductDao; ProductRepository | ProductViewModels, product and pending-product screens, billing picker |
| Customers / credit | customers, credit_transactions | CustomerDao, CreditDao; CustomerRepository, CreditRepository | Customer and credit screens, billing customer selection |
| Workers | workers (credential fields are sensitive) | WorkerDao; WorkerRepository, AuthRepository | Worker management and login |
| Sales | bills, bill_items, payments | BillDao; BillingRepository | BillingViewModel and billing/history screens |
| Inventory | product stock, stock_adjustments | ProductDao, StockDao; StockRepository, BillingRepository, TradeRepository | Stock screens, purchases and returns |
| Suppliers / purchases | suppliers, purchase_bills, purchase_bill_items, supplier_payments | SupplierDao, PurchaseBillDao, PurchaseBillItemDao, TradeDao; TradeRepository | TradeViewModel and TradeScreens |
| Returns | sale_returns, sale_return_items | TradeDao; TradeRepository | Return screens |
| Reports / closing | day_closings; reports derived from sales/returns | BillDao, TradeDao; OwnerToolsRepository | OwnerToolsScreens |
| Audit | audit_logs | AuditLogDao; AuditRepository | Existing audit views |
| Settings / owner | settings, owner_credentials | SettingsDao, OwnerCredentialDao; SettingsRepository | SettingsViewModel / SettingsScreen |
| Remote printing | remote_print_jobs, shop_devices | RemotePrintRepository, FirebaseManager | RemotePrintViewModels / RemotePrintScreens; ShopPrintAgentService and PrintJobWorker |

There is no implemented expense table or expense-entry flow in this supplied project. References to expenses occur in report explanatory text. The earlier statement that expenses were stored in settings was not supported by the source.

Room remains the UI data source. Existing repositories, billing transactions, BluetoothPrintManager and ESC/POS output were not replaced. Firebase currently serves the existing remote-print feature. Authentication is anonymous Firebase device membership plus separate local owner/worker authentication; these are different security boundaries.

## Validation completed

The actual `SyncJournalSchema.kt` and `SyncTransferSchema.kt` were compiled with Kotlin 2.0.20, then their generated SQL was executed against SQLite tables from the checked-in Room version-5 schema. **25 tests passed**, covering migration preservation/foreign keys, existing financial records, rollback, reopen, 3,000-product bootstrap, random identities across two databases, stock capture, deletion, local-ID reuse, delayed acknowledgement, local-only field exclusion, replacement of old triggers, stock-review revisions, restart persistence, frozen uploads, complete product/cost acknowledgement, and interrupted-page rollback.

**42 pure JVM tests passed**, covering the wire contract, authorization, stale edits, retries, exact Long money serialization, financial bundle completeness/arithmetic, conservative merge proposals, stock-review races and preservation of independent bill histories. See `tools/test_sync_protocol.py`.

**15 Firebase security-rules tests passed** against the isolated demo project on the local Firestore emulator. They cover permissions/privacy, atomic commits, immutability, schema validation, finite quantities, credit arithmetic and typed bundle publication. Expected permission-denied messages appear in the log for denial tests. This run did not contact the live project.

Fifteen Android instrumentation tests cover production journal/transfer/receiver methods, including the 100 − 5 − 3 = 92 case, complete invoice rollback, duplicate bill numbers, standalone payments, reviewed aliases and resumed scanning. **These have not run.** There is no claim that physical two-phone sync was tested.

The Kotlin source/API check passed for **37 files**, including the changed sync/database/worker/backup sources and their test sources, using cached Android APIs. This is a limited compiler check; it does not run Room or Hilt annotation processors, produce an APK, or execute instrumentation tests. Run `tools/check_sync_kotlin.py --help` for required paths.

The full Android build remains unverified. Both the installed JBR and a checksum-verified isolated Temurin Java 17 runtime fail generating version-catalog accessors with `java.nio.file.AccessDeniedException` on a Gradle JAR. A minimal Java check also cannot resolve that workspace file using `Path.toRealPath()`. The build fails before app compilation. See `build-verification.log` and `build-java17-verification.log`. This is not a successful APK build, Room migration validation, or Hilt check.

## Work still required before enabling sync

1. Connect the tested first-link planner/snapshot to an owner review screen and coordinated, transactional merge execution. Preserve BOTH phones' independent records; review product and customer/credit overlap. Never sum two copies of opening stock.
2. Implement a shared approved opening-stock baseline/cutover and connect subsequent stock-movement upload/download to it. The engine deliberately leaves those records waiting; replaying pre-link deltas against a reviewed current count would be incorrect.
3. Complete canonical identity adoption and owner-visible conflict resolution. Reference aliases work, but they are not a complete catalogue/customer merge protocol.
4. Add authenticated linking, status UI, lifecycle-aware change notifications and continuation scheduling for large backlogs. The bounded engine is connected to the repository but remains disabled. Printer Shop ID entry is not business-database linking.
5. Complete the local owner/worker-session versus Firebase device-role boundary, device provisioning, permission changes and sanitized shop-profile projection. Emulator rules passing does not resolve these app integration tasks.
6. Implement cloud-safe backup restoration and portable origin metadata. The current guard prevents destructive legacy restore on linked data; it does not implement that restore feature.
7. Run full Gradle/Room/Hilt builds, Android instrumentation/migration tests, and physical two-phone offline/reconnect tests. Broaden adversarial rules validation for full financial workflow and bootstrap before release.

No new cloud collections have been deployed. No cloud data is currently transferred by the new journal. Do not set the release gate to true or publish the draft rules merely to bypass these remaining steps.

## Draft Firebase printer authorization

The draft rules require `enabled: true` on each member document, `role: "OWNER"` on the owner phone, and `printer: true` on the designated shop printer phone. A printer may have `role: "WORKER"`. Add these fields through Firebase Console/Admin SDK before any later deployment of these rules. A client cannot promote itself. The existing live Firebase rules were not changed.

Keep the currently working APK on the phones. This work is source code in progress, not a replacement APK.
