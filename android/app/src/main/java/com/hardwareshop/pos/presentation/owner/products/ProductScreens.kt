package com.hardwareshop.pos.presentation.owner.products

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.presentation.common.*
import com.hardwareshop.pos.util.CalculationEngine
import com.hardwareshop.pos.util.ProductVariantMetadata
import java.io.File
import androidx.compose.material3.MenuAnchorType
// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT MANAGEMENT SCREEN  (owner — list + search)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProductManagementScreen(
    onNavigateUp: () -> Unit,
    onNewProduct: () -> Unit,
    onProductClick: (Long) -> Unit,
    viewModel: ProductManagementViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var deleteCandidate by remember { mutableStateOf<Product?>(null) }
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importProducts(context, it) }
    }

    state.message?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                "Products (${state.products.size})",
                onNavigateUp = onNavigateUp
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FloatingActionButton(
                    onClick = { importLauncher.launch("*/*") },
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Default.UploadFile,
                        "Import Excel",
                        tint = AppColors.OnPrimary
                    )
                }
                FloatingActionButton(
                    onClick = onNewProduct,
                    containerColor = AppColors.Secondary
                ) {
                    Icon(
                        Icons.Default.Add,
                        "Add Product",
                        tint = AppColors.OnPrimary
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                placeholder = { Text("Search products…") },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                leadingIcon = {
                    Icon(Icons.Default.Search, null)
                },
                trailingIcon = if (state.searchQuery.isNotBlank()) {
                    {
                        IconButton(
                            onClick = {
                                viewModel.onSearchChange("")
                            }
                        ) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 6.dp
                    )
            )

            state.message?.let { msg ->
                Text(
                    msg,
                    color = AppColors.Success,
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 4.dp
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.isLoading || state.isImporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.products.isEmpty() && !state.isLoading) {
                Box(
                    Modifier.fillMaxSize(),
                    Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Inventory2,
                            null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(64.dp)
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            if (state.searchQuery.isNotBlank()) {
                                "No products match \"${state.searchQuery}\""
                            } else {
                                "No products yet. Tap + to add."
                            },
                            color = AppColors.TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        state.products,
                        key = { it.id }
                    ) { product ->

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(1.dp)
                        ) {
                            Row(
                                Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = categoryIcon(product.categoryName),
                                        contentDescription = product.categoryName,
                                        tint = AppColors.Primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        product.displayLabel,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        "${product.categoryName} › ${product.typeName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.TextSecondary
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            CalculationEngine.formatRupees(
                                                product.sellingPricePaisa
                                            ),
                                            color = AppColors.Primary,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )

                                        Text(
                                            "Stock: ${
                                                CalculationEngine.formatQuantity(
                                                    product.currentStock
                                                )
                                            } ${product.unit}",
                                            color = if (product.isLowStock) {
                                                AppColors.LowStock
                                            } else {
                                                AppColors.TextSecondary
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            onProductClick(product.id)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            "Edit",
                                            tint = AppColors.Primary
                                        )
                                    }

                                    IconButton(
                                        enabled = state.deletingProductId == null,
                                        onClick = { deleteCandidate = product }
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            "Delete product",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val productToDelete = deleteCandidate
    if (productToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                if (state.deletingProductId == null) deleteCandidate = null
            },
            title = { Text("Delete product?") },
            text = {
                Text(
                    "Delete ${productToDelete.displayLabel}? It will disappear from billing. Previous bills and stock history will stay safe."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = state.deletingProductId == null,
                    onClick = {
                        viewModel.deleteProduct(productToDelete)
                        deleteCandidate = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    enabled = state.deletingProductId == null,
                    onClick = { deleteCandidate = null }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT IMAGE PICKER  — reusable for create/edit
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProductImagePicker(
    imagePath: String?,
    context: android.content.Context,
    onImagePicked: (Uri) -> Unit,
    onRemove: () -> Unit
) {
    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let(onImagePicked)
    }

    // Camera launcher
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<Uri?>(null)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let(onImagePicked)
        }
    }

    fun launchCamera() {
        val file = File(
            context.cacheDir,
            "product_capture_${System.currentTimeMillis()}.jpg"
        ).also {
            it.parentFile?.mkdirs()
        }

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        cameraUri = uri
        try { cameraLauncher.launch(uri) }
        catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No camera app is available. Choose a photo from Gallery.", android.widget.Toast.LENGTH_LONG).show()
        }
        catch (_: SecurityException) {
            android.widget.Toast.makeText(context, "Camera access is unavailable. Choose a photo from Gallery.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    var showMenu by remember {
        mutableStateOf(false)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Product Image (optional)",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    AppColors.Divider,
                    RoundedCornerShape(12.dp)
                )
                .background(AppColors.Surface)
                .clickable {
                    showMenu = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (imagePath != null) {
                val imageFile = File(
                    context.filesDir,
                    imagePath
                )

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Product image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Remove button overlay
                Box(
                    Modifier.fillMaxSize(),
                    Alignment.TopEnd
                ) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier
                            .size(28.dp)
                            .padding(4.dp)
                            .background(
                                AppColors.Error.copy(alpha = 0.85f),
                                RoundedCornerShape(50)
                            )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            tint = AppColors.OnPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.AddAPhoto,
                        null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )

                    Text(
                        "Add Photo",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppColors.TextSecondary
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = {
                    showMenu = false
                }
            ) {
                DropdownMenuItem(
                    text = {
                        Text("Take Photo")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.CameraAlt,
                            null
                        )
                    },
                    onClick = {
                        showMenu = false
                        launchCamera()
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text("Choose from Gallery")
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Photo,
                            null
                        )
                    },
                    onClick = {
                        showMenu = false
                        galleryLauncher.launch("image/*")
                    }
                )

                if (imagePath != null) {
                    DropdownMenuItem(
                        text = {
                            Text("Remove Image")
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                null,
                                tint = AppColors.Error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRemove()
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            "Tap to add/change image",
            style = MaterialTheme.typography.labelSmall,
            color = AppColors.TextSecondary
        )
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT DETAIL SCREEN  (create / edit)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ProductDetailScreen(
    productId: Long?,
    draftId: String? = null,
    onNavigateUp: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ProductDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(productId, draftId) {
        viewModel.init(productId, draftId)
    }
    BackHandler(enabled = !state.loadFailed && !state.isInitializing) { viewModel.saveForLater() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (!state.isEditMode) {
                    if (draftId == null) "New Product" else "Finish Draft"
                } else {
                    "Edit: ${state.name}"
                },
                onNavigateUp = { if (state.loadFailed || state.isInitializing) onNavigateUp() else viewModel.saveForLater() }
            )
        }
    ) { padding ->
        if (state.isInitializing || state.loadFailed) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                if (state.isInitializing) CircularProgressIndicator()
                state.error?.let { ErrorMessage(it) }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Text(state.draftMessage, style = MaterialTheme.typography.bodySmall)
            if (state.isImageSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            val gaps = state.form().gaps(state.ignoredOptional, validateStock = !state.isEditMode)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pending details", fontWeight = FontWeight.SemiBold)
                    if (gaps.isEmpty()) Text("Details complete. Save the product to finish.")
                    gaps.filterNot { it.optional }.forEach { Text("• ${it.label}", style = MaterialTheme.typography.bodySmall) }
                    state.form().gaps(validateStock = !state.isEditMode).filter { it.optional }.forEach { gap ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = gap.key in state.ignoredOptional,
                                onCheckedChange = { viewModel.setNotNeeded(gap.key, it) }, enabled = !state.isLoading)
                            Text("${gap.label}: not needed", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("Only tick Not needed for details that do not apply. Optional gaps remain in Pending Item Details after saving.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            // ── Category ──────────────────────────────────────────────────────
            Text(
                "Category *",
                style = MaterialTheme.typography.labelLarge
            )

            var catExpanded by remember {
                mutableStateOf(false)
            }

            ExposedDropdownMenuBox(
                expanded = catExpanded,
                onExpandedChange = {
                    catExpanded = it
                }
            ) {
                OutlinedTextField(
                    value = state.categories
                        .firstOrNull {
                            it.id == state.selectedCatId
                        }
                        ?.name ?: "Select category",

                    onValueChange = {},

                    readOnly = true,

                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            catExpanded
                        )
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(
                            MenuAnchorType.PrimaryNotEditable,
                            enabled = true
                        )
                )

                ExposedDropdownMenu(
                    expanded = catExpanded,
                    onDismissRequest = {
                        catExpanded = false
                    }
                ) {
                    state.categories.forEach { cat ->
                        DropdownMenuItem(
                            text = {
                                Text(cat.name)
                            },
                            onClick = {
                                viewModel.onCategorySelected(cat.id)
                                catExpanded = false
                            }
                        )
                    }
                }
            }


            // ── Type ──────────────────────────────────────────────────────────
            Text(
                "Type *",
                style = MaterialTheme.typography.labelLarge
            )

            var typeExpanded by remember {
                mutableStateOf(false)
            }

            ExposedDropdownMenuBox(
                expanded = typeExpanded,
                onExpandedChange = {
                    if (state.types.isNotEmpty()) {
                        typeExpanded = it
                    }
                }
            ) {
                OutlinedTextField(
                    value = state.types
                        .firstOrNull {
                            it.id == state.selectedTypeId
                        }
                        ?.name ?: "Select type",

                    onValueChange = {},

                    readOnly = true,

                    enabled = state.types.isNotEmpty(),

                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            typeExpanded
                        )
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(
                            MenuAnchorType.PrimaryNotEditable,
                            enabled = state.types.isNotEmpty()
                        )
                )

                ExposedDropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = {
                        typeExpanded = false
                    }
                ) {
                    state.types.forEach { type ->
                        DropdownMenuItem(
                            text = {
                                Text(type.name)
                            },
                            onClick = {
                                viewModel.update {
                                    copy(selectedTypeId = type.id)
                                }
                                typeExpanded = false
                            }
                        )
                    }
                }
            }


            // ── Product Image ─────────────────────────────────────────────────
            ProductImagePicker(
                imagePath = state.imagePath,
                context = LocalContext.current,
                onImagePicked = viewModel::onImagePicked,
                onRemove = viewModel::removeImage
            )


            // ── Name & Size ───────────────────────────────────────────────────
            OutlinedTextField(
                value = state.name,
                onValueChange = {
                    viewModel.update {
                        copy(name = it)
                    }
                },
                label = {
                    Text("Product Name *")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.size,
                onValueChange = {
                    viewModel.update {
                        copy(size = it)
                    }
                },
                label = {
                    Text("Size * (e.g. 1/2\", 20mm, 3kg)")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )


            // ── Unit ──────────────────────────────────────────────────────────
            Text(
                "Unit *",
                style = MaterialTheme.typography.labelLarge
            )

            var unitExpanded by remember {
                mutableStateOf(false)
            }

            ExposedDropdownMenuBox(
                expanded = unitExpanded,
                onExpandedChange = {
                    unitExpanded = it
                }
            ) {
                OutlinedTextField(
                    value = state.unit,
                    onValueChange = {},
                    readOnly = true,

                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(
                            unitExpanded
                        )
                    },

                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(
                            MenuAnchorType.PrimaryNotEditable,
                            enabled = true
                        )
                )

                ExposedDropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = {
                        unitExpanded = false
                    }
                ) {
                    viewModel.availableUnits.forEach { u ->
                        DropdownMenuItem(
                            text = {
                                Text(u)
                            },
                            onClick = {
                                viewModel.update {
                                    copy(unit = u)
                                }
                                unitExpanded = false
                            }
                        )
                    }
                }
            }


            // ── Selling / Purchase price ──────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = if (state.isPipe) state.pipeLengthPrice else state.sellingPrice,
                    onValueChange = {
                        viewModel.update {
                            if (isPipe) copy(pipeLengthPrice = it, sellingPrice = if (unit.equals("Foot", true)) pipeFootPrice else it) else copy(sellingPrice = it)
                        }
                    },
                    label = {
                        Text(if (state.isPipe) "Price per Length ₹ *" else "Selling Price ₹ *")
                    },
                    leadingIcon = {
                        Text(
                            "₹",
                            Modifier.padding(start = 4.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )

                OutlinedTextField(
                    value = state.purchasePrice,
                    onValueChange = {
                        viewModel.update {
                            copy(purchasePrice = it)
                        }
                    },
                    label = {
                        Text("Purchase Price ₹")
                    },
                    leadingIcon = {
                        Text(
                            "₹",
                            Modifier.padding(start = 4.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )
            }


            // ── GST + Stock ───────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.gstRatePercent,
                    onValueChange = {
                        viewModel.update {
                            copy(gstRatePercent = it)
                        }
                    },
                    label = {
                        Text("GST %")
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )

                OutlinedTextField(
                    value = state.initialStock,
                    onValueChange = {
                        viewModel.update {
                            copy(initialStock = it)
                        }
                    },
                    label = {
                        Text(
                            if (state.isEditMode) {
                                "Current Stock"
                            } else {
                                "Opening Stock (Optional)"
                            }
                        )
                    },
                    singleLine = true,
                    enabled = !state.isEditMode,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )

                OutlinedTextField(
                    value = state.minimumStock,
                    onValueChange = {
                        viewModel.update {
                            copy(minimumStock = it)
                        }
                    },
                    label = {
                        Text("Min Stock (Optional)")
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )
            }

            if (state.isEditMode) {
                Text(
                    "To change stock, use Stock Management → Adjust Stock (owner audit trail required).",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }


            // ── Search aliases ────────────────────────────────────────────────
            if (state.isPipe) {
                OutlinedTextField(state.pipeFootPrice, { value -> viewModel.update {
                    copy(pipeFootPrice = value, sellingPrice = if (unit.equals("Foot", true)) value else pipeLengthPrice)
                } }, label = { Text("Price per Foot ₹ *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Text("One full pipe = ${state.pipeFeetPerLength} ft; stock is recorded in ${state.unit}.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = state.piecesPerBox,
                onValueChange = { value -> if (value.all(Char::isDigit)) viewModel.update { copy(piecesPerBox = value) } },
                label = { Text("Pieces per box (optional)") },
                supportingText = { Text("For Piece/Nos products only. Price and stock stay per piece. Leave blank to disable box entry.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text("Common hardware names, spelling variants and equivalent sizes are searched automatically. Extra aliases below are optional.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            state.existingProduct?.let { original ->
                val originalAttributes=ProductVariantMetadata.read(original)
                val attributes=ProductVariantMetadata.read(original.copy(searchAliases=state.searchAliases))
                if(originalAttributes!=null && attributes!=null) {
                    Text("Product family: ${attributes.parent}")
                    fun changeAttributes(changed:ProductVariantMetadata.Attributes) {
                        viewModel.update { copy(name=ProductVariantMetadata.displayName(changed),
                            searchAliases=ProductVariantMetadata.aliases(changed,searchAliases)) }
                    }
                    if(originalAttributes.colour.isNotBlank()) OutlinedTextField(attributes.colour,
                        {changeAttributes(attributes.copy(colour=it))},label={Text("Colour")},modifier=Modifier.fillMaxWidth())
                    if(originalAttributes.model.isNotBlank()) OutlinedTextField(attributes.model,
                        {changeAttributes(attributes.copy(model=it))},label={Text("Type / finish / model")},modifier=Modifier.fillMaxWidth())
                    if(originalAttributes.extra.isNotBlank()) OutlinedTextField(attributes.extra,
                        {changeAttributes(attributes.copy(extra=it))},label={Text("Other specification")},modifier=Modifier.fillMaxWidth())
                }
            }
            OutlinedTextField(
                value = ProductVariantMetadata.visibleAliases(state.searchAliases),
                onValueChange = {
                    viewModel.update {
                        copy(searchAliases = ProductVariantMetadata.replaceVisibleAliases(searchAliases,it))
                    }
                },
                label = {
                    Text("Kannada / Hindi / English aliases (comma-separated)")
                },
                placeholder = {
                    Text("e.g. bend, ನಳ್ಳಿ, नल, shop nickname")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )


            // ── Error ─────────────────────────────────────────────────────────
            state.error?.let {
                ErrorMessage(it)
            }


            // ── Save ──────────────────────────────────────────────────────────
            PrimaryActionButton(
                text = if (!state.isEditMode) {
                    "CREATE PRODUCT"
                } else {
                    "SAVE CHANGES"
                },
                onClick = viewModel::save,
                isLoading = state.isLoading,
                icon = Icons.Default.Save
            )

            OutlinedButton(onClick = viewModel::saveForLater, enabled = !state.isLoading && !state.isImageSaving,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null)
                Spacer(Modifier.width(8.dp))
                Text("SAVE FOR LATER")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

