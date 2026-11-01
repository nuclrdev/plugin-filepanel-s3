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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

/**
 * Covers what the secret store promises: secrets go to the OS credential store and nowhere else,
 * they round-trip and are forgotten, and secrets saved by earlier versions in the encrypted file
 * are moved over without anyone being asked again.
 */
class SecretStoreTest {

	private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

	private static final String TOKEN = "FwoGZXIvYXdzEExampleSessionToken";

	private final InMemoryCredentialStore keychain = new InMemoryCredentialStore();

	@Test
	@DisplayName("A saved secret comes back on the next run, from the credential store")
	void roundTripsASecret(@TempDir Path dir) throws Exception {

		storeIn(dir).put("profile-1", SECRET, null);

		// A second instance over the same credential store stands in for the next launch.
		SecretStore.Entry entry = storeIn(dir).get("profile-1");

		assertNotNull(entry);
		assertEquals(SECRET, entry.secretAccessKey());
		assertNull(entry.sessionToken());
		assertEquals(SECRET, keychain.entries.get("profile-1"));
	}

	@Test
	@DisplayName("A session token is kept under its own key")
	void roundTripsASessionToken(@TempDir Path dir) throws Exception {

		storeIn(dir).put("profile-1", SECRET, TOKEN);

		assertEquals(TOKEN, storeIn(dir).get("profile-1").sessionToken());
		assertEquals(TOKEN, keychain.entries.get("profile-1" + SecretStore.SESSION_SUFFIX));
	}

	@Test
	@DisplayName("A session token too long for one Windows entry is split, and comes back whole")
	void splitsALongSessionToken(@TempDir Path dir) throws Exception {

		String token = "IQoJb3JpZ2luX2VjE".repeat(200);   // 3400 characters

		storeIn(dir).put("profile-1", SECRET, token);

		assertEquals(token, storeIn(dir).get("profile-1").sessionToken());
		assertTrue(keychain.entries.containsKey(SecretStore.sessionKey("profile-1", 3)));
		assertTrue(keychain.entries.values().stream().allMatch(v -> v.length() <= SecretStore.CHUNK_CHARS));
	}

	@Test
	@DisplayName("A shorter token clears the parts a longer one left behind")
	void aShorterTokenClearsStaleParts(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, "x".repeat(2500));
		store.put("profile-1", SECRET, TOKEN);

		assertEquals(TOKEN, store.get("profile-1").sessionToken());
		assertFalse(keychain.entries.containsKey(SecretStore.sessionKey("profile-1", 1)));
		assertFalse(keychain.entries.containsKey(SecretStore.sessionKey("profile-1", 2)));

