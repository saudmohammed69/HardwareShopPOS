package com.hardwareshop.pos.data.website

import androidx.room.withTransaction
import com.google.gson.*
import com.hardwareshop.pos.BuildConfig
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.SyncIdentityLookup
import com.hardwareshop.pos.data.sync.SyncJournal
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

internal fun JsonObject.string(key:String):String=get(key)?.takeUnless{it.isJsonNull}?.asString.orEmpty()
internal fun JsonObject.long(key:String):Long=get(key)?.takeUnless{it.isJsonNull}?.asLong?:0L
internal fun json(vararg fields:Pair<String,Any?>):JsonObject=Gson().toJsonTree(fields.toMap()).asJsonObject
data class WebsiteOrigin(val orderId:String,val billId:Long,val serverBillId:String,val device:String,
    val posBillSyncId:String,val pendingCompletion:Boolean=true)
data class WebsiteHandoff(val row:WebsiteOrderRow,val items:List<CartItem>,val customerName:String,val phone:String,val notes:String)

/** Only local synthetic integration is enabled. All financial writes still use
 * BillingRepository on its original AppDatabase and transaction. */
@Singleton
class WebsiteOrdersRepository @Inject constructor(
    private val db:AppDatabase,private val cache:WebsiteOrdersDatabase,private val products:ProductRepository,
    private val billing:BillingRepository,private val session:SessionHolder,private val transport:WebsiteTransport
) {
    private val dao get()=cache.orders()
    private val network=Mutex()
    val configured:Boolean get()=BuildConfig.DEBUG&&transport.connection()!=null
    fun observe():Flow<List<WebsiteOrderRow>> = if(configured)dao.observe().map{authorize();it} else flowOf(emptyList())
    private suspend fun authorize(owner:Boolean=false):WebsiteFixtureConnection {
        check(configured){"Local synthetic website connection is not configured. Production integration is disabled."}
        PermissionChecker.enforceLogin(session.current);if(owner)PermissionChecker.enforceOwner(session.current)
        (session.current as? UserSession.WorkerSession)?.let {
            val worker=db.workerDao().getWorkerById(it.workerId)
            check(worker?.isActive==true&&worker.username==it.username&&worker.maxDiscountPaisa==it.maxDiscountPaisa&&worker.requireOwnerApprovalForDiscount==it.requireOwnerApprovalForDiscount){"Worker permissions changed. Log in again."}
        }
        val c=checkNotNull(transport.connection())
        check(db.settingsDao().getSetting("website-local-fixture")?.value==c.fixtureDatabaseId){"Only the explicitly provisioned synthetic database may connect"}
        check(SyncJournal(db).linkedStore()==null){"Website fixture cannot connect a Firebase-linked shop"}
        return c
    }
    private suspend fun request(route:String,body:JsonObject?=null,owner:Boolean=false):JsonObject {
        authorize(owner);val selected=session.current
        val account=(selected as? UserSession.WorkerSession)?.let{db.workerDao().getWorkerById(it.workerId)}
        val response=transport.request(route,body,session.isOwner)
        check(session.current==selected&&(account==null||db.workerDao().getWorkerById(account.id)==account)){"Session changed during Orders request; refresh after authentication"}
        authorize(owner);return response
    }
    suspend fun row(id:String):WebsiteOrderRow {authorize();return requireNotNull(dao.order(id)){"Order is not cached; refresh Orders"}}
    suspend fun snapshot():List<WebsiteOrderRow>{authorize();return dao.all()}
    suspend fun publicationLabels():List<Pair<WebsiteProductRow,String>>{authorize(true);return dao.products().map{it to (products.getProductById(it.localId)?.displayLabel?:"Inactive product")}}
    suspend fun markRead(id:String){authorize();cache.withTransaction{dao.order(id)?.let{dao.put(it.copy(unread=false))}}}
    suspend fun markAlerted(id:String){authorize();cache.withTransaction{dao.order(id)?.let{dao.put(it.copy(alerted=true))}}}
    suspend fun markAlerts(ids:List<String>){authorize();cache.withTransaction{for(id in ids)dao.order(id)?.let{dao.put(it.copy(alerted=true))}}}
    suspend fun checkLine(id:String,productId:String,checked:Boolean){authorize();cache.withTransaction{
        val r=requireNotNull(dao.order(id));check(r.status in listOf("ACCEPTED","PREPARING")){"Accept the order before preparing"}
        val doc=JsonParser.parseString(r.document).asJsonObject
        require(doc.getAsJsonArray("items").any{it.asJsonObject.string("productId")==productId})
        val set=JsonParser.parseString(r.checklist).asJsonArray.map{it.asString}.toMutableSet()
        if(checked)set.add(productId)else set.remove(productId)
        dao.put(r.copy(checklist=Gson().toJson(set)))
    }}
    suspend fun applyPage(page:JsonObject,beforeCursorCommit:(()->Unit)?=null) {
        authorize();val events=page.getAsJsonArray("events");require(events.size()<=100)
        val next=page.long("cursor");cache.withTransaction {
            val previous=dao.cursor()?.sequence?:0L;require(next>=previous){"Cursor cannot move backwards"}
            for(element in events){val e=element.asJsonObject;val id=e.string("id");UUID.fromString(id)
                val revision=e.long("revision");val seq=e.long("seq");val status=e.string("status")
                require(revision>0&&seq>0&&seq<=next&&status in STATUSES)
                val doc=e.getAsJsonObject("doc");val archived=doc.has("archiveRef")
                if(archived)require(status in TERMINAL&&doc.string("archiveRef").matches(Regex("[a-f0-9]{64}")))
                else {
                    require(doc.getAsJsonArray("items").size() in 1..25)
                    val customer=doc.getAsJsonObject("customer");require(customer.string("name").length in 1..100&&customer.string("phone").matches(Regex("[6-9][0-9]{9}")))
                    for(line in doc.getAsJsonArray("items")){val l=line.asJsonObject;require(l.string("productId").length in 1..100&&l.string("name").length in 1..200);require((l.string("estimatedLineTotalPaisa").toLongOrNull()?:-1)>=0)}
                }
                require((doc.string("estimatedSubtotalPaisa").toLongOrNull()?:-1)>=0&&doc.toString().toByteArray().size<=131072)
                val old=dao.order(id);if(old!=null&&old.revision>=revision)continue
                val origin=origin(id)
                val itemsChanged=old!=null&&runCatching{JsonParser.parseString(old.document).asJsonObject["items"]!=doc["items"]}.getOrDefault(true)
                dao.put(WebsiteOrderRow(id,revision,if(origin!=null)"COMPLETED" else status,doc.toString(),seq,e.long("updated_at"),
                    unread=if(status in TERMINAL||origin!=null)false else old?.unread?: (status=="NEW"),alerted=old?.alerted?:false,
                    checklist=if(itemsChanged)"[]" else old?.checklist?:"[]",claimDevice=e.string("lock_device").ifBlank{null},serverBillId=e.string("bill_id").ifBlank{null}))
            }
            beforeCursorCommit?.invoke();dao.putCursor(WebsiteCursor(sequence=next))
        }
    }
    suspend fun refresh()=network.withLock {
        authorize()
        // A single interrupted completion must not prevent other orders reaching
        // the inbox. Report the saved-bill problem after independently catching up.
        var completionError:Exception?=null
        try{flushCompletions()}catch(e:CancellationException){throw e}catch(e:Exception){completionError=e}
        var more:Boolean
        do {val page=request("pos/events?cursor=${dao.cursor()?.sequence?:0}");applyPage(page);more=page.get("hasMore")?.asBoolean?: (page.getAsJsonArray("events").size()==100)}while(more)
        reconcileLocalBills()
        completionError?.let{throw it}
    }
    suspend fun action(id:String,action:String,reason:String="",availabilityChecked:Boolean=false) {
        require(action in listOf("accept","prepare","ready","cancel"));val r=row(id)
        if(action=="accept")check(availabilityChecked){"Manually check availability before accepting"}
        if(action=="ready"){
            val required=JsonParser.parseString(r.document).asJsonObject.getAsJsonArray("items").map{it.asJsonObject.string("productId")}.toSet()
            check(JsonParser.parseString(r.checklist).asJsonArray.map{it.asString}.containsAll(required)){"Check every prepared item before marking ready"}
        }
        val body=json("expectedRevision" to r.revision);if(action=="cancel"){require(reason.isNotBlank());body.addProperty("reason",reason)}
        try{request("orders/$id/$action",body)}finally{runCatching{refresh()}}
    }
    suspend fun adjust(id:String,items:JsonArray,reason:String,confirmed:Boolean){
        val r=row(id);check(confirmed){"Confirm changes with the customer first"};require(reason.isNotBlank())
        request("orders/$id/adjust",json("expectedRevision" to r.revision,"items" to items,"reason" to reason,"customerConfirmed" to confirmed));refresh()
    }
    suspend fun publishCatalog():Int {
        authorize(true);check(storage().get("acceptNew").asBoolean){"Storage safety cutoff reached. Catalog publication stopped; active orders remain protected."};val catalogue=products.observeAllActiveProducts().first()
        var count=0
        val existingMappings=dao.products().associateBy{it.localId}
        val pending=mutableListOf<Pair<Product,WebsiteProductRow>>()
        for(p in catalogue){
            // SyncJournal owns the identity. Never use another phone's numeric product ID.
            val existingMapping=existingMappings[p.id]
            val publicId=existingMapping?.publicId?:db.withTransaction{db.openHelper.readableDatabase.query("SELECT syncId FROM sync_foreign_identity WHERE tableName='products' AND localKey=? UNION ALL SELECT syncId FROM sync_identity WHERE tableName='products' AND localKey=? AND deleted=0 LIMIT 1",arrayOf<Any>(p.id.toString(),p.id.toString())).use{if(it.moveToFirst())it.getString(0)else null}}
            check(!publicId.isNullOrBlank()){ "Product ${p.name} has no stable sync identity; publication stopped" }
            val saved=dao.product(publicId);val mapping=WebsiteProductRow(publicId,p.id,saved?.published?:true);dao.putProduct(mapping)
            pending+=p to mapping;count++
        }
        // Deactivated products are hidden; records/prices in the POS are never changed.
        val activeIds=catalogue.map{it.id}.toSet()
        for(m in dao.products().filter{it.localId !in activeIds&&it.published})products.getProductById(m.localId)?.let{p->val hidden=m.copy(published=false);pending+=p to hidden;dao.putProduct(hidden)}
        var batch=JsonArray()
        suspend fun flush(){if(batch.size()>0){request("catalog/publish-batch",json("products" to batch),true);batch=JsonArray()}}
        for((p,m) in pending){val projection=projection(p,m)
            if(batch.size()>=40 || (batch.toString().toByteArray().size+projection.toString().toByteArray().size)>28000)flush()
            batch.add(projection)
        }
        flush();return count
    }
    private fun projection(p:Product,m:WebsiteProductRow):JsonObject {
        val product=json("id" to m.publicId,"name" to p.name,"category" to p.categoryName,"type" to p.typeName,"size" to p.size,"unit" to p.unit,
            "aliases" to p.searchAliases.replace(Regex("\\[\\[pos-[^\\]]*\\]\\]"),""),"sellingPricePaisa" to p.sellingPricePaisa.toString(),"gstRateBp" to p.gstRateBp,"isPipe" to p.isPipe)
        if(p.isPipe){product.addProperty("feetPerLength",p.pipeFeetPerLength);product.addProperty("lengthPricePaisa",p.pipeLengthPricePaisa.toString());product.addProperty("footPricePaisa",p.pipeFootPricePaisa.toString())}
        return json("product" to product,"published" to m.published)
    }
    suspend fun publications():List<WebsiteProductRow>{authorize(true);return dao.products()}
    suspend fun setPublished(id:String,visible:Boolean){authorize(true);val m=requireNotNull(dao.product(id));val p=requireNotNull(products.getProductById(m.localId));require(!visible||p.isActive);dao.putProduct(m.copy(published=visible));request("catalog/publish",projection(p,m.copy(published=visible)),true)}
    suspend fun publishSettings(openMinute:Int,closeMinute:Int,maintenance:Boolean){
        authorize(true);require(openMinute in 0..1439&&closeMinute in 1..1440&&openMinute<closeMinute)
        val settings=db.settingsDao();val info=SettingsRepository(settings,db.ownerCredentialDao(),session).getShopInfo()
        settings.upsertSetting(SettingsEntity("website-opening-minute",openMinute.toString()));settings.upsertSetting(SettingsEntity("website-closing-minute",closeMinute.toString()));settings.upsertSetting(SettingsEntity("website-maintenance",maintenance.toString()))
        request("owner/public-config",json("name" to info.name.ifBlank{"HardwareShopPOS"},"phone" to info.phone,"address" to info.address,"openMinute" to openMinute,"closeMinute" to closeMinute,"maintenance" to maintenance),true)
    }
    suspend fun hours():Triple<Int,Int,Boolean>{authorize(true);val s=db.settingsDao();return Triple(s.getSetting("website-opening-minute")?.value?.toIntOrNull()?:540,s.getSetting("website-closing-minute")?.value?.toIntOrNull()?:1200,s.getSetting("website-maintenance")?.value=="true")}
    suspend fun storage():JsonObject=request("owner/storage",owner=true)
    suspend fun prepareHandoff(id:String):WebsiteHandoff {
        val c=authorize();val r=row(id);check(origin(id)==null){"This order already has a saved bill; open its Bill History link"}
        check(r.status in listOf("READY_FOR_PICKUP","BILLING_IN_PROGRESS")){"Accept, prepare and mark ready before billing"}
        val doc=JsonParser.parseString(r.document).asJsonObject;check(!doc.has("archiveRef")){"Archived orders cannot be billed"}
        val items=doc.getAsJsonArray("items").map { element ->
            val line=element.asJsonObject;val publicId=line.string("productId")
            val mapped=dao.product(publicId)?.localId?:db.withTransaction{SyncIdentityLookup.reference(db.openHelper.readableDatabase,"products",publicId)?.toLongOrNull()}
            val p=mapped?.let{products.getProductById(it)};check(p?.isActive==true){"${line.string("name")} is not mapped to an active local product. Publish/synchronize the catalog first."}
            check(p.size.trim()==line.string("size").trim()&&p.unit.equals(line.string("unit"),true)){"Variant size or unit changed. Confirm the revised item in Orders before starting billing."}
            if(p.isPipe)PipeBilling.item(p,line.string("fullLengths").toLong(),line.string("extraFeet").toDouble()).also{item->
                check(java.math.BigDecimal.valueOf(item.quantity).compareTo(java.math.BigDecimal(line.string("inventoryUnitEquivalent")))==0){"Pipe length/unit conversion changed. Confirm the revised item in Orders before starting billing."}
            }
            else {val q=line.string("quantity").toDouble();CartItem(p,q,p.sellingPricePaisa,CalculationEngine.itemTotalPaisa(q,p.sellingPricePaisa))}
        }
        // Persist intent before the first network claim. Token/device never enter backups.
        val intent=cache.withTransaction{dao.claim(id)?:WebsiteClaim(id,c.deviceId,newToken(),orderRevision=r.revision).also{dao.putClaim(it)}}
        check(intent.device==c.deviceId){"Billing belongs to another installation"}
        val claimed=request("orders/$id/billing-claim",json("expectedRevision" to r.revision,"claimToken" to intent.token))
        check(claimed.string("status")=="BILLING_IN_PROGRESS"){"Order is already completed; refresh and reconcile its existing bill"}
        val billId=claimed.string("billId");UUID.fromString(billId)
        dao.putClaim(intent.copy(serverBillId=billId,revision=claimed.long("revision"),orderRevision=r.revision))
        refresh();val current=row(id);check(current.claimDevice==c.deviceId&&current.serverBillId==billId){"Billing ownership must be confirmed before opening cart"}
        val customer=doc.getAsJsonObject("customer")
        return WebsiteHandoff(current,items,customer.string("name"),customer.string("phone"),doc.string("note"))
    }
    /** Wrapper joins the original billing transaction; no second bill/payment/stock engine. */
    suspend fun commit(id:String,cart:List<CartItem>,save:suspend(String)->com.hardwareshop.pos.domain.model.Result<Bill>,beforeOriginCommit:(()->Unit)?=null):com.hardwareshop.pos.domain.model.Result<Bill> {
        return try {
            val c=authorize();val claim=requireNotNull(dao.claim(id)){"Acquire online billing ownership first"}
            check(claim.device==c.deviceId&&!claim.serverBillId.isNullOrBlank()){ "Billing claim is incomplete; recover it before saving" }
            val result=db.withTransaction {
                origin(id)?.let{return@withTransaction com.hardwareshop.pos.domain.model.Result.Success(requireNotNull(billing.getBillById(it.billId)))}
                val row=row(id);check(row.status=="BILLING_IN_PROGRESS"&&row.claimDevice==c.deviceId&&row.serverBillId==claim.serverBillId){"Order is not owned by this installation"}
                val expected=JsonParser.parseString(row.document).asJsonObject.getAsJsonArray("items")
                // Quantities/items must be customer-confirmed in Orders; final POS prices/GST/discount/payment remain the existing rules.
                check(cart.size==expected.size()){"Reload the confirmed order from Orders before saving; ask the owner to review further item changes"}
                for(line in expected){val l=line.asJsonObject;val map=dao.product(l.string("productId"))?.localId?:SyncIdentityLookup.reference(db.openHelper.readableDatabase,"products",l.string("productId"))?.toLongOrNull()
                    val item=cart.singleOrNull{it.product.id==map};check(item!=null){"Reload the confirmed order from Orders before saving"}
                    check(if(item.product.isPipe)item.pipeLengths==l.string("fullLengths").toLong()&&java.math.BigDecimal.valueOf(item.pipeFeet).compareTo(java.math.BigDecimal(l.string("extraFeet")))==0 else java.math.BigDecimal.valueOf(item.quantity).compareTo(java.math.BigDecimal(l.string("quantity")))==0){"Reload confirmed quantities from Orders before saving; ask the owner to review further changes"}
                }
                val saved=save("web-"+id)
                if(saved is com.hardwareshop.pos.domain.model.Result.Error)throw IllegalStateException(saved.message)
                val bill=(saved as com.hardwareshop.pos.domain.model.Result.Success).data
                val syncId=db.openHelper.readableDatabase.query("SELECT syncId FROM sync_identity WHERE tableName='bills' AND localKey=?",arrayOf<Any>(bill.id.toString())).use{check(it.moveToFirst()){ "Bill sync identity is missing" };it.getString(0)}
                beforeOriginCommit?.invoke()
                val origin=WebsiteOrigin(id,bill.id,requireNotNull(claim.serverBillId),c.deviceId,syncId)
                db.settingsDao().upsertSetting(SettingsEntity(originKey(id),Gson().toJson(origin)))
                com.hardwareshop.pos.domain.model.Result.Success(bill)
            }
            // A crash here is recovered from the origin/outbox in the accounting DB.
            runCatching{reconcileLocalBills()};result
        }catch(e:CancellationException){throw e}catch(e:Exception){com.hardwareshop.pos.domain.model.Result.Error(e.message?:"Website billing failed; order and cart are recoverable",e)}
    }
    internal suspend fun origin(id:String):WebsiteOrigin?=db.settingsDao().getSetting(originKey(id))?.let{Gson().fromJson(it.value,WebsiteOrigin::class.java)}
    suspend fun billLink(id:String):Long? {
        authorize();origin(id)?.let{return it.billId};val row=dao.order(id)?:return null
        val global=JsonParser.parseString(row.document).asJsonObject.string("finalizedPosBillSyncId");if(global.isBlank())return null
        return db.withTransaction{SyncIdentityLookup.reference(db.openHelper.readableDatabase,"bills",global)?.toLongOrNull()}
    }
    suspend fun reconcileLocalBills(){authorize();for(r in dao.all())origin(r.id)?.let{origin->
        val doc=JsonParser.parseString(r.document).asJsonObject
        if(r.status!="COMPLETED"||doc.get("localCompletionPending")?.asBoolean!=origin.pendingCompletion){doc.addProperty("localCompletionPending",origin.pendingCompletion);dao.put(r.copy(status="COMPLETED",unread=false,document=doc.toString()))}
    }}
    suspend fun flushCompletions(){
        val c=authorize()
        for(setting in db.settingsDao().getFeatureRecords(ORIGIN_PREFIX)){
            val origin=Gson().fromJson(setting.value,WebsiteOrigin::class.java);if(!origin.pendingCompletion)continue
            val claim=checkNotNull(dao.claim(origin.orderId)){"Saved bill completion needs owner reconciliation; claim was not restored. Never bill it again."}
            check(origin.device==c.deviceId&&claim.device==c.deviceId){"Another installation owns saved-bill synchronization"}
            checkNotNull(billing.getBillById(origin.billId)){"Saved bill is missing; owner reconciliation required"}
            request("orders/${origin.orderId}/complete",json("expectedRevision" to claim.revision,"claimToken" to claim.token,"billId" to origin.serverBillId,"posBillSyncId" to origin.posBillSyncId))
            db.withTransaction{val latest=requireNotNull(this@WebsiteOrdersRepository.origin(origin.orderId));db.settingsDao().upsertSetting(setting.copy(value=Gson().toJson(latest.copy(pendingCompletion=false))))}
        }
    }
    companion object {
        const val ORIGIN_PREFIX="website-origin:proof-shop:"
        fun originKey(id:String)=ORIGIN_PREFIX+id
        val TERMINAL=setOf("COMPLETED","CANCELLED")
        val STATUSES=setOf("NEW","ACCEPTED","PREPARING","READY_FOR_PICKUP","BILLING_IN_PROGRESS","COMPLETED","CANCELLED")
        private fun newToken()=ByteArray(32).also{java.security.SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
    }
}
