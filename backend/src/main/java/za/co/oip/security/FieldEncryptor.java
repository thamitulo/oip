package za.co.oip.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import za.co.oip.common.error.Exceptions.BusinessRuleException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts individual sensitive values (e.g. directors' identity numbers) with AES-256-GCM before they are stored.
 * The key comes from {@code OIP_DATA_ENCRYPTION_KEY} (base64 of 32 random bytes: {@code openssl rand -base64 32}).
 * Output format: {@code v1:<base64(iv || ciphertext+tag)>}; GCM authenticates the value, so tampering is detected.
 */
@Component
public class FieldEncryptor {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(@Value("${oip.security.data-encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("OIP_DATA_ENCRYPTION_KEY must be base64 (openssl rand -base64 32)");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("OIP_DATA_ENCRYPTION_KEY must decode to 32 bytes (openssl rand -base64 32)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("Unrecognised encrypted value");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Encrypted value could not be decrypted (wrong key or tampered data)", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BusinessRuleException("Identity numbers cannot be stored or used until the server has an encryption key "
                    + "(OIP_DATA_ENCRYPTION_KEY). Ask your platform administrator.");
        }
    }
}
