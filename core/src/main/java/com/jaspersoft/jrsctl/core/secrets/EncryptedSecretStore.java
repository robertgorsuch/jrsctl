package com.jaspersoft.jrsctl.core.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsctl.core.platform.Durability;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code $JRSCTL_HOME/secrets.enc} store (spec §5.2): a JSON file {@code {version:2,
 * kdf:"PBKDF2WithHmacSHA256", iterations:600000, salt:base64, verifier:{iv,ct},
 * entries:{name:{iv:base64, ct:base64}}}} whose entries are AES-256/GCM ciphertexts under a key
 * derived from the operator passphrase. The KDF salt is <em>machine-bound</em>: the 16 random bytes
 * stored in the file are concatenated with the machine identity ({@code /etc/machine-id}, else
 * {@code COMPUTERNAME}, else the host name) before being fed to PBKDF2, so a copied file cannot be
 * unlocked on another machine even with the passphrase. Version 1 files were salted with the DNS
 * host name instead; when such a file refuses the current identity it is retried with that legacy
 * identity and, if that unlocks it, rewritten in place as version 2 under the current one. A
 * version 2 file is never retried. Each entry uses a fresh 96-bit IV and the entry name as GCM
 * associated data, so a ciphertext cannot be re-labelled. Invariants: the passphrase is requested
 * lazily and only for operations that need the key ({@code init}, {@code set}, {@code get}); a
 * wrong passphrase is detected by the verifier before anything is written; every write is atomic
 * (temp file + rename) and, on POSIX, owner-only; no secret value or passphrase ever appears in an
 * exception message. Only JDK cryptography is used.
 */
public final class EncryptedSecretStore {

  public static final int VERSION = 2;

  /** Files of this version were salted with the DNS host name; see the class comment. */
  public static final int LEGACY_VERSION = 1;

  private static final Logger LOG = LoggerFactory.getLogger(EncryptedSecretStore.class);
  public static final String KDF = "PBKDF2WithHmacSHA256";
  public static final int ITERATIONS = 600_000;

  private static final int MIN_ITERATIONS = 100_000;
  private static final int SALT_BYTES = 16;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int KEY_BITS = 256;
  private static final String VERIFIER_AAD = "jrsctl-secrets-verifier";
  private static final byte[] VERIFIER_PLAINTEXT = "jrsctl".getBytes(StandardCharsets.UTF_8);

  private final Path file;
  private final PassphraseSource passphrase;
  private final String machineId;
  private final Optional<String> legacyMachineId;
  private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  private final SecureRandom random = new SecureRandom();

  private final Restrictor restrictor;
  private byte[] cachedSalt;
  private String cachedIdentity;
  private SecretKey cachedKey;

  /**
   * Makes a file readable by its owner only, the platform's way. The store itself knows only the
   * POSIX permission bits; on Windows the owner-only ACL needs the platform's file operations and
   * {@code icacls}, which the application supplies (assessment item S6).
   */
  @FunctionalInterface
  public interface Restrictor {
    void restrict(Path file) throws IOException;
  }

  public EncryptedSecretStore(Path file, PassphraseSource passphrase) {
    this(file, passphrase, EncryptedSecretStore::posixOwnerOnly);
  }

  /** As above with the platform's owner-only restriction for the file it writes. */
  public EncryptedSecretStore(Path file, PassphraseSource passphrase, Restrictor restrictor) {
    this(file, passphrase, hostName(), Optional.of(legacyHostName()), restrictor);
  }

  /** As above with an explicit machine identity (tests, or a deliberately portable store). */
  public EncryptedSecretStore(Path file, PassphraseSource passphrase, String machineId) {
    this(file, passphrase, machineId, Optional.empty());
  }

  /**
   * As above, also naming the identity a version 1 store may have been salted with (the DNS host
   * name used before 2026-09-10); it is tried only when a version 1 file refuses the current one.
   */
  public EncryptedSecretStore(
      Path file, PassphraseSource passphrase, String machineId, Optional<String> legacyMachineId) {
    this(file, passphrase, machineId, legacyMachineId, EncryptedSecretStore::posixOwnerOnly);
  }

  public EncryptedSecretStore(
      Path file,
      PassphraseSource passphrase,
      String machineId,
      Optional<String> legacyMachineId,
      Restrictor restrictor) {
    this.file = Objects.requireNonNull(file, "file");
    this.passphrase = Objects.requireNonNull(passphrase, "passphrase");
    this.machineId = Objects.requireNonNull(machineId, "machineId");
    this.legacyMachineId = Objects.requireNonNull(legacyMachineId, "legacyMachineId");
    this.restrictor = Objects.requireNonNull(restrictor, "restrictor");
  }

