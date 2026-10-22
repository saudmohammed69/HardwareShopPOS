package com.hardwareshop.pos.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hardwareshop.pos.data.local.database.AppDatabase
import com.hardwareshop.pos.data.local.entity.SettingsEntity
import com.hardwareshop.pos.domain.model.*
import com.hardwareshop.pos.util.ImageManager
import com.hardwareshop.pos.util.PermissionChecker
import com.hardwareshop.pos.util.SessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/** Owner workspace, separate from saleable products; included in the existing settings backup. */
@Singleton
class PendingProductRepository @Inject constructor(
    private val db: AppDatabase,
    private val products: ProductRepository,
    private val images: ImageManager,
    private val session: SessionHolder
) {
    private val gson = Gson()
    private val settings get() = db.settingsDao()
    private fun draftKey(id: String)  = "product:draft:$id"
    private fun doneKey(id: String)   = "product:done:$id"
    private fun reviewKey(id: Long)   = "product:review:$id"
    private fun owner() = PermissionChecker.enforceOwner(session.current)

    private fun decode(value: String): ProductDraft = try {
        val draft = requireNotNull(gson.fromJson(value, ProductDraft::class.java))
        require(draft.id.isNotBlank())
        draft.form.gaps(draft.ignoredOptional, validateStock = draft.productId == null)
        draft
    } catch (e: Exception) {
        throw IllegalStateException("A saved item draft could not be read. It has been kept; restore a valid backup to recover it.", e)
    }

    private fun decodeReview(value: String?): Set<String> = if (value.isNullOrBlank()) emptySet() else
        (gson.fromJson<Set<String>>(value, object : TypeToken<Set<String>>() {}.type) ?: emptySet())
            .intersect(ProductForm.OPTIONAL_KEYS)

    fun observePending(): Flow<List<PendingProduct>> = flow {
        owner()
        emitAll(combine(products.observeAllActiveProducts(), settings.observeProductWorkspace()) { list, notes ->
            owner()
            val values    = notes.associate { it.key to it.value }
            val drafts    = notes.filter { it.key.startsWith("product:draft:") }.map { decode(it.value) }
                .filter { doneKey(it.id) !in values }
            val editedIds = drafts.mapNotNull { it.productId }.toSet()

            // Existing saved products — show in pending only if they have non-purchase gaps.
            // Purchase price is fully optional and never appears as a gap, so a product
            // with only a missing purchase price is NOT shown in the pending list.
            val saved = list.filter { it.id !in editedIds }.mapNotNull { product ->
                val reviewed  = decodeReview(values[reviewKey(product.id)])
                val form      = ProductForm.fromProduct(product, reviewed)
                val withPhoto = form.copy(imagePath = form.imagePath?.takeIf { images.resolveFile(it) != null })
                // A saved catalogue product may legitimately have no size. Do not
                // return a priced item to Pending merely for that optional detail.
                val gaps      = withPhoto.gaps(reviewed, validateStock = false, requireSize = false)
                if (gaps.isEmpty()) null
                else PendingProduct("product:${product.id}", null, product.id, product.name, product.size, gaps)
            }

            drafts.sortedByDescending { it.updatedAt }.map {
                val form = it.form.copy(imagePath = it.form.imagePath?.takeIf { path -> images.resolveFile(path) != null })
                PendingProduct(draftKey(it.id), it.id, it.productId,
                    it.form.name.ifBlank { "Unnamed item" }, it.form.size,
                    form.gaps(it.ignoredOptional, validateStock = it.productId == null), it.updatedAt)
            } + saved
        }.flowOn(Dispatchers.IO))
    }

    suspend fun getDraft(id: String): ProductDraft? {
        owner()
        if (settings.getSetting(doneKey(id)) != null) return null
        return settings.getSetting(draftKey(id))?.let { decode(it.value) }
    }

    suspend fun getDraftForProduct(id: Long): ProductDraft? {
        owner()
        return settings.getProductWorkspace().filter { it.key.startsWith("product:draft:") }
            .map { decode(it.value) }.filter { it.productId == id }
            .sortedByDescending { it.updatedAt }.firstOrNull { settings.getSetting(doneKey(it.id)) == null }
    }

    suspend fun reviewedFields(id: Long): Set<String> {
        owner()
        return decodeReview(settings.getSetting(reviewKey(id))?.value)
    }

    suspend fun saveDraft(draft: ProductDraft) {
        owner()
        db.withTransaction {
            check(settings.getSetting(doneKey(draft.id)) == null) { "This draft has already been completed or discarded." }
            settings.upsertSetting(SettingsEntity(draftKey(draft.id), gson.toJson(draft)))
        }
    }

    suspend fun discard(id: String) {
        owner()
        db.withTransaction {
            settings.upsertSetting(SettingsEntity(doneKey(id), "discarded"))
            settings.deleteSetting(draftKey(id))
        }
    }

    /** Product, audit, optional-field review and draft removal commit together. */
    suspend fun complete(draft: ProductDraft, publish: suspend () -> Long): Long {
        owner()
        return db.withTransaction {
            check(settings.getSetting(doneKey(draft.id)) == null) { "This draft was already completed." }
            val id = publish()
            // Only persist what the owner explicitly acknowledged via the "Not needed" checkbox.
            // Purchase price is never emitted as a gap so it never needs to go into reviewed.
            val reviewed = draft.ignoredOptional.intersect(ProductForm.OPTIONAL_KEYS)
            settings.upsertSetting(SettingsEntity(reviewKey(id), gson.toJson(reviewed)))
            settings.upsertSetting(SettingsEntity(doneKey(draft.id), id.toString()))
            settings.deleteSetting(draftKey(draft.id))
            id
        }
    }
}
