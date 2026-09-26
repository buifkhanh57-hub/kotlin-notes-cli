package notably.service

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import notably.model.CryptoException
import notably.storage.Json
import notably.storage.JsonParseException
import notably.storage.JsonValue
import notably.storage.jsonNumber
import notably.storage.jsonObject
import notably.storage.jsonString

/**
 * Password-based encryption for the `notably lock` command.
 *
 * ## Scheme
 *
 * 1. A 16-byte random salt is generated per lock operation.
 * 2. The passphrase is stretched with PBKDF2-HMAC-SHA256
 *    (`javax.crypto.SecretKeyFactory`, part of the JVM — no third-party code)
 *    using [ITERATIONS] rounds into a 256-bit key.
 * 3. Payload encryption then uses, in order of preference:
 *      * **AES-GCM** (`AES/GCM/NoPadding`, 128-bit auth tag) when the JVM
 *        provides 256-bit AES — authenticated encryption;
 *      * otherwise a **fallback XOR stream cipher**: a keystream built as
 *        `SHA-256(key ‖ iv ‖ counter)` per 32-byte block, XORed over the data.
 * 4. A 16-byte *passphrase verifier* `SHA-256(magic ‖ salt ‖ passphrase)`
 *    is stored so an incorrect passphrase is rejected cheaply and with a
 *    clear message before any bulk decryption is attempted.
 *
 * The result is a self-describing JSON envelope (format id, version, cipher,
 * KDF parameters, base64 salt/iv/check/data) written to `notes.json.enc`.
 *
 * ## Honest limitations — read this before trusting it with anything serious
 *
 * * **PBKDF2 is not memory-hard.** Modern GPUs/ASICs brute-force PBKDF2 far
 *   faster than e.g. Argon2id would allow. Argon2 is not available in the
 *   JVM standard library, so a higher iteration count is the only lever.
 * * **The fallback XOR cipher is home-made.** It is *not* a vetted
 *   construction and offers no integrity: ciphertexts are malleable. It
 *   exists purely as a portability net for JVMs without AES-GCM; treat the
 *   AES-GCM path as the only real encryption mode.
 * * **A passphrase verifier is stored on disk**, which enables unlimited
 *   offline guessing of weak passphrases. Use a long, unique passphrase.
 * * **No rekeying / KDF rotation.** Envelopes record the parameters used,
 *   but you must re-lock to change the passphrase.
 * * **Locking protects only `notes.json` at rest.** Editor temp files,
 *   prior backups in `backups/`, and anything you `notably export` remain
 *   plaintext copies. `notably lock` deletes plaintext backups of the note
 *   index, but exports are your responsibility.
 * * **In-memory plaintext** exists while the CLI runs; JVM swap/core dumps
 *   are out of scope.
 */
object CryptoService {

    /** Format marker stored inside the envelope. */
    const val FORMAT_ID = "notably-encrypted"

    /** Envelope schema version. */
    const val VERSION = 1

    /** Key-derivation function (JVM standard provider). */
    const val KDF_ALGO = "PBKDF2WithHmacSHA256"

    /** Preferred cipher: authenticated AES in GCM mode. */
    const val CIPHER_GCM = "AES/GCM/NoPadding"

    /** Fallback cipher identifier for the XOR keystream mode. */
    const val CIPHER_XOR = "XOR-SHA256-CTR"

    const val KEY_BITS = 256
    const val SALT_BYTES = 16
    const val IV_BYTES_GCM = 12
    const val IV_BYTES_XOR = 16
    const val GCM_TAG_BITS = 128

    /** PBKDF2 iteration count — deliberately high for a CLI tool (~0.1s). */
    const val ITERATIONS = 120_000

    /** Minimum passphrase length accepted by the CLI layer. */
    const val MIN_PASSPHRASE_LENGTH = 8

    private val rng = SecureRandom()
    private val base64 = java.util.Base64.getEncoder()
    private val base64Decode = java.util.Base64.getDecoder()

