package com.wuwei.capability;

import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cryptographic capability for Skills.
 * All implementations use JDK built-in APIs — zero extra dependencies.
 *
 * Exposes:
 *   capability.crypto.deriveKey(password, salt)    → base64 key
 *   capability.crypto.encrypt(plaintext, key)       → base64 ciphertext
 *   capability.crypto.decrypt(ciphertext, key)      → plaintext
 *   capability.crypto.hash(data)                    → hex
 *   capability.crypto.randomBytes(n)                → base64
 *   capability.crypto.generatePassword(len)         → string
 */
@Component
public class CryptoCapability {

    private static final Logger log = LoggerFactory.getLogger(CryptoCapability.class);

    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_LEN = 128;
    private static final int GCM_NONCE_LEN = 12;
    private static final int MAX_RANDOM_BYTES = 1024;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public ProxyObject forSkill(String skillId) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) {
                return switch (key) {
                    case "deriveKey" -> (ProxyExecutable) args ->
                        deriveKey(args[0].asString(), args[1].asString());
                    case "encrypt" -> (ProxyExecutable) args ->
                        encrypt(args[0].asString(), args[1].asString());
                    case "decrypt" -> (ProxyExecutable) args ->
                        decrypt(args[0].asString(), args[1].asString());
                    case "hash" -> (ProxyExecutable) args ->
                        hash(args[0].asString());
                    case "randomBytes" -> (ProxyExecutable) args ->
                        randomBytes(args[0].asInt());
                    case "generatePassword" -> (ProxyExecutable) args ->
                        generatePassword(args[0].asInt());
                    default -> null;
                };
            }

            @Override
            public boolean hasMember(String key) {
                return Set.of("deriveKey", "encrypt", "decrypt", "hash",
                    "randomBytes", "generatePassword").contains(key);
            }

            @Override
            public Set<String> getMemberKeys() {
                return Set.of("deriveKey", "encrypt", "decrypt", "hash",
                    "randomBytes", "generatePassword");
            }

            @Override
            public void putMember(String key, Value value) {}

            @Override
            public boolean removeMember(String key) { return false; }
        };
    }

    // ── Key Derivation (PBKDF2WithHmacSHA256) ──────────────────────

    private String deriveKey(String password, String saltBase64) {
        log.debug("crypto.deriveKey [{}]: salt=*** password=***", "skill");
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt,
                PBKDF2_ITERATIONS, AES_KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (Exception e) {
            log.error("deriveKey failed: {}", e.getMessage());
            throw new RuntimeException("Key derivation failed: " + e.getMessage(), e);
        }
    }

    // ── AES-256-GCM Encrypt ────────────────────────────────────────

    private String encrypt(String plaintext, String keyBase64) {
        log.debug("crypto.encrypt key=***");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Key must be 256 bits (32 bytes)");
            }

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");

            byte[] nonce = new byte[GCM_NONCE_LEN];
            SECURE_RANDOM.nextBytes(nonce);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

            // Format: nonce || ciphertext
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("encrypt failed: {}", e.getMessage());
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    // ── AES-256-GCM Decrypt ────────────────────────────────────────

    private String decrypt(String ciphertextBase64, String keyBase64) {
        log.debug("crypto.decrypt key=***");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Key must be 256 bits (32 bytes)");
            }

            byte[] combined = Base64.getDecoder().decode(ciphertextBase64);
            if (combined.length < GCM_NONCE_LEN + 1) {
                throw new IllegalArgumentException("Ciphertext too short");
            }

            byte[] nonce = new byte[GCM_NONCE_LEN];
            byte[] ciphertext = new byte[combined.length - GCM_NONCE_LEN];
            System.arraycopy(combined, 0, nonce, 0, GCM_NONCE_LEN);
            System.arraycopy(combined, GCM_NONCE_LEN, ciphertext, 0, ciphertext.length);

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LEN, nonce);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext);
        } catch (Exception e) {
            log.error("decrypt failed: {}", e.getMessage());
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    // ── SHA-256 Hash ───────────────────────────────────────────────

    private String hash(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("hash failed: {}", e.getMessage());
            throw new RuntimeException("Hash failed: " + e.getMessage(), e);
        }
    }

    // ── Random Bytes ───────────────────────────────────────────────

    private String randomBytes(int count) {
        if (count < 1 || count > MAX_RANDOM_BYTES) {
            throw new IllegalArgumentException(
                "randomBytes count must be between 1 and " + MAX_RANDOM_BYTES);
        }
        byte[] bytes = new byte[count];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ── Password Generator ─────────────────────────────────────────

    private String generatePassword(int length) {
        if (length < 1 || length > 256) {
            throw new IllegalArgumentException("Password length must be between 1 and 256");
        }
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ── Proxy helpers for CapabilityManager.executeProxy ────────────

    public Object executeProxy(String method, java.util.List<Object> args) {
        return switch (method) {
            case "deriveKey" -> deriveKey((String) args.get(0), (String) args.get(1));
            case "encrypt" -> encrypt((String) args.get(0), (String) args.get(1));
            case "decrypt" -> decrypt((String) args.get(0), (String) args.get(1));
            case "hash" -> hash((String) args.get(0));
            case "randomBytes" -> randomBytes(((Number) args.get(0)).intValue());
            case "generatePassword" -> generatePassword(((Number) args.get(0)).intValue());
            default -> Map.of("error", "Unknown crypto method: " + method);
        };
    }

    // ── Result proxy factory (for search/etc returning structured data) ──

    public static ProxyObject mapProxy(Map<String, Object> map) {
        return new ProxyObject() {
            @Override
            public Object getMember(String key) { return map.get(key); }
            @Override
            public boolean hasMember(String key) { return map.containsKey(key); }
            @Override
            public Set<String> getMemberKeys() { return map.keySet(); }
            @Override
            public void putMember(String key, Value value) {}
            @Override
            public boolean removeMember(String key) { return false; }
        };
    }
}
