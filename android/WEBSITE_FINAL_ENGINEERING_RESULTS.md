<!-- CATALOG_PUBLICATION_20261022 -->
## Published customer catalog — 22 October 2026

**PUBLISHED · CATALOG ONLY · QR GENERATED · LIVE POS ORDERING BLOCKED.** Verified weighted progress remains **83.5%**; no unexecuted acceptance credit is awarded.

- Permanent production URL: https://hardwareshoppos.pages.dev/
- Cloudflare account: owner-approved account ending `19f0`; project `hardwareshoppos`, production branch `main`, direct Pages upload.
- Actual deployment ID: `db8ed9c4-89dd-4809-ba79-31fc37afed28`.
- Owner approved the exact 1,478 catalog rows / 805 product-name families and confirmed the existing selling prices. Independent live Room parity was not read. Unverified, disputed and ambiguous rows remain excluded.
- Hosted public product fields are name, size, selling unit and integer-paisa selling price. No buying prices, stock, supplier/customer records, private Git history or credentials were uploaded.
- Build manifest SHA-256: `79bfb718e95400a32daf84df3928c8d1b8520742139719bcf2fe57b2df43bc97`.
- Catalog SHA-256: `6e77ff97c28bee415c6d6b78e534ee50f698b83336cb3530516b1c8f76fff1c6`.
- Actual certificate-verified HTTPS reads returned 200 for the permanent homepage/manifest and matched every hosted asset hash. CSP and disabled API routes were checked; orders/recovery/edit routes returned 404. This is publication verification, not full browser/native/backend acceptance.
- QR: `../HardwareShopPOS-Website-Public/qr-output/HardwareShopPOS-QR.png` and `HardwareShopPOS-QR.pdf`; caption **SCAN TO VIEW ITEMS & PRICES**. PDF rendered and visually reviewed. Physical phone scanning remains unverified.
- PNG SHA-256: `34d2042b7e4ac5f3f144da927767694abfcff1bd411fb5ad22b320987e0080c2`.
- PDF SHA-256: `47c44fff25cee08f8ff168ad49b7c45d2a26b3a0d77cf34a01c6c1de1daafb23`.

### Scope, costs and remaining blockers

This release contains nine static build files including the `_headers` control file, no Functions/Workers code, no D1/Firebase connection and no customer mutations. No payment details, paid plan, purchased domain, real device enrollment or production shop data were used. Cloudflare documents static requests as free on both free and paid accounts: https://developers.cloudflare.com/pages/functions/pricing/. Billing subscriptions were not audited because Billing Read permission was not requested; no account-wide or live-ordering cost guarantee is made. Existing 500-order capacity and authentication/sender/headroom blockers remain open.

The owner explicitly deferred tests and then approved this static publication. Records preserve `localAcceptancePassed=false` with `localTestsDeferredByOwner=true`, scoped only to `STATIC_CATALOG_ONLY`. This cannot enable checkout, order APIs, Functions or live POS integration. No Android/JVM/backend/browser acceptance suite or capacity workload ran in this publication task. Existing POS financial transactions, products, prices, stock, Room 11, Backup 8, payments, GST, pipes, Firebase and printing source were unchanged in this task.

Remaining live-ordering gates: both historical backend transport failures, production owner/device trust and adapter, secure notification sender, safe billing/recovery acceptance, provider/quota headroom, named physical phone/printer acceptance and separate live-ordering approval. The owner-approved public GitHub repository `saudmohammed69/HardwareShopPOS` now contains 531 files at commit `3974c6a0447fe78a0874fe0f9bd2bf475be706bb` on `main`, verified against the remote ref. It contains the catalog website, Android source/tests/docs, owner-approved catalog assets, and catalog-only QR PNG/PDF in a clean Git history; the original POS Git history was not uploaded. As authorized by the owner, the Android catalog assets include purchase-price and stock fields; the deployed customer website still exposes only names, size/unit, and selling price. Firebase configuration, signing keys, credentials, databases/backups, local build files, and deployment approvals remain excluded. No tests were run during this upload; existing test deferral remains.

### Commands and retained evidence

Wrangler 4.152.0 is installed separately in `../HardwareShopPOS-Deployment-Tools`. The owner approved OAuth account/user read plus Pages write permissions, stored using Windows keyring mode; no Workers/D1/billing write scope was granted.

Executed: `node build.mjs`; `node scripts/prepare-release.mjs --approval release-approval.json`; `wrangler pages project create hardwareshoppos --production-branch main --force`; `wrangler pages deploy <reviewed snapshot upload> --project-name hardwareshoppos --branch main --commit-message "Owner-approved static catalog; online ordering disabled"`; `wrangler pages deployment list --project-name hardwareshoppos --json`; `node scripts/verify-published-catalog.mjs --approval release-approval.json --artifact <reviewed snapshot upload> --out release-local/hosted-verification.json`; `python scripts/make-verified-qr.py --approval release-approval.json --artifact <reviewed snapshot upload> --verification release-local/hosted-verification.json --out qr-output`; `pdftoppm -png -singlefile -scale-to 1100 qr-output/HardwareShopPOS-QR.pdf release-local/qr-print-preview`.

