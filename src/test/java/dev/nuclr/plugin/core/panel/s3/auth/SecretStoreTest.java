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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers what the secret store promises: it round-trips, it forgets, and it never stores plainly. */
class SecretStoreTest {

	private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

	@Test
	@DisplayName("A saved secret comes back on the next run")
	void roundTripsASecret(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);

		// A second instance over the same files stands in for the next launch of the app.
		SecretStore reopened = storeIn(dir);
		SecretStore.Entry entry = reopened.get("profile-1");

		assertNotNull(entry);
		assertEquals(SECRET, entry.secretAccessKey());
		assertNull(entry.sessionToken());
	}

	@Test
	@DisplayName("A session token is remembered alongside the secret")
	void roundTripsASessionToken(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, "FwoGZXIvYXdzEExampleSessionToken");

		SecretStore.Entry entry = storeIn(dir).get("profile-1");

		assertNotNull(entry);
		assertEquals("FwoGZXIvYXdzEExampleSessionToken", entry.sessionToken());
	}

	@Test
	@DisplayName("The secret is not readable in the file")
	void doesNotWriteTheSecretInClear(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);

		String onDisk = Files.readString(dir.resolve("secrets.enc"), StandardCharsets.UTF_8);

		assertFalse(onDisk.contains(SECRET), "the secret must not appear in the stored file");
		assertFalse(onDisk.contains("profile-1"), "the profile id must not appear either");
	}

	@Test
	@DisplayName("Profiles are kept apart")
	void keepsProfilesApart(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);
		store.put("profile-2", "second-secret", null);

		assertEquals(SECRET, store.get("profile-1").secretAccessKey());
		assertEquals("second-secret", store.get("profile-2").secretAccessKey());
		assertNull(store.get("profile-3"));
	}

	@Test
	@DisplayName("Forgetting one profile leaves the others alone")
	void removesOneProfile(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);
		store.put("profile-2", "second-secret", null);

		store.remove("profile-1");

		assertNull(store.get("profile-1"));
		assertFalse(store.has("profile-1"));
		assertEquals("second-secret", store.get("profile-2").secretAccessKey());
	}

	@Test
	@DisplayName("Forgetting the last profile leaves no file behind")
	void deletesTheFileWhenEmptied(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);
		assertTrue(Files.exists(dir.resolve("secrets.enc")));

		store.remove("profile-1");

		assertFalse(Files.exists(dir.resolve("secrets.enc")), "nothing is left to protect");
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
	@DisplayName("A store whose key is gone asks again rather than failing")
	void survivesALostKeyFile(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);

		// A restored backup, a synced home directory, a half-copied install: the ciphertext is
		// there but the key that opens it is not. The user must be re-prompted, not locked out.
		Files.delete(dir.resolve("secrets.key"));

		assertNull(storeIn(dir).get("profile-1"));
	}

	@Test
	@DisplayName("A corrupt store asks again rather than failing")
	void survivesACorruptFile(@TempDir Path dir) throws Exception {

		SecretStore store = storeIn(dir);
		store.put("profile-1", SECRET, null);
		Files.writeString(dir.resolve("secrets.enc"), "not base64 and not a cipher text");

		assertNull(storeIn(dir).get("profile-1"));
	}

	@Test
	@DisplayName("Reading an absent store is empty, not an error")
	void readsAnAbsentStore(@TempDir Path dir) {
		assertNull(storeIn(dir).get("profile-1"));
		assertFalse(storeIn(dir).has("profile-1"));
	}

	private static SecretStore storeIn(Path dir) {
		return new SecretStore(dir.resolve("secrets.enc"), dir.resolve("secrets.key"));
	}
}