    /** Self-describing encryption envelope (one JSON object on disk). */
    data class EncryptedEnvelope(
        val format: String,
        val version: Int,
        val cipher: String,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val check: String,
        val data: String,
        val createdAt: Instant
    ) {
        /** Serializes the envelope to a pretty-printed JSON object. */
        fun toJson(): JsonValue.JsonObject = jsonObject(
            "format" to jsonString(format),
            "version" to jsonNumber(version.toLong()),
            "cipher" to jsonString(cipher),
            "kdf" to jsonString(kdf),
            "iterations" to jsonNumber(iterations.toLong()),
            "salt" to jsonString(salt),
            "iv" to jsonString(iv),
            "check" to jsonString(check),
            "data" to jsonString(data),
            "createdAt" to jsonString(createdAt.toString())
        )

        companion object {
            /** Parses and structurally validates an envelope from JSON. */
            fun fromJson(obj: JsonValue.JsonObject?): EncryptedEnvelope {
                if (obj == null) throw CryptoException("encrypted envelope is not a JSON object")
                val format = obj.str("format") ?: ""
                if (format != FORMAT_ID) throw CryptoException("not a notably encrypted envelope (format='$format')")
                val version = obj.long("version")?.toInt() ?: 0
                if (version != VERSION) throw CryptoException("unsupported envelope version $version")
                val cipher = obj.str("cipher") ?: ""
                if (cipher != CIPHER_GCM && cipher != CIPHER_XOR) {
                    throw CryptoException("unsupported cipher '$cipher'")
                }
                val kdf = obj.str("kdf") ?: ""
                if (kdf != KDF_ALGO) throw CryptoException("unsupported KDF '$kdf'")
                val iterations = obj.long("iterations")?.toInt() ?: 0
                if (iterations < 1) throw CryptoException("envelope has invalid KDF iteration count")
                for (field in listOf("salt", "iv", "check", "data")) {
                    if (obj.str(field).isNullOrBlank()) throw CryptoException("envelope is missing '$field'")
                }
                return EncryptedEnvelope(
                    format = format,
                    version = version,
                    cipher = cipher,
                    kdf = kdf,
                    iterations = iterations,
                    salt = obj.str("salt")!!,
                    iv = obj.str("iv")!!,
                    check = obj.str("check")!!,
                    data = obj.str("data")!!,
                    createdAt = try {
                        Instant.parse(obj.str("createdAt") ?: "")
                    } catch (_: Exception) {
                        Instant.EPOCH
                    }
                )
            }
        }
    }

