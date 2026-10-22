package com.hardwareshop.pos.presentation.owner.misc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.WarrantyRepository
import com.hardwareshop.pos.domain.model.Product
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class WarrantyUiState(
    val records: List<WarrantyRepository.Record> = emptyList(),
    val products: List<Product> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val selected: WarrantyRepository.Record? = null,
    val editing: Boolean = false,
    val editingRecord: WarrantyRepository.Record? = null,
    val draft: WarrantyRepository.Draft = WarrantyRepository.Draft(),
    val addingService: Boolean = false,
    val serviceDate: String = LocalDate.now().toString(),
    val serviceProblem: String = "",
    val serviceAction: String = ""
)

@HiltViewModel
class WarrantyViewModel @Inject constructor(private val repository: WarrantyRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(WarrantyUiState())
    val state: StateFlow<WarrantyUiState> = mutableState.asStateFlow()
    private var recordsJob: Job? = null
    private var productsJob: Job? = null

    init { refresh() }

    fun refresh() {
        recordsJob?.cancel()
        productsJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        recordsJob = viewModelScope.launch {
            repository.observe().catch { error ->
                mutableState.update { it.copy(loading = false, records = emptyList(), error = error.message ?: "Cannot load the register") }
            }.collect { records ->
                mutableState.update { old ->
                    old.copy(records = records, loading = false,
                        selected = if (!old.editing && !old.addingService) records.firstOrNull { it.id == old.selected?.id } else old.selected)
                }
            }
        }
        productsJob = viewModelScope.launch {
            repository.observeProducts().catch { error ->
                mutableState.update { it.copy(products = emptyList(), error = error.message ?: "Cannot load products") }
            }.collect { products -> mutableState.update { it.copy(products = products) } }
        }
    }

    fun newRecord() = mutableState.update { old ->
        if (old.busy) old else old.copy(selected = null, editing = true, editingRecord = null,
            draft = WarrantyRepository.Draft(), addingService = false, error = null, message = null)
    }

    fun select(record: WarrantyRepository.Record) = mutableState.update { old ->
        if (old.busy) old else old.copy(selected = record, editing = false, addingService = false, error = null, message = null)
    }

    fun edit() = mutableState.update { old ->
        if (old.busy || old.selected == null) old else old.copy(editing = true, editingRecord = old.selected,
            draft = WarrantyRepository.Draft.from(old.selected), error = null, message = null)
    }

    fun cancel() = mutableState.update { old ->
        if (old.busy) old else if (old.addingService) old.copy(addingService = false, error = null)
        else if (old.editing) old.copy(editing = false, editingRecord = null, error = null)
        else old.copy(selected = null, error = null, message = null)
    }

    fun updateDraft(change: (WarrantyRepository.Draft) -> WarrantyRepository.Draft) = mutableState.update { old ->
        if (old.busy) old else old.copy(draft = change(old.draft), error = null)
    }

    fun save() {
        val old = mutableState.value
        if (old.busy || !old.editing) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = repository.save(old.draft, old.editingRecord)
                mutableState.update { it.copy(busy = false, selected = saved, editing = false, editingRecord = null, message = "Warranty record saved") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(busy = false, error = error.message ?: "Cannot save this record") }
            }
        }
    }

    fun beginService() = mutableState.update { old ->
        if (old.busy || old.selected == null) old else old.copy(addingService = true, serviceDate = LocalDate.now().toString(),
            serviceProblem = "", serviceAction = "", error = null, message = null)
    }

    fun updateService(date: String? = null, problem: String? = null, action: String? = null) = mutableState.update { old ->
        if (old.busy) old else old.copy(serviceDate = date ?: old.serviceDate, serviceProblem = problem ?: old.serviceProblem,
            serviceAction = action ?: old.serviceAction, error = null)
    }

    fun saveService() {
        val old = mutableState.value
        val record = old.selected ?: return
        if (old.busy || !old.addingService) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = repository.addService(record, old.serviceDate, old.serviceProblem, old.serviceAction)
                mutableState.update { it.copy(busy = false, selected = saved, addingService = false, message = "Service entry saved") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(busy = false, error = error.message ?: "Cannot add service") }
            }
        }
    }
}
