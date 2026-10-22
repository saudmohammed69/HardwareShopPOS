# Website order notifications — Phase 2 design only

> **Current Version 1 owner decision — 19 October 2026:** See [WEBSITE_ARCHITECTURE_V1.md](WEBSITE_ARCHITECTURE_V1.md). Normal target is 200–500 orders/day; sustained acceptance target 500/day; 1,000/day remains stress only. Preserve existing POS login; separate worker cloud login is out of scope. Earlier individual sign-in proposals below are historical. D1 remains the online order authority, with no full-order Firebase mirror. Production authorization/sender/capacity acceptance remains blocked; no security checks are relaxed.

17 October 2026. No FCM sender, service account, production endpoint or new notification channel was activated in this phase.

## Preserve remote printing

The current `HardwareShopFcmService.onMessageReceived` reads `type`, then immediately requires `jobId` and `shopId` before its PRINT_JOB branch. A future website message without jobId would be discarded. Phase 3 should branch on type first: PRINT_JOB keeps its existing authorization, printer/device checks, unique WorkManager job and Bluetooth/print flow; WEB_ORDER_EVENT parses its own event/order/shop identifiers and schedules independent order reconciliation. Do not send website orders through the remote-print queue or change existing printer token behavior.

## Order synchronization is authoritative

FCM is a hint after a durable backend commit. It never accepts an order, writes a bill or deducts stock. An enrolled/authorized POS fetches the independent Cloudflare cursor feed on foreground entry, reconnect, manual **Refresh Orders**, push, and a bounded foreground interval with jitter/backoff. Cache the page effects and cursor in one local transaction; compare revisions, tolerate replay and expose stale/pending/error state. The local proof tests interrupted cursor commits and repeated delivery. Native integration and the manual-refresh UI are future work.

Foreground polling stops when the UI leaves foreground. Use WorkManager for permitted deferred catch-up rather than a perpetual background loop or an unjustified foreground service. Android periodic work is not an instantaneous notification mechanism; exact scheduling and battery constraints require device verification. Keep ordinary offline counter billing independent of web synchronization.

## Alert deduplication and permissions

Persist processed alert/event identity separately from unread/handled state. Fetch committed order data before issuing an alert; safe FCM payloads carry only identifiers and a generic message, never phone numbers, notes, capabilities or credentials. Deduplicate foreground audio and OS notifications by the same event identity. Historical catch-up groups old new-order events rather than sounding once per past status change. Completed/cancelled revisions update the cache without producing another new-order sound.

Use a dedicated Orders notification channel, unread count and tap-to-order intent. Respect Android 13+ notification permission, user mute, Do Not Disturb, app force-stop and battery restrictions. Show an owner/worker-visible disabled-alert/stale-sync indicator and a test-alert control; do not promise audible delivery in prohibited states. Require local authorized access before displaying private details opened from a notification. Official behavior references: [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission), [FCM Android delivery](https://firebase.google.com/docs/cloud-messaging/android/receive-messages).

## ₹0 sender gate

Prefer FCM HTTP v1 only after a trusted sender, messaging-only server credential and Workers Free cold/warm CPU budgets are verified. Keep credentials in server secret bindings; never in the website, APK, repository, logs or backup. Do not enable Blaze/Functions or buy a push API. Store an event/delivery outbox for bounded retry and cleanup; duplicate send after lost acknowledgement must remain harmless at POS.

If secure free sending cannot be verified, foreground reconciliation plus permitted WorkManager/manual refresh is the local design fallback with explicit delay expectations. Do not claim real push delivery based on this design or fixture tests. A real-device notification/permission/dedup/reconnect test remains a production gate.

## Phase 3B local fixture verification — 17 October 2026

Native integration tests pass for the dedicated Orders notification channel, permission/mute-aware behavior, generic/private-safe notification content, persisted event deduplication and foreground/manual reconciliation; these are local fixture results (part of `phase3b-native-integration-rerun.log`, 10/10 total). The service branches website events before print-only fields and uses separate bounded WorkManager reconciliation, leaving existing PRINT_JOB authorization and Bluetooth worker routing unchanged. No FCM sender/credential was deployed and no actual sound/background/FCM delivery was verified. The dashboard awards only local permission/channel/dedup/reconciliation credit; production sender, real device behavior and sound/background guarantees remain unverified.

<!-- V1_FINAL -->
## Version 1 final local notification-cost scope

No sender or registration-token enrollment was activated. Existing Firebase sessions/PRINT_JOB routing are unchanged. Each measured 500-order cycle includes one extra signed refresh/new-order/device plus 10% duplicate hints, in addition to full foreground polling and catch-up. These are actual D1/API costs, not delivered FCM or Android alert tests. FCM HTTP v1 credential/token CPU, authorized recipient lifecycle, durable outbox/retry costs, physical delivery/sound and background scheduling remain unverified. No Firebase Functions/Blaze dependency or order mirror is authorized. Stop that sender integration until strict free safety is verified.
