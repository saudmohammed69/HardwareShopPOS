package com.hardwareshop.pos.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.remote.*
import com.hardwareshop.pos.data.repository.AuthRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Result
import com.hardwareshop.pos.util.SessionHolder
import com.hardwareshop.pos.util.UserSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CloudLoginUiState(
    val ready:Boolean=false, val loading:Boolean=false,
    val hasLocalOwner:Boolean=false, val status:String="", val error:String?=null,
    val ownerHome:Boolean=false, val workerLogin:Boolean=false
)

@HiltViewModel
class CloudLoginViewModel @Inject constructor(
    private val cloud:CloudAccountRepository, private val local:AuthRepository,
    private val settings:SettingsRepository,
    private val saved:CloudSessionStore, private val session:SessionHolder,
    private val sync:CloudSyncRepository
) : ViewModel() {
    private val _state=MutableStateFlow(CloudLoginUiState())
    val state=_state.asStateFlow()
    init { viewModelScope.launch {
        val owner=local.isOwnerSetup()
        _state.update { it.copy(ready=true,hasLocalOwner=owner) }
    } }
    fun connect(email:String,password:String,create:Boolean,workerPhone:Boolean) {
        if(!_state.value.ready || _state.value.loading) return
        _state.update { it.copy(loading=true,error=null,status="Connecting your shop…") }
        viewModelScope.launch {
            try {
                require(android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { "Enter a valid email address" }
                val passwordValidation=com.hardwareshop.pos.util.SecurityManager.validatePassword(password)
                require(passwordValidation !is com.hardwareshop.pos.util.SecurityManager.ValidationResult.Error) {
                    (passwordValidation as com.hardwareshop.pos.util.SecurityManager.ValidationResult.Error).message
                }
                if(create) cloud.createFromLogin(email,password) else cloud.signInFromLogin(email,password)
                // Verified cloud OWNER account; keep all pre-existing local credentials.
                session.setSession(UserSession.OwnerSession(settings.getShopInfo().name))
                if(!local.isOwnerSetup()) {
                    val result=local.setupOwner(password,settings.getShopInfo().name.ifBlank { "Shop" },"","","")
                    check(result is Result.Success) { "Shop connected. Set up offline access before continuing." }
                }
                saved.remember()
                _state.update { it.copy(status="Syncing products, stock and bills…") }
                // Background scheduling already started at connection. This bounded first
                // pass gives immediate data without blocking billing for a large history.
                withTimeoutOrNull(30_000) {
                    try { sync.runOnce() }
                    catch(e:CancellationException) { throw e }
                    catch(_:Exception) { /* Pending records remain durable for automatic retry. */ }
                }
                if(workerPhone) { saved.clear(); session.clear() }
                _state.update { it.copy(ownerHome=!workerPhone,workerLogin=workerPhone) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                session.clear(); saved.clear()
                val owner=local.isOwnerSetup()
                _state.update { it.copy(error=e.message ?: "Unable to connect",status="",hasLocalOwner=owner) }
            } finally { _state.update { it.copy(loading=false) } }
        }
    }
}