    /**
     * Encrypts [plaintext] under [passphrase] and returns the JSON envelope
     * text. When [forceXor] is true the fallback cipher is used directly
     * (useful for tests and for users who want deterministic behavior).
     */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray, forceXor: Boolean = false): String {
        if (passphrase.isEmpty()) throw CryptoException("passphrase must not be empty")
        val salt = randomBytes(SALT_BYTES)
        val key = pbkdf2(passphrase, salt, ITERATIONS, KEY_BITS)
        val cipherName: String
        val iv: ByteArray
        val data: ByteArray
        if (forceXor || !aesGcmAvailable()) {
            cipherName = CIPHER_XOR
            iv = randomBytes(IV_BYTES_XOR)
            data = xorCrypt(key, iv, plaintext)
        } else {
            val gcmIv = randomBytes(IV_BYTES_GCM)
            val gcmResult = try {
                gcmCrypt(key, gcmIv, plaintext, encrypt = true)
            } catch (_: GeneralSecurityException) {
                null
            }
            if (gcmResult != null) {
                cipherName = CIPHER_GCM
                iv = gcmIv
                data = gcmResult
            } else {
                // AES-GCM unusable at runtime (policy/provider): degrade honestly.
                cipherName = CIPHER_XOR
                iv = randomBytes(IV_BYTES_XOR)
                data = xorCrypt(key, iv, plaintext)
            }
        }
        val envelope = EncryptedEnvelope(
            format = FORMAT_ID,
            version = VERSION,
            cipher = cipherName,
            kdf = KDF_ALGO,
            iterations = ITERATIONS,
            salt = base64.encodeToString(salt),
            iv = base64.encodeToString(iv),
            check = base64.encodeToString(verifier(passphrase, salt)),
            data = base64.encodeToString(data),
            createdAt = Instant.now()
        )
        return Json.write(envelope.toJson(), pretty = true)
    }

    /**
     * Verifies the passphrase and decrypts an envelope produced by [encrypt].
     *
     * @throws CryptoException on wrong passphrase, corrupted envelope, or
     *         unsupported parameters (exit code [notably.model.ExitCodes.CRYPTO])
     */
    fun decrypt(envelopeJson: String, passphrase: CharArray): ByteArray {
        val envelope = try {
            EncryptedEnvelope.fromJson(Json.parse(envelopeJson).asObjectOrNull())
        } catch (e: JsonParseException) {
            throw CryptoException("encrypted envelope is corrupted: ${e.message}", e)
        }
        val salt = try {
            base64Decode.decode(envelope.salt)
        } catch (_: IllegalArgumentException) {
            throw CryptoException("envelope salt is not valid base64")
        }
        val expectedCheck = verifier(passphrase, salt)
        val actualCheck = try {
            base64Decode.decode(envelope.check)
        } catch (_: IllegalArgumentException) {
            throw CryptoException("envelope verifier is not valid base64")
        }
        if (!MessageDigest.isEqual(expectedCheck, actualCheck)) {
            throw CryptoException("incorrect passphrase")
        }
        val iv = try {
            base64Decode.decode(envelope.iv)
        } catch (_: IllegalArgumentException) {
            throw CryptoException("envelope IV is not valid base64")
        }
        val data = try {
            base64Decode.decode(envelope.data)
        } catch (_: IllegalArgumentException) {
            throw CryptoException("envelope payload is not valid base64")
        }
        val key = pbkdf2(passphrase, salt, envelope.iterations, KEY_BITS)
        return when (envelope.cipher) {
            CIPHER_GCM -> try {
                gcmCrypt(key, iv, data, encrypt = false)
            } catch (_: AEADBadTagException) {
                throw CryptoException("incorrect passphrase (authentication tag mismatch)")
            } catch (e: GeneralSecurityException) {
                throw CryptoException("decryption failed: ${e.message}", e)
            }
            CIPHER_XOR -> xorCrypt(key, iv, data)
            else -> throw CryptoException("unsupported cipher '${envelope.cipher}'")
        }
    }

    /** PBKDF2-HMAC-SHA256 via the standard JCE provider. */
    fun pbkdf2(passphrase: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, keyBits)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGO).generateSecret(spec).encoded
        } catch (e: GeneralSecurityException) {
            throw CryptoException("key derivation failed: ${e.message}", e)
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * The 16-byte passphrase verifier: first 16 bytes of
     * `SHA-256(FORMAT_ID ‖ salt ‖ passphrase-utf8)`. Compared with
     * [MessageDigest.isEqual] (constant-time) during decryption.
     */
    fun verifier(passphrase: CharArray, salt: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FORMAT_ID.toByteArray(Charsets.UTF_8))
        digest.update(salt)
        digest.update(String(passphrase).toByteArray(Charsets.UTF_8))
        return digest.digest().copyOfRange(0, 16)
    }

    /**
     * Fallback stream cipher: keystream block *i* =
     * `SHA-256(key ‖ iv ‖ be32(i))`, XORed over the payload. Symmetric —
     * the same call encrypts and decrypts.
     */
    fun xorCrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val counter = ByteArray(4)
        var offset = 0
        var block = 0
        while (offset < data.size) {
            counter[0] = (block ushr 24).toByte()
            counter[1] = (block ushr 16).toByte()
            counter[2] = (block ushr 8).toByte()
            counter[3] = block.toByte()
            val keystream = sha256(key, iv, counter)
            val chunk = minOf(32, data.size - offset)
            for (i in 0 until chunk) {
                out[offset + i] = (data[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            offset += chunk
            block++
        }
        return out
    }

    /** AES-GCM encryption/decryption in one place. */
    private fun gcmCrypt(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_GCM)
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(data)
    }

    /** True only when a full dummy AES-GCM round-trip succeeds on this JVM. */
    private fun aesGcmAvailable(): Boolean = try {
        val cipher = Cipher.getInstance(CIPHER_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(32), "AES"), GCMParameterSpec(GCM_TAG_BITS, ByteArray(12)))
        cipher.doFinal(ByteArray(1))
        true
    } catch (_: Exception) {
        false
    }

    private fun randomBytes(count: Int): ByteArray = ByteArray(count).also { rng.nextBytes(it) }

    private fun sha256(vararg chunks: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (chunk in chunks) digest.update(chunk)
        return digest.digest()
    }

    /**
     * Approximate on-disk size (bytes) of an envelope holding [plaintextSize]
     * bytes: base64 expands the payload by ~4/3 and the JSON wrapper adds a
     * fixed overhead of keys, salt/iv/check material and the timestamp.
     */
    fun encodedSize(plaintextSize: Int): Int =
        ((plaintextSize + 2) / 3) * 4 + 320
}
