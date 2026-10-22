package com.hardwareshop.pos.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hardwareshop.pos.data.website.WebsiteOrdersRepository
import com.hardwareshop.pos.util.SessionHolder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class WebsiteOrderHintWorker @AssistedInject constructor(@Assisted context:Context,@Assisted params:WorkerParameters,
    private val orders:WebsiteOrdersRepository,private val session:SessionHolder,private val alerts:WebsiteOrderNotifications):CoroutineWorker(context,params){
    override suspend fun doWork():Result {
        // A push is only a hint. A logged-out process must wait for authorized
        // foreground reconciliation; no private cache/financial writes from payloads.
        if(!orders.configured||!session.isLoggedIn)return Result.success()
        return try{orders.refresh();val pending=orders.snapshot().filter{WebsiteOrderNotifications.shouldAlert(it)};if(alerts.showBatch(pending))orders.markAlerts(pending.map{it.id});Result.success()}catch(e:CancellationException){throw e}catch(_:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
}