  public Path file() {
    return file;
  }

  public boolean exists() {
    return Files.isRegularFile(file);
  }

  /** Creates an empty store with a fresh salt; refuses to overwrite an existing file. */
  public void init() {
    if (exists()) {
      throw new SecretException(
          "secrets store already exists at " + file + "; remove it first to re-initialise");
    }
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    SecretKey key = deriveKey(salt);
    ObjectNode root = json.createObjectNode();
    root.put("version", VERSION);
    root.put("kdf", KDF);
    root.put("iterations", ITERATIONS);
    root.put("salt", Base64.getEncoder().encodeToString(salt));
    root.set("verifier", encrypt(key, VERIFIER_AAD, VERIFIER_PLAINTEXT));
    root.set("entries", json.createObjectNode());
    write(root);
  }

  /** Adds or replaces {@code name}; the secret is read once and not retained. */
  public void set(String name, Secret secret) {
    checkName(name);
    Objects.requireNonNull(secret, "secret");
    ObjectNode root = read();
    SecretKey key = unlock(root);
    char[] chars = secret.chars();
    byte[] plain = null;
    try {
      // UTF-8 needs at most 3 bytes per UTF-16 code unit; allocate() so the array can be zeroed
      ByteBuffer bb = ByteBuffer.allocate(chars.length * 3);
      CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
      encoder.encode(CharBuffer.wrap(chars), bb, true);
      encoder.flush(bb);
      bb.flip();
      plain = new byte[bb.remaining()];
      bb.get(plain);
      Arrays.fill(bb.array(), (byte) 0);
      entries(root).set(name, encrypt(key, name, plain));
    } finally {
      Arrays.fill(chars, '\0');
      if (plain != null) {
        Arrays.fill(plain, (byte) 0);
      }
    }
    write(root);
  }

  /** Removes {@code name}; false when it was not present. No passphrase is needed. */
  public boolean remove(String name) {
    checkName(name);
    ObjectNode root = read();
    ObjectNode entries = entries(root);
    if (!entries.has(name)) {
      return false;
    }
    entries.remove(name);
    write(root);
    return true;
  }

  /** Entry names in sorted order. No passphrase is needed. */
  public List<String> list() {
    ObjectNode entries = entries(read());
    List<String> names = new ArrayList<>();
    for (Iterator<String> it = entries.fieldNames(); it.hasNext(); ) {
      names.add(it.next());
    }
    Collections.sort(names);
    return List.copyOf(names);
  }

  /**
   * True when {@link #get} would not ask anyone for the passphrase: the key is already derived, or
   * the passphrase source answers without a prompt (field test 2, D1).
   */
  public synchronized boolean canUnlockWithoutPrompt() {
    return cachedKey != null || passphrase.availableWithoutPrompt();
  }

