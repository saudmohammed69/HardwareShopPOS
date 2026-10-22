# Cloud sync setup — 6 October 2026 (release gate ON)

## What syncs automatically (both directions)

| Data | How it syncs |
|---|---|
| Categories, product types, products | Trigger journal → outbox → Firestore; other phone downloads and applies |
| Customers | Same (workers may add; credit-limit changes need owner) |
| Bills, bill items, payments | Complete invoice bundles, atomic; duplicate bill numbers get a suffix on merge — both sales are kept |
| Credit transactions (bill + standalone) | Signed amounts verified; balances recomputed from entries |
| Suppliers, purchases, supplier payments | Private owner scope, complete invoice bundles |
| Sales returns / exchanges | Public scope, complete bundles |
| Stock adjustments, day closings, audit logs | Standalone records |
| Settings (shop name/phone/address/GST) | Allowlisted keys only |
| **Stock** | Owner phone publishes one baseline per product (absolute quantity); after that, every post-link sale/purchase/adjustment flows as a signed movement, applied exactly once on each phone |

Sync runs: on app open, on link, every 15 minutes in the background, and from
Settings → SYNC NOW. Nothing waits for a button press.

## First-link stock model (important)

Each phone keeps its own historical stock movements; they are **not** replayed to the
other phone (they were retired at link time). Instead:

1. The **first phone to sync** (the owner/billing phone) publishes its current quantity
   for every established product as the shop baseline.
2. The other phone adopts that baseline for each product it has **not sold locally**;
   products it already sold since linking are parked for review instead of being
   overwritten.
3. From then on every stock change flows as a delta and both phones converge.

After the first sync completes, do one physical spot-check: if any product's quantity
looks wrong on the second phone, correct it with Stock → Adjust on the owner phone —
the correction syncs everywhere.

## Firebase Console steps (project `hardwareshoppos`)

1. Authentication → Sign-in method → enable **Anonymous**.
2. Firestore Database → create (production mode) if absent.
3. Firestore → Rules → paste all of `firestore.sync-v2.rules` → **Publish**. It covers
   the print paths and the `sync_v2_public*` / `sync_v2_private*` collections.
4. Approve each phone with a member document `shops/<shopId>/members/<uid>`:
   - Owner/billing phone: `{ "enabled": true, "role": "OWNER" }`
   - Second phone: `{ "enabled": true, "role": "WORKER" }`
   - The printer phone additionally needs `"printer": true`.
   Worker phones upload bills, customers and stock decreases, and receive everything;
   catalogue changes and stock increases are owner actions by design.
5. Each phone: Settings → Cloud Sync → enter the Shop ID → LINK THIS PHONE TO SHOP.
   Linking verifies the member document first; if it says "not authorized", fix step 4.
6. SYNC NOW once, then just use the app.

## Honest limits

- Recommended topology: one **OWNER** phone (billing/stock authority) + one **WORKER**
  phone. If you make BOTH phones OWNER, each will also see the other's products as
  additional items, and unsold copies can park conflicting baselines for review —
  workable but messier.
- Genuine conflicts park as "need review" (shown in Settings and after SYNC NOW) — they
  are never silently merged or overwritten. Zero-quantity baselines are recorded but never
  overwrite local stock. Baselines are immutable, like all stock history.
- Cloud records are immutable (no deletes). Test first with a throwaway Shop ID (e.g.
  `shop-test1`) if you want a dry run, and take Settings → Create Backup on both phones
  before the real link.
- Workers' stock increases (rare) park for owner review; workers cannot publish baselines.
