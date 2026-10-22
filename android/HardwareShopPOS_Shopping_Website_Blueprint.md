# HardwareShopPOS: Public Shopping Website Integration Blueprint & Architectural Assessment
**Document Version**: 1.0.0  
**Target Project**: `HardwareShopPOS` (`com.hardwareshop.pos`)  
**Scope**: Inspection, Technical Feasibility Assessment, Infrastructure Comparison, Security Isolation, and End-to-End Architectural Specification.  
**Constraint**: Inspection & Planning Only (No code modifications executed). Zero-Cost Infrastructure Requirement (₹0 / $0).

---

## A. Existing Project Audit

An in-depth inspection of the `HardwareShopPOS` codebase confirms the following technology stack, database architecture, and operational frameworks:

### 1. Technology Stack & Versions
* **Language**: Kotlin (JDK 17/21 target, coroutines `1.8+`, Flow streams).
* **UI Framework**: Jetpack Compose (`Material 3`, `Navigation Compose`).
* **Local Persistence**: Room SQLite Database Version 10 (`DB_NAME = "hardware_shop_pos.db"`). WAL (Write-Ahead Logging) mode enabled in [AppDatabase.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/data/local/database/AppDatabase.kt).
* **Dependency Injection**: Dagger Hilt & Koin DI (`DatabaseModule`, `AppModule`, `RepositoryModule` in [Modules.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/di/Modules.kt)).
* **Cloud & Auth**: Firebase Anonymous Auth, Firebase Cloud Firestore, Firebase Cloud Messaging (FCM).
* **Background Scheduling**: Android Jetpack WorkManager (`HiltWorker` implementations: `CloudSyncWorker`, `PrintJobWorker`, `RemotePrintUploadWorker`, `ExcelSyncWorker`).
* **Thermal Printing**: ESC/POS thermal printing via Bluetooth (`BluetoothPrintManager.kt`) and remote printing agent service (`ShopPrintAgentService.kt`).

### 2. Verified Data Models & Storage (`com.hardwareshop.pos.data.local.entity`)
* **[Entities.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/data/local/entity/Entities.kt)** defines 16 Room entities:
  * `CategoryEntity` & `ProductTypeEntity`: Category hierarchy.
  * `ProductEntity`: Stores `name`, `size`, `unit`, `sellingPricePaisa`, `purchasePricePaisa` (private cost), `gstRateBp`, `currentStock`, `minimumStock`, `searchAliases`, `isPipe`, `pipeLengthPricePaisa`, `pipeFootPricePaisa`, `pipeFeetPerLength`.
  * `BillEntity` & `BillItemEntity`: Invoice master and line items (with pipe lengths & feet tracking).
  * `CustomerEntity` & `CreditTransactionEntity`: Customer profiles and Udhar/Khata transactions.
  * `StockAdjustmentEntity` & `StockBaselineEntity`: Stock modifications and cutover baselines.
  * `SupplierEntity`, `PurchaseBillEntity`, `PurchaseBillItemEntity`, `SupplierPaymentEntity`: Trade purchases.
  * `DayClosingEntity`: Daily cash, UPI, card, and credit sales reconciliations.
  * `ShopDeviceEntity` & `RemotePrintJobEntity`: Remote thermal printing job state.

### 3. Existing Synchronization Mechanism (`com.hardwareshop.pos.data.sync`)
* **Triggers**: [SyncJournalSchema.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/data/sync/SyncJournalSchema.kt) installs SQLite triggers on 19 Room tables upon database open. Any Room mutation logs an entry into `sync_dirty`.
* **Outbox / Inbox Protocol**: [SyncOutboxProjector.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/data/sync/SyncOutboxProjector.kt) converts dirty rows into immutable version-2 envelope mutations.
* **Cloud Transport**: [FirestoreSyncTransport.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/data/sync/FirestoreSyncTransport.kt) commits head records and change operations atomically to Firestore under `shops/{shopId}/sync_v2_public` or `shops/{shopId}/sync_v2_private`.
* **FCM Handler**: [HardwareShopFcmService.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/service/HardwareShopFcmService.kt) processes background push payloads for print jobs and sync wake-ups.

---

## B. Feasibility Assessment

