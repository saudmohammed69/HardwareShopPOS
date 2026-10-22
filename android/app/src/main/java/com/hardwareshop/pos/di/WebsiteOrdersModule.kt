package com.hardwareshop.pos.di

import android.content.Context
import androidx.room.Room
import com.hardwareshop.pos.data.website.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object WebsiteOrdersModule {
    @Provides @Singleton fun database(@ApplicationContext context:Context):WebsiteOrdersDatabase =
        Room.databaseBuilder(context,WebsiteOrdersDatabase::class.java,"website_orders_local.db")
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
    @Provides @Singleton fun transport(@ApplicationContext context:Context):WebsiteTransport {
        val pairedState=context.noBackupFilesDir.listFiles().orEmpty().any {
            it.name.startsWith("website-pairing-local.json")||it.name.startsWith("website-paired-rotation.json")||
                it.name.startsWith("website-pairing-restore-quarantine.json")||it.name.startsWith("website-pair-uncertain-")||it.name.startsWith("website-paired-operations-")
        }
        if(pairedState)return PairedLocalWebsiteTransport.open(context)?:object:WebsiteTransport {
            override fun connection():WebsiteFixtureConnection?=null
            override suspend fun request(route:String,body:com.google.gson.JsonObject?,owner:Boolean):com.google.gson.JsonObject=error("Pairing quarantined or unavailable; owner review required")
        }
        return LocalWebsiteTransport(context)
    }
}
