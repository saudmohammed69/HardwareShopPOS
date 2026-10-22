package com.hardwareshop.pos.presentation.website

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.service.WebsiteOrderNotifications
import com.hardwareshop.pos.util.SessionHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class WebsiteOrdersState(val rows:List<WebsiteOrderRow> = emptyList(),val busy:Boolean=false,val error:String?=null,
    val message:String?=null,val storage:String?=null,val handoff:WebsiteHandoff?=null,
    val publications:List<Pair<WebsiteProductRow,String>> = emptyList(),val hours:Triple<Int,Int,Boolean>?=null)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class WebsiteOrdersViewModel @Inject constructor(private val orders:WebsiteOrdersRepository,
    private val session:SessionHolder,private val alerts:WebsiteOrderNotifications):ViewModel(){
    private val mutable=MutableStateFlow(WebsiteOrdersState())
    val state=mutable.asStateFlow()
    val configured get()=orders.configured
    val owner get()=session.isOwner
    val authenticated get()=session.isLoggedIn
    val alertsPermitted get()=alerts.permitted
    init {viewModelScope.launch{session.sessions.flatMapLatest{if(session.isLoggedIn)orders.observe().catch{e->mutable.update{it.copy(error="Local Orders cache unavailable. Counter billing remains available. ${e.message}")};emit(emptyList())}else flowOf(emptyList())}.collect{rows->mutable.update{it.copy(rows=rows)}}}}
    private fun task(block:suspend()->Unit){if(mutable.value.busy)return;mutable.update{it.copy(busy=true,error=null)};viewModelScope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(error=e.message?:"Could not update Orders. Refresh before retrying.")}}finally{mutable.update{it.copy(busy=false)}}}}
    suspend fun foregroundRefresh(){if(!configured||!authenticated||mutable.value.busy)return;try{refreshNow()}catch(e:CancellationException){throw e}catch(e:Exception){mutable.update{it.copy(error="Orders may be out of date. ${e.message}")}}}
    private suspend fun refreshNow(){orders.refresh();val pending=orders.snapshot().filter{WebsiteOrderNotifications.shouldAlert(it)};if(alerts.showBatch(pending))orders.markAlerts(pending.map{it.id})
        if(owner){val s=orders.storage();mutable.update{it.copy(storage="${s.string("level")}: ${s.long("bytes")/1000000} MB used. Warning 325 MB · critical 400 MB · stop new orders 450 MB. Active/uncollected orders are protected.")}}
        mutable.update{it.copy(message="Orders refreshed",error=null)}
    }
    fun refresh()=task{refreshNow()}
    fun read(id:String)=task{orders.markRead(id)}
    fun action(id:String,action:String,reason:String="",available:Boolean=false)=task{orders.action(id,action,reason,available);mutable.update{it.copy(message="Order updated")}}
    fun check(id:String,product:String,value:Boolean)=task{orders.checkLine(id,product,value)}
    fun adjust(id:String,items:JsonArray,reason:String,confirmed:Boolean)=task{orders.adjust(id,items,reason,confirmed);mutable.update{it.copy(message="Customer-confirmed changes saved")}}
    fun handoff(id:String)=task{mutable.update{it.copy(handoff=orders.prepareHandoff(id))}}
    fun publish()=task{val n=orders.publishCatalog();mutable.update{it.copy(publications=orders.publicationLabels(),message="$n active product variants published safely")}}
    fun publications()=task{mutable.update{it.copy(publications=orders.publicationLabels())}}
    fun visible(id:String,value:Boolean)=task{orders.setPublished(id,value);mutable.update{it.copy(publications=orders.publicationLabels())}}
    fun settings(open:Int,close:Int,maintenance:Boolean)=task{orders.publishSettings(open,close,maintenance);mutable.update{it.copy(message="Public shop details and daily hours saved")}}
    fun loadHours()=task{mutable.update{it.copy(hours=orders.hours())}}
    fun history(id:String,open:(Long)->Unit)=task{val bill=orders.billLink(id);if(bill==null)mutable.update{it.copy(error="The finalized bill has not synchronized to this installation. Never create a replacement bill.")}else open(bill)}
}
