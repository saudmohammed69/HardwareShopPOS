package com.hardwareshop.pos.util

import com.hardwareshop.pos.service.WebsiteOrderHint
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WebsiteOrderHintTest {
    @Test fun websiteHintUsesIndependentOrderIdentityWithoutPrintJob(){
        val data=mapOf("type" to "WEB_ORDER_EVENT","shopId" to "proof-shop","orderId" to UUID.randomUUID().toString(),"eventId" to "fixture-event-1")
        assertTrue(WebsiteOrderHint.valid(data));assertFalse(WebsiteOrderHint.valid(data-"eventId"));assertFalse(WebsiteOrderHint.valid(data+ ("shopId" to "another-shop")))
        assertFalse(WebsiteOrderHint.valid(mapOf("type" to "PRINT_JOB","shopId" to "proof-shop","jobId" to UUID.randomUUID().toString())))
    }
}
