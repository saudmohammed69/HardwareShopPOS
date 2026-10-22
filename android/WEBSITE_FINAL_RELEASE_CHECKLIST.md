<!-- CATALOG_PUBLICATION_20261022 -->
## Current static release — 22 October 2026

- [x] Exact Cloudflare account, `hardwareshoppos` project, 1,478 catalog prices and static deployment approved by owner.
- [x] Catalog-only publication at https://hardwareshoppos.pages.dev/; deployment and asset hashes recorded.
- [x] Permanent HTTPS, security headers and disabled order APIs verified.
- [x] QR PNG and printable PDF generated with **SCAN TO VIEW ITEMS & PRICES**; PDF visually reviewed.
- [ ] Physical-phone QR scan, complete website/backend/Android/POS/printing acceptance and live-ordering activation. Tests remain owner-deferred; no pass is inferred.

**83.5% unchanged; catalog is published, ordering is disabled.** See `WEBSITE_FINAL_ENGINEERING_RESULTS.md` for current evidence. Older checklist sections below are historical.
<!-- END_CATALOG_PUBLICATION_20261022 -->

## Separate site-only catalog candidate — 22 October 2026

The earlier `website-publication` artifact remains an empty maintenance package. A new, isolated repository at `../HardwareShopPOS-Website-Public` now has a 1,478-row catalog-only review candidate and local 3/3 package tests. It has no remote and no deployment. Its build manifest says `REVIEW_PREVIEW`; a normal release build is blocked until an approval file matches the exact catalog hash. The candidate is from the bundled asset rather than a fresh Room export and still needs owner price/source approval.

# HardwareShopPOS — Final release checklist

Current status: **local maintenance preview prepared; release blocked**. Verified progress remains 83.5%.

## Complete locally

- [x] Static Pages package builds without cloud credentials or a database connection.
- [x] Current candidate contains zero products, no checkout or customer mutation code, no Functions, and `liveOrdering=false`.
- [x] Build manifest hashes every artifact file.
- [x] Website publication tests pass 3/3 across seven emulated viewport sizes.
- [x] QR approval gate tests pass 4/4; the generator requires a matching static artifact and HTTPS verification.
- [x] `.node-version` pins Pages build runtime to Node 22.
- [x] Current-source Android JVM suite: 312/312 passed with Gradle 8.7, Temurin 17 and the scoped socket workaround.
- [x] Current-source debug/release APK builds and instrumentation-test APK compilation succeeded; release artifact metadata/hash are recorded in `WEBSITE_FINAL_ENGINEERING_RESULTS.md`.
- [x] Existing Room 11 and BackupManager format 8 remain unchanged; no billing or stock data was touched.

## Blocked before customer release

- [ ] Prepare publishable Git history excluding purchase prices, stock and private shop records, then authenticate and review before pushing. No push occurred; current local history contains sensitive fields.
- [ ] Approve the exact Cloudflare account, Pages project name, public content, and resulting `pages.dev` URL. Confirm the account remains on Free and does not require payment information or an upgrade.
- [ ] Approve a reconciled public catalog made from a current POS export. Existing assets disagree (3 selling-price conflicts, 173 unmatched variants, 240 rows without a positive selling price in `final_catalog`); none has been published.
- [ ] Run native Android billing, Orders, pairing, restore, printing-permission, cold-start and keyboard acceptance on a ready emulator/approved device. The API 36 AVD requires 2,048 MB; host free RAM was 741 MiB; zero native tests executed in this recheck.
- [ ] Resolve both Phase 4J backend transport failures and rerun the unchanged signed/shared-IP tests. Keep signatures, freshness, replay protection and uncertain claims intact.
- [ ] Verify the 500-order workload, account-wide reads/writes/requests, finite storage and archive costs. The historical 90,833-write busy-day model is not a safe 500/day acceptance result.
- [ ] Approve a trustworthy proprietor/bootstrap and device-revocation recovery procedure before real enrollment.
- [ ] Deploy the exact reviewed static artifact only after those named account/project/content approvals.
- [ ] Verify the actual HTTPS URL, artifact manifest, headers, disabled API behavior and mobile page after deployment.
- [ ] Generate and inspect QR PNG/PDF only after the verified permanent URL exists.

## Release decision

Do not enable website ordering, Workers/D1, production Firebase/FCM, or real device enrollment. A static maintenance page, if later approved and deployed, would be a preview only; it would not mean QR ordering is live. Phase 5 remains blocked.
