package com.rfidunlock.app.kdeconnect

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Построение SSLContext для KDE Connect.
 *
 * KDE Connect использует взаимную TLS-аутентификацию self-signed
 * сертификатами. Во время сопряжения сертификат партнёра ещё не
 * известен, поэтому применяется «захватывающий» trust manager,
 * который принимает любой сертификат и запоминает его. После
 * сопряжения сохранённый сертификат можно проверять на совпадение.
 */
object SslHelper {

    /** Trust manager, принимающий любой сертификат и сохраняющий его. */
    class CapturingTrustManager : X509TrustManager {
        @Volatile
        var lastPeerCertificate: X509Certificate? = null
            private set

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            chain.firstOrNull()?.let { lastPeerCertificate = it }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            chain.firstOrNull()?.let { lastPeerCertificate = it }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun createContext(
        identity: DeviceIdentity,
        trustManager: TrustManager,
    ): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "kdeconnect",
                identity.privateKey,
                CHAR_PASSWORD,
                arrayOf(identity.certificate),
            )
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CHAR_PASSWORD)
        }
        return SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, arrayOf(trustManager), null)
        }
    }

    /**
     * Настроить SSLSocket: запросить клиентский сертификат (mutual TLS).
     */
    fun configureSocket(socket: SSLSocket, asServer: Boolean) {
        socket.useClientMode = !asServer
        if (asServer) {
            socket.needClientAuth = true
        }
    }

    private val CHAR_PASSWORD = "rfidunlock".toCharArray()
}
