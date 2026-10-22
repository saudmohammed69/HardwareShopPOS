package com.hardwareshop.pos
import com.google.gson.JsonParser
import com.hardwareshop.pos.domain.model.Product
import com.hardwareshop.pos.util.PipeBilling
import java.io.File
import org.junit.Test
import org.junit.Assert.*

class WebsitePipeParityTest {
    @Test fun localBackendFixturesMatchTheActualPosPipeEngine() {
        val file=listOf(File("tools/website-proof/fixtures/pipe.json"),File("../tools/website-proof/fixtures/pipe.json")).first {it.isFile}
        JsonParser.parseString(file.readText()).asJsonArray.forEach {element->
            val c=element.asJsonObject
            val p=Product(1,1,"Pipes",1,"PVC","Synthetic pipe","1 inch",c["unit"].asString,40000,0,0,10.0,0.0,null,
                isActive=true,isPipe=true,pipeLengthPricePaisa=c["lengthPrice"].asLong,pipeFootPricePaisa=c["footPrice"].asLong,pipeFeetPerLength=c["physical"].asInt)
            try {
                val item=PipeBilling.item(p,c["lengths"].asLong,c["feet"].asDouble)
                assertFalse("Expected invalid pipe fixture: $c",c.has("error"))
                assertEquals(c["total"].asLong,item.itemTotalPaisa)
                assertEquals(c["quantity"].asDouble,item.quantity,0.000000001)
            } catch(e:IllegalArgumentException){assertTrue(c.toString(),c.has("error"))}
              catch(e:ArithmeticException){assertTrue(c.toString(),c.has("error"))}
        }
    }
}
