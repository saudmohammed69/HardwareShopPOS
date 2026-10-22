package com.hardwareshop.pos.di

import android.content.Context
import com.hardwareshop.pos.data.local.dao.*
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.repository.*
import com.hardwareshop.pos.util.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// DATABASE MODULE
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.getInstance(context)

    @Provides fun provideSupplierDao(db: AppDatabase) = db.supplierDao()
    @Provides fun providePurchaseBillDao(db: AppDatabase) = db.purchaseBillDao()
    @Provides fun providePurchaseBillItemDao(db: AppDatabase) = db.purchaseBillItemDao()
    @Provides fun provideCategoryDao(db: AppDatabase)        = db.categoryDao()
    @Provides fun provideProductTypeDao(db: AppDatabase)     = db.productTypeDao()
    @Provides fun provideProductDao(db: AppDatabase)         = db.productDao()
    @Provides fun provideCustomerDao(db: AppDatabase)        = db.customerDao()
    @Provides fun provideWorkerDao(db: AppDatabase)          = db.workerDao()
    @Provides fun provideBillDao(db: AppDatabase)            = db.billDao()
    @Provides fun provideCreditDao(db: AppDatabase)          = db.creditDao()
    @Provides fun provideStockDao(db: AppDatabase)           = db.stockDao()
    @Provides fun provideAuditLogDao(db: AppDatabase)        = db.auditLogDao()
    @Provides fun provideSettingsDao(db: AppDatabase)        = db.settingsDao()
    @Provides fun provideOwnerCredentialDao(db: AppDatabase) = db.ownerCredentialDao()
    @Provides fun provideSyncDao(db: AppDatabase) = db.syncDao()
}

// ─────────────────────────────────────────────────────────────────────────────
// APP MODULE  — utilities, session, generators
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSessionHolder(): SessionHolder = SessionHolder()

    @Provides
    @Singleton
    fun provideBillNumberGenerator(db: AppDatabase): BillNumberGenerator =
        BillNumberGenerator(db)

    /**
     * BluetoothPrintManager is provided explicitly because it now takes
     * SettingsDao directly (in addition to SettingsRepository) to allow
     * writing the printer MAC address without triggering the owner-session
     * enforcement that SettingsRepository.set() applies.
     *
     * Hilt cannot infer which constructor to use when multiple injectable
     * parameters share the same type, so we provide it manually.
     */
    @Provides
    @Singleton
    fun provideBluetoothPrintManager(
        @ApplicationContext context: Context,
        settingsDao: SettingsDao,
        settingsRepository: SettingsRepository
    ): BluetoothPrintManager = BluetoothPrintManager(context, settingsDao, settingsRepository)

    // ImageManager uses @Inject constructor — Hilt binds it automatically.
    // No explicit @Provides needed.
}

// ─────────────────────────────────────────────────────────────────────────────
// REPOSITORY MODULE
// ─────────────────────────────────────────────────────────────────────────────
// Hilt auto-binds @Inject @Singleton repositories.

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    // All repositories use @Inject constructors + @Singleton — Hilt binds them automatically.
}

