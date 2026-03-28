package org.tasks.http

import okhttp3.OkHttpClient
import org.tasks.security.JvmCertStore
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class DefaultOkHttpClientFactory(
    private val certStore: JvmCertStore? = null,
) : OkHttpClientFactory {
    override suspend fun newClient(
        foreground: Boolean,
        cookieKey: String?,
        block: (OkHttpClient.Builder) -> Unit,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(true)
        if (certStore != null) {
            val trustManager = TasksCertTrustManager(certStore)
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustManager), null)
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        block(builder)
        return builder.build()
    }
}

private class TasksCertTrustManager(
    private val certStore: JvmCertStore,
) : X509TrustManager {

    private val systemTm: X509TrustManager = TrustManagerFactory
        .getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .firstOrNull { it is X509TrustManager } as? X509TrustManager
        ?: error("No X509TrustManager found")

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTm.acceptedIssuers

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        systemTm.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            systemTm.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (chain.isNotEmpty() && certStore.isTrusted(chain[0])) {
                return
            }
            certStore.lastFailedChain = chain
            throw e
        }
    }
}
