/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.s3.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * On-disk storage for the secrets of profiles the user has asked this machine to remember.
 *
 * <p>Separate from {@link S3ProfileStore} on purpose. That file stays free of secrets and safe to
 * sync; this one never is. Only profiles with {@link S3Profile#isRememberSecret()} set ever reach
 * here, and a secret the endpoint rejects is deleted again, so a rotated key does not leave the
 * user stuck behind a saved one that no longer works.
 *
 * <h2>What the encryption is, and what it is not</h2>
 *
 * <p>The document is encrypted with AES-GCM under a key held in a sibling file. On POSIX systems
 * both files are also narrowed to their owner; on Windows they inherit the user profile's own ACL,
 * which already excludes other users. That much is worth doing: it keeps the secret out of backups,
 * cloud-synced home directories, screen shares and casual greps, which is how saved credentials
 * usually escape.
 *
 * <p>It is not a vault. The key sits beside the ciphertext, so anything running as this user can
 * read both — the same property {@code ~/.aws/credentials} has, and the reason remembering is
 * opt-in per profile rather than the default.
 */
@Slf4j
public final class SecretStore {

	/** One profile's remembered credentials. */
	public record Entry(String secretAccessKey, String sessionToken) {}

	private static final String ALGORITHM = "AES";
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int KEY_BYTES = 32;
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ObjectMapper mapper = new ObjectMapper();

	private final Path file;

	private final Path keyFile;

	/**
	 * Create a store over the given files.
	 *
	 * @param file    the encrypted secrets document; created on first save
	 * @param keyFile the key protecting it; created alongside
	 */
	public SecretStore(Path file, Path keyFile) {
		this.file = file;
		this.keyFile = keyFile;
	}

	/**
	 * The production store, beside the profile list in {@code ~/.nuclr/s3}.
	 *
	 * @return the default store
	 */
	public static SecretStore defaultStore() {
		Path directory = Path.of(System.getProperty("user.home"), ".nuclr", "s3");
		return new SecretStore(directory.resolve("secrets.enc"), directory.resolve("secrets.key"));
	}

	/**
	 * The file holding the encrypted secrets, shown to the user when they ask where it lives.
	 *
	 * @return the secrets file
	 */
	public Path file() {
		return file;
	}

	/**
	 * The remembered credentials for a profile.
	 *
	 * @param profileId the profile id
	 * @return the entry, or {@code null} when nothing is remembered for it
	 */
	public synchronized Entry get(String profileId) {
		if (profileId == null) {
			return null;
		}
		return readAll().get(profileId);
	}

	/**
	 * Remember a profile's credentials on this machine, replacing anything held for it.
	 *
	 * @param profileId       the profile id
	 * @param secretAccessKey the secret access key; a blank value removes the entry instead
	 * @param sessionToken    the session token, or {@code null} for a long-lived key
	 * @throws IOException if the store cannot be written
	 */
	public synchronized void put(String profileId, String secretAccessKey, String sessionToken) throws IOException {

		if (profileId == null) {
			return;
		}
		if (secretAccessKey == null || secretAccessKey.isBlank()) {
			remove(profileId);
			return;
		}

		var entries = readAll();
		entries.put(profileId, new Entry(secretAccessKey,
				sessionToken == null || sessionToken.isBlank() ? null : sessionToken));
		writeAll(entries);
	}

	/**
	 * Forget a profile's remembered credentials.
	 *
	 * @param profileId the profile id
	 * @throws IOException if the store cannot be written
	 */
	public synchronized void remove(String profileId) throws IOException {

		if (profileId == null) {
			return;
		}
		var entries = readAll();
		if (entries.remove(profileId) == null) {
			return;
		}
		if (entries.isEmpty()) {
			// Nothing left worth protecting; leave no file behind to wonder about.
			Files.deleteIfExists(file);
			return;
		}
		writeAll(entries);
	}

	/**
	 * Whether anything is remembered for a profile.
	 *
	 * @param profileId the profile id
	 * @return {@code true} when a secret is stored for it
	 */
	public synchronized boolean has(String profileId) {
		return get(profileId) != null;
	}

	// -------------------------------------------------------------------------
	// Storage
	// -------------------------------------------------------------------------

	/**
	 * Every stored entry.
	 *
	 * <p>Forgiving in the same way {@link S3ProfileStore} is: a store that cannot be read — a lost
	 * or replaced key file, a truncated write — yields nothing, so the user is asked for the secret
	 * again rather than being locked out of the panel.
	 */
	private Map<String, Entry> readAll() {

		if (!Files.isRegularFile(file)) {
			return new LinkedHashMap<>();
		}

		try {
			byte[] payload = Base64.getDecoder().decode(Files.readAllBytes(file));
			if (payload.length <= IV_BYTES) {
				return new LinkedHashMap<>();
			}

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES));
			byte[] plain = cipher.doFinal(payload, IV_BYTES, payload.length - IV_BYTES);

			Map<String, Entry> entries = mapper.readValue(
					new String(plain, StandardCharsets.UTF_8), new TypeReference<Map<String, Entry>>() {});
			return entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);

		} catch (Exception e) {
			log.warn("Cannot read the saved S3 secrets [{}]: {}. They will be asked for again.",
					file, e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	private void writeAll(Map<String, Entry> entries) throws IOException {

		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			var iv = new byte[IV_BYTES];
			RANDOM.nextBytes(iv);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(mapper.writeValueAsString(entries).getBytes(StandardCharsets.UTF_8));

			var payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);

			Files.write(file, Base64.getEncoder().encode(payload));
			restrictToOwner(file);

		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not encrypt the saved secrets: " + e.getMessage(), e);
		}
	}

	/** The key protecting the store, generated on first use. */
	private SecretKeySpec key() throws IOException {

		if (Files.isRegularFile(keyFile)) {
			byte[] existing = Base64.getDecoder().decode(Files.readAllBytes(keyFile));
			if (existing.length == KEY_BYTES) {
				return new SecretKeySpec(existing, ALGORITHM);
			}
			log.warn("The S3 secret key file [{}] is not usable; generating a new one", keyFile);
		}

		Path parent = keyFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		var material = new byte[KEY_BYTES];
		RANDOM.nextBytes(material);
		Files.write(keyFile, Base64.getEncoder().encode(material));
		restrictToOwner(keyFile);

		return new SecretKeySpec(material, ALGORITHM);
	}

	/**
	 * Narrow a file's permissions to its owner, where the filesystem has a notion of that.
	 *
	 * <p>This is the POSIX case, where a fresh file would otherwise be world-readable under a
	 * default umask. Windows is deliberately left alone: files created under the user profile
	 * already inherit an ACL that does not grant other users, and replacing that ACL with a
	 * hand-built one is a good way to lock out the very process that just wrote the file — when
	 * the owner is a group such as Administrators rather than the running user, an owner-only
	 * entry denies the app its own secrets.
	 *
	 * <p>Best effort either way: a filesystem that supports neither model must not stop the user
	 * saving a secret they asked to save. The file is then only as private as the directory
	 * holding it, which is why the class documentation is careful about what this protects against.
	 */
	private static void restrictToOwner(Path path) {

		try {
			PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
			if (posix != null) {
				posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
			}
		} catch (Exception e) {
			log.debug("Could not restrict [{}] to its owner: {}", path, e.getMessage());
		}
	}
}
