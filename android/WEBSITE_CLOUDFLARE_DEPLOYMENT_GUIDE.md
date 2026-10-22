<!-- CATALOG_PUBLICATION_20261022 -->
## Actual approved publication — 22 October 2026

Project `hardwareshoppos` is published at https://hardwareshoppos.pages.dev/ with 1,478 catalog rows, production branch `main`, deployment `db8ed9c4-89dd-4809-ba79-31fc37afed28`. Updates must upload only a freshly approved, hash-verified static snapshot from `HardwareShopPOS-Website-Public`; never upload the POS repository, its Git history or local credentials. The installed private Wrangler 4.152.0 required `--force` once at project creation to opt out of automatic Workers delegation. Existing-project deployment uses ordinary Pages deploy. Owner-deferred tests are recorded honestly and apply only to static catalog publication. No backend/order activation is authorized by this release. Current commands/hashes are recorded in `WEBSITE_FINAL_ENGINEERING_RESULTS.md`; the earlier proposal below is historical.
<!-- END_CATALOG_PUBLICATION_20261022 -->

# HardwareShopPOS — Cloudflare Pages preparation guide

This guide prepares a static maintenance preview. **Nothing has been connected or deployed.** Do not run the deployment steps until the owner approves the exact Cloudflare account, Pages project, artifact/content, Free-plan status, and public URL.

## Reviewed local artifact

The current artifact was built to:

```text
website-publication/build/final-maintenance-preview
```

Its manifest is `MAINTENANCE`, has zero products, disables live ordering and customer mutations, and declares `functions=false`. It has no backend endpoints and no product inventory. Do not replace it with the synthetic website-proof catalog.

Build a fresh copy from the repository root:

```powershell
node website-publication/build.mjs --out=website-publication/build/release-candidate
```

Cloudflare Pages configuration after approval:

```text
Root directory: /
Build command: node website-publication/build.mjs --out=website-publication/build/release-candidate
Build output directory: website-publication/build/release-candidate
Node version: 22 (.node-version)
```

The builder uses Node built-ins and the checked-in pricing projection; it does not install a cloud SDK or call a service. A catalog-only build requires a separately reviewed, sanitized catalog file and an approval/hash file. The current build must remain maintenance mode until those exact public contents are approved.

## Account and project setup — owner action only

1. Sign in to the Cloudflare account the owner selects and verify the account/plan in its dashboard. This local work did not verify account eligibility, card requirements, shared account use, or any billing setting. If the dashboard asks for payment details, an upgrade, or automatic paid service activation, stop.
2. Approve an available project name. Cloudflare's Direct Upload docs state the site uses `<PROJECT_NAME>.pages.dev`, or a suffixed name if that name is already taken. Do not print a QR from an assumed name.
3. Choose the upload model before creating the project. Git integration can automatically deploy after pushes; Direct Upload cannot later be changed into a Git-integrated project. See [Git integration](https://developers.cloudflare.com/pages/configuration/git-integration/) and [Direct Upload](https://developers.cloudflare.com/pages/get-started/direct-upload/).
4. The GitHub repository must first be pushed and reviewed. If Git integration is selected, keep production and preview deployments paused until the exact build and public content are approved. The Pages Git integration automatically deploys on pushes by default.
5. Create the Pages project only after those approvals. Do not create Workers, D1, secrets, bindings, routes, FCM senders, or ordering APIs for this static preview.

Cloudflare's published Pages Free limits currently list 500 builds/month, one concurrent build, up to 20,000 site files, and 25 MiB per asset. The current candidate has ten files and the build output is static. These published limits do not verify this account's eligibility, a card-free signup, or future quotas. See [Pages limits](https://developers.cloudflare.com/pages/platform/limits/).

Cloudflare documents a build output directory and build command in [Pages build configuration](https://developers.cloudflare.com/pages/configuration/build-configuration/). Its current build image documentation lists Node.js 22.16.0 as the default and `.node-version` as an override mechanism; this repository pins `22` in [`.node-version`](.node-version). See [Pages build image](https://developers.cloudflare.com/pages/configuration/build-image/).

## Deployment commands — not executed

For a Git-integrated project, use the build settings above and verify branch deployment controls before allowing any production push. For a previously owner-approved Direct Upload project, the documented Wrangler operation is:

```powershell
# Requires the owner's approved account and a deliberate deployment decision.
# Do not run until the release checklist is approved.
npx wrangler pages deploy website-publication/build/release-candidate --project-name <approved-project>
```

Cloudflare Direct Upload can be performed using Wrangler or the dashboard; both create an actual deployment. This document does not authorize either method. No command above was run and no account token belongs in the repository.

## Post-deploy verification required before QR creation

Run only after deployment approval. Use `website-publication/make-verified-qr.py` with an owner-reviewed approval record containing the exact project, URL, artifact-manifest SHA-256, deployment ID, Free-plan check, and separate account/project/content/deployment approval flags. The script performs strict HTTPS validation and refuses redirects outside the approved origin. It checks the served manifest equals the reviewed artifact, verifies static security headers and mode, and rejects order API routes before writing QR media.

If the real page differs, ordering is enabled, a security header is absent, an API responds unexpectedly, or HTTPS verification fails, stop and do not print a QR.

## Current stopping point

No Cloudflare account was accessed. No Pages project or URL is selected. The GitHub push is still unconfirmed. Therefore there is no deployment ID, no live HTTPS verification, and no final QR PNG/PDF.
