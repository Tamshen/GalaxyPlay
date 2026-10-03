package com.shilapi.xcertplay.mfi

import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalMfiIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsPemAndBase64WithWhitespaceAndReloadsTheSameIdentity() {
        val (key, certificate) = syntheticIdentity()
        val directory = File(temporary.root, "offline-mfi")
        LocalMfiIdentityStore.prepareText(pem("PRIVATE KEY", key), base64(certificate).chunked(37).joinToString("\r\n")).use {
            LocalMfiIdentityStore.install(directory, it)
        }
        assertArrayEquals(certificate, LocalMfiAuthenticationClient.load(directory).readCertificate())
        assertArrayEquals(key, File(directory, "identity.pk8").readBytes())
        LocalMfiIdentityStore.prepareText(base64(key), pem("CERTIFICATE", certificate)).use {
            LocalMfiIdentityStore.install(directory, it)
        }
        assertEquals(64, LocalMfiAuthenticationClient.load(directory).signChallenge(ByteArray(32)).size)
    }

    @Test fun mismatchedOrMalformedInputLeavesTheExistingIdentityUntouched() {
        val (key, certificate) = syntheticIdentity()
        val (otherKey, _) = syntheticIdentity()
        val directory = File(temporary.root, "offline-mfi")
        LocalMfiIdentityStore.prepareBytes(key, certificate).use { LocalMfiIdentityStore.install(directory, it) }
        for ((keyText, certificateText) in listOf(
            base64(otherKey) to base64(certificate),
            pem("EC PRIVATE KEY", key) to base64(certificate),
            "" to base64(certificate),
            base64(key) to (pem("CERTIFICATE", certificate) + pem("CERTIFICATE", certificate)),
            ("A".repeat(LocalMfiIdentityStore.MAX_TEXT_LENGTH + 1)) to base64(certificate),
            base64(key) to "not-base64!",
        )) {
            assertThrows(Exception::class.java) { LocalMfiIdentityStore.prepareText(keyText, certificateText) }
            assertArrayEquals(key, File(directory, "identity.pk8").readBytes())
            assertArrayEquals(certificate, LocalMfiAuthenticationClient.load(directory).readCertificate())
        }
    }

    @Test fun acceptsPkcs7PemWithoutChangingTheCertificatePayload() {
        val (key, certificate) = syntheticIdentity()
        val factory = CertificateFactory.getInstance("X.509")
        val pkcs7 = factory.generateCertPath(listOf(factory.generateCertificate(certificate.inputStream()))).getEncoded("PKCS7")
        val directory = File(temporary.root, "offline-mfi")
        LocalMfiIdentityStore.prepareText(base64(key), pem("PKCS7", pkcs7)).use {
            LocalMfiIdentityStore.install(directory, it)
        }
        assertArrayEquals(pkcs7, LocalMfiAuthenticationClient.load(directory).readCertificate())
    }

    @Test fun replacementLoadsNewMaterialAndRecoversAnInterruptedDirectorySwap() {
        val (key, certificate) = syntheticIdentity()
        val (nextKey, nextCertificate) = syntheticIdentity()
        val directory = File(temporary.root, "offline-mfi")
        LocalMfiIdentityStore.prepareBytes(key, certificate).use { LocalMfiIdentityStore.install(directory, it) }
        val previous = File(temporary.root, "offline-mfi-previous")
        assertTrue(directory.renameTo(previous))
        LocalMfiIdentityStore.read(directory).close()
        assertArrayEquals(key, File(directory, "identity.pk8").readBytes())
        LocalMfiIdentityStore.prepareBytes(nextKey, nextCertificate).use { LocalMfiIdentityStore.install(directory, it) }
        assertArrayEquals(nextCertificate, LocalMfiAuthenticationClient.load(directory).readCertificate())
        assertFalse(previous.exists())
        assertFalse(File(temporary.root, "offline-mfi-importing").exists())
    }

    @Test fun invalidDestinationDoesNotDestroyTheInputAndClosedMaterialCannotBeInstalled() {
        val (key, certificate) = syntheticIdentity()
        val identity = LocalMfiIdentityStore.prepareBytes(key, certificate)
        assertThrows(Exception::class.java) {
            LocalMfiIdentityStore.install(File(temporary.root, "missing/identity"), identity)
        }
        val directory = File(temporary.root, "offline-mfi")
        LocalMfiIdentityStore.install(directory, identity)
        identity.close()
        assertThrows(Exception::class.java) { LocalMfiIdentityStore.install(directory, identity) }
        assertArrayEquals(certificate, LocalMfiAuthenticationClient.load(directory).readCertificate())
    }

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun pem(label: String, bytes: ByteArray) = "-----BEGIN $label-----\n${base64(bytes).chunked(64).joinToString("\n")}\n-----END $label-----"

    private fun syntheticIdentity(): Pair<ByteArray, ByteArray> {
        // 每次生成自签名测试身份，只验证导入流程，不代表可通过 iPhone 的 MFi 信任检查。
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val algorithm = AlgorithmIdentifier(X9ObjectIdentifiers.ecdsa_with_SHA256)
        val name = X500Name("CN=L7 import test only")
        val tbs = V3TBSCertificateGenerator().apply {
            setSerialNumber(ASN1Integer(BigInteger.ONE))
            setSignature(algorithm); setIssuer(name); setSubject(name)
            setStartDate(Time(Date(0))); setEndDate(Time(Date(4102444800000L)))
            setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
        }.generateTBSCertificate()
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(tbs.encoded) }
        return pair.private.encoded to DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign()))).encoded
    }
}
