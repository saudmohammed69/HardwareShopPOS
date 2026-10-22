# Website billing safety — Phase 2 local proof and integration gate

17 October 2026, Asia/Kolkata. No production billing schema, bill, product, price or stock was changed by this proof. Native Orders/billing integration is not implemented in this phase.

## Existing source authority

`BillingRepository.createBill` joins a Room transaction. It validates current product configuration and role permissions, uses the existing calculation engines, saves bill/items/payment/credit/audit records and decrements stock once. A persisted `checkout:<checkoutId>` setting returns the saved bill on supported local retries. Printing happens after save. Preserve this behavior.

That marker is local; it cannot stop two independent devices from each creating a bill for the same website order. Current schema has no unique website origin. Stable local numeric product/bill IDs also are not portable across installations. Existing SyncJournal generates/maps global identities and the financial bundle protocol/rules have strict allowlists; adding origin metadata requires a compatibility review and migration, not a new parallel bill store.

## Protocol exercised locally

1. Customer order UUID and submission identity are durable in local D1. Acceptance is revision/status checked and atomic with its event. There is no checkout stock check or reservation.
2. Before a billing request, the POS proof persists a random claim intent. An online claim binds the order to one authenticated fixture installation and one server-generated global bill UUID. Repeating with the persisted intent returns the same UUID. A different installation is refused, including when its Firebase-style UID is shared with another worker.
3. The lock is sticky: no automatic expiry or reassignment, including after a very old updated timestamp or service restart. A lost claim response is recoverable with the pre-send intent. A lost/reinstalled device that cannot prove its state requires owner reconciliation; there is no blind force-rebill action.
4. The isolated SQLite POS proof commits one unique order/bill link, bill/payment, synthetic stock effect and completion outbox in one transaction. A pre-commit interruption rolls all effects back. A post-commit restart/retry reuses the bill, and a lost completion acknowledgement can be retried with the same identity without another completion event.
5. Independently, `WebsitePhase2SafetyTest` exercises the **actual** Kotlin createBill on a named synthetic Room database, adding a prototype-only raw uniqueness table there. Ten concurrent retries of the same stable checkout UUID produce one saved bill, one payment, stock 20→18 once, and unchanged selling price 12,345 paisa. This does not change the production Room schema or prove native cross-device synchronization.
6. Order feed pages and their cursor are committed together in the isolated local inbox. Interrupted page application rolls back; repeated/stale events do not overwrite newer revisions. Local completed orders can be represented immediately while cloud completion remains in a durable outbox.

Tests and commands: `tools/website-proof/test/backend.test.mjs`, `test/additional.test.mjs`, `pos-proof.mjs`, and the Android `WebsitePhase2SafetyTest`; execution results are recorded in [Phase 2 tests](WEBSITE_PHASE2_TEST_RESULTS.md).

## Requirements before real native integration

- Use real verified Firebase token/membership/tenant/role checks and owner-approved revocable installation proof. `LOCAL_IDENTITIES` is deliberately a fixture adapter. A supplied Android ID or worker name is not authorization.
- Derive tenant-scoped stable website checkout identity; bind the claim to the installation and persist the request intent before sending. Keep installation credentials in secure storage and out of backups. Restored/reinstalled devices need enrollment/reconciliation.
- Protect the existing cart with save/replace/cancel choices, resolve stable product references, and revalidate current POS prices/GST/unit/pipe semantics. A website estimate is not a paid bill or authoritative POS amount.
- In a future reviewed Room migration, add unique tenant+order and stable global bill-origin constraints, local order completion and a completion outbox. Integrate them into the existing transaction; do not put network/printing inside it. Preserve source identity on retries and roll back completion if the bill fails.
- Preserve existing sync identities. Review the mapping from the server's bill UUID to the existing bill's sync identity and extend origin metadata/rules/receivers together; do not overwrite a generated SyncJournal identity or assume `mapIncoming` replaces it. Test original-device and receiver uniqueness before enabling the feature.
- Existing unified Bill History must query the same saved bill once; Completed Orders links to it. Actual native website-origin history/linking remains unimplemented. Do not manufacture a second invoice/history row.
- Reconcile claim and completion ordering independently of existing bill-cloud sync. Ordinary counter billing remains offline. A newly acquired website billing claim requires the server; only its bound installation may later commit offline. Other devices cannot acquire/reassign that order while it might already have been committed.
- Restrict abandoned-claim recovery to affirmative proof of no bill/outbox or owner-led reconciliation. Completed orders are not reopened to implement refunds; use existing return/cancellation workflows with an audit link.

The supported-client protocol prevents duplicate effects under its tested transactions, uniqueness and retry boundaries. It cannot establish arbitrary offline multi-device exactly-once behavior without secure ownership, durable identities and native integration. A malicious/privileged client can lie about completion; device compromise, destructive restores and lost credentials remain explicit trust/recovery boundaries. This phase does not claim that limitation is solved in production.

## Phase 3B local native integration update — 17 October 2026

The earlier “native integration is not implemented” section is historical. Under explicit Phase 3B local-only approval, the existing Room billing path now has a debug/test website-origin adapter. The independent `WebsiteOrdersDatabase v1` holds inbox/cache state; the existing Room 11 and BackupManager format 8 were not migrated. The adapter calls the existing `BillingRepository.createBill` and writes a stable `web-<order UUID>` origin plus completion outbox in the same Room transaction. Existing cart content is kept until worker review and explicit park/replace choice. Current product prices and quantities are revalidated before the original billing screen/save.

Local proof passed: ten native integration tests, including concurrent duplicate retries, claim ownership conflict, lost claim/completion response recovery, injected pre-commit rollback, once-only stock effect and unified bill-history identity; plus one Compose checklist→Ready→review→existing billing flow. Backend CAS/concurrency tests passed 37/37. This proves the synthetic/local transaction boundary exercised, not an arbitrary cloud client or real two-device Firebase session. Real verified membership, owner-controlled device enrollment, cross-device sync in production and secure lost-device reconciliation remain mandatory before rollout. Preserve offline counter billing independently.