Initial project creation attempted Wrangler's new automatic Workers delegation and failed before deployment. Source inspection confirmed `--force` only opts out of that delegation; direct Pages creation then succeeded. Python's direct HTTPS path returned 403, and a later sandboxed TLS-context initialization hit the host's SSL key-log hook permission. No TLS, proxy, antivirus or security setting was disabled. The independently executed Node HTTPS verifier succeeded with certificate checks enabled; QR generation consumed its fresh hash-matched evidence offline. Original CLI logs remain under the private deployment-tools `.private/logs/` folder. Poppler emitted display-font warnings, but the rendered one-page PDF was legible and unclipped.

Evidence: `../HardwareShopPOS-Website-Public/release-local/hosted-verification.json`, `qr-output/verification.json`, `release-local/deployment-proposal.json`, the ignored owner approval records, and snapshot `release-local/catalog-79bfb718e95400a3-de0ad2e6-7916-4d2f-9ab4-8aa40041fff5/`. No failed historical evidence was removed.

Earlier sections below are historical and do not override this publication status.
<!-- END_CATALOG_PUBLICATION_20261022 -->

## Local catalog preparation update — 22 October 2026

Verified completion remains **83.5%**. A standalone, fresh local site repository now exists at `../HardwareShopPOS-Website-Public`; it contains no Android app, original POS Git history, remote, credentials, stock, supplier, purchase-price, or customer records. Its static candidate has 1,478 name/size/unit/selling-price rows grouped into 805 name families. Candidate SHA-256: `6e77ff97c28bee415c6d6b78e534ee50f698b83336cb3530516b1c8f76fff1c6`.

The candidate is projected only from `app/src/main/assets/final_catalog.json`: 240 missing/invalid-price rows, 37 priced rows marked `Needs Identification`, and 7 rows with unit-review notes were excluded. The three disputed prices have no accepted master price and are excluded; the 173 store-only unmatched variants were not merged. No price or unit was inferred. This is not a fresh export from the current live Room database, so it remains pending owner review.

The separate site-only project uses only four public product fields, a static catalog, search, grouped sizes/prices, responsive CSS, strict static headers and no order API. `npm test` passed **3/3** and `npm run build -- --preview` produced a 1,478-row `REVIEW_PREVIEW` artifact. The ordinary build correctly refuses to create a deployable artifact until `public/catalog-approval.json` approves the exact catalog hash. Browser visual/mobile and physical-device checks were not executed; this host’s in-app browser inventory is disabled and Chrome headless exited before rendering.

No Cloudflare account or project was accessed; no GitHub remote was added or push made. There is no deployed URL or QR. The two backend transport failures, production owner/device trust, secure FCM sender, current-room price parity, 500/day Free-plan headroom, and native/physical POS acceptance remain open. Live ordering remains disabled.

# HardwareShopPOS — Final local engineering results

Updated 22 October 2026 (Asia/Kolkata). Local preparation only. No Cloudflare or Firebase account was connected, no deployment occurred, no paid service was activated, and no live shop database was read or changed.

## Final current-source recheck — 22 October 2026

**Release remains blocked; verified progress stays 83.5%.** The clean source checkpoint before this recheck was commit `2180828f32a12150eeaf6c054dca727cd33d2aec`, tree `e82767aadac4a8c1a07fe56f46fec1359f87be3b`. No Android, billing, catalog source, backend or financial source was edited in this recheck.

