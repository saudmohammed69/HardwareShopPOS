package com.hardwareshop.pos.presentation.owner.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class InventoryManagerViewModel @Inject constructor(private val products:ProductRepository,
    private val manager:InventoryManagerRepository,private val session:SessionHolder):ViewModel() {
    val owner get()=session.current is UserSession.OwnerSession
    val catalogue=products.observeAllActiveProducts().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val categories=products.observeActiveCategories().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val _types=MutableStateFlow<List<ProductType>>(emptyList());val types=_types.asStateFlow()
    private val _busy=MutableStateFlow(false);val busy=_busy.asStateFlow()
    private val _message=MutableStateFlow<String?>(null);val message=_message.asStateFlow()
    private var typeJob:Job?=null
    fun category(id:Long) { typeJob?.cancel();typeJob=viewModelScope.launch { products.observeTypesForCategory(id).collect { _types.value=it } } }
    fun save(request:InventoryManagerRepository.Request,onSaved:()->Unit) {
        if(!owner || _busy.value) return
        _busy.value=true;_message.value=null
        viewModelScope.launch {
            try { val ids=manager.save(request);_message.value="Saved ${ids.size} variants. Available in billing.";onSaved() }
            catch(e:CancellationException){throw e}
            catch(e:Exception){_message.value=e.message ?: "Could not save inventory"}
            finally {_busy.value=false}
        }
    }
    fun createCategory(name:String,onCreated:(Long)->Unit) {
        if(!owner || _busy.value) return
        viewModelScope.launch { when(val result=products.createCategory(name)) {
            is Result.Success -> { category(result.data);onCreated(result.data) }
            is Result.Error -> _message.value=result.message
            else -> Unit
        } }
    }
    fun createType(categoryId:Long,name:String,onCreated:(Long)->Unit) {
        if(!owner || _busy.value) return
        viewModelScope.launch { when(val result=products.createProductType(categoryId,name)) {
            is Result.Success -> onCreated(result.data)
            is Result.Error -> _message.value=result.message
            else -> Unit
        } }
    }
}
