package com.hardwareshop.pos.di

import com.hardwareshop.pos.data.local.dao.RemotePrintJobDao
import com.hardwareshop.pos.data.local.dao.ShopDeviceDao
import com.hardwareshop.pos.data.local.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for remote printing components.
 * FirebaseManager and RemotePrintRepository use @Inject constructors +
 * @Singleton — Hilt binds them automatically without explicit @Provides.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteModule {

    @Provides
    @Singleton
    fun provideRemotePrintJobDao(db: AppDatabase): RemotePrintJobDao =
        db.remotePrintJobDao()

    @Provides
    @Singleton
    fun provideShopDeviceDao(db: AppDatabase): ShopDeviceDao =
        db.shopDeviceDao()
}
