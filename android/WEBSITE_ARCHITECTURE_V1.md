# HardwareShopPOS — Final architecture and cost requirements, Version 1

Owner decision recorded 19 October 2026. This document supersedes conflicting older proposals about traffic, duplicating online orders into Firebase, and separate worker cloud sign-in. It authorizes local development and synthetic verification only.

## Approved service responsibilities

| Component | Version 1 responsibility |
| --- | --- |
| Cloudflare Pages Free | Mobile-first, text-only customer QR shopping website on a free hosting subdomain. |
| Cloudflare Workers Free | Public-safe catalog and validated customer order APIs; authenticated POS synchronization and order operations. |
| Cloudflare D1 Free | Primary online order, status, audit, billing-identity and recovery store. Do not mirror every order into Firebase. |
| Firebase Cloud Messaging | Optional wake-up hints to authorized POS installations, conditional on a verified secure free sender. D1 reconciliation remains authoritative. |
| Existing Firebase integration | Preserve current POS synchronization and printing sessions. A finalized bill may continue its existing normal sync; this does not authorize a second website-order mirror. |
| Existing Room database | Preserve ordinary offline POS billing, existing once-only website billing origins/outbox, stock transactions and unified Bill History. |

## Customer and worker scope

Customers physically inside the shop scan the QR, browse/search, select variants and pipe quantities, and submit a cart with contact details. No customer OTP, online payment, automatic billing, public stock checking or automatic reservation. Workers review availability and manually use existing billing. Customer confirmation is required for adjustments. Success is shown only after durable backend storage; errors preserve the cart and must not invent an order number.

**Keep the existing POS login unchanged. Do not implement separate worker cloud login.** Earlier requests to choose an individual Orders sign-in workflow are superseded. Do not wire the existing DEBUG individual-identity gate into production or relax it to accept shared identities.

## Authentication compatibility finding and stopped integration

The current local signed backend explicitly requires an INDIVIDUAL UID/person/shop membership and an enrolled device. Its membership lookup and transactional guards reject shared identities. The Android DEBUG gate also expects that individual identity to match an owner-approved local worker binding. This is a tested older security baseline, not a compatible Version 1 authorization implementation.

Production wiring is stopped. A shared Firebase UID plus a client-selected worker ID is not individual identity. Removing the individual check, trusting client role claims or enabling the fixture bearer adapter would weaken authentication and is prohibited.

### Version 1 design direction, not implemented authority

Use an owner-approved, shop-scoped installation principal for online Orders rather than asserting a cloud identity for each worker:

1. Keep the existing owner/worker login and local permission checks. Distinguish the locally selected worker audit label from server-verified device identity.
2. Issue installation-specific, nonexportable AndroidKeystore keys. Owner-reviewed pairing binds the public key, shop, allowed operations, expiry and version in trusted server records. Never ship shared bearer secrets or an owner credential in the APK or website.
3. Verify signatures, exact request/body/URL binding, mutation replay protection, current enrollment scope and revocation on every protected operation. Recheck authorization inside mutation transactions. Reinstall, database restore or key replacement requires review; copied backup metadata must not reactivate authority.
4. Bootstrap/owner approval must use a trustworthy owner authority and one-use, short-lived, key-bound approval. The existing shared Firebase account or an unsigned local OWNER label cannot establish that authority. No public self-owner route is allowed.
5. Server permissions authorize the installation, not a named worker. Existing local worker permissions still govern the UI and billing handoff. Any requirement for server-verifiable individual worker attribution remains unsupported by this design; do not represent a device signature as proof of a person.
6. Sticky billing claims remain tied to the original installation and bill identity. Revocation cannot make an uncertain offline bill safe to reassign. Keep REVIEW_REQUIRED reconciliation and ordinary offline counter billing unchanged.

**Exact security blocker:** owner bootstrap/approval, installation capability policy, local permission handoff and restore/revocation lifecycle need a reviewed and tested device-principal protocol. The current individual-principal guards cannot simply be reused with a shared UID. No such protocol, real enrollment or production provider trust is claimed implemented by this document.

## Traffic and acceptance

- Normal target: 200–500 website orders/day. The sustained acceptance target is 500/day.
- Keep 1,000/day as stress evidence; earlier stress results do not prove the new login/device architecture.
- Include 5-/20-line carts, 20-/100-customer concurrency, five/ten installations, full catalog browsing, customer edits/cancellations, retries, adjustments, signed synchronization, notification-triggered refreshes, archive/restore and protected backlog.
- Distinguish compressed local service cycles, long-term storage models, actual elapsed operation and provider measurements. A local two-cycle test is not 48 hours of Cloudflare operation.
- Re-test the final reviewed Version 1 authorization/sender/archive schema before claiming sustained acceptance. Keep quota use shared with other account workloads visible.

**Verified shared-IP blocker:** the current local limiter permits three orders/IP/ten minutes. A new 20-customer shared-IP test persisted three orders and rejected 17 with HTTP 429; retrying accepted submission identities produced no duplicates or false confirmations. Customers on carrier NAT can share an IP, so distinct-IP load profiles do not establish safe in-shop admission. The current control remains intact. A reviewed abuse policy and trustworthy production client-IP extraction are required; simply raising/removing the limit is not an accepted repair.

## Strict zero-payment boundaries

Target ₹0/month; only free service features. No purchased domain, paid plan, payment information, automatic upgrade, extra account to evade limits, paid fallback or hidden paid UI/API/storage dependency. Public documentation is not proof of this owner's account eligibility or available quota.

Retain the 450,000,000-byte admission cutoff, warnings and owner-controlled verified archive cleanup. Never automatically discard active, uncollected or unresolved orders. Finite free storage cannot provide unlimited retention; if safe capacity or a required secure feature cannot fit, stop the affected operation and report the blocker. Normal offline POS billing remains independent.

## Notifications

FCM is a hint, never an order database or acceptance receipt. Reconcile from D1 after hints, foreground startup, missed notifications and manual refresh, with persistent deduplication. Keep existing PRINT_JOB routing and Firebase session intact. No real FCM sender is activated.

A prospective Workers HTTP v1 sender needs protected server credentials, token management, authorized recipient registration, bounded retries and a durable delivery policy within CPU/query/write limits. Do not add Firebase Cloud Functions or Blaze as an assumed free dependency. If the secure free sender cannot be verified, label push unsupported and retain the documented foreground/manual/background-permitted reconciliation behavior; do not promise immediate background alerts.

## Approval boundary

No deployment, production cloud activation, real account access, real enrollment or live shop data is authorized. This decision does not approve release. Current verified weighted progress stays 83.5% until additional acceptance evidence is earned. See WEBSITE_V1_VERIFICATION_RESULTS.md for actual current executions and remaining blockers.