- **Android:** Gradle 8.7 tasks ran with Temurin 17.0.20.1 and the repository's scoped `jdk.net.unixdomain.tmpdir` workaround. `:app:testDebugUnitTest` passed **312/312** (0 failures/errors/skips). `:app:assembleDebug`, `:app:assembleRelease` and `:app:assembleDebugAndroidTest` succeeded. Current unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`, 12,537,868 bytes, SHA-256 `fe3d5ce3a1b8adb4d1d560d243f0930fb9a216a36ae44e92ae8f4a08e4a586fd`; `aapt` identifies package `com.hardwareshop.pos`, version 1.4.21, target SDK 35. This is a local unsigned artifact, not a store-ready signed release.
- **Native Android:** instrumentation sources compile, but no native test executed. ADB has no connected device; the API 36 AVD is stopped, configured for 2,048 MB, and available host RAM was 741 MiB at the readiness check. Do not count the compiled test APK as native acceptance. The 15-second keyboard/cold-navigation gates and billing/Orders/Keystore/restore/printing runtime tests remain open.
- **Website:** a fresh 10-file `MAINTENANCE` artifact built locally with 0 products, `liveOrdering=false`, no customer mutations and no Functions. Its deployment-manifest SHA-256 is `8dffcc5f4d72a1331ea984d835eeb89a5159f0d7b235e54b0f2f433697927922`. Browser publication tests passed **3/3** using the bundled Playwright/Chrome path; QR approval-gate tests passed **4/4**. These verify local gates, not a Cloudflare deployment or a public QR.
- **Catalog:** the committed catalog assets are not one reconciled publication source: `final_catalog.json` has 1,762 rows, 240 without a positive selling price; `store_catalog.json` has 206 rows. Name/size/unit matching finds 33 shared rows, only 30 with matching selling prices, and 173 `store_catalog` rows without a match. No current live POS export or owner-approved public price list was verified. No catalog was published or exported.
- **Backend:** the Phase 4J transport test files are byte-identical to their retained evidence. The two failures therefore remain current for the unchanged backend source: shared-IP anonymous ordering and concurrent signed reads. The recorded socket failures occur before matching Worker-entry evidence in some controls; no single root cause or supported repair is established. Live ordering remains disabled.
- **Cloudflare/GitHub/QR:** no Cloudflare account or Pages project was connected; no deployment, public URL, production integration or QR image/PDF exists. The public GitHub remote is configured, but the local history includes purchase-price/stock data and GitHub authentication was unavailable in the earlier push attempt. Nothing was pushed in this recheck.

