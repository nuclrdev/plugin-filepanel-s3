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
package dev.nuclr.plugin.core.panel.s3.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3FilePanelPlugin;
import dev.nuclr.plugin.core.panel.s3.auth.AwsConfigFiles;
import dev.nuclr.plugin.core.panel.s3.auth.CredentialsResolver;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.S3ProfileStore;
import dev.nuclr.plugin.core.panel.s3.auth.SecretCache;
import dev.nuclr.plugin.core.panel.s3.auth.SecretStore;
import tools.jackson.databind.ObjectMapper;

class S3TransferTest {

	@TempDir
	Path dir;

	private final Keychain keychain = new Keychain();
	private Path file;
	private S3ProfileStore store;
	private SecretStore secrets;
	private S3FilePanelPlugin plugin;

	@BeforeEach
	void setUp() {
		file = dir.resolve("profiles.json");
		store = new S3ProfileStore(file);
		secrets = new SecretStore(keychain, dir.resolve("secrets.enc"), dir.resolve("secrets.key"));
		S3Clients.useStore(store);
		S3Clients.useSecretStore(secrets);
		plugin = new S3FilePanelPlugin();
		plugin.preinit(null);
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
		SecretCache.clear();
		CredentialsResolver.clear();
		S3Clients.useStore(S3ProfileStore.defaultStore());
		S3Clients.useSecretStore(SecretStore.defaultStore());
	}

