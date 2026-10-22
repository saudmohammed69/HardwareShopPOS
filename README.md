# HardwareShopPOS catalog website

Standalone static catalog site prepared for review. It displays product names, sizes, selling prices and the listed selling unit. Online ordering is disabled in this build. There are no external runtime dependencies, images, Workers, D1 bindings, Firebase connections, customer forms, credentials, or POS application source.

**Published catalog:** https://hardwareshoppos.pages.dev/ (22 October 2026). The owner approved this exact 1,478-row catalog and confirmed its selling prices. Production deployment: `db8ed9c4-89dd-4809-ba79-31fc37afed28`. The permanent HTTPS page, exact hosted asset hashes and disabled API routes were verified. Automated acceptance suites remain deferred by the owner; no live ordering or physical-device acceptance is claimed. QR PNG/PDF are stored locally under ignored `qr-output/` and say **SCAN TO VIEW ITEMS & PRICES**.

## Catalog update process

Update only `public/catalog.json`, keeping each row to `name`, `size`, `unit`, and `sellingPricePaisa`. Use integer paise as a decimal string, for example `"2500"` for ₹25.00. Rebuild and rerun the tests, then publish to the same Pages project so its stable `pages.dev` URL does not change.

This local catalog is a review candidate from the bundled POS master asset, not a current live Room database export. It contains 1,478 size/unit entries grouped into 805 product-name families. It excludes 240 missing/invalid selling prices, 37 items marked `Needs Identification`, and 7 rows whose notes say the unit needs review. It also excludes all store-only unmatched entries; the three disputed price rows have no accepted price in the master asset and are not included. No missing or disputed value was inferred.

Catalog SHA-256: `6e77ff97c28bee415c6d6b78e534ee50f698b83336cb3530516b1c8f76fff1c6`

The owner approved the initial exact catalog and confirmed current prices. Any later catalog change requires fresh price/source approval before publication. The source is still the bundled POS master, not an independently read live Room export.

## Local use

Requires Node.js 20 or newer. Run `npm test`, then `npm run build -- --preview` for the local review build. Its manifest is marked `REVIEW_PREVIEW`, which the QR gate will reject. The normal `npm run build` fails closed until `public/catalog-approval.json` records approval for the exact catalog hash. Serve the `dist` folder with the included `npm run serve` command for local preview. Do not deploy until account, project, catalog, Free-plan eligibility, and deployment are approved.

## Prepared release workflow

For a local catalog preview without running tests, double-click `START-CATALOG-PREVIEW.cmd`. It builds the review artifact and serves it on loopback; it does not publish it. Port 4318 must be free, so stop the shared Android fixture before using this separate catalog preview.

Tests are currently deferred by the owner. No acceptance suites have been run for the recent changes. Preview compilation/source packaging do not establish functional acceptance. `.node-version` pins the intended Pages build runtime to Node 22.

1. Review `public/catalog.json` against current POS selling prices. After explicit owner approval, create the ignored `public/catalog-approval.json` with `publicCatalogApproved: true`, the exact `catalogSha256`, and an ISO `approvedAt` timestamp. Do not set approval flags on the owner's behalf.
2. Build the release with `npm run build`. This does not deploy anything. Note the hash of `dist/deployment-manifest.json`.
3. Copy `release-approval.example.json` to the ignored `release-approval.json`. Record the approved Cloudflare account ID, existing Pages project, exact permanent URL, catalog hash and manifest hash. Only record `localAcceptancePassed: true` after checks actually pass. If the owner explicitly defers testing for a catalog-only publication, keep it false and record `localTestsDeferredByOwner: true` with `testDeferralScope: "STATIC_CATALOG_ONLY"`; this exception cannot authorize Functions, checkout or order APIs. Record the deployment ID only after a real deployment.
4. Run `npm run prepare:release -- --approval release-approval.json`. It verifies the exact allowlisted artifact and approvals, creates a separate upload snapshot under ignored `release-local/`, and writes a scoped PowerShell upload command. It performs no login, deployment, project creation or payment action. The generated command uses an already installed Wrangler executable; account and project eligibility must be verified separately.
5. After the approved upload actually succeeds, record the real deployment ID and verify HTTPS. Run `python scripts/make-verified-qr.py --approval release-approval.json --artifact <snapshot upload folder> --out qr-output`. This requires Pillow and ReportLab in the selected Python environment. The generator verifies the stable production origin, catalog bytes, manifest, headers and disabled APIs before creating `HardwareShopPOS-QR.png` and `HardwareShopPOS-QR.pdf`. The caption is **SCAN TO VIEW ITEMS & PRICES**. Physical scan acceptance is still required.

If the Python network client is unavailable, use `node scripts/verify-published-catalog.mjs --approval release-approval.json --artifact <snapshot upload folder> --out release-local/hosted-verification.json`, then supply `--verification release-local/hosted-verification.json` to the QR command. That evidence must match the exact permanent URL, deployment, manifest, every public file, security headers and disabled API routes and be less than 15 minutes old. Certificate verification remains enabled. This is publication verification, not native/backend or physical-phone acceptance.

The private workstation uses Wrangler 4.152.0 in `../HardwareShopPOS-Deployment-Tools`. This CLI version initially attempts to delegate new Pages projects to Workers. The approved project was created directly on Pages using `pages project create hardwareshoppos --production-branch main --force`; here `--force` only opts out of that delegation. Existing-project updates use ordinary `pages deploy <reviewed snapshot> --project-name hardwareshoppos --branch main`. Do not publish the repository root or enable Workers as an automatic fallback.

The QR URL stays unchanged when the approved catalog is updated and published to the same Pages project's production branch. Reapprove each changed catalog/artifact hash. Do not deploy preview manifests or branch-preview URLs as permanent QR destinations.

## Public source handoff

`npm run export:source` creates a fresh allowlisted source directory under ignored `release-local/`, with per-file SHA-256 hashes. It copies the public-only catalog and site tooling, excludes Git history, owner approval files, databases, backups, local fixtures and Android sources, and refuses obvious credential markers. It does not claim a complete credential audit or price approval and does not create a commit or push. A later reviewed GitHub upload should use this standalone source, never the original POS repository's sensitive history. If private records were previously pushed, removing files in a new commit alone does not remove them from old history; that needs a separately reviewed history cleanup and any necessary credential rotation.

## Ordering development

`npm run preview:ordering` reuses the private sibling project's existing synthetic customer preview at loopback port 4319, when that sibling and its Node 22+ dependencies are available. It is not shipped in the static artifact and is not real customer ordering.

For one shared website/Android synthetic database, use the sibling project's `tools/website-proof/START-LOCAL-SETUP.cmd` or `npm run dev:shared` from that folder. See its `LOCAL_SETUP.md`. No real device or production database is connected automatically. The public release stays catalog-only until the production integration and acceptance gates are complete.
