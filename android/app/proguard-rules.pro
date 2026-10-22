# ─────────────────────────────────────────────────────────────────────────────
# Hardware Shop POS — ProGuard / R8 rules
# ─────────────────────────────────────────────────────────────────────────────

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepclassmembers class ** { @kotlin.jvm.JvmStatic *; }
-keepclassmembers class ** { @kotlin.jvm.JvmField *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Room (Entity classes must survive shrinking so SQL maps correctly) ─────────
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase { abstract *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Fts4 class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep interface * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ── Hilt / Dagger ─────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager** { *; }
-keepclasseswithmembernames class * { @dagger.* <fields>; }
-keepclasseswithmembernames class * { @javax.inject.* <fields>; }

# ── Gson (backup serialisation) ───────────────────────────────────────────────
-keepattributes Signature
-keepattributes EnclosingMethod
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep all entity classes used in backup payload
-keep class com.hardwareshop.pos.data.local.entity.** { *; }
# Persistent local operations and the public catalog use stable Gson field names.
-keep class com.hardwareshop.pos.data.repository.WantListRepository$Entry { *; }
-keep class com.hardwareshop.pos.data.repository.PipeOffcutRepository$Record { *; }
-keep class com.hardwareshop.pos.data.repository.PipeOffcutRepository$Sale { *; }
-keep class com.hardwareshop.pos.presentation.worker.billing.BillingViewModel$WeightCalibration { *; }
-keep class com.hardwareshop.pos.data.repository.CreditReminderRepository$Attempt { *; }
-keep class com.hardwareshop.pos.data.repository.BillConsolidationRepository$Record { *; }
-keep class com.hardwareshop.pos.data.repository.ShopCatalogRepository$DraftReference { *; }
-keep class com.hardwareshop.pos.util.ShopCatalog$* { *; }

# ── Timber ────────────────────────────────────────────────────────────────────
# Strip debug log calls from release builds
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# ── Coil ──────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── Bluetooth ─────────────────────────────────────────────────────────────────
-keep class android.bluetooth.** { *; }

# ── Compose (keep generated lambdas) ─────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Navigation ────────────────────────────────────────────────────────────────
-keep class androidx.navigation.** { *; }

# ── App-specific: domain models (used in ViewModels, survives obfuscation) ────
-keep class com.hardwareshop.pos.domain.model.** { *; }

# ── General Android ───────────────────────────────────────────────────────────
-keepclassmembers class * extends android.os.Parcelable { static ** CREATOR; }
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }
