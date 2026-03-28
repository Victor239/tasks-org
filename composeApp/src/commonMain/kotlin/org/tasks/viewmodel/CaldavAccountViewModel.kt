package org.tasks.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.tasks.caldav.CaldavClientProvider
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.jobs.BackgroundWork
import org.tasks.security.KeyStoreEncryption
import org.tasks.sync.SyncSource
import org.tasks.ui.DisplayableException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

class CaldavAccountViewModel(
    private val provider: CaldavClientProvider,
    private val caldavDao: CaldavDao,
    private val encryption: KeyStoreEncryption,
    private val backgroundWork: BackgroundWork,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Error(
            val message: String? = null,
            val resource: StringResource? = null,
        ) : State
        data object Success : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun save(name: String, url: String, username: String, password: String) {
        viewModelScope.launch {
            _state.value = State.Loading
            try {
                val principal = provider.forUrl(url, username, password).homeSet(username, password)
                val displayName = name.ifBlank { hostFromUrl(url) }
                val account = CaldavAccount(
                    uuid = UUIDHelper.newUUID(),
                    name = displayName,
                    url = principal,
                    username = username,
                    password = encryption.encrypt(password),
                    accountType = CaldavAccount.TYPE_CALDAV,
                )
                caldavDao.insert(account)
                viewModelScope.launch { backgroundWork.sync(SyncSource.ACCOUNT_ADDED) }
                _state.value = State.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: DisplayableException) {
                _state.value = State.Error(resource = e.resource)
            } catch (e: Exception) {
                _state.value = State.Error(message = e.message ?: "Failed to connect")
            }
        }
    }

    fun dismissError() {
        _state.value = State.Idle
    }

    private fun hostFromUrl(url: String): String = try {
        URI(url).host?.takeIf { it.isNotBlank() } ?: url
    } catch (_: Exception) {
        url
    }
}
