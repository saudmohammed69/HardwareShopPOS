package com.hardwareshop.pos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hardwareshop.pos.data.website.*
import com.hardwareshop.pos.domain.model.Result
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class WebsitePairedRestoreTest {
    @Test fun formatEightRestoreQuarantinesExternalAuthorityWithoutChangingProductSnapshot()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();check(context.packageName.endsWith(".debug")&&android.os.Build.HARDWARE in listOf("ranchu","goldfish"))
        val f=WebsiteNativeFixture(context);val key=WebsiteDeviceKey("hardwareshop.website.test.restore.${UUID.randomUUID()}");key.ensureCreated()
        val metadata=File(context.noBackupFilesDir,"website-pairing-local.json");check(!WebsiteRestoreQuarantine.hasAtomicState(context,metadata.name)&&!WebsiteRestoreQuarantine.blocked(context)){"Previous pairing uncertainty must be preserved for review"}
        val quarantine=File(context.noBackupFilesDir,"website-pairing-restore-quarantine.json")
        val legacy=File(context.noBackupFilesDir,"website-local-proof.json");val previousLegacy=legacy.takeIf{it.exists()}?.readBytes()
        var succeeded=false
        try{val original=f.seed();val uri=(f.backup().createBackup() as Result.Success).data
            val record=PairedLocalWebsiteTransport.Record(UUID.randomUUID().toString(),UUID.randomUUID().toString(),f.fixtureId,key.fingerprint())
            metadata.writeText(com.google.gson.Gson().toJson(record));val transport=PairedLocalWebsiteTransport(context,record,key);assertNotNull(transport.connection())
            assertTrue(f.backup().restoreBackup(uri) is Result.Success);assertTrue(quarantine.exists());assertNull(transport.connection())
            assertEquals(original.map{it.sellingPricePaisa to it.currentStock},f.db.productDao().getAllProducts().map{it.sellingPricePaisa to it.currentStock})
            // Incomplete pairing metadata must not select an older bearer fixture.
            legacy.writeText(com.google.gson.Gson().toJson(WebsiteFixtureConnection("device-a",f.fixtureId,"fixture-owner-a","fixture-worker-a")))
            assertNotNull(LocalWebsiteTransport(context).connection());assertTrue(metadata.delete())
            assertNull(com.hardwareshop.pos.di.WebsiteOrdersModule.transport(context).connection())
            assertTrue(quarantine.renameTo(File(quarantine.path+".bak")));assertTrue(WebsiteRestoreQuarantine.blocked(context))
            assertNull(transport.connection());assertNull(com.hardwareshop.pos.di.WebsiteOrdersModule.transport(context).connection())
            succeeded=true
        }finally{if(succeeded){metadata.delete();android.util.AtomicFile(quarantine).delete();key.deleteForLocalTest()};if(previousLegacy==null)legacy.delete() else legacy.writeBytes(previousLegacy);f.close(preserve=!succeeded)}
    }
}