  /** The decrypted entry, or empty when no such entry exists. */
  public Optional<Secret> get(String name) {
    checkName(name);
    ObjectNode root = read();
    SecretKey key = unlock(root); // a wrong passphrase is reported even for an unknown entry
    JsonNode entry = entries(root).get(name);
    if (entry == null || !entry.isObject()) {
      return Optional.empty();
    }
    byte[] plain = decrypt(key, name, entry, "entry " + name);
    try {
      CharBuffer cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(plain));
      char[] chars = new char[cb.remaining()];
      cb.get(chars);
      if (cb.hasArray()) {
        Arrays.fill(cb.array(), '\0');
      }
      try {
        return Optional.of(Secret.of(chars));
      } finally {
        Arrays.fill(chars, '\0');
      }
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  // ---- crypto -----------------------------------------------------------------------------------

  /**
   * Derives the key for {@code root} and proves it against the verifier. A version 1 file that
   * refuses the current machine identity is retried with the legacy one and, on success, rebound to
   * the current identity and rewritten as version 2 before the key is returned.
   */
  private SecretKey unlock(ObjectNode root) {
    byte[] salt = base64(root, "salt");
    int iterations = root.path("iterations").asInt(0);
    if (iterations < MIN_ITERATIONS) {
      throw new SecretException(
          "secrets store "
              + file
              + " declares "
              + iterations
              + " KDF iterations; refusing to use it");
    }
    JsonNode verifier = root.get("verifier");
    if (verifier == null || !verifier.isObject()) {
      throw new SecretException("secrets store " + file + " has no verifier; re-initialise it");
    }
    SecretKey key = deriveKey(salt, iterations, machineId);
    if (verifies(key, verifier)) {
      return key;
    }
    boolean legacyFile = root.path("version").asInt(-1) == LEGACY_VERSION;
    if (legacyFile && legacyMachineId.isPresent() && !legacyMachineId.get().equals(machineId)) {
      SecretKey legacyKey = deriveKey(salt, iterations, legacyMachineId.get());
      if (verifies(legacyKey, verifier)) {
        rebind(root, legacyKey, key);
        return key;
      }
    }
    throw wrongPassphrase();
  }

  private boolean verifies(SecretKey key, JsonNode verifier) {
    Optional<byte[]> check = tryDecrypt(key, VERIFIER_AAD, verifier);
    if (check.isEmpty()) {
      return false;
    }
    boolean ok = Arrays.equals(check.get(), VERIFIER_PLAINTEXT);
    Arrays.fill(check.get(), (byte) 0);
    return ok;
  }

  /**
   * Re-encrypts the verifier and every entry from {@code from} to {@code to}, stamps the current
   * version and writes the file, so the store is bound to this machine's identity from now on.
   */
  private void rebind(ObjectNode root, SecretKey from, SecretKey to) {
    root.set("verifier", encrypt(to, VERIFIER_AAD, VERIFIER_PLAINTEXT));
    ObjectNode entries = entries(root);
    List<String> names = new ArrayList<>();
    entries.fieldNames().forEachRemaining(names::add);
    for (String name : names) {
      byte[] plain = decrypt(from, name, entries.get(name), "entry " + name);
      try {
        entries.set(name, encrypt(to, name, plain));
      } finally {
        Arrays.fill(plain, (byte) 0);
      }
    }
    root.put("version", VERSION);
    write(root);
    LOG.warn(
        "secrets store {} was created by an earlier build and bound to the host name; it is now"
            + " bound to this machine's identity (version {})",
        file,
        VERSION);
  }

  private SecretKey deriveKey(byte[] salt) {
    return deriveKey(salt, ITERATIONS, machineId);
  }

  private synchronized SecretKey deriveKey(byte[] salt, int iterations, String identity) {
    if (cachedKey != null && Arrays.equals(cachedSalt, salt) && identity.equals(cachedIdentity)) {
      return cachedKey;
    }
    byte[] machine = identity.getBytes(StandardCharsets.UTF_8);
    byte[] boundSalt = new byte[salt.length + machine.length];
    System.arraycopy(salt, 0, boundSalt, 0, salt.length);
    System.arraycopy(machine, 0, boundSalt, salt.length, machine.length);
    try (Secret pass = passphrase.require()) {
      char[] chars = pass.chars();
      PBEKeySpec spec = new PBEKeySpec(chars, boundSalt, iterations, KEY_BITS);
      try {
        byte[] raw = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        SecretKey key = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        cachedSalt = salt.clone();
        cachedIdentity = identity;
        cachedKey = key;
        return key;
      } catch (GeneralSecurityException e) {
        throw new SecretException("key derivation failed: " + e.getClass().getSimpleName(), e);
      } finally {
        spec.clearPassword();
        Arrays.fill(chars, '\0');
      }
    }
  }

  private ObjectNode encrypt(SecretKey key, String aad, byte[] plain) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      byte[] ct = cipher.doFinal(plain);
      ObjectNode node = json.createObjectNode();
      node.put("iv", Base64.getEncoder().encodeToString(iv));
      node.put("ct", Base64.getEncoder().encodeToString(ct));
      return node;
    } catch (GeneralSecurityException e) {
      throw new SecretException("encryption failed: " + e.getClass().getSimpleName(), e);
    }
  }

  private byte[] decrypt(SecretKey key, String aad, JsonNode entry, String what) {
    return tryDecrypt(key, aad, entry)
        .orElseThrow(
            () ->
                new SecretException(
                    "secrets store " + file + ": " + what + " is corrupt or was re-labelled"));
  }

  /** Empty when the GCM tag does not verify: wrong key, wrong label, or altered ciphertext. */
  private Optional<byte[]> tryDecrypt(SecretKey key, String aad, JsonNode entry) {
    byte[] iv = base64(entry, "iv");
    byte[] ct = base64(entry, "ct");
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return Optional.of(cipher.doFinal(ct));
    } catch (AEADBadTagException e) {
      return Optional.empty();
    } catch (GeneralSecurityException e) {
      throw new SecretException("decryption failed: " + e.getClass().getSimpleName(), e);
    }
  }

  private SecretException wrongPassphrase() {
    return new SecretException(
        "passphrase does not unlock "
            + file
            + " on this machine (wrong passphrase, or the store"
            + " was created on another host); "
            + PassphraseUnavailableException.REMEDIATION);
  }

  // ---- file -------------------------------------------------------------------------------------

  private ObjectNode read() {
    if (!exists()) {
      throw new SecretException("secrets store not found at " + file + "; run jrsctl secrets init");
    }
    JsonNode root;
    try (InputStream in = Files.newInputStream(file)) {
      root = json.readTree(in);
    } catch (IOException e) {
      throw new SecretException("cannot read secrets store " + file + ": " + e.getMessage(), e);
    }
    if (!(root instanceof ObjectNode obj)) {
      throw new SecretException("secrets store " + file + " is not a JSON object");
    }
    int version = obj.path("version").asInt(-1);
    if (version != VERSION && version != LEGACY_VERSION) {
      throw new SecretException(
          "secrets store "
              + file
              + " has version "
              + version
              + "; this build supports versions "
              + LEGACY_VERSION
              + " and "
              + VERSION);
    }
    if (!KDF.equals(obj.path("kdf").asText())) {
      throw new SecretException("secrets store " + file + " uses an unsupported kdf");
    }
    return obj;
  }

  private void write(ObjectNode root) {
    Path dir = file.toAbsolutePath().getParent();
    Path tmp = null;
    try {
      if (dir != null) {
        Files.createDirectories(dir);
      }
      tmp = Files.createTempFile(dir, "secrets", ".tmp");
      // Restricted before a byte is written; the atomic move keeps the permissions (S6).
      restrictor.restrict(tmp);
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        json.writeValue(out, root);
      }
      Durability.sync(tmp);
      Durability.move(
          tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      Durability.syncDirectory(dir);
      tmp = null;
    } catch (IOException e) {
      throw new SecretException("cannot write secrets store " + file + ": " + e.getMessage(), e);
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // best effort; the temp file holds only ciphertext
        }
      }
    }
  }

  /**
   * The POSIX half of owner-only; a no-op elsewhere, which is why the application supplies its own.
   */
  static void posixOwnerOnly(Path path) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }
  }

  private static ObjectNode entries(ObjectNode root) {
    JsonNode entries = root.get("entries");
    if (entries instanceof ObjectNode obj) {
      return obj;
    }
    throw new SecretException("secrets store has no entries object; re-initialise it");
  }

  private static byte[] base64(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || !v.isTextual()) {
      throw new SecretException("secrets store is missing field '" + field + "'");
    }
    try {
      return Base64.getDecoder().decode(v.asText());
    } catch (IllegalArgumentException e) {
      throw new SecretException("secrets store field '" + field + "' is not valid base64", e);
    }
  }

  private static void checkName(String name) {
    Objects.requireNonNull(name, "name");
    if (!SecretRef.ENC_NAME.matcher(name).matches()) {
      throw new SecretException(
          "invalid secret name '" + name + "': use letters, digits, '_', '.', '-'");
    }
  }

  /**
   * The identity builds before 2026-09-10 salted with: the DNS host name, which flips between short
   * and fully qualified forms as resolvers change. Kept only to unlock version 1 files.
   */
  private static String legacyHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      Map<String, String> env = System.getenv();
      return env.getOrDefault("COMPUTERNAME", env.getOrDefault("HOSTNAME", "localhost"));
    }
  }

  private static String hostName() {
    for (String path : List.of("/etc/machine-id", "/var/lib/dbus/machine-id")) {
      try {
        Path p = Path.of(path);
        if (Files.isRegularFile(p)) {
          String id = Files.readString(p, StandardCharsets.UTF_8).trim();
          if (!id.isEmpty()) {
            return id;
          }
        }
      } catch (Exception ignored) {
        // Fall back to next identifier source
      }
    }
    Map<String, String> env = System.getenv();
    String computerName = env.get("COMPUTERNAME");
    if (computerName != null && !computerName.isBlank()) {
      return computerName.trim();
    }
    String hostEnv = env.get("HOSTNAME");
    if (hostEnv != null && !hostEnv.isBlank()) {
      return hostEnv.trim();
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "localhost";
    }
  }
}
