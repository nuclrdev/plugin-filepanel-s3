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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Storage for the secrets of profiles the user has asked this machine to remember.
 *
 * <p>Separate from {@link S3ProfileStore} on purpose. That file stays free of secrets and safe to
 * sync. Only profiles with {@link S3Profile#isRememberSecret()} set ever reach here, and a secret
 * the endpoint rejects is deleted again, so a rotated key does not leave the user stuck behind a
 * saved one that no longer works.
 *
 * <h2>Where the secrets live</h2>
 *
 * <p>In the OS credential store - Windows Credential Manager, macOS Keychain, or the Linux Secret
 * Service - reached through the {@link NuclrCredentialStore} the host hands this plugin, already
 * scoped to its id. The secret access key is kept under the profile id and a session token, when
 * there is one, under {@code <profile id>:session}. Windows keeps each entry as UTF-16 in at most
 * 2560 bytes, 1280 characters, and session tokens can be longer, so a long token is split across
 * {@code :session}, {@code :session.1}, {@code :session.2} and so on.
 *
 * <h2>The file it replaces</h2>
 *
 * <p>Before 1.0.9 remembered secrets were an AES-GCM document in {@code ~/.nuclr/s3/secrets.enc},
 * under a key in the sibling {@code secrets.key}. The first time a store is used with a working
 * credential store, every entry in that file is moved into it and both files are deleted. Until
 * then, and on a machine with no usable credential store, the file is still read so no one loses
 * a secret they saved; nothing new is ever written to it.
 */
@Slf4j
public final class SecretStore {

	/** One profile's remembered credentials. */
	public record Entry(String secretAccessKey, String sessionToken) {}

	/** Suffix of the key a profile's session token, or its first part, is kept under. */
	static final String SESSION_SUFFIX = ":session";

	/** Characters per entry: well inside the 1280 Windows allows. */
	static final int CHUNK_CHARS = 1000;

	/** Parts read or cleared at most, far beyond any real session token. */
	private static final int MAX_CHUNKS = 32;

	private static final String LEGACY_TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int LEGACY_KEY_BYTES = 32;
	private static final int LEGACY_IV_BYTES = 12;
	private static final int LEGACY_TAG_BITS = 128;

	/** The host's store, installed when the plugin is given its context. */
	private static volatile NuclrCredentialStore installed;

	private final ObjectMapper mapper = new ObjectMapper();

	/** The store to use, or {@code null} to use whatever the plugin was given. */
	private final NuclrCredentialStore explicit;

	private final Path legacyFile;

	private final Path legacyKeyFile;

	private boolean migrated;

	/**
	 * Create a store over a credential store and the legacy files to move into it.
	 *
	 * @param credentials   the OS credential store, or {@code null} to use the one installed by
	 *                      {@link #useCredentialStore}
	 * @param legacyFile    the encrypted secrets document earlier versions wrote
	 * @param legacyKeyFile the key that protected it
	 */
	public SecretStore(NuclrCredentialStore credentials, Path legacyFile, Path legacyKeyFile) {
		this.explicit = credentials;
		this.legacyFile = legacyFile;
		this.legacyKeyFile = legacyKeyFile;
	}

	/**
	 * The production store: the host's credential store, and the old files in {@code ~/.nuclr/s3}.
	 *
	 * @return the default store
	 */
	public static SecretStore defaultStore() {
		Path directory = Path.of(System.getProperty("user.home"), ".nuclr", "s3");
		return new SecretStore(null, directory.resolve("secrets.enc"), directory.resolve("secrets.key"));
	}

	/**
	 * Install the host's credential store for every {@link #defaultStore()}. Called from the
	 * plugin's {@code preinit}; every instance of the plugin is given a view of the same entries.
	 *
	 * @param credentials the store, or {@code null} when the host offers none
	 */
	public static void useCredentialStore(NuclrCredentialStore credentials) {
		installed = credentials;
	}

	/**
	 * The remembered credentials for a profile.
	 *
	 * @param profileId the profile id
	 * @return the entry, or {@code null} when nothing is remembered for it
	 */
	public synchronized Entry get(String profileId) {
		if (!isUsableKey(profileId)) {
			return null;
		}
		migrate();
		NuclrCredentialStore credentials = credentials();
		if (credentials != null) {
			try {
				Optional<String> secret = credentials.get(profileId);
				if (secret.isPresent()) {
					return new Entry(secret.get(), readSessionToken(credentials, profileId));
				}
			} catch (NuclrCredentialException e) {
				log.debug("No saved S3 secret for {}: {}", profileId, e.getMessage());
			}
		}
		// Only ever non-empty while the file has not been moved: no credential store, or one
		// that refused the move.
		return readLegacy().get(profileId);
	}

	/**
	 * Remember a profile's credentials on this machine, replacing anything held for it.
	 *
	 * @param profileId       the profile id
	 * @param secretAccessKey the secret access key; a blank value removes the entry instead
	 * @param sessionToken    the session token, or {@code null} for a long-lived key
	 * @throws IOException if the credential store is missing or refuses; the message is fit to
	 *                     show a user
	 */
	public synchronized void put(String profileId, String secretAccessKey, String sessionToken) throws IOException {

		if (!isUsableKey(profileId)) {
			return;
		}
		if (secretAccessKey == null || secretAccessKey.isBlank()) {
			remove(profileId);
			return;
		}
		migrate();

		NuclrCredentialStore credentials = credentials();
		if (credentials == null) {
			throw new IOException("This machine has no OS credential store Commander can use, so the secret"
					+ " is kept for this session only.");
		}
		try {
			credentials.set(profileId, secretAccessKey);
			if (sessionToken == null || sessionToken.isBlank()) {
				deleteSessionToken(credentials, profileId, 0);
			} else {
				setSessionToken(credentials, profileId, sessionToken);
			}
		} catch (NuclrCredentialException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/**
	 * Store a session token in parts, then clear any parts a longer token left behind.
	 *
	 * <p>A key without its token is worse than nothing saved: it would be sent alone and rejected.
	 * So a token that cannot be stored takes the key with it.
	 */
	private static void setSessionToken(NuclrCredentialStore credentials, String profileId, String sessionToken)
			throws NuclrCredentialException {
		try {
			int parts = (sessionToken.length() + CHUNK_CHARS - 1) / CHUNK_CHARS;
			if (parts > MAX_CHUNKS) {
				throw new NuclrCredentialException(NuclrCredentialException.Reason.ACCESS_FAILED,
						"The session token is too long to save.");
			}
			for (int i = 0; i < parts; i++) {
				int end = Math.min(sessionToken.length(), (i + 1) * CHUNK_CHARS);
				credentials.set(sessionKey(profileId, i), sessionToken.substring(i * CHUNK_CHARS, end));
			}
			deleteSessionToken(credentials, profileId, parts);
		} catch (NuclrCredentialException e) {
			try {
				credentials.delete(profileId);
				deleteSessionToken(credentials, profileId, 0);
			} catch (NuclrCredentialException ignored) {
				// The original failure is the one worth reporting.
			}
			throw e;
		}
	}

	/** The token's parts joined, or {@code null} when there is none. */
	private static String readSessionToken(NuclrCredentialStore credentials, String profileId)
			throws NuclrCredentialException {
		var token = new StringBuilder();
		for (int i = 0; i < MAX_CHUNKS; i++) {
			Optional<String> part = credentials.get(sessionKey(profileId, i));
			if (part.isEmpty()) {
				break;
			}
			token.append(part.get());
		}
		return token.isEmpty() ? null : token.toString();
	}

	/** Delete the token's parts from {@code first} on, stopping at the first that is not there. */
	private static void deleteSessionToken(NuclrCredentialStore credentials, String profileId, int first)
			throws NuclrCredentialException {
		for (int i = first; i < MAX_CHUNKS; i++) {
			String key = sessionKey(profileId, i);
			if (credentials.get(key).isEmpty()) {
				break;
			}
			credentials.delete(key);
		}
	}

	static String sessionKey(String profileId, int part) {
		return part == 0 ? profileId + SESSION_SUFFIX : profileId + SESSION_SUFFIX + "." + part;
	}

	/**
	 * Forget a profile's remembered credentials.
	 *
	 * @param profileId the profile id
	 * @throws IOException if the credential store or the legacy file cannot be changed
	 */
	public synchronized void remove(String profileId) throws IOException {

		if (!isUsableKey(profileId)) {
			return;
		}
		migrate();
		NuclrCredentialStore credentials = credentials();
		if (credentials != null) {
			try {
				credentials.delete(profileId);
				deleteSessionToken(credentials, profileId, 0);
			} catch (NuclrCredentialException e) {
				throw new IOException(e.getMessage(), e);
			}
		}
		removeLegacy(profileId);
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

	private NuclrCredentialStore credentials() {
		return explicit != null ? explicit : installed;
	}

	/**
	 * The credential store refuses blank keys and keys with NUL or '|'. Profile ids are generated
	 * UUIDs and always pass; this guards ids that reached the store some other way.
	 */
	private static boolean isUsableKey(String profileId) {
		return profileId != null && !profileId.isBlank()
				&& profileId.indexOf('|') < 0 && profileId.indexOf('\0') < 0;
	}

	// -------------------------------------------------------------------------
	// Moving the legacy file into the credential store
	// -------------------------------------------------------------------------

	/**
	 * Move every entry of the legacy file into the credential store, then delete the file and its
	 * key. Tried once per store; a failed move leaves the file as it was, still read, for the next
	 * start to try again.
	 */
	private void migrate() {

		if (migrated) {
			return;
		}
		NuclrCredentialStore credentials = credentials();
		if (credentials == null) {
			return;
		}
		migrated = true;

		if (!Files.isRegularFile(legacyFile)) {
			deleteLegacyFiles();
			return;
		}
		Map<String, Entry> entries = readLegacy();
		try {
			for (var item : entries.entrySet()) {
				if (!isUsableKey(item.getKey()) || item.getValue().secretAccessKey() == null
						|| item.getValue().secretAccessKey().isBlank()) {
					continue;
				}
				credentials.set(item.getKey(), item.getValue().secretAccessKey());
				String token = item.getValue().sessionToken();
				if (token == null || token.isBlank()) {
					deleteSessionToken(credentials, item.getKey(), 0);
				} else {
					setSessionToken(credentials, item.getKey(), token);
				}
			}
		} catch (NuclrCredentialException e) {
			log.warn("Could not move the saved S3 secrets into the OS credential store: {}. [{}] is kept.",
					e.getMessage(), legacyFile);
			return;
		}
		deleteLegacyFiles();
		log.info("Moved {} saved S3 secret(s) into the OS credential store and deleted [{}]",
				entries.size(), legacyFile);
	}

	private void deleteLegacyFiles() {
		try {
			Files.deleteIfExists(legacyFile);
			Files.deleteIfExists(legacyKeyFile);
		} catch (IOException e) {
			log.warn("Could not delete the old S3 secrets file [{}]: {}", legacyFile, e.getMessage());
		}
	}

	/**
	 * Every entry in the legacy file. Forgiving: a file that cannot be read - a lost key, a
	 * truncated write - yields nothing, so the user is asked for the secret again rather than
	 * being locked out of the panel.
	 */
	private Map<String, Entry> readLegacy() {

		if (!Files.isRegularFile(legacyFile) || !Files.isRegularFile(legacyKeyFile)) {
			return new LinkedHashMap<>();
		}

		try {
			byte[] key = Base64.getDecoder().decode(Files.readAllBytes(legacyKeyFile));
			byte[] payload = Base64.getDecoder().decode(Files.readAllBytes(legacyFile));
			if (key.length != LEGACY_KEY_BYTES || payload.length <= LEGACY_IV_BYTES) {
				return new LinkedHashMap<>();
			}

			Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
					new GCMParameterSpec(LEGACY_TAG_BITS, payload, 0, LEGACY_IV_BYTES));
			byte[] plain = cipher.doFinal(payload, LEGACY_IV_BYTES, payload.length - LEGACY_IV_BYTES);

			Map<String, Entry> entries = mapper.readValue(
					new String(plain, StandardCharsets.UTF_8), new TypeReference<Map<String, Entry>>() {});
			return entries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(entries);

		} catch (Exception e) {
			log.warn("Cannot read the old saved S3 secrets [{}]: {}. They will be asked for again.",
					legacyFile, e.getMessage());
			return new LinkedHashMap<>();
		}
	}

	/**
	 * Forget a profile in a legacy file that is still there. Nothing is ever added to that file, so
	 * when the profile was its last entry the file and its key simply go.
	 */
	private void removeLegacy(String profileId) throws IOException {

		Map<String, Entry> entries = readLegacy();
		if (entries.remove(profileId) == null) {
			return;
		}
		if (entries.isEmpty()) {
			deleteLegacyFiles();
			return;
		}
		try {
			byte[] key = Base64.getDecoder().decode(Files.readAllBytes(legacyKeyFile));
			var iv = new byte[LEGACY_IV_BYTES];
			new SecureRandom().nextBytes(iv);
			Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(LEGACY_TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(mapper.writeValueAsString(entries).getBytes(StandardCharsets.UTF_8));
			var payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
			Files.write(legacyFile, Base64.getEncoder().encode(payload));
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Could not update the old saved secrets: " + e.getMessage(), e);
		}
	}
}
