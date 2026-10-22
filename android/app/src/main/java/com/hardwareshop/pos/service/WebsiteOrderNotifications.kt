package com.hardwareshop.pos.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hardwareshop.pos.MainActivity
import com.hardwareshop.pos.data.website.WebsiteOrderRow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Distinct channel/notification IDs; never touches the Bluetooth print queue. */
@Singleton
class WebsiteOrderNotifications @Inject constructor(@ApplicationContext private val context:Context){
    val permitted:Boolean get()=(android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(context,android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&NotificationManagerCompat.from(context).areNotificationsEnabled()
    fun showBatch(rows:List<WebsiteOrderRow>):Boolean {
        val pending=rows.filter(::shouldAlert);if(pending.isEmpty())return false
        if(pending.size==1)return show(pending.single())
        val identity=java.util.UUID.nameUUIDFromBytes(pending.map{it.id}.sorted().joinToString("|").toByteArray()).toString()
        return show(pending.first().copy(id="batch-$identity"),pending.size)
    }
    fun show(row:WebsiteOrderRow,count:Int=1):Boolean {
        if(!permitted||!shouldAlert(row))return false
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Website orders",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_website_orders",true)
        val pending=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(if(count==1)"New website order" else "$count new website orders")
            .setContentText("Open Orders to review and check availability.").setContentIntent(pending).setOnlyAlertOnce(true).setAutoCancel(true).build()
        return try {manager.notify(row.id,0,notice);true}catch(_:SecurityException){false}
    }
    companion object {const val CHANNEL="website_orders";fun shouldAlert(row:WebsiteOrderRow)=row.status=="NEW"&&row.unread&&!row.alerted}
}

/** Website hints have their own required identifiers, never a print-job ID. */
object WebsiteOrderHint {
    fun valid(data:Map<String,String>):Boolean=data["type"]=="WEB_ORDER_EVENT"&&data["shopId"]=="proof-shop"&&
        runCatching{java.util.UUID.fromString(data["orderId"]);data["eventId"]?.matches(Regex("[A-Za-z0-9_-]{1,100}"))==true}.getOrDefault(false)
}
