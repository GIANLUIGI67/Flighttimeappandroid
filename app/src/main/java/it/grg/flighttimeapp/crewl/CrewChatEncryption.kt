package it.grg.flighttimeapp.crewl

// P-256 ECDH key agreement + AES-256-GCM encryption (Signal-grade, Play Store compliant).
// Cross-platform compatible with the iOS implementation (same curves, same HKDF, same wire format).
//
// Firebase node: /userPublicKeys/{uid}/p256  →  base64( 0x04 || x[32] || y[32] )  (uncompressed EC point)
// Wire format for encrypted payloads: base64( nonce[12] || ciphertext || GCM-tag[16] )
//
// Call CrewChatEncryption.getInstance(context).setup() after the user signs in.

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*
import kotlin.coroutines.resume

class CrewChatEncryption private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val root = FirebaseDatabase.getInstance().reference

    // threadId → derived AES-256 key bytes (cached after first derivation)
    private val sessionKeys = mutableMapOf<String, ByteArray>()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "crew_e2e_prefs_v1",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // MARK: - Setup (call once after sign-in)

    suspend fun setup() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val keyPair = loadOrCreateKeyPair()
        val pubKeyB64 = encodePublicKeyToB64(keyPair.public as ECPublicKey)
        root.child("userPublicKeys").child(uid).setValue(mapOf("p256" to pubKeyB64))
    }

    // MARK: - Session key (P-256 ECDH → HKDF-SHA256 → AES-256)

    suspend fun sessionKey(threadId: String, peerUid: String): ByteArray? {
        sessionKeys[threadId]?.let { return it }

        val peerPubKeyB64 = fetchPeerPublicKey(peerUid) ?: return null
        val peerPublicKey = decodeB64ToPublicKey(peerPubKeyB64) ?: return null
        val myKeyPair = loadOrCreateKeyPair()

        val sharedSecret = try {
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myKeyPair.private)
            ka.doPhase(peerPublicKey, true)
            ka.generateSecret()
        } catch (e: Exception) { return null }

        val key = hkdf(
            ikm = sharedSecret,
            salt = "CrewLayoverChat_v1".toByteArray(Charsets.UTF_8),
            info = threadId.toByteArray(Charsets.UTF_8),
            length = 32
        )
        sessionKeys[threadId] = key
        return key
    }

    fun cachedSessionKey(threadId: String): ByteArray? = sessionKeys[threadId]

    // MARK: - Encrypt / Decrypt

    /** Encrypts a UTF-8 string. Returns base64( nonce[12] || ciphertext || GCM-tag[16] ). */
    fun encrypt(plaintext: String, key: ByteArray): String? = try {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(nonce + ciphertext, Base64.NO_WRAP)
    } catch (e: Exception) { null }

    /** Decrypts a base64 string produced by encrypt(). Returns null on failure. */
    fun decrypt(base64: String, key: ByteArray): String? {
        return try {
            val combined = Base64.decode(base64, Base64.NO_WRAP)
            if (combined.size < 28) return null  // 12 nonce + 16 tag minimum
            val nonce = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    // MARK: - Key management (EncryptedSharedPreferences → Android Keystore-backed)

    private fun loadOrCreateKeyPair(): KeyPair {
        val savedPriv = prefs.getString("ec_priv", null)
        val savedPub = prefs.getString("ec_pub", null)
        if (savedPriv != null && savedPub != null) {
            return try {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(savedPriv, Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(savedPub, Base64.NO_WRAP)))
                KeyPair(pub, priv)
            } catch (e: Exception) {
                generateAndSaveKeyPair()
            }
        }
        return generateAndSaveKeyPair()
    }

    private fun generateAndSaveKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val kp = gen.generateKeyPair()
        prefs.edit()
            .putString("ec_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .putString("ec_pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    // MARK: - Cross-platform public key encoding (uncompressed EC point = iOS x963Representation)

    /** Encodes an ECPublicKey as base64( 0x04 || x[32] || y[32] ) — compatible with iOS x963Representation. */
    private fun encodePublicKeyToB64(pub: ECPublicKey): String {
        val x = pub.w.affineX.toByteArray().stripLeadingZero(32)
        val y = pub.w.affineY.toByteArray().stripLeadingZero(32)
        val uncompressed = ByteArray(65).also { buf ->
            buf[0] = 0x04
            x.copyInto(buf, 1)
            y.copyInto(buf, 33)
        }
        return Base64.encodeToString(uncompressed, Base64.NO_WRAP)
    }

    /** Decodes base64( 0x04 || x[32] || y[32] ) back to an ECPublicKey. */
    private fun decodeB64ToPublicKey(b64: String): PublicKey? {
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            if (bytes.size != 65 || bytes[0] != 0x04.toByte()) return null
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            val params = AlgorithmParameters.getInstance("EC")
            params.init(ECGenParameterSpec("secp256r1"))
            val ecParams = params.getParameterSpec(ECParameterSpec::class.java)
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams))
        } catch (e: Exception) { null }
    }

    // MARK: - Firebase public key fetch

    private suspend fun fetchPeerPublicKey(uid: String): String? =
        suspendCancellableCoroutine { cont ->
            root.child("userPublicKeys").child(uid).child("p256")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        cont.resume(snapshot.value as? String)
                    }
                    override fun onCancelled(error: DatabaseError) {
                        cont.resume(null)
                    }
                })
        }

    // MARK: - HKDF-SHA256 (RFC 5869)

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val copyLen = minOf(t.size, length - offset)
            t.copyInto(result, offset, 0, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    // MARK: - Singleton

    companion object {
        @Volatile private var instance: CrewChatEncryption? = null

        fun getInstance(context: Context): CrewChatEncryption =
            instance ?: synchronized(this) {
                instance ?: CrewChatEncryption(context.applicationContext).also { instance = it }
            }
    }
}

/** Trims or left-pads a BigInteger byte array to exactly [size] bytes. */
private fun ByteArray.stripLeadingZero(size: Int): ByteArray {
    val trimmed = if (this.size > size && this[0] == 0.toByte()) this.copyOfRange(1, this.size) else this
    return if (trimmed.size < size) ByteArray(size - trimmed.size) + trimmed else trimmed
}
