package com.hardwareshop.pos.presentation.owner.misc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class WantListState(val entries:List<WantListRepository.Entry> = emptyList(),val matches:List<Product> = emptyList(),
    val lowStock:List<Product> = emptyList(),val query:String="",val loaded:Boolean=false,val busy:Boolean=false,val error:String?=null,val message:String?=null)

@HiltViewModel
@OptIn(FlowPreview::class,ExperimentalCoroutinesApi::class)
class WantListViewModel @Inject constructor(private val repository:WantListRepository,products:ProductRepository,
    private val session:SessionHolder):ViewModel() {
    private val mutable=MutableStateFlow(WantListState())
    val state=mutable.asStateFlow()
    private val query=MutableStateFlow("")
    private val catalogue=MutableStateFlow<BillingCatalogue?>(null)
    init {
        viewModelScope.launch {repository.observe().catch {e->mutable.update {it.copy(error=e.message ?: "Could not load Want List")}}
            .collect {rows->mutable.update {it.copy(entries=rows,loaded=true)}}}
        viewModelScope.launch {products.observeBillingCatalogue().catch {e->mutable.update {it.copy(error=e.message ?: "Could not load products")}}
            .collect {prepared->catalogue.value=prepared;mutable.update {it.copy(lowStock=prepared.products.filter {p->p.isLowStock}.take(100))}}}
        viewModelScope.launch {combine(catalogue,query.debounce(120)){prepared,text->prepared to text}.collectLatest {(prepared,text)->
            val matches=if(text.isBlank())emptyList() else prepared?.search(text).orEmpty().take(20)
            if(query.value==text && catalogue.value===prepared)mutable.update {it.copy(matches=matches)}
        }}
    }
    fun search(text:String) {query.value=text.take(160);mutable.update {it.copy(query=query.value,matches=emptyList())}}
    fun dismissError(){mutable.update {it.copy(error=null,message=null)}}
    fun canEdit(entry:WantListRepository.Entry)=session.current is UserSession.OwnerSession ||
        (session.current as? UserSession.WorkerSession)?.workerId?.let {entry.createdBy=="worker:$it"}==true
    private fun action(work:suspend ()->String) {
        if(mutable.value.busy)return
        mutable.update {it.copy(busy=true,error=null,message=null)}
        viewModelScope.launch {try {val message=work();mutable.update {it.copy(message=message)}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update {it.copy(error=e.message ?: "Could not save Want List")}}
            finally {mutable.update {it.copy(busy=false)}}}
    }
    fun save(previous:WantListRepository.Entry?,productId:Long?,name:String,quantity:String,unit:String,notes:String,priority:Int,onSaved:()->Unit)=action {
        repository.save(previous,productId,name,quantity,unit,notes,priority);onSaved();"Want List item saved"
    }
    fun purchased(entry:WantListRepository.Entry,value:Boolean)=action {repository.setPurchased(entry,value);"Want List updated"}
    fun remove(entry:WantListRepository.Entry)=action {repository.remove(entry);"Want List item removed"}
    fun addLowStock(product:Product)=action {if(repository.quickAdd(product))"Added to Want List — review the quantity" else "This product is already pending in Want List"}
    fun shareText(entries:List<WantListRepository.Entry>)=repository.shareText(entries)
    fun error(message:String){mutable.update {it.copy(error=message)}}
}
