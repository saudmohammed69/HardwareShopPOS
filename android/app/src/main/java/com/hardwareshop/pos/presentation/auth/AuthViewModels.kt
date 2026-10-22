package com.hardwareshop.pos.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hardwareshop.pos.data.repository.AuthRepository
import com.hardwareshop.pos.data.repository.SettingsRepository
import com.hardwareshop.pos.domain.model.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// LOGIN VIEW MODEL
// ─────────────────────────────────────────────────────────────────────────────

data class LoginUiState(
    val shopName:         String  = "",
    val isLoading:        Boolean = false,
    val error:            String? = null,
    val navigateToWorker: Boolean = false,
    val navigateToOwner:  Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository:     AuthRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val info = settingsRepository.getShopInfo()
            _state.update { it.copy(shopName = info.name) }
        }
    }

    fun loginWorker(username: String, password: String) {
        if (_state.value.isLoading) return
        if (username.isBlank() || password.isBlank()) {
            _state.update { it.copy(error = "Username and password are required") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
            when (val result = authRepository.authenticateWorker(username.trim(), password)) {
                is Result.Success -> _state.update { it.copy(isLoading = false, navigateToWorker = true) }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = result.message) }
                else              -> Unit
            }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Unable to sign in: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }

    fun loginOwner(password: String) {
        if (_state.value.isLoading) return
        if (password.isBlank()) {
            _state.update { it.copy(error = "Password is required") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
            when (val result = authRepository.authenticateOwner(password)) {
                is Result.Success -> _state.update { it.copy(isLoading = false, navigateToOwner = true) }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = result.message) }
                else              -> Unit
            }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Unable to sign in: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OWNER SETUP VIEW MODEL  (first-run only)
// ─────────────────────────────────────────────────────────────────────────────

data class OwnerSetupUiState(
    val shopName:      String  = "",
    val shopPhone:     String  = "",
    val shopAddress:   String  = "",
    val shopGstNumber: String  = "",
    val password:      String  = "",
    val confirmPass:   String  = "",
    val isLoading:     Boolean = false,
    val error:         String? = null,
    val isComplete:    Boolean = false
)

@HiltViewModel
class OwnerSetupViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(OwnerSetupUiState())
    val state: StateFlow<OwnerSetupUiState> = _state.asStateFlow()

    fun update(block: OwnerSetupUiState.() -> OwnerSetupUiState) = _state.update(block)

    fun setup() {
        if (_state.value.isLoading) return
        val s = _state.value
        when {
            s.shopName.isBlank()    -> { _state.update { it.copy(error = "Shop name is required") }; return }
            s.password.length < 6   -> { _state.update { it.copy(error = "Password must be at least 6 characters") }; return }
            s.password != s.confirmPass -> { _state.update { it.copy(error = "Passwords do not match") }; return }
        }
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
            when (val result = authRepository.setupOwner(
                password       = s.password,
                shopName       = s.shopName,
                shopPhone      = s.shopPhone,
                shopAddress    = s.shopAddress,
                shopGstNumber  = s.shopGstNumber
            )) {
                is Result.Success -> _state.update { it.copy(isLoading = false, isComplete = true) }
                is Result.Error   -> _state.update { it.copy(isLoading = false, error = result.message) }
                else              -> Unit
            }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "Unable to sign in: ${e.message}") } }
            finally { _state.update { it.copy(isLoading = false) } }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SPLASH VIEW MODEL  (checks owner setup status, routes accordingly)
// ─────────────────────────────────────────────────────────────────────────────

sealed class SplashDestination {
    object Setup : SplashDestination(); object Login : SplashDestination()
    object Cloud : SplashDestination(); object Owner : SplashDestination(); object Worker : SplashDestination()
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val savedSession: com.hardwareshop.pos.data.remote.CloudSessionStore
) : ViewModel() {

    private val _destination = MutableStateFlow<SplashDestination?>(null)
    val destination: StateFlow<SplashDestination?> = _destination.asStateFlow()

    init {
        viewModelScope.launch {
            _destination.value = when(savedSession.restore()) {
                is com.hardwareshop.pos.util.UserSession.OwnerSession -> SplashDestination.Owner
                is com.hardwareshop.pos.util.UserSession.WorkerSession -> SplashDestination.Worker
                else -> SplashDestination.Cloud
            }
        }
    }
}
