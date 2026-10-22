# Local website and POS setup

Current source is **implemented; startup succeeded, acceptance remains pending**. The owner requested test execution be left for later. The shared service was started locally on 22 October 2026 after a sandbox loopback `EACCES` required a scoped local-network permission. No application or cloud account is started by opening this document.

## Start the customer and Android fixture together

Requires Node.js 22+ and the pinned dependencies already in this folder. Double-click `START-LOCAL-SETUP.cmd`, or run from this folder:

```powershell
npm run dev:shared
```

If dependencies are missing, install the existing pinned lockfile with `npm ci --ignore-scripts --no-audit --no-fund`. The launcher never installs software or logs into a provider automatically.

The intended listeners are:

| Purpose | Address |
| --- | --- |
| Customer search, cart, pipe inputs, details and order recovery | `http://127.0.0.1:4317/` |
| Existing signed DEBUG Android pairing/order adapter | `http://127.0.0.1:4318/shops/proof-shop/` |

Both listeners share **one** Miniflare/D1 runtime. Customer orders and signed worker operations therefore address the same synthetic order store. Neither listener is exposed on Wi-Fi. Busy ports fail rather than connecting to an unknown service. An Android emulator still requires an explicitly selected disposable fixture and loopback reverse mapping; starting this server alone does not configure or enroll an Android installation.

Each start creates `.local-state/shared-setup/<run UUID>/`. `setup.json` records its URLs and directory. `control.json` contains a private random synthetic administrator control key; it is not included in any page or printed to the console. Existing customer, admin and device signing/replay checks remain in use. Only the separately keyed loopback `POST /__fixture/bootstrap` can create a **synthetic** first-owner grant for this run. There is no public owner self-approval route. Real proprietor verification remains a separate production requirement.

Products, prices and identities here are sample fixtures, not the 1,478-row real-price catalog candidate. The DEBUG POS must use its disposable database fixture, fixture ID and Android Keystore installation key. It must not be Firebase-linked. Existing `WebsiteOrdersRepository` checks intentionally refuse a live shop database. Use the existing `WebsitePairedIntegrationTest`/process-recovery fixture procedures when later accepting this wiring; no real-device provisioning or APK replacement is performed by the launcher.

For a selected disposable emulator only, the later operator can use `adb -s <emulator serial> reverse tcp:4318 tcp:4318`. Do not run device setup against the shop's installed production application. The customer preview stays in the computer browser. Physical phone/TLS acceptance is separate.

Ctrl+C stops both listeners after in-flight requests finish. No retained run directories, receipts or uncertain claims are deleted. Restart creates a fresh authority and run; it does not silently reenroll old devices or reassign sticky billing claims. Keep a failed run directory for investigation. Restart/revocation recovery must be checked separately.

## Publishable real-price catalog

The independent `HardwareShopPOS-Website-Public` sibling project is the public-source candidate. Its public JSON has only name, size, selling unit and integer-paisa selling price. It contains 1,478 verified-source candidate rows, with disputed/missing/ambiguous records excluded. It has no dependency on this private fixture in its static artifact.

From the public project, build a review preview with `npm run build -- --preview`, then `npm run serve`. This is catalog-only. An ordinary release build needs owner approval for the exact catalog bytes and current price parity. Do not deploy `tools/website-proof`, its synthetic authority, `.local-state`, evidence or the Android project.

## Deferred acceptance handoff

No test suite was executed for this setup. Service startup succeeded; this does not verify customer submissions, Android wiring, concurrency or recovery. The two historical transport failures remain unresolved; collecting better diagnostic evidence is not a transport pass. When testing is resumed, first run the focused shared-IP and signed-read tests, then browser/native integration and bounded capacity only after those pass. Keep the existing 60-second signature freshness and 15-second UI limits. Do not enable live ordering from a passing static build.

Production still needs approved account/project/catalog deployment, a real trusted owner/device adapter and sender, backend/native acceptance, account-wide free-tier headroom, physical phone/printer acceptance and separate live-ordering approval. The release adapter currently refuses production use; this launcher does not remove that protection.
