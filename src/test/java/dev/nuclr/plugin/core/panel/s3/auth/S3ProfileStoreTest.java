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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the saved-profile file: that it round-trips, that it survives being broken, and — the point
 * of the whole design — that it never contains a secret.
 */
class S3ProfileStoreTest {

	private static S3Profile accessKeyProfile() {
		var profile = new S3Profile();
		profile.setName("Production");
		profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
		profile.setAccessKeyId("AKIAEXAMPLE");
		profile.setRegion("eu-west-1");
		return profile;
	}

	@Test
	@DisplayName("A profile round-trips through the file")
	void roundTrips(@TempDir Path directory) throws IOException {

		var store = new S3ProfileStore(directory.resolve("profiles.json"));
		S3Profile saved = accessKeyProfile();
		saved.setEndpoint("https://minio.example.com:9000");
		saved.setPathStyleAccess(true);
		saved.setBucket("data");
		saved.setPrefix("reports/");
		store.upsert(saved);

		S3Profile loaded = store.byId(saved.getId());

		assertNotNull(loaded);
		assertEquals("Production", loaded.getName());
		assertEquals(S3Profile.AuthMode.ACCESS_KEY, loaded.getAuthMode());
		assertEquals("AKIAEXAMPLE", loaded.getAccessKeyId());
		assertEquals("eu-west-1", loaded.getRegion());
		assertEquals("https://minio.example.com:9000", loaded.getEndpoint());
		assertTrue(loaded.isPathStyleAccess());
		assertEquals("data", loaded.getBucket());
		assertEquals("reports/", loaded.getPrefix());
	}

	@Test
	@DisplayName("The saved file contains no secret, whatever was entered")
	void secretsAreNeverWritten(@TempDir Path directory) throws IOException {

		// The whole reason the secret lives in SecretCache rather than on the profile: a synced or
		// backed-up profile file must not be a credential leak.
		Path file = directory.resolve("profiles.json");
		var store = new S3ProfileStore(file);
		S3Profile profile = accessKeyProfile();
		store.upsert(profile);
		SecretCache.put(profile.getId(), "super-secret-value", "session-token-value");

		String contents = Files.readString(file, StandardCharsets.UTF_8);

		assertFalse(contents.contains("super-secret-value"));
		assertFalse(contents.contains("session-token-value"));
		assertTrue(contents.contains("AKIAEXAMPLE"), "the access key id is not a secret and is saved");
		SecretCache.drop(profile.getId());
	}

	@Test
	@DisplayName("Profiles come back sorted by display name")
	void loadIsSorted(@TempDir Path directory) throws IOException {

		var store = new S3ProfileStore(directory.resolve("profiles.json"));
		var zebra = accessKeyProfile();
		zebra.setName("Zebra");
		var alpha = accessKeyProfile();
		alpha.setName("alpha");
		store.save(List.of(zebra, alpha));

		List<S3Profile> loaded = store.load();

		assertEquals(List.of("alpha", "Zebra"), loaded.stream().map(S3Profile::getName).toList());
	}

	@Test
	@DisplayName("Upsert replaces by id rather than adding a duplicate")
	void upsertReplaces(@TempDir Path directory) throws IOException {

		var store = new S3ProfileStore(directory.resolve("profiles.json"));
		S3Profile profile = accessKeyProfile();
		store.upsert(profile);

		profile.setName("Renamed");
		store.upsert(profile);

		assertEquals(1, store.load().size());
		assertEquals("Renamed", store.byId(profile.getId()).getName());
	}

	@Test
	@DisplayName("Removing a profile leaves the others alone")
	void removeKeepsTheRest(@TempDir Path directory) throws IOException {

		var store = new S3ProfileStore(directory.resolve("profiles.json"));
		S3Profile keep = accessKeyProfile();
		keep.setName("Keep");
		S3Profile drop = accessKeyProfile();
		drop.setName("Drop");
		store.save(List.of(keep, drop));

		store.remove(drop.getId());

		assertEquals(1, store.load().size());
		assertEquals("Keep", store.load().get(0).getName());
		assertNull(store.byId(drop.getId()));
	}

	@Test
	@DisplayName("A missing file reads as an empty list")
	void missingFileIsEmpty(@TempDir Path directory) {
		assertTrue(new S3ProfileStore(directory.resolve("nothing.json")).load().isEmpty());
	}

	@Test
	@DisplayName("A corrupt file costs the saved list, not the use of the panel")
	void corruptFileIsEmpty(@TempDir Path directory) throws IOException {

		Path file = directory.resolve("profiles.json");
		Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

		assertTrue(new S3ProfileStore(file).load().isEmpty());
	}

	@Test
	@DisplayName("Unknown fields from a newer version are ignored rather than fatal")
	void unknownFieldsAreIgnored(@TempDir Path directory) throws IOException {

		Path file = directory.resolve("profiles.json");
		Files.writeString(file, """
				[ { "id": "abc", "name": "Future", "authMode": "ACCESS_KEY",
				    "region": "us-east-1", "somethingNewerVersionsAdded": true } ]
				""", StandardCharsets.UTF_8);

		List<S3Profile> loaded = new S3ProfileStore(file).load();

		assertEquals(1, loaded.size());
		assertEquals("Future", loaded.get(0).getName());
	}

	@Test
	@DisplayName("A profile missing its id is given one")
	void missingIdIsGenerated(@TempDir Path directory) throws IOException {

		Path file = directory.resolve("profiles.json");
		Files.writeString(file, "[ { \"name\": \"No id\", \"region\": \"us-east-1\" } ]", StandardCharsets.UTF_8);

		List<S3Profile> loaded = new S3ProfileStore(file).load();

		assertEquals(1, loaded.size());
		assertNotNull(loaded.get(0).getId());
		assertFalse(loaded.get(0).getId().isBlank());
		assertEquals(S3Profile.AuthMode.ACCESS_KEY, loaded.get(0).getAuthMode(), "a missing mode defaults");
	}

	@Test
	@DisplayName("A profile with no name describes where it points")
	void unnamedProfileDescribesItself() {

		var bucketScoped = new S3Profile();
		bucketScoped.setBucket("photos");
		bucketScoped.setPrefix("2026/");
		assertEquals("s3://photos/2026/", bucketScoped.displayName());

		var custom = new S3Profile();
		custom.setEndpoint("https://minio.example.com");
		assertEquals("https://minio.example.com", custom.displayName());

		var plain = new S3Profile();
		plain.setRegion("eu-north-1");
		assertEquals("S3 (eu-north-1)", plain.displayName());
	}

	@Test
	@DisplayName("A starting prefix is normalised to a usable key prefix")
	void prefixIsNormalised() {

		var profile = new S3Profile();

		profile.setPrefix("reports");
		assertEquals("reports/", profile.effectivePrefix(), "a prefix always ends in a slash");

		profile.setPrefix("/reports/2026/");
		assertEquals("reports/2026/", profile.effectivePrefix(), "keys have no leading slash");

		profile.setPrefix("   ");
		assertEquals("", profile.effectivePrefix());

		profile.setPrefix(null);
		assertEquals("", profile.effectivePrefix());
	}

	@Test
	@DisplayName("A blank region falls back to the default")
	void blankRegionFallsBack() {

		var profile = new S3Profile();
		profile.setRegion("  ");

		assertEquals(S3Profile.DEFAULT_REGION, profile.effectiveRegion());
	}
}
