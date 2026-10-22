# Phase 2 backup safety and future production procedure

17 October 2026, Asia/Kolkata. No live shop database was opened, restored, migrated or changed for this proof.

## Verified application behavior

Room is version 11. Backup format is 8. `BackupManager.createBackup()` reads the business tables inside one Room `withTransaction`; committed WAL data participates in that coherent logical snapshot. It writes the ZIP through a `.partial` file and finalizes after closing it. Financial data does not depend on copying only the main SQLite file. Owner/worker credentials and local device/checkout settings have established exclusions; preserve them.

`restoreBackup()` validates the archive/version/values before replacement, creates a safety backup, checks linked-shop and remote-print restrictions, and applies data in one Room transaction. Linked-shop restore remains forbidden. This phase makes no change to those guards and introduces no destructive migration fallback.

`WebsitePhase2SafetyTest.walSnapshotRestoresIntoAnotherDatabaseAndSurvivesReopen` creates a named `phase2-proof-<UUID>.db`, enables WAL, inserts synthetic products, and commits a synthetic bill through the existing repository. It verifies a nonempty WAL, exports using the actual BackupManager, restores into a second independent named DB, compares complete product/bill/item/payment/credit snapshots, repeats restore, reopens through SQLite read-only and checks integrity plus price/stock. Related tests verify linked-shop restore refusal and ten same-identity bill retries. The app's `hardware_shop_pos.db` is not used by these safety tests.

A recoverable synthetic `synthetic-recoverable.hwpos.zip` has been pulled from disposable emulator-5598 into `tools/website-proof/evidence/android-backup`. It contains no production data. The wider navigation tests use only that disposable debug emulator and preserve its non-fixture products; they do not run on a shop phone.

## Production backup before any future migration

1. Confirm the correct installation, version, tenant and owner; resolve in-flight sales/printing and avoid editing product photos while exporting. Do not change cloud plans or unlink a shop merely to bypass restore protection.
2. Use the existing owner backup action. Its transaction provides the WAL-consistent financial snapshot; let the export finish before copying the completed `.hwpos.zip` to an owner-controlled independent location. Retain the previous known-good archive too.
3. Verify ZIP integrity, backup magic/version, export time and expected products/prices/bills/payments/stock counts. Record a SHA-256 hash, app version and Room/schema version. Keep the archive private because it contains customer/business records. Never put it in a public website folder.
4. Rehearse restoration only on an isolated, authorized, disconnected test installation; compare business records and stock to the exported snapshot. Preserve the current linked-shop restore restriction. A linked-shop archive needs a dedicated cloud-safe recovery procedure before any live restore can be approved.
5. Retain the original APK, schema, migration source and verified backup. A downgrade is not a rollback plan for a migrated database. Test old→new migration plus interruption/retry before release.
6. Take a fresh verified backup immediately before an approved production migration and record the rollback/reconciliation decision. This phase performs no production migration or restore.

Do not pull/copy an open main `.db` alone: committed rows can still be in `-wal`. Do not assume an arbitrary file copy of `.db/-wal/-shm` while writes continue is atomic. The verified logical export is the supported route here; a separate physical snapshot procedure would need connection quiescence/checkpoint/close verification and its own isolated recovery test before use.

## Phase 3B native-origin backup guard update — 17 October 2026

Website billing origin metadata is stored in the existing logical Settings records already included in backup format 8. Claim tokens, local fixture credentials and device installation secrets are excluded. Import validates every website origin against a bill present in the archive. If the current database already contains a website origin, restoring a snapshot without that origin is refused, preserving the claim/outbox fence. A native integration test passed the guard and isolated recovery path; the Room schema stayed at version 11 and no destructive migration fallback was added. These are synthetic isolated tests; no live backup/restore or shop database was used. Follow the existing production backup procedure before any future separately approved migration.
