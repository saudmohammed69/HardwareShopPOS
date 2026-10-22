package com.hardwareshop.pos.presentation.owner.misc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.PipeCutPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class PipeToolsState(val products:List<Product> = emptyList(),val offcuts:List<PipeOffcutRepository.Record> = emptyList(),
    val selected:Product?=null,val plan:PipeCutPlanner.Plan?=null,val operationId:String="",val busy:Boolean=false,
    val error:String?=null,val message:String?=null)

@HiltViewModel
class PipeToolsViewModel @Inject constructor(private val products:ProductRepository,
    private val offcuts:PipeOffcutRepository):ViewModel() {
    private val mutable=MutableStateFlow(PipeToolsState())
    val state=mutable.asStateFlow()
    init {
        viewModelScope.launch {
            products.observeAllActiveProducts().collectLatest { rows ->
                val pipes=products.canonicalBillingProducts(rows).filter { it.isPipe }
                mutable.update { it.copy(products=pipes,selected=it.selected?.let { old->pipes.firstOrNull { p->p.id==old.id } }) }
            }
        }
        viewModelScope.launch {
            offcuts.observe().catch { e->mutable.update { it.copy(error=e.message ?: "Could not load offcuts") } }
                .collect { rows->mutable.update { it.copy(offcuts=rows) } }
        }
    }
    fun select(product:Product) { mutable.update { it.copy(selected=product,plan=null,error=null,message=null) } }
    fun invalidatePlan() { mutable.update { it.copy(plan=null,message=null) } }
    fun dismissError() { mutable.update { it.copy(error=null) } }
    private fun action(work:suspend ()->String) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null,message=null) }
        viewModelScope.launch {
            try { val message=work();mutable.update { it.copy(message=message) } }
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update { it.copy(error=e.message ?: "Could not save") }}
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun add(length:String,location:String)=action {
        val product=requireNotNull(mutable.value.selected) { "Select a pipe first" }
        offcuts.add(product,length,location);mutable.update { it.copy(plan=null) };"Offcut saved"
    }
    fun discard(record:PipeOffcutRepository.Record)=action {offcuts.discard(record);mutable.update {it.copy(plan=null)};"Offcut discarded and stock adjusted once"}
    fun remove(record:PipeOffcutRepository.Record)=action { offcuts.remove(record);mutable.update { it.copy(plan=null) };"Offcut removed" }
    fun use(record:PipeOffcutRepository.Record,length:String)=action {
        val product=mutable.value.products.firstOrNull { it.id==record.productId } ?: error("Pipe is unavailable")
        val requested=PipeCutPlanner.feet(length)
        require(requested<=record.feet) { "Requested length exceeds this offcut" }
        val plan=PipeCutPlanner.plan(product.id,listOf(requested),listOf(record.piece()))
        offcuts.recordCuts(plan,product,java.util.UUID.randomUUID().toString())
        mutable.update { it.copy(plan=null) };"Piece use recorded; remaining length saved"
    }
    fun calculate(lengths:String,allowance:String)=action {
        val snapshot=mutable.value
        val product=requireNotNull(snapshot.selected) { "Select a pipe first" }
        require(product.pipeFeetPerLength==20) { "This planner uses 20-ft pipes. Check the product's configured length." }
        val recorded=offcuts.snapshot()
        val kerf=if(allowance.isBlank() || allowance.matches(Regex("0(?:\\.0{1,2})?"))) 0 else PipeCutPlanner.feet(allowance)
        val plan=withContext(Dispatchers.Default) {
            PipeCutPlanner.plan(product.id,PipeCutPlanner.requests(lengths),recorded.filter {it.available}.map { it.piece() },kerf)
        }
        mutable.update { it.copy(plan=plan,operationId=java.util.UUID.randomUUID().toString()) }
        "Plan ready — check the pieces before cutting"
    }
    fun recordPlan()=action {
        val snapshot=mutable.value
        offcuts.recordCuts(requireNotNull(snapshot.plan),requireNotNull(snapshot.selected),snapshot.operationId)
        mutable.update { it.copy(plan=null) };"Cuts recorded and leftovers saved"
    }
}