| Feature Requirement | Supported in Current App? | Technical Assessment & Action Plan |
| :--- | :--- | :--- |
| **Catalog Browsing & Search** | ✅ Supported | Local catalog (`products`, `categories`, `product_types`) and search matcher ([ProductSearch.kt](file:///C:/Users/mohammed%20saud/Documents/Codex/HardwareShopPOS/app/src/main/java/com/hardwareshop/pos/util/ProductSearch.kt)) are fully built. Public fields can be exported to Firestore `public_catalog`. |
| **Pipes & Units (Lengths & Feet)** | ✅ Supported | `isPipe`, `pipeLengthPricePaisa`, `pipeFootPricePaisa`, `pipeFeetPerLength` exist in `ProductEntity` and `BillItemEntity`. Website cart can reuse these exact schemas. |
| **Search Aliases & Slang** | ✅ Supported | `searchAliases` field exists on `ProductEntity`. Can be synced directly to web catalog search index. |
| **Private Data Protection** | ✅ Supported | Existing Firestore rules (`firestore.sync-v2.rules`) strictly enforce `PUBLIC` vs `PRIVATE` scoping. Cost prices (`product_costs`), margins, and suppliers remain in private scope. |
| **Website Order Queue in POS** | ❌ Needs Addition | POS currently receives Remote Print Jobs (`remote_print_jobs`), but does NOT have a Room table or UI screen for `WebOrderEntity`. Needs new DAO, Entity, and UI screen. |
| **Stock Reservations** | ❌ Needs Addition | `currentStock` tracks physical stock. A `reservedQuantity` abstraction needs to be added to calculate `availableToPromise = currentStock - reservedQuantity`. |
| **Sound & Push Notifications** | 🟡 Partially Supported | FCM service (`HardwareShopFcmService.kt`) exists for print jobs. Needs a new notification channel (`web_orders_channel`) and ringtone player for new web orders. |

---

## C. Free Infrastructure Comparison (Strictly ₹0 Budget)

To comply with the **₹0 / $0 budget constraint**, we evaluate genuinely free hosting and backend options:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        COMPARE FREE TIERS (₹0)                          │
├──────────────────────────┬──────────────────────────┬───────────────────┤
│ Metric                   │ Firebase (Spark Plan)    │ Vercel + Supabase │
├──────────────────────────┼──────────────────────────┼───────────────────┤
│ Database Reads           │ 50,000 / day             │ Unlimited API     │
│ Database Writes          │ 20,000 / day             │ 500 MB DB total   │
│ Web Hosting Bandwidth    │ 10 GB / month            │ 100 GB / month    │
│ Cloud Image Storage      │ 5 GB (Firebase Storage)  │ 1 GB              │
│ FCM Push Notifications   │ 100% Free & Unlimited    │ Not Supported     │
│ Credit Card Required?    │ NO                       │ NO                │
└──────────────────────────┴──────────────────────────┴───────────────────┤
```

### Recommended Infrastructure Architecture: **Firebase Spark Plan + Vercel Static Web Catalog**
* **Public Web Catalog Hosting**: **Vercel** or **Firebase Hosting** (100% Free, no credit card required, instant global CDN deployment).
* **Live Orders & Catalog Database**: **Firebase Cloud Firestore** (Reuses existing `FirebaseManager.kt` infrastructure in the app).
* **Order Push Notifications**: **Firebase Cloud Messaging (FCM)** (100% Free, unlimited push messages to POS Android devices).
* **Product Image Hosting**: **Firebase Storage Free Tier** (5 GB storage, 1 GB/day download bandwidth).

---

## D. Proposed System Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      SYSTEM ARCHITECTURE OVERVIEW                        │
└──────────────────────────────────────────────────────────────────────────┘

 [ Customer Mobile / PC ]                  [ HardwareShopPOS Android ]
         │                                            │
         │ 1. Browse Catalog & Submit Order           │ 2. Sync Public Catalog
         ▼                                            ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                      FIREBASE CLOUD FIRESTORE                            │
├──────────────────────────────────────────────────────────────────────────┤
│  /shops/{shopId}/public_catalog/{productId}                              │
│  /shops/{shopId}/web_orders/{orderId}                                    │
│  /shops/{shopId}/config/shop_info                                        │
└──────────────────────────────────────────────────────────────────────────┘
         │                                            │
         │ 3. FCM Web Order Push                      │ 4. Order Sync Listener
         ▼                                            ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │         POS Android App Notification & Web Orders Screen               │
 │  (Worker Reviews Order → Accepts → Reserves Stock → Transfers to Bill) │
 └────────────────────────────────────────────────────────────────────────┘
```

---

## E. Database and Data Flow Blueprint

### 1. Firestore Public Web Schema (`/shops/{shopId}/`)

#### Collection: `public_catalog/{productId}`
```json
{
  "productId": "prod_10492",
  "name": "Supreme CPVC Pipe 10ft",
  "categoryName": "Pipes",
  "typeName": "CPVC",
  "size": "1 inch",
  "unit": "Piece",
  "sellingPricePaisa": 24000,
  "gstRateBp": 1800,
  "isPipe": true,
  "pipeLengthPricePaisa": 24000,
  "pipeFootPricePaisa": 1400,
  "pipeFeetPerLength": 20,
  "imageUrl": "https://firebasestorage.googleapis.com/.../pipe.jpg",
  "searchAliases": "cpvc, supreme, 1 inch pipe",
  "isPublished": true,
  "updatedAt": 1718000000000
}
```

#### Collection: `web_orders/{orderId}`
```json
{
  "orderId": "WO-8849",
  "customerName": "Ramesh Plumber",
  "customerPhone": "9876543210",
  "preferredPickupTime": "2026-10-12 11:30 AM",
  "generalNotes": "Please keep pipes tied with rope",
  "status": "NEW",
  "acceptedByWorkerName": null,
  "claimToken": null,
  "items": [
    {
      "productId": "prod_10492",
      "productName": "Supreme CPVC Pipe 10ft",
      "size": "1 inch",
      "unit": "Piece",
      "quantity": 5,
      "unitPricePaisa": 24000,
      "itemTotalPaisa": 120000,
      "isPipe": true,
      "pipeLengths": 5,
      "pipeFeet": 0,
      "itemNote": "Cut 1 pipe in half"
    }
  ],
  "estimatedSubtotalPaisa": 120000,
  "estimatedTaxPaisa": 21600,
  "estimatedTotalPaisa": 141600,
  "createdAt": 1718005000000
}
```

### 2. POS Local Room Database Additions (Migration 10To11)

```kotlin
@Entity(tableName = "web_orders")
data class WebOrderEntity(
    @PrimaryKey val orderId: String,
    val customerName: String,
    val customerPhone: String,
    val preferredPickupTime: String,
    val generalNotes: String,
    val status: String, // "NEW", "ACCEPTED", "PREPARING", "READY", "COMPLETED", "CANCELLED"
    val acceptedByWorkerName: String?,
    val estimatedTotalPaisa: Long,
    val isUnread: Boolean = true,
    val createdBillId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "web_order_items",
    foreignKeys = [
        ForeignKey(
            entity = WebOrderEntity::class,
            parentColumns = ["orderId"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("orderId")]
)
data class WebOrderItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val productId: Long?,
    val productName: String,
    val productSize: String,
    val productUnit: String,
    val quantity: Double,
    val unitPricePaisa: Long,
    val itemTotalPaisa: Long,
    val isPipe: Boolean,
    val pipeLengths: Long,
    val pipeFeet: Double,
    val itemNote: String
)

@Entity(tableName = "stock_reservations")
data class StockReservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: String,
    val productId: Long,
    val reservedQuantity: Double,
    val createdAt: Long = System.currentTimeMillis()
)
```

---

## F. Security Blueprint & Data Isolation

1. **Public vs. Private Scope Isolation**:
   * Public Web Catalog (`public_catalog`) strictly exposes: Name, Category, Type, Size, Unit, Estimated Selling Price, Image URL, Search Aliases, and Published status.
   * **Private Data Never Exposed**: Buying cost (`purchasePricePaisa`), profit margin, supplier details, worker PINs/hashes, physical stock quantities, and internal bill logs are strictly blocked by Firestore security rules.

2. **Firestore Security Rules for Web Orders**:
   ```javascript
   match /shops/{shopId}/public_catalog/{productId} {
     allow read: if true; // Public read for all customers
     allow write: if syncMember(shopId); // Only POS workers/owner can edit catalog
   }

   match /shops/{shopId}/web_orders/{orderId} {
     allow read: if syncMember(shopId);
     allow create: if request.resource.data.status == 'NEW' &&
       request.resource.data.customerName is string &&
       request.resource.data.customerPhone.matches('[0-9]{10}') &&
       request.resource.data.items.size() >= 1 &&
       request.resource.data.items.size() <= 100;
     allow update: if syncMember(shopId); // Only POS workers can accept/process orders
   }
   ```

3. **Spam & Anti-Bot Protection**:
   * **No Customer Accounts Needed**: Mobile number + Name stored in customer's local browser `localStorage`.
   * **Rate Limiting**: Client-side submission cooldown (maximum 3 orders per 10 minutes per IP/device) + Firestore rule size & structure validation.

---

## G. Order and Billing Workflow Specification

### 1. Order State Machine Transitions

```
 [ NEW (Submitted on Web) ]
             │
             ▼ (Worker taps "Accept Order")
    [ ACCEPTED & RESERVED ] ───▶ Stock Reservations Recorded (ATP updated)
             │
             ▼ (Worker prepares goods)
     [ PREPARING / READY ]
             │
             ▼ (Customer arrives at shop & pays)
  [ BILLING HANDOFF TO POS ] ───▶ Loads into BillingViewModel Cart
             │
             ▼ (Bill saved successfully)
   [ COMPLETED ARCHIVE ] ──────▶ 1. Bill saved in Unified Bill History
                                 2. Stock deducted in Room
                                 3. Stock reservation released
                                 4. Order archived
```

### 2. Stock Reservation & Over-Reservation Shortage Handling
* **Physical Stock**: Actual physical quantity on shelves (`currentStock`).
* **Reserved Stock**: Total quantity locked in accepted `ACCEPTED`/`PREPARING` web orders.
* **Available-to-Promise (ATP)**:
  $$\text{ATP} = \text{Physical Stock} - \text{Total Reserved Stock}$$
* **Over-Reservation Rule**: As explicitly specified by the shop owner, over-reservation IS allowed (ATP can go negative, e.g. $\text{ATP} = -2$). When ATP goes negative:
  * The POS displays a **Yellow Shortage Warning Badge** (*"⚠ Shortage: 2 units short for web orders"*).
  * The order is NOT rejected automatically. The worker can call the customer or fulfill from incoming stock.

### 3. Concurrency Protection (Simultaneous Worker Acceptance)
When multiple workers on different phones tap "Accept Order" at the exact same time:
* An atomic Firestore transaction executes a **Claim Fence**:
  ```javascript
  tx.update(orderRef, {
    status: 'ACCEPTED',
    acceptedByWorkerName: workerName,
    claimToken: uniqueToken
  });
  ```
* Only the first worker's transaction succeeds; subsequent taps receive *"Order already accepted by [Worker Name]"*.

---

## H. Implementation Roadmap for Codex

```
┌──────────────────────────────────────────────────────────────────────────┐
│                       CODEX IMPLEMENTATION PHASES                        │
├─────────────────────────┬────────────────────────────────────────────────┤
│ Phase 1: Database       │ Execute Migration 10To11 adding WebOrder,      │
│                         │ WebOrderItem, and StockReservation tables.     │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Phase 2: Web Catalog    │ Deploy lightweight React/HTML static catalog   │
│                         │ to Vercel/Firebase Hosting.                    │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Phase 3: POS Orders UI  │ Build Orders Screen, Notification Sound player,│
│                         │ and Order Detail modal in HardwareShopPOS.     │
├─────────────────────────┼────────────────────────────────────────────────┤
│ Phase 4: Billing Bridge │ Wire "Transfer to Bill" action in POS to load  │
│                         │ order items directly into BillingViewModel.    │
└─────────────────────────┴────────────────────────────────────────────────┘
```

---

## I. Acceptance Criteria for Future Implementation

1. **Zero Impact on POS Performance**: The web order listener and catalog sync must run on `Dispatchers.IO` and never block counter billing.
2. **Zero Price/Cost Leakage**: Buying prices, supplier names, and profit margins must NEVER be present in the public web catalog Firestore collection.
3. **Double-Deduction Prevention**: Fulfilling a web order must deduct stock exactly ONCE (releasing the reservation while committing the bill).
4. **Offline Resilience**: If internet is down, POS billing functions normally. Received web orders stay queued in Room DB until resolved.
5. **₹0 Infrastructure Cost**: Operates 100% within free allowances of Firebase Hosting, Firestore Spark Plan, and Vercel CDN.

---

**Blueprint Concluded**. Ready for review and future execution.
