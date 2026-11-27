package dev.shipyard.security;

import dev.shipyard.storage.StorageLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets at rest (environment variables, webhook secrets, access
 * tokens) with AES-256-GCM. The key lives in {@code database/secret.key},
 * which must be backed up together with the database.
 */
@Component
public class SecretCipher {
    private static final int IV_LEN = 12;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(StorageLayout layout) {
        this.key = loadOrCreate(layout.secretKeyFile());
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        if (!encoded.startsWith("v1:")) throw new IllegalStateException("Unknown secret format");
        try {
            byte[] all = Base64.getDecoder().decode(encoded.substring(3));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, IV_LEN));
            return new String(c.doFinal(all, IV_LEN, all.length - IV_LEN), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed (wrong secret.key?)", e);
        }
    }

    private static SecretKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(file).strip());
                return new SecretKeySpec(raw, "AES");
            }
            KeyGenerator gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            SecretKey k = gen.generateKey();
            Files.writeString(file, Base64.getEncoder().encodeToString(k.getEncoded()));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem
            }
            return k;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
