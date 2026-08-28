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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers when a remembered secret survives and when it must not.
 *
 * <p>The distinction is the whole point of the feature: routine housekeeping has to leave a saved
 * key alone, or remembering it would be worthless, while a key the endpoint has rejected has to go,
 * or the user would be stuck re-offering it forever.
 */
class RememberedSecretTest {

	private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

	@AfterEach
	void clearCaches() {
		SecretCache.clear();
		CredentialsResolver.clear();
	}

	@Test
	@DisplayName("A remembered secret is used instead of prompting")
	void usesTheRememberedSecret(@TempDir Path dir) throws Exception {

		SecretStore secrets = storeIn(dir);
		S3Profile profile = profile();
		secrets.put(profile.getId(), SECRET, null);

		// No prompt can appear in a headless test, so resolving at all is the assertion: had the
		// store been ignored, this would have tried to open a dialog instead of returning.
		AwsCredentials credentials = resolver(secrets).resolve(profile);

		assertEquals(SECRET, credentials.secretAccessKey());
		assertEquals("AKIAEXAMPLEEXAMPLE", credentials.accessKeyId());
	}

	@Test
	@DisplayName("A remembered secret is used even when the profile flag was never set")
	void doesNotGateReadingOnTheProfileFlag(@TempDir Path dir) throws Exception {

		// The flag records the choice made in the profile dialog; ticking "remember" in the
		// connect prompt writes the store without touching it. Reading must not depend on it.
		SecretStore secrets = storeIn(dir);
		S3Profile profile = profile();
		profile.setRememberSecret(false);
		secrets.put(profile.getId(), SECRET, null);

		assertEquals(SECRET, resolver(secrets).resolve(profile).secretAccessKey());
	}

	@Test
	@DisplayName("Editing a profile leaves the remembered secret in place")
	void invalidateKeepsTheSavedSecret(@TempDir Path dir) throws Exception {

		SecretStore secrets = storeIn(dir);
		S3Profile profile = profile();
		secrets.put(profile.getId(), SECRET, null);

		// What happens on every profile edit, and at the end of every session.
		resolver(secrets).invalidate(profile);

		assertTrue(secrets.has(profile.getId()),
				"changing a region must not throw the saved key away");
	}

	@Test
	@DisplayName("Credentials the endpoint rejects are forgotten")
	void rejectionDropsTheSavedSecret(@TempDir Path dir) throws Exception {

		SecretStore secrets = storeIn(dir);
		S3Profile profile = profile();
		secrets.put(profile.getId(), SECRET, null);

		resolver(secrets).rejected(profile);

		assertFalse(secrets.has(profile.getId()),
				"a rejected key must not be re-offered on the next attempt");
	}

	@Test
	@DisplayName("Rejecting a profile that saved nothing is harmless")
	void rejectionWithoutASavedSecret(@TempDir Path dir) {
		resolver(storeIn(dir)).rejected(profile());
	}

	private static S3Profile profile() {
		S3Profile profile = new S3Profile();
		profile.setId("remembered-secret-test");
		profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
		profile.setAccessKeyId("AKIAEXAMPLEEXAMPLE");
		profile.setRememberSecret(true);
		return profile;
	}

	private static CredentialsResolver resolver(SecretStore secrets) {
		return new CredentialsResolver(AwsConfigFiles.user(), secrets);
	}

	private static SecretStore storeIn(Path dir) {
		return new SecretStore(dir.resolve("secrets.enc"), dir.resolve("secrets.key"));
	}
}