	/** One action through the plugin's act, as Commander sends it. */
	private Map<String, Object> act(String action, Map<String, Object> input) {
		var data = new HashMap<String, Object>(input);
		data.put("transfer.protocol", 1);
		if (!action.equals(S3Transfer.Describe)) {
			data.putIfAbsent("type", "connections");
		}
		plugin.act(null, action, List.of(), null, data, null);
		return data;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> rows() {
		return (List<Map<String, Object>>) act(S3Transfer.Snapshot, Map.of()).get("rows");
	}

	@Test
	void describeDeclaresTheConnectionsCategoryAndNoSessionToken() {
		var data = act(S3Transfer.Describe, Map.of());

		@SuppressWarnings("unchecked")
		var provider = ((List<Map<String, Object>>) data.get("transfer.providers")).getFirst();
		assertEquals("connections", provider.get("type"));
		assertEquals(1, provider.get("version"));
		assertEquals("S3", provider.get("title"));
		var kinds = new HashMap<String, Object>();
		var suffixes = new HashMap<String, Object>();
		for (Object field : (List<?>) provider.get("fields")) {
			var map = (Map<?, ?>) field;
			String name = (String) map.get("name");
			assertTrue(name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"), name);
			assertFalse(name.toLowerCase().contains("session"), "a session token is never a field: " + name);
			kinds.put(name, map.get("kind"));
			suffixes.put(name, map.get("credentialSuffix"));
		}
		assertEquals("ROUTING", kinds.get("endpoint"));
		assertEquals("ROUTING", kinds.get("region"));
		assertEquals("ROUTING", kinds.get("pathStyleAccess"));
		assertEquals("PLAIN", kinds.get("authMode"));
		assertEquals("PLAIN", kinds.get("accessKeyId"));
		assertEquals("PLAIN", kinds.get("rememberSecret"));
		assertEquals("PLAIN", kinds.get("awsProfileName"));
		assertEquals("SECRET", kinds.get("secretAccessKey"));
		assertEquals("", suffixes.get("secretAccessKey"));
		assertEquals(1, kinds.values().stream().filter("SECRET"::equals).count());
	}

	@Test
	void aSnapshotNeverCarriesASecret() throws Exception {
		var profile = accessKey("a", "prod");
		profile.setEndpoint("https://admin:hunter2@minio.example.com:9000/?X-Amz-Security-Token=tok&keep=1");
		store.upsert(profile);
		secrets.put("a", "the-secret-key", "the-session-token");

		var row = rows().getFirst();

		assertEquals("a", row.get("rowId"));
		assertEquals("prod", row.get("label"));
		assertEquals(true, row.get("hasSecrets"));
		var fields = (Map<?, ?>) row.get("fields");
		assertEquals("https://minio.example.com:9000/?keep=1", fields.get("endpoint"));
		assertEquals("AKIAEXAMPLE", fields.get("accessKeyId"));
		assertEquals("ACCESS_KEY", fields.get("authMode"));
		assertEquals("true", fields.get("rememberSecret"));
		String all = fields.toString();
		assertFalse(all.contains("hunter2") || all.contains("tok&") || all.contains("the-secret-key")
				|| all.contains("the-session-token"), all);
		fields.values().forEach(value -> assertTrue(value instanceof String));
	}

	@Test
	void readSecretsGivesTheSecretAccessKeyAndNeverTheSessionToken() throws Exception {
		store.upsert(accessKey("a", "prod"));
		secrets.put("a", "the-secret-key", "the-session-token");
		rows();

		var answer = (Map<?, ?>) act(S3Transfer.ReadSecrets, Map.of("rowId", "a")).get("secrets");

		assertEquals(Map.of("secretAccessKey", "the-secret-key"), answer);
	}

	@Test
	void readSecretsFindsAKeyStillInThePre109File() throws Exception {
		writeLegacy(Map.of("old", new SecretStore.Entry("legacy-secret", "legacy-token")));
		keychain.failWrites = true; // the move fails, so the key is only in the old file
		store.upsert(accessKey("old", "legacy"));

		assertEquals(true, rows().getFirst().get("hasSecrets"));
		var answer = (Map<?, ?>) act(S3Transfer.ReadSecrets, Map.of("rowId", "old")).get("secrets");

		assertEquals(Map.of("secretAccessKey", "legacy-secret"), answer);
	}

	@Test
	void aProfileFromAwsFilesExportsOnlyTheProfileName() throws Exception {
		var profile = new S3Profile();
		profile.setId("p");
		profile.setAuthMode(S3Profile.AuthMode.AWS_PROFILE);
		profile.setAwsProfileName("work");
		store.upsert(profile);

		var row = rows().getFirst();

		assertEquals(false, row.get("hasSecrets"));
		assertEquals("work", ((Map<?, ?>) row.get("fields")).get("awsProfileName"));
		assertEquals("AWS_PROFILE", ((Map<?, ?>) row.get("fields")).get("authMode"));
	}

	@Test
	void anEntryWithoutAnIdIsStillExportable() throws Exception {
		Files.writeString(file, "[{\"id\":\"\",\"name\":\"legacy\"},{\"id\":\"b\",\"name\":\"other\"}]");

		var first = rows();
		var second = rows();

		assertEquals(List.of("#1", "b"), first.stream().map(row -> row.get("rowId")).toList());
		assertEquals(first.stream().map(row -> row.get("rowId")).toList(),
				second.stream().map(row -> row.get("rowId")).toList(), "row ids are stable between snapshots");
		assertEquals(false, first.getFirst().get("hasSecrets"));
		assertEquals(Map.of(), act(S3Transfer.ReadSecrets, Map.of("rowId", "#1")).get("secrets"));
	}

	@Test
	void findEmbeddedSecretsNamesAnEndpointThatStillCarriesOne() {
		assertEquals(List.of("endpoint"), embedded("https://u:p@minio:9000"));
		assertEquals(List.of("endpoint"), embedded("https://minio:9000/?X-Amz-Signature=abc"));
		assertEquals(List.of("endpoint"), embedded("minio:9000?x-amz-security-token=abc"));
		assertEquals(List.of(), embedded("https://minio.example.com:9000"));
		assertEquals(List.of(), embedded("https://minio:9000/?region=x"));
		assertEquals(List.of(), act(S3Transfer.FindEmbeddedSecrets, Map.of("fields", Map.of("name", "x")))
				.get("fieldNames"));
	}

	private Object embedded(String endpoint) {
		return act(S3Transfer.FindEmbeddedSecrets, Map.of("fields", Map.of("endpoint", endpoint))).get("fieldNames");
	}

	@Test
	void applyAppendsUnderTheGivenIdsKeepsTheRestAndNeverWritesTheKeychain() throws Exception {
		store.upsert(accessKey("existing", "local"));
		secrets.put("existing", "kept-secret", null);
		var before = new LinkedHashMap<>(keychain.entries);

		var data = act(S3Transfer.Apply, Map.of("items", List.of(
				item("new-1", Map.of("name", "minio", "authMode", "ACCESS_KEY", "accessKeyId", "AKIANEW",
						"rememberSecret", "true", "region", "eu-west-1",
						"endpoint", "https://root:crafted@minio.example.com:9000?X-Amz-Security-Token=t",
						"pathStyleAccess", "true", "bucket", "data", "prefix", "reports/")),
				item("new-2", Map.of("authMode", "SSO", "awsProfileName", "corp")))));

		var ids = store.loadAsSaved().stream().map(S3Profile::getId).toList();
		assertEquals(List.of("existing", "new-1", "new-2"), ids);
		S3Profile minio = store.byId("new-1");
		assertEquals("minio", minio.getName());
		assertEquals("AKIANEW", minio.getAccessKeyId());
		assertTrue(minio.isRememberSecret());
		assertEquals("eu-west-1", minio.getRegion());
		assertEquals("https://minio.example.com:9000", minio.getEndpoint(), "a crafted file cannot keep a password");
		assertTrue(minio.isPathStyleAccess());
		assertEquals("data", minio.getBucket());
		assertEquals("reports/", minio.getPrefix());
		S3Profile sso = store.byId("new-2");
		assertEquals(S3Profile.AuthMode.SSO, sso.getAuthMode());
		assertEquals("corp", sso.getAwsProfileName());
		assertFalse(data.containsKey("recoveredSecrets"), "nothing S3 could use is ever recovered");
		assertEquals(before, keychain.entries, "apply never writes the keychain");
		assertFalse(Files.readString(file).contains("crafted"));
	}

	@Test
	void anImportedProfileUsesTheKeyCommanderStoresUnderItsNewId() throws Exception {
		act(S3Transfer.Apply, Map.of("items", List.of(item("new-1",
				Map.of("authMode", "ACCESS_KEY", "accessKeyId", "AKIANEW", "rememberSecret", "true")))));
		// What Commander does after apply returns: newId + credentialSuffix ("").
		keychain.entries.put("new-1", "imported-secret");

		var resolver = new CredentialsResolver(new AwsConfigFiles(dir.resolve("none-c"), dir.resolve("none-k")),
				secrets);
		var credentials = resolver.resolve(store.byId("new-1"));

		assertEquals("AKIANEW", credentials.accessKeyId());
		assertEquals("imported-secret", credentials.secretAccessKey());
		assertNull(credentials.sessionToken());
	}

	@Test
	void applyOverAnUnreadableStoreThrowsAndChangesNothing() throws Exception {
		Files.writeString(file, "{not json");
		byte[] before = Files.readAllBytes(file);

		assertThrows(IllegalStateException.class,
				() -> act(S3Transfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"))))));
		assertThrows(IllegalStateException.class, () -> act(S3Transfer.Remove, Map.of("ids", List.of("n"))));
		assertArrayEquals(before, Files.readAllBytes(file));
	}

	@Test
	void aFailedWriteThrowsAndLeavesTheFileAsItWas() throws Exception {
		store.upsert(accessKey("existing", "local"));
		byte[] before = Files.readAllBytes(file);
		Files.createDirectory(dir.resolve("profiles.json.tmp")); // the write cannot land

		assertThrows(UncheckedIOException.class,
				() -> act(S3Transfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"))))));
		assertArrayEquals(before, Files.readAllBytes(file));
	}

	@Test
	void anUnknownAuthModeIsRefusedBeforeAnythingIsWritten() throws Exception {
		store.upsert(accessKey("existing", "local"));
		byte[] before = Files.readAllBytes(file);

		assertThrows(IllegalArgumentException.class, () -> act(S3Transfer.Apply,
				Map.of("items", List.of(item("n", Map.of("authMode", "NOPE"))))));
		assertArrayEquals(before, Files.readAllBytes(file));
	}

	@Test
	void removeDeletesOnlyTheGivenIds() throws Exception {
		store.upsert(accessKey("keep", "a"));
		store.upsert(accessKey("drop", "b"));

		act(S3Transfer.Remove, Map.of("ids", List.of("drop", "unknown")));

		assertEquals(List.of("keep"), store.loadAsSaved().stream().map(S3Profile::getId).toList());
	}

	@Test
	void anotherProtocolVersionOrCategoryIsNotAnswered() {
		var data = new HashMap<String, Object>();
		data.put("transfer.protocol", 2);
		plugin.act(null, S3Transfer.Describe, List.of(), null, data, null);
		assertEquals(Map.of("transfer.protocol", 2), data);

		var other = act(S3Transfer.Snapshot, Map.of("type", "profiles"));
		assertFalse(other.containsKey("rows"));
		var noType = new HashMap<String, Object>(Map.of("transfer.protocol", 1));
		plugin.act(null, S3Transfer.Snapshot, List.of(), null, noType, null);
		assertFalse(noType.containsKey("rows"));
	}

	@Test
	void everyExportedFieldIsDeclaredAndNotSecret() {
		var profile = accessKey("x", "n");
		profile.setAwsProfileName("p");
		profile.setEndpoint("https://e");
		profile.setPathStyleAccess(true);
		profile.setBucket("b");
		profile.setPrefix("p/");
		var exported = new ArrayList<>(S3Transfer.fields(profile).keySet());

		assertTrue(S3Transfer.fieldNames().containsAll(exported), exported.toString());
		assertFalse(exported.contains("secretAccessKey"));
	}

	private static S3Profile accessKey(String id, String name) {
		var profile = new S3Profile();
		profile.setId(id);
		profile.setName(name);
		profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
		profile.setAccessKeyId("AKIAEXAMPLE");
		profile.setRememberSecret(true);
		profile.setRegion("us-east-1");
		return profile;
	}

	private static Map<String, Object> item(String id, Map<String, String> fields) {
		return Map.of("newId", id, "fields", fields, "keyFiles", Map.of());
	}

	/** The file exactly as SecretStore wrote it before 1.0.9: base64(iv + AES-GCM(json)), key beside it. */
	private void writeLegacy(Map<String, SecretStore.Entry> entries) throws Exception {
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

	/** The OS credential store in memory; {@link #failWrites} stands in for a locked keychain. */
	private static final class Keychain implements NuclrCredentialStore {

		final Map<String, String> entries = new LinkedHashMap<>();
		boolean failWrites;

		@Override
		public Optional<String> get(String key) {
			return Optional.ofNullable(entries.get(key));
		}

		@Override
		public void set(String key, String secret) throws NuclrCredentialException {
			if (failWrites) {
				throw new NuclrCredentialException(NuclrCredentialException.Reason.ACCESS_FAILED, "Locked.");
			}
			entries.put(key, secret);
		}

		@Override
		public void delete(String key) {
			entries.remove(key);
		}
	}
}
