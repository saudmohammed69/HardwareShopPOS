<!-- CATALOG_PUBLICATION_20261022 -->
## Generated QR — 22 October 2026

Permanent target verified: https://hardwareshoppos.pages.dev/. Generated local artifacts: `../HardwareShopPOS-Website-Public/qr-output/HardwareShopPOS-QR.png` and `HardwareShopPOS-QR.pdf`, caption **SCAN TO VIEW ITEMS & PRICES**. The generator consumed actual certificate-verified HTTPS evidence matching deployment `db8ed9c4-89dd-4809-ba79-31fc37afed28` and all public file hashes. Four-module quiet zone; printable PDF rendered and visually checked. Physical multi-phone scanning remains pending. The PDF clearly states catalog-only and online ordering unavailable. Do not use an ordering caption until separate live acceptance/approval. Hashes and evidence are in `WEBSITE_FINAL_ENGINEERING_RESULTS.md` and `qr-output/verification.json`; earlier planning sections are historical.
<!-- END_CATALOG_PUBLICATION_20261022 -->

# HardwareShopPOS — Permanent QR publishing plan

## Current state

No QR image has been created. The local website has not been deployed, and there is no verified permanent URL. A QR for localhost, a branch preview, a guessed Pages project, or the GitHub repository would point customers to the wrong destination.

The intended URL format is `https://<owner-approved-project>.pages.dev/`. Cloudflare may suffix a project name if it is unavailable, so record the actual final URL after the owner-approved Pages deployment. Do not assume the project name.

## Safe page behind the QR

The current prepared candidate is an empty `MAINTENANCE` page: zero products, no ordering, no customer edit/cancel, no Worker/Functions, and no production POS connection. If the owner later approves a sanitized catalog, rebuild from that exact content, hash it, review the public fields, and deploy it with ordering still disabled.

## Generation gate

The QR tool refuses to generate media until the owner approves the Cloudflare account, project, content, deployment and Free plan; the approval record matches the artifact manifest; the chosen URL exactly matches the Pages project; and a real HTTPS check confirms the deployed manifest, security headers, maintenance/catalog mode and unavailable order routes. Certificate validation stays enabled and redirects must remain on the approved origin.

The generator writes `HardwareShopPOS-Catalog-QR.png`, `HardwareShopPOS-Catalog-QR.pdf`, and a verification record. It labels a maintenance page as “Website preview - catalog being prepared”; a catalog-only page is labeled separately. QR approval-gate tests pass 4/4 locally. That result is not live HTTPS or deployment verification.

After approval and deployment, the owner can run:

```powershell
python website-publication/make-verified-qr.py `
  --approval <owner-approved-deployment-record.json> `
  --artifact website-publication/build/<reviewed-artifact> `
  --out <new-qr-output-directory>
```

Then scan the PNG and PDF on multiple phones, verify that every scan opens the exact HTTPS URL, and inspect that the visible page still says ordering is unavailable. Print the QR only after those checks. Keep a record of its exact URL, deployment ID, artifact-manifest hash, QR-file hashes and verification time.

## Required owner input before publishing

Approve the exact Cloudflare account, available Pages project name and resulting `pages.dev` URL; approve the exact maintenance/catalog contents; confirm Free-plan/no-payment status; and authorize that specific deployment. Until then, the QR deliverables are the tested generator and this plan only—there is no PNG/PDF to distribute.
