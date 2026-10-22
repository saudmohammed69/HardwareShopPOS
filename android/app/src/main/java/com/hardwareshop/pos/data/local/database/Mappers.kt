package com.hardwareshop.pos.data.local.database

import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.domain.model.*

// ─────────────────────────────────────────────────────────────────────────────
// All entity → domain and domain → entity mappings live here.
// Keeps entities and domain models independent; changes in schema only
// require touching this file, not every ViewModel.
// ─────────────────────────────────────────────────────────────────────────────

// ── Category ─────────────────────────────────────────────────────────────────

fun CategoryEntity.toDomain() = Category(
    id           = id,
    name         = name,
    displayOrder = displayOrder,
    isActive     = isActive
)

// ── ProductType ───────────────────────────────────────────────────────────────

fun ProductTypeEntity.toDomain(categoryName: String = "") = ProductType(
    id           = id,
    categoryId   = categoryId,
    categoryName = categoryName,
    name         = name,
    displayOrder = displayOrder,
    isActive     = isActive
)

// ── Product ───────────────────────────────────────────────────────────────────

fun ProductEntity.toDomain(categoryName: String = "", typeName: String = "") = Product(
    id                 = id,
    categoryId         = categoryId,
    categoryName       = categoryName,
    typeId             = typeId,
    typeName           = typeName,
    name               = name,
    size               = size,
    unit               = unit,
    sellingPricePaisa  = sellingPricePaisa,
    purchasePricePaisa = purchasePricePaisa,
    gstRateBp          = gstRateBp,
    currentStock       = currentStock,
    minimumStock       = minimumStock,
    imagePath          = imagePath,
    searchAliases      = searchAliases,
    isActive           = isActive,
    isPipe = isPipe, pipeLengthPricePaisa = pipeLengthPricePaisa,
    pipeFootPricePaisa = pipeFootPricePaisa, pipeFeetPerLength = pipeFeetPerLength,
    piecesPerBox       = piecesPerBox
)

fun Product.toEntity() = ProductEntity(
    id                 = id,
    categoryId         = categoryId,
    typeId             = typeId,
    name               = name,
    size               = size,
    unit               = unit,
    sellingPricePaisa  = sellingPricePaisa,
    purchasePricePaisa = purchasePricePaisa,
    gstRateBp          = gstRateBp,
    searchAliases      = searchAliases,
    isPipe = isPipe, pipeLengthPricePaisa = pipeLengthPricePaisa,
    pipeFootPricePaisa = pipeFootPricePaisa, pipeFeetPerLength = pipeFeetPerLength,
    piecesPerBox       = piecesPerBox,
    currentStock       = currentStock,
    minimumStock       = minimumStock,
    imagePath          = imagePath,
    isActive           = isActive,
    updatedAt          = System.currentTimeMillis()
)

// ── Customer ──────────────────────────────────────────────────────────────────

fun CustomerEntity.toDomain(outstandingBalancePaisa: Long = 0L) = Customer(
    id                     = id,
    name                   = name,
    phone                  = phone,
    address                = address,
    gstNumber              = gstNumber,
    outstandingBalancePaisa = outstandingBalancePaisa,
    isActive               = isActive
)

// ── Worker ────────────────────────────────────────────────────────────────────

fun WorkerEntity.toDomain() = Worker(
    id                              = id,
    name                            = name,
    username                        = username,
    phone                           = phone,
    maxDiscountPaisa                = maxDiscountPaisa,
    requireOwnerApprovalForDiscount = requireOwnerApprovalForDiscount,
    isActive                        = isActive
)

// ── Bill / BillItem ───────────────────────────────────────────────────────────

fun BillItemEntity.toDomain() = BillItem(
    id              = id,
    productId       = productId,
    productName     = productName,
    productSize     = productSize,
    productUnit     = productUnit,
    quantity        = quantity,
    unitPricePaisa  = unitPricePaisa,
    gstRateBp       = gstRateBp,
    itemTotalPaisa  = itemTotalPaisa,
    gstAmountPaisa  = gstAmountPaisa,
    isPipe = isPipe, pipeLengths = pipeLengths, pipeFeet = pipeFeet,
    pipeFootPricePaisa = pipeFootPricePaisa
)

fun BillEntity.toDomain(
    items: List<BillItem>,
    customer: Customer?
) = Bill(
    id                   = id,
    billNumber           = billNumber,
    customer             = customer,
    customerName         = customerName,
    customerPhone        = customerPhone,
    customerGstNumber    = customerGstNumber,
    shopGstNumber        = shopGstNumber,
    discountNote         = discountNote,
    workerName           = workerName,
    items                = items,
    subtotalPaisa        = subtotalPaisa,
    discountPaisa        = discountPaisa,
    gstEnabled           = gstEnabled,
    gstAmountPaisa       = gstAmountPaisa,
    totalPaisa           = totalPaisa,
    paidPaisa            = paidPaisa,
    creditPaisa          = creditPaisa,
    paymentMethod        = PaymentMethod.fromString(paymentMethod),
    status               = when (status) {
        "CANCELLED" -> BillStatus.CANCELLED
        "EDITED"    -> BillStatus.EDITED
        else        -> BillStatus.ACTIVE
    },
    cancellationReason   = cancellationReason,
    notes                = notes,
    isPrinted            = isPrinted,
    createdAt            = createdAt
)

// ── Credit ────────────────────────────────────────────────────────────────────

fun CreditTransactionEntity.toDomain() = CreditTransaction(
    id                   = id,
    customerId           = customerId,
    billId               = billId,
    type                 = CreditTransactionType.valueOf(type),
    amountPaisa          = amountPaisa,
    previousBalancePaisa = previousBalancePaisa,
    newBalancePaisa      = newBalancePaisa,
    notes                = notes,
    createdAt            = createdAt
)

// ── Audit ─────────────────────────────────────────────────────────────────────

fun AuditLogEntity.toDomain() = AuditLog(
    id          = id,
    actionType  = actionType,
    entityType  = entityType,
    entityId    = entityId,
    actorRole   = actorRole,
    actorName   = actorName,
    beforeValue = beforeValue,
    afterValue  = afterValue,
    reason      = reason,
    createdAt   = createdAt
)

// ── Stock Adjustment ──────────────────────────────────────────────────────────

fun StockAdjustmentEntity.toDomain() = StockAdjustment(
    id          = id,
    productId   = productId,
    productName = productName,
    oldStock    = oldStock,
    newStock    = newStock,
    difference  = difference,
    reason      = reason,
    notes       = notes,
    createdAt   = createdAt
)
