package com.rfidunlock.app.kdeconnect

import android.content.Context
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.UUID

/**
 * Идентичность устройства для KDE Connect: стабильный deviceId и
 * self-signed X.509-сертификат (RSA). Сохраняется в PKCS12-хранилище
 * в приватной директории приложения.
 *
 * Сертификат и deviceId используются при TLS-рукопожатии и сопряжении
 * с GSConnect. CN сертификата ДОЛЖЕН совпадать с deviceId (требование
 * протокола KDE Connect).
 */
class DeviceIdentity private constructor(
    val deviceId: String,
    val deviceName: String,
    val certificate: X509Certificate,
    val privateKey: PrivateKey,
) {
    companion object {
        private const val KEYSTORE_FILE = "kdeconnect_identity.p12"
        private const val KEYSTORE_PASSWORD = "rfidunlock"
        private const val ALIAS = "kdeconnect"

        init {
            // Заменяем системный BC на полноценный, чтобы X509-билдер работал.
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }

        fun loadOrCreate(context: Context, deviceName: String): DeviceIdentity {
            val file = File(context.filesDir, KEYSTORE_FILE)
            val ks = KeyStore.getInstance("PKCS12")
            if (file.exists()) {
                file.inputStream().use { ks.load(it, KEYSTORE_PASSWORD.toCharArray()) }
                val cert = ks.getCertificate(ALIAS) as X509Certificate
                val key = ks.getKey(ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
                val id = cert.subjectX500Principal.name
                    .substringAfter("CN=").substringBefore(",")
                return DeviceIdentity(id, deviceName, cert, key)
            }

            val deviceId = UUID.randomUUID().toString().replace("-", "")
            val keyPair = generateKeyPair()
            val cert = generateCertificate(deviceId, keyPair)

            ks.load(null, null)
            ks.setKeyEntry(
                ALIAS,
                keyPair.private,
                KEYSTORE_PASSWORD.toCharArray(),
                arrayOf(cert),
            )
            file.outputStream().use { ks.store(it, KEYSTORE_PASSWORD.toCharArray()) }
            return DeviceIdentity(deviceId, deviceName, cert, keyPair.private)
        }

        private fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        private fun generateCertificate(deviceId: String, keyPair: KeyPair): X509Certificate {
            val notBefore = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.time
            val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }.time
            val subject = X500Name("CN=$deviceId, OU=KDE Connect, O=RFID Unlock")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                notBefore,
                notAfter,
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.private)
            return JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer))
        }
    }

    /** Base64 DER сертификата (для сравнения/доверия при сопряжении). */
    fun certificatePem(): String =
        Base64.encodeToString(certificate.encoded, Base64.NO_WRAP)
}