		store.remove("profile-1");
		assertTrue(keychain.entries.isEmpty(), "forgetting a profile leaves nothing in the keychain");
	}

	@Test
	@DisplayName("Saving a long-lived key drops a session token saved before")
	void replacingWithoutATokenDropsTheOldOne(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, TOKEN);
		store.put("profile-1", "rotated-secret", null);

		assertEquals("rotated-secret", store.get("profile-1").secretAccessKey());
		assertNull(store.get("profile-1").sessionToken());
	}

	@Test
	@DisplayName("Nothing is written to disk")
	void writesNoFile(@TempDir Path dir) throws Exception {

		storeIn(dir).put("profile-1", SECRET, TOKEN);

		try (var files = Files.list(dir)) {
			assertEquals(0, files.count(), "secrets belong in the credential store, not in a file");
		}
	}

	@Test
	@DisplayName("Forgetting one profile leaves the others alone")
	void removesOneProfile(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, TOKEN);
		store.put("profile-2", "second-secret", null);

		store.remove("profile-1");

		assertNull(store.get("profile-1"));
		assertFalse(store.has("profile-1"));
		assertFalse(keychain.entries.containsKey("profile-1" + SecretStore.SESSION_SUFFIX));
		assertEquals("second-secret", store.get("profile-2").secretAccessKey());
	}

	@Test
	@DisplayName("A blank secret removes the entry rather than storing nothing")
	void treatsABlankSecretAsRemoval(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);

		store.put("profile-1", "  ", null);

		assertNull(store.get("profile-1"));
	}

	@Test
	@DisplayName("A token that cannot be stored takes the key with it")
	void aRefusedTokenLeavesNoKeyBehind(@TempDir Path dir) {

		keychain.failWrites = SecretStore.SESSION_SUFFIX;

		IOException failure = assertThrows(IOException.class, () -> storeIn(dir).put("profile-1", SECRET, TOKEN));

		assertEquals("The keychain is locked.", failure.getMessage());
		assertNull(storeIn(dir).get("profile-1"), "a key sent without its token would only be rejected");
	}

	@Test
	@DisplayName("Without a credential store, saving fails and says why")
	void refusesToSaveWithoutACredentialStore(@TempDir Path dir) {

		SecretStore store = new SecretStore(null, dir.resolve("secrets.enc"), dir.resolve("secrets.key"));
		SecretStore.useCredentialStore(null);

		assertThrows(IOException.class, () -> store.put("profile-1", SECRET, null));
		assertNull(store.get("profile-1"));
	}

	@Test
	@DisplayName("Reading an absent entry is empty, not an error")
	void readsAnAbsentEntry(@TempDir Path dir) {
		assertNull(storeIn(dir).get("profile-1"));
		assertFalse(storeIn(dir).has("profile-1"));
	}

	// ---- the encrypted file earlier versions wrote --------------------------------------------

	@Test
	@DisplayName("Secrets in the old encrypted file move to the credential store, and the file goes")
	void movesTheLegacyFile(@TempDir Path dir) throws Exception {

		writeLegacy(dir, Map.of(
				"profile-1", new SecretStore.Entry(SECRET, null),
				"profile-2", new SecretStore.Entry("second-secret", TOKEN)));

		SecretStore store = storeIn(dir);

		assertEquals(SECRET, store.get("profile-1").secretAccessKey());
		assertEquals(TOKEN, store.get("profile-2").sessionToken());
		assertEquals("second-secret", keychain.entries.get("profile-2"));
		assertFalse(Files.exists(dir.resolve("secrets.enc")), "the old file is deleted once moved");
		assertFalse(Files.exists(dir.resolve("secrets.key")), "and so is its key");
	}

	@Test
	@DisplayName("A move the credential store refuses keeps the old file, still readable")
	void keepsTheLegacyFileWhenTheMoveFails(@TempDir Path dir) throws Exception {

		writeLegacy(dir, Map.of("profile-1", new SecretStore.Entry(SECRET, null)));
		keychain.failWrites = "";

		SecretStore store = storeIn(dir);

		assertEquals(SECRET, store.get("profile-1").secretAccessKey(), "no one loses a secret they saved");
		assertTrue(Files.exists(dir.resolve("secrets.enc")), "the next start tries again");
	}

	@Test
	@DisplayName("Without a credential store the old file is still read, and never written")
	void readsTheLegacyFileWithoutACredentialStore(@TempDir Path dir) throws Exception {

		writeLegacy(dir, Map.of("profile-1", new SecretStore.Entry(SECRET, null),
				"profile-2", new SecretStore.Entry("second-secret", null)));
		SecretStore.useCredentialStore(null);
		SecretStore store = new SecretStore(null, dir.resolve("secrets.enc"), dir.resolve("secrets.key"));

		assertEquals(SECRET, store.get("profile-1").secretAccessKey());

		store.remove("profile-1");
		assertNull(store.get("profile-1"), "forgetting still works on the old file");
		assertEquals("second-secret", store.get("profile-2").secretAccessKey());
	}

	@Test
	@DisplayName("An old file whose key is gone is dropped, not an error")
	void survivesALegacyFileWithoutItsKey(@TempDir Path dir) throws Exception {

		writeLegacy(dir, Map.of("profile-1", new SecretStore.Entry(SECRET, null)));
		Files.delete(dir.resolve("secrets.key"));

		assertNull(storeIn(dir).get("profile-1"));
	}

	private SecretStore storeIn(Path dir) {
		return new SecretStore(keychain, dir.resolve("secrets.enc"), dir.resolve("secrets.key"));
	}

	/** The file exactly as SecretStore wrote it before 1.0.9: base64(iv + AES-GCM(json)), key beside it. */
	private static void writeLegacy(Path dir, Map<String, SecretStore.Entry> entries) throws Exception {

		var random = new SecureRandom();
		var key = new byte[32];
		random.nextBytes(key);
		var iv = new byte[12];
		random.nextBytes(iv);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
		byte[] encrypted = cipher.doFinal(new ObjectMapper().writeValueAsString(new LinkedHashMap<>(entries))
				.getBytes(StandardCharsets.UTF_8));

		var payload = new byte[iv.length + encrypted.length];
		System.arraycopy(iv, 0, payload, 0, iv.length);
		System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);

		Files.write(dir.resolve("secrets.enc"), Base64.getEncoder().encode(payload));
		Files.write(dir.resolve("secrets.key"), Base64.getEncoder().encode(key));
	}
}
