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
import org.tasks.security.JvmCertStore
import org.tasks.security.KeyStoreEncryption
import org.tasks.sync.SyncSource
import org.tasks.ui.DisplayableException
import java.net.URI
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.cancellation.CancellationException

class CaldavAccountViewModel(
    private val provider: CaldavClientProvider,
    private val caldavDao: CaldavDao,
    private val encryption: KeyStoreEncryption,
    private val backgroundWork: BackgroundWork,
    private val certStore: JvmCertStore? = null,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Error(
            val message: String? = null,
            val resource: StringResource? = null,
        ) : State
        data class CertError(
            val fingerprint: String,
            val subject: String,
            val issuer: String,
            val validUntil: String,
        ) : State
        data object Success : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var pendingCert: X509Certificate? = null
    private var pendingName = ""
    private var pendingUrl = ""
    private var pendingUsername = ""
    private var pendingPassword = ""

    fun save(name: String, url: String, username: String, password: String) {
        pendingName = name
        pendingUrl = url
        pendingUsername = username
        pendingPassword = password
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
                val cert = certStore?.lastFailedChain?.firstOrNull()
                if (cert != null && e.hasCause<SSLHandshakeException>()) {
                    pendingCert = cert
                    _state.value = State.CertError(
                        fingerprint = JvmCertStore.fingerprint(cert),
                        subject = cert.subjectX500Principal.commonName(),
                        issuer = cert.issuerX500Principal.commonName(),
                        validUntil = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                            .format(cert.notAfter),
                    )
                } else {
                    _state.value = State.Error(message = e.message ?: "Failed to connect")
                }
            }
        }
    }

    fun acceptCert() {
        val cert = pendingCert ?: return
        certStore?.trust(cert)
        pendingCert = null
        save(pendingName, pendingUrl, pendingUsername, pendingPassword)
    }

    fun dismissError() {
        pendingCert = null
        _state.value = State.Idle
    }

    private fun hostFromUrl(url: String): String = try {
        URI(url).host?.takeIf { it.isNotBlank() } ?: url
    } catch (_: Exception) {
        url
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is T) return true
        t = t.cause
    }
    return false
}

private fun javax.security.auth.x500.X500Principal.commonName(): String =
    name.split(",").firstOrNull { it.trim().startsWith("CN=") }
        ?.substringAfter("=")?.trim()
        ?: name
