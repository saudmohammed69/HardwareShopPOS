# Local HardwareShopPOS backend proof

Current workstation wiring: see [LOCAL_SETUP.md](LOCAL_SETUP.md). `npm run dev:shared` prepares one synthetic backend for both the customer preview and signed DEBUG Android adapter. The new wiring is unexecuted; owner-deferred testing remains pending. Historical phase sections below describe their recorded revisions, not current release acceptance.

**Phase 2 only. Not deployable production configuration.** No website UI, Cloudflare account, Firebase connection, live POS database or FCM sender is included. The existing Android application remains the billing authority.

Requires Node 22+ (tested Node 24.18.0). Install the pinned dependencies with `npm ci --ignore-scripts --no-audit --no-fund`. On this Windows host Node needed `NODE_OPTIONS=--use-system-ca` for the trusted corporate certificate chain; TLS verification stays enabled.

```powershell
npm test
npm run capacity
# From the Android project root, after building:
& '.\tools\website-proof\run-android.ps1'
```

`package-lock.json` pins dependencies. `runtime.mjs` starts Miniflare/workerd on loopback only with a native local D1 binding. `d1-fixture.mjs` is a separately keyed, local SQL fixture/inspection driver; never publish it. It avoids a synchronous Miniflare Node proxy hang on this host. `worker.mjs` is the actual backend proof; it fails closed without `LOCAL_PROOF_ONLY=true`. `LOCAL_IDENTITIES` is an explicit test adapter, not Firebase token verification. All identities, customer details and products in these tests are synthetic. No deployment command or remote database configuration exists.

`schema.sql` stores a bounded JSON line snapshot with its order rather than doing one database write for every item/status combination. Events and indexed CAS updates preserve revisions; admission accounting and create-event insertion share a D1 batch. Failed conditional updates cannot append a winning event. D1 row counters include the local engine's index costs; measurements are not a Cloudflare account bill.

Checkout looks up only explicitly published public fields and recalculates integer-paisa estimates. It never fetches stock, rejects shortage, reserves goods or deducts stock. There are no backend stock or reservation tables. Owner publication can hide/show safe variants. Normal item quantities use at most two decimals; pipe prices are independent for full lengths and feet, with the actual POS conversion and precision checks. Shared fixtures are also executed by the Kotlin PipeBilling engine.

Customer submission has a persistent random submission ID and separate 256-bit capability, hashed server-side. Same-ID/same-payload retry returns the committed receipt; changed payload conflicts. Recovery/edit/cancel require the capability; editing and cancellation stop after acceptance. No capability appears in a URL. `submission-client.mjs` retains the unresolved frozen cart and identity across response loss/restart; it clears the cart only after persisted success. A request failure never invents a confirmed order number.

Worker operations require the fixture credential/tenant/role. Revision and status predicates serialize acceptance and later transitions. A billing claim is sticky to one installation and global bill identity; retry returns that identity, another installation is refused, and there is no automatic expiry/reassignment. `pos-proof.mjs` tests a separate SQLite transaction/outbox/inbox/cursor protocol. It is not a replacement billing engine. `WebsitePhase2SafetyTest` separately calls the real Kotlin createBill on named synthetic Room databases to verify retry and stock-once behavior. Production wiring and real enrolled-device authentication remain unimplemented.

Local state is under `.local-state/<random UUID>`. Evidence logs/JSON and a synthetic public catalog gzip are under `evidence`. Never point the harness at production. The capacity test deliberately generates a synthetic 1,000-order day for each item-count/concurrency combination; each scenario gets a fresh local database. CPU and latency are local measurements; they do not verify Workers' 10 ms per-request quota or production network latency.

Security and release gaps remain explicit: real Firebase JWT/membership verification, secure owner-approved device enrollment/revocation, trusted worker identity, production origins/headers, external anti-bot validation, shared-carrier abuse tuning, retention/deletion/export, per-request CPU measurement, real account quotas, sender security and all native Orders/billing integration. Local keys are fixtures, not acceptable production secrets. No paid fallback is implemented.


## Phase 3A customer preview and owner archives

From this folder, `npm run dev` opens a loopback-only synthetic customer preview at `http://127.0.0.1:4317`. `npm run build:web` generates `local-customer-build/` with safe catalog/config fixtures and hashes. This is not a deployed site; do not publish the build or test driver. Actual shop branding must later come from an approved allowlisted export using the existing POS setting keys. All preview products/prices are synthetic.

`npm run test:browser` uses installed Chrome and bundled Playwright to verify customer workflows, seven viewport sizes, 36-size scrolling, identity recovery, contact privacy, and throttled data/CPU. It is not physical Safari/mobile-network acceptance. Run the Android build/tests, browser tests and `npm run capacity:retention` sequentially on this memory-limited host.

`retention.mjs` has no public endpoint or timer. Owner-selected terminal orders require explicit collection/resolution/final-bill attestation, encrypted checksummed archive, matching second copy, complete isolated D1 restore/field comparison and confirmation before guarded compaction. Active/uncollected/unresolved orders never expire. Online identities and terminal cursor checkpoints remain; details/full events live in the verified archives. Protect archive passphrases and use already-owned independent backup media before any production cleanup. The harness's two files on one disk do not prove disaster recovery.

The final local 90-day projection is 458.277 MB, over the 450 MB admission cutoff. Production retention capacity is unverified and stopped. There is no unlimited or guaranteed free capacity claim. See `WEBSITE_RETENTION_DESIGN.md` and `WEBSITE_PHASE3A_TEST_RESULTS.md` in the project root. Real authentication/enrollment, account quotas/CPU, native Orders/billing integration and real-device acceptance remain future gates. Phase 3A authorizes no deployment or paid service.
