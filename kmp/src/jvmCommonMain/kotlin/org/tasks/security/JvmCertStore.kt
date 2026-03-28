package org.tasks.security

import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

class JvmCertStore(private val storeFile: File) {

    @Volatile var lastFailedChain: Array<out X509Certificate>? = null

    private val trustedFingerprints: MutableSet<String> by lazy {
        if (storeFile.exists())
            storeFile.readLines().filter { it.isNotBlank() }.toMutableSet()
        else
            mutableSetOf()
    }

    fun isTrusted(cert: X509Certificate): Boolean = fingerprint(cert) in trustedFingerprints

    @Synchronized
    fun trust(cert: X509Certificate) {
        trustedFingerprints += fingerprint(cert)
        storeFile.writeText(trustedFingerprints.joinToString("\n"))
    }

    companion object {
        fun fingerprint(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}
