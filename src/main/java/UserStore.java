import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

class UserStore {
    private static final int ITERATIONS = 65536;
    private final Path file;
    private final Map<String, String[]> accounts = new HashMap<>();
    UserStore(String fileName) { this.file = Paths.get(fileName); load(); }
    private void load() {
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split(":");
                if (parts.length == 3) accounts.put(parts[0].toLowerCase(Locale.ROOT), parts);
            }
        } catch (IOException e) { System.out.println("Warning: could not read " + file + " (" + e.getMessage() + ")"); }
    }
    synchronized String register(String username, String password) {
        if (!Protocol.isValidUsername(username)) return Protocol.USERNAME_RULES;
        if (!Protocol.isValidPassword(password)) return Protocol.PASSWORD_RULES;
        String key = username.toLowerCase(Locale.ROOT);
        if (accounts.containsKey(key)) return "That username is already taken.";
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        String[] record = {username, Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(hash(password, salt))};
        try {
            Files.write(file, Collections.singletonList(String.join(":", record)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { return "The server could not save your account."; }
        accounts.put(key, record);
        return null;
    }
    String authenticate(String username, String password) {
        String[] record;
        synchronized (this) { record = accounts.get(username.toLowerCase(Locale.ROOT)); }
        if (record == null) return null;
        try {
            byte[] salt = Base64.getDecoder().decode(record[1]);
            byte[] expected = Base64.getDecoder().decode(record[2]);
            return MessageDigest.isEqual(expected, hash(password, salt)) ? record[0] : null;
        } catch (IllegalArgumentException damagedRecord) { return null; }
    }
    private static byte[] hash(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) { throw new IllegalStateException(e); }
    }
}
