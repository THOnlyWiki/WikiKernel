package cc.thonly.wikikernel.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AiSecrets {
    private static final String PREFIX = "encrypted:v1:";
    private final Path keyPath;
    private byte[] key;

    public AiSecrets(@Value("${wikikernel.ai.secret-directory:./run/secrets}") String directory) {
        keyPath = Path.of(directory).resolve("ai-master.key");
    }

    private synchronized byte[] key() throws Exception {
        if (key != null) return key;
        Files.createDirectories(keyPath.toAbsolutePath().getParent());
        byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        try {
            if (keyPath.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(keyPath, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                Files.write(keyPath, generated);
            } else {
                Files.write(keyPath, generated, StandardOpenOption.CREATE_NEW);
            }
        } catch (FileAlreadyExistsException ignored) { }
        key = Files.readAllBytes(keyPath);
        if (key.length != 32) throw new IllegalStateException("Invalid AI master key");
        return key;
    }

    public String seal(String value) {
        if (value == null || value.isEmpty() || value.startsWith(PREFIX)) return value;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            return PREFIX + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot encrypt AI credential", exception);
        }
    }

    public String open(String value) {
        if (value == null || !value.startsWith(PREFIX)) return value;
        try {
            if (!Files.exists(keyPath)) throw new IllegalStateException("Missing AI master key");
            String[] parts = value.substring(PREFIX.length()).split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read AI credential. Restore ai-master.key from backup.");
        }
    }
}
