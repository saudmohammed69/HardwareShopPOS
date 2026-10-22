package com.hardwareshop.pos

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.*
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.*
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.data.sync.*
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.*
import java.util.UUID

/** All financial databases here are named phase3b-proof-*, never the shop DB. */
internal class WebsiteNativeFixture(val context:Context,val device:String="device-a") {
    val fixtureId=UUID.randomUUID().toString()
    val mainName="phase3b-proof-${UUID.randomUUID()}.db"
    val cacheName="phase3b-cache-${UUID.randomUUID()}.db"
    val session=SessionHolder().apply{setSession(UserSession.OwnerSession("Synthetic native shop"))}
    var db=mainDatabase();private set
    var cache=cacheDatabase();private set
    val transport=WebsiteFixtureTransport(context,WebsiteFixtureConnection(device,fixtureId,"fixture-owner-${device.last()}","fixture-worker-${device.last()}"))
    fun products()=ProductRepository(db,db.categoryDao(),db.productTypeDao(),db.productDao(),db.stockDao(),AuditRepository(db.auditLogDao(),session),session)
    fun billing()=BillingRepository(db,db.billDao(),db.productDao(),db.creditDao(),AuditRepository(db.auditLogDao(),session),BillNumberGenerator(db),SettingsRepository(db.settingsDao(),db.ownerCredentialDao(),session),session)
    fun repository()=WebsiteOrdersRepository(db,cache,products(),billing(),session,transport)
    fun backup()=BackupManager(context,db,AuditRepository(db.auditLogDao(),session),session)
    private fun mainDatabase():AppDatabase {
        check(context.packageName.endsWith(".debug"))
        return Room.databaseBuilder(context,AppDatabase::class.java,mainName).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object:RoomDatabase.Callback(){override fun onOpen(sql:SupportSQLiteDatabase){(SyncJournalSchema.statements(true,true)+SyncTransferSchema.statements()).forEach(sql::execSQL)}}).build()
    }
    private fun cacheDatabase()=Room.databaseBuilder(context,WebsiteOrdersDatabase::class.java,cacheName).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    suspend fun seed():List<Product>{
        db.settingsDao().upsertSetting(SettingsEntity("website-local-fixture",fixtureId))
        db.settingsDao().upsertSetting(SettingsEntity("shop_name","Synthetic native shop"));db.settingsDao().upsertSetting(SettingsEntity("shop_phone","9876543210"));db.settingsDao().upsertSetting(SettingsEntity("shop_address","Synthetic test street"))
        val cat=db.categoryDao().insertCategory(CategoryEntity(name="Phase3B fixture ${UUID.randomUUID()}"))
        val type=db.productTypeDao().insertType(ProductTypeEntity(categoryId=cat,name="Synthetic variants"))
        val ids=listOf(
            ProductEntity(categoryId=cat,typeId=type,name="Native proof elbow ${fixtureId.take(8)}",size="1 inch",unit="Piece",sellingPricePaisa=12345,purchasePricePaisa=7777,currentStock=20.0,searchAliases="socket [[pos-variant:hidden-marker]]"),
            ProductEntity(categoryId=cat,typeId=type,name="Native proof PVC ${fixtureId.take(8)}",size="1 inch",unit="Length",sellingPricePaisa=40000,purchasePricePaisa=11111,gstRateBp=1800,currentStock=20.0,isPipe=true,pipeLengthPricePaisa=40000,pipeFootPricePaisa=3500,pipeFeetPerLength=20),
            ProductEntity(categoryId=cat,typeId=type,name="Native proof elbow ${fixtureId.take(8)}",size="3/4 inch",unit="Piece",sellingPricePaisa=8500,purchasePricePaisa=4444,currentStock=20.0)
        ).map{db.productDao().insertProduct(it)}
        return ids.map{requireNotNull(products().getProductById(it))}
    }
    suspend fun createOrder(publicId:String,quantity:String="2"):String {
        val body=json("submissionId" to UUID.randomUUID().toString(),"capability" to "b".repeat(64),"customer" to json("name" to "Synthetic pickup customer","phone" to "9876543210"),
            "items" to JsonArray().apply{add(json("productId" to publicId,"quantity" to quantity))})
        return transport.request("fixture/orders",body,true).string("id")
    }
    suspend fun save(items:List<CartItem>,checkout:String,discount:Long=0,payment:PaymentMethod=PaymentMethod.CASH)=billing().createBill(items,null,"Synthetic pickup customer","9876543210",discount,"",false,payment,items.sumOf{it.itemTotalPaisa},"Native local proof",checkoutId=checkout)
    suspend fun replicateProduct(source:WebsiteNativeFixture,publicId:String,targetProduct:Product){
        SyncJournal(db).mapIncoming(publicId,"products",targetProduct.id.toString())
        cache.orders().putProduct(WebsiteProductRow(publicId,targetProduct.id))
    }
    fun reopen(){cache.close();db.close();db=mainDatabase();cache=cacheDatabase()}
    fun close(preserve:Boolean=false){cache.close();db.close();if(!preserve){context.deleteDatabase(cacheName);context.deleteDatabase(mainName)}}
}

internal class WebsiteFixtureTransport(context:Context,private val fixture:WebsiteFixtureConnection):LocalWebsiteTransport(context){
    var offline=false;var loseClaim=false;var loseComplete=false;var requests=0
    val sentBodies=mutableListOf<Pair<String,JsonObject?>>()
    override fun connection()=fixture
    override suspend fun request(route:String,body:JsonObject?,owner:Boolean):JsonObject {
        requests++;synchronized(sentBodies){sentBodies+=route to body?.deepCopy()}
        check(!offline){"Synthetic network interruption"};val response=super.request(route,body,owner)
        if(route.endsWith("billing-claim")&&loseClaim){loseClaim=false;throw java.io.IOException("Synthetic lost claim response")}
        if(route.endsWith("complete")&&loseComplete){loseComplete=false;throw java.io.IOException("Synthetic lost completion response")}
        return response
    }
}