The currently published-provider documentation still describes finite Free limits: D1 5 million rows read/day, 100,000 rows written/day, 500 MB per database, 5 GB per account and 50 queries/Worker invocation; Workers 100,000 requests/day and 10 ms CPU/request; Pages 500 builds/month, one concurrent build, 20,000 files/site and 25 MiB/file. These published quotas do not establish account eligibility or capacity. Historical 500-order evidence reached 90,833 known writes on a busy day before unmeasured costs, so sustained 500/day and guaranteed ₹0 operation remain unverified. Sources: [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/), [D1 limits](https://developers.cloudflare.com/d1/platform/limits/), [Workers limits](https://developers.cloudflare.com/workers/platform/limits/), [Pages limits](https://developers.cloudflare.com/pages/platform/limits/).

**Final disposition:** **NOT PUBLISHED · local maintenance preview only · LIVE ORDERING BLOCKED · QR BLOCKED · POS integration not verified.** No progress increase is earned.

Files changed in this final recheck: `WEBSITE_FINAL_ENGINEERING_RESULTS.md`, `WEBSITE_PROGRESS.md`, `WEBSITE_RELEASE_BLOCKERS.md`, `WEBSITE_ZERO_COST_AUDIT.md` and `WEBSITE_FINAL_RELEASE_CHECKLIST.md`. Android/backend/catalog source was not changed; build and website outputs are local ignored artifacts. Current Android commands were `:app:testDebugUnitTest`, `:app:assembleDebug :app:assembleRelease`, and `:app:assembleDebugAndroidTest` via Gradle 8.7 with Temurin 17 and the scoped socket-directory property. Website commands were `node website-publication/build.mjs --out=website-publication/build/final-maintenance-preview-20261022`, `node --test website-publication/test-publication.mjs` (with bundled Playwright/Chrome paths), and `python website-publication/test-qr-gate.py`.

## Outcome

Verified weighted progress remains **83.5%**. The safe static maintenance package is built and its publication tests pass. The website is prepared for later owner-approved publishing as a maintenance preview. It has no products, checkout, order mutations, Worker, or live integration. There is no permanent URL yet, so no final QR image or PDF has been created.

The website cannot be marked ready for customer ordering. The current Android source now passes the JVM suite and both APK assemblies using compatible Java 17 plus the scoped socket workaround; `ProductOrdering.kt` differs from the older Phase 4J manifest but was included in these current builds. Native execution remains blocked because no emulator/device is running and host memory is low. Two backend transport failures are retained on byte-identical backend sources. Do not treat a compiled instrumentation APK as runtime acceptance.

## Verified publishing artifact

The repeatable local build at `website-publication/build/final-maintenance-preview` has ten static files. Its deployment manifest reports `mode=MAINTENANCE`, `liveOrdering=false`, `customerMutations=false`, `functions=false`, and `productCount=0`. The catalog is empty. The artifact contains no synthetic items or shop inventory fields. The manifest SHA-256 is `8dffcc5f4d72a1331ea984d835eeb89a5159f0d7b235e54b0f2f433697927922`.

`node --test website-publication/test-publication.mjs` passed **3/3**. This included the empty maintenance build, allowlist rejection of private product fields and mismatched approvals, API/mutation denial, same-origin-only requests, no image/form elements, CSP checks, and responsive layouts at 320, 360, 390, 412, 768, 1024 and 1440 pixels. No console errors or external requests were observed. The first browser attempt was blocked by the sandbox before page load (`ERR_NETWORK_ACCESS_DENIED`); the rerun with local loopback permission passed. Test outputs use per-run paths so prior evidence is retained.

`python website-publication/test-qr-gate.py` passed **4/4** local tests. The QR script now rejects unknown/live artifact modes and checks that the remote configuration matches the reviewed manifest. Its PDF label distinguishes a maintenance page from a catalog page. These tests use synthetic approval values only; they do not authorize or simulate a real deployment.

The QR generator was **not** run against a website. It intentionally requires owner approval, a chosen Pages project, a matching artifact hash, an actual deployment ID, strict HTTPS verification, and checks of the live response before writing PNG/PDF files.

## Existing engineering gates (Phase 4J evidence)

| Area | Verified result | Current decision |
|---|---|---|
| Android release | Current source `assembleRelease` passed; `aapt` reports package `com.hardwareshop.pos`, v1.4.21, target SDK 35. APK SHA-256 and size are in the current-source recheck above. | Local unsigned APK only. Full static DEX guard review and signed release remain open. |
| JVM regression | Current source `:app:testDebugUnitTest` passed 312/312, 0 failures/errors/skips. | Verified with Gradle 8.7 + Temurin 17 and the scoped socket-directory workaround. |
| Native Android | Instrumentation-test APK compiles, but no native test executed. No ADB device is attached; the configured API 36 AVD requires 2,048 MB and host free RAM fell to 741 MiB. | Blocked. Current keyboard/cold-navigation, billing, Orders, recovery, Keystore and printing runtime acceptance remains open. |
| Backend | 104 passed, 2 failed, 1 platform skip (106 executed/107 defined). Concurrent anonymous ordering and signed-read transport failed. | Live ordering stays disabled. Do not increase timeouts, weaken signatures, or replay uncertain billing claims. |
| Capacity | Phase 4J gated new capacity runs after transport/native failures. Historical five-device 500 profile modeled 90,833 known writes on a busy day. | Not verified at 500/day; only 9,167 writes remain below the published 100,000 allowance before unknown costs. No production or permanent ₹0 claim. |
| Identity/security | No trusted real owner bootstrap, production device enrollment, real Turnstile, or secure FCM sender was verified. | Production order integration blocked. |
| POS preservation | Historical audits preserve Room 11, BackupManager 8, existing billing/stock/GST/payments, pipe calculations, Firebase sync, and Bluetooth printing. This work changed no Android or financial source. | No production POS data was accessed or changed in this work. |

Cloudflare's current Pages limits are finite: Free lists 500 builds/month, one concurrent build, 20,000 files/site, and a 25 MiB maximum asset. The ten-file local artifact is below those file limits, but no Cloudflare account eligibility, card requirement, shared usage, or hosted deployment was tested. See the [Pages limits](https://developers.cloudflare.com/pages/platform/limits/) and [Pages build configuration](https://developers.cloudflare.com/pages/configuration/build-configuration/) docs.

## Files changed in the earlier publishing-preparation continuation

- `.node-version` — pins Pages builds to Node 22.
- `.gitignore` — ignores future Python bytecode and generated publication test output.
- `website-publication/test-publication.mjs` — unique test-run outputs and configurable Playwright/Chrome paths; removes machine-specific paths.
- `website-publication/make-verified-qr.py` — stricter artifact/settings gate and correct maintenance-page label.
- `website-publication/test-qr-gate.py` — four no-network approval-gate tests.
- The four `WEBSITE_FINAL_*` preparation/release documents and the current progress/audit/blocker summaries.

No product, price, stock, payment, billing, database, or Android application behavior was edited in this continuation. The `origin` remote points to the requested public GitHub repository, but these local changes have not been pushed. No Cloudflare Pages project is connected.

## Commands executed

```powershell
node website-publication/build.mjs --out=website-publication/build/final-catalog-only-candidate-final
node --check website-publication/test-publication.mjs
node --check website-publication/build.mjs
node --check website-publication/catalog.mjs
node --check website-publication/serve.mjs
$env:PLAYWRIGHT_MODULE_PATH='<local Playwright module path>'
$env:CHROME_EXECUTABLE_PATH='<local Chrome executable path>'
node --test website-publication/test-publication.mjs
python website-publication/test-qr-gate.py
```

No new backend load run was made: the failure-path backend sources and tests match the retained Phase 4J manifest byte-for-byte, and no candidate transport repair was made. Current-source Android build/JVM/instrumentation-compilation results are recorded at the top of this file.

## Decision

**Phase 5 is not recommended.** Keep website ordering disabled. Actual catalog publication, stable Pages URL, hosted HTTPS verification, QR PNG/PDF, native acceptance, backend transport reliability, owner/device trust, and free-tier capacity remain open.
