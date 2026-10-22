// Compose state delegate writes are observed during recomposition; retain the UI state updates.
@file:Suppress("AssignedValueIsNeverRead")

package com.hardwareshop.pos.presentation.owner.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hardwareshop.pos.data.repository.PendingProductRepository
import com.hardwareshop.pos.domain.model.PendingProduct
import com.hardwareshop.pos.presentation.common.AppTopBar
import com.hardwareshop.pos.presentation.common.ErrorMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingProductUiState(
    val items: List<PendingProduct> = emptyList(), val query: String = "", val filter: String = "All",
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null
) {
    val visible get() = items.filter {
        (filter == "All" || (filter == "Drafts") == it.isDraft) &&
            (query.isBlank() || (it.name + " " + it.size + " " + it.gaps.joinToString { gap -> gap.label })
                .contains(query.trim(), ignoreCase = true))
    }
}

@HiltViewModel
class PendingProductViewModel @Inject constructor(private val repository: PendingProductRepository) : ViewModel() {
    private val _state = MutableStateFlow(PendingProductUiState())
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            repository.observePending().catch { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Cannot load pending items") }
            }.collect { items -> _state.update { it.copy(items = items, loading = false) } }
        }
    }
    fun query(value: String) = _state.update { it.copy(query = value) }
    fun filter(value: String) = _state.update { it.copy(filter = value) }
    fun discard(id: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { repository.discard(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = e.message ?: "Cannot discard draft") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

@Composable
fun PendingProductScreen(
    onNavigateUp: () -> Unit, onNewProduct: () -> Unit,
    onDraft: (String) -> Unit, onProduct: (Long) -> Unit,
    viewModel: PendingProductViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var discard by remember { mutableStateOf<PendingProduct?>(null) }
    discard?.let { item ->
        AlertDialog(onDismissRequest = { discard = null }, title = { Text("Discard draft?") },
            text = { Text("Discard the unfinished changes for ${item.name}? An existing saved product will be kept.") },
            confirmButton = { TextButton(onClick = { item.draftId?.let(viewModel::discard); discard = null }) { Text("Discard draft") } },
            dismissButton = { TextButton(onClick = { discard = null }) { Text("Keep draft") } })
    }
    Scaffold(topBar = { AppTopBar("Pending Item Details (${state.items.size})", onNavigateUp = onNavigateUp) },
        floatingActionButton = { FloatingActionButton(onClick = onNewProduct) { Icon(Icons.Default.Add, "New item") } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Finish saved drafts and review missing details. New drafts are kept out of billing. Existing products remain available.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Drafts", "Existing").forEach { name ->
                        val count = when (name) { "Drafts" -> state.items.count { it.isDraft }; "Existing" -> state.items.count { !it.isDraft }; else -> state.items.size }
                        FilterChip(selected = state.filter == name, onClick = { viewModel.filter(name) }, label = { Text("$name ($count)") })
                    }
                }
            }
            item { OutlinedTextField(value = state.query, onValueChange = viewModel::query, label = { Text("Search name or missing detail") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Default.Search, null) }) }
            state.error?.let { error -> item { ErrorMessage(error) } }
            if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && state.visible.isEmpty()) item {
                Text(if (state.items.isEmpty()) "All caught up. There are no pending item details."
                    else "No items match this search or filter.", modifier = Modifier.padding(vertical = 24.dp))
            }
            items(state.visible, key = { it.key }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.name + item.size.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (!item.isDraft) "Existing product · review details" else if (item.productId == null) "Draft · not available for billing" else "Draft changes · saved product unchanged",
                            style = MaterialTheme.typography.labelMedium)
                        if (item.gaps.isEmpty()) Text("Details are ready. Open this draft and save the product.")
                        else item.gaps.forEach { gap ->
                            Text("• ${gap.label}" + if (gap.optional) " — optional; review or mark Not needed" else " — required",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        item.updatedAt?.let { time ->
                            Text("Draft saved " + java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time)),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !state.busy, onClick = {
                                if (item.draftId != null) onDraft(item.draftId) else item.productId?.let(onProduct)
                            }) { Text("Complete details") }
                            if (item.isDraft) TextButton(enabled = !state.busy, onClick = { discard = item }) { Text("Discard draft") }
                        }
                    }
                }
            }
        }
    }
}
