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
package dev.nuclr.plugin.core.panel.s3.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3FilePanelPlugin;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.S3ProfileStore;
import dev.nuclr.plugin.core.panel.s3.auth.SecretCache;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The actions against an in-memory S3 server, run the way the commander runs them:
 * through {@code act} on an instance that shows no pane, with no other panel, no
 * selection and no focused resource.
 */
class S3ActionsTest {

	/** Records what an action reports, and answers its confirmations. */
	private static final class Recording implements NuclrPluginCallback {

		Map<String, Object> result;
		String error;
		boolean completed;
		boolean approve;
		final List<String> confirmTitles = new ArrayList<>();
		final List<String> confirmDiffs = new ArrayList<>();

		@Override
		public void onStart(String description) {
		}

		@Override
		public void onProgress(long current, long total) {
		}

		@Override
		public void onComplete() {
			completed = true;
		}

		@Override
		public void onError(String description, Exception e) {
			error = description;
		}

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public void onResult(Map<String, Object> result) {
			this.result = result;
		}

		@Override
		public boolean confirm(String title, String details, String diff) {
			confirmTitles.add(title);
			confirmDiffs.add(diff);
			return approve;
		}
	}

	private FakeS3 s3;
	private FakeS3 other;
	private S3FilePanelPlugin plugin;
	private S3Profile main;
	private S3Profile scoped;
	private S3Profile remote;

	@BeforeEach
	void setUp(@TempDir Path dir) throws Exception {
		s3 = new FakeS3();
		other = new FakeS3();
		var store = new S3ProfileStore(dir.resolve("profiles.json"));
		S3Clients.useStore(store);

		main = profile("main-id", "Main", s3, "");
		scoped = profile("scoped-id", "Scoped", s3, "assets");
		scoped.setPrefix("web");
		remote = profile("remote-id", "Remote", other, "");
		store.upsert(main);
		store.upsert(scoped);
		store.upsert(remote);

		plugin = new S3FilePanelPlugin();
		plugin.preinit(null);
		plugin.init();
	}

	private static S3Profile profile(String id, String name, FakeS3 server, String bucket) {
		var profile = new S3Profile();
		profile.setId(id);
		profile.setName(name);
		profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
		profile.setAccessKeyId("AKIAEXAMPLEEXAMPLE");
		profile.setRegion("us-east-1");
		profile.setEndpoint(server.endpoint());
		profile.setPathStyleAccess(true);
		profile.setBucket(bucket);
		SecretCache.put(id, "not-a-real-secret", null);
		return profile;
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
		s3.close();
		other.close();
		S3Clients.useStore(S3ProfileStore.defaultStore());
	}

	private Recording run(String id, Map<String, Object> args, boolean approve) {
		var callback = new Recording();
		callback.approve = approve;
		plugin.act(null, id, List.of(), null, new HashMap<>(args), callback);
		return callback;
	}

	private Map<String, Object> ok(String id, Map<String, Object> args) {
		Recording callback = run(id, args, false);
		assertNull(callback.error, () -> id + " failed: " + callback.error);
		assertTrue(callback.completed);
		assertNotNull(callback.result);
		return callback.result;
	}

	private String error(String id, Map<String, Object> args) {
		Recording callback = run(id, args, false);
		assertNotNull(callback.error, () -> id + " should fail, but returned " + callback.result);
		assertFalse(callback.completed);
		return callback.error;
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Map<String, Object> map, String key) {
		return (T) map.get(key);
	}

	private static List<Object> keys(Map<String, Object> listing) {
		List<Map<String, Object>> entries = get(listing, "entries");
		return entries.stream().map(entry -> entry.get("key")).toList();
	}

	// ------------------------------------------------------------------
	// actions.json
	// ------------------------------------------------------------------

	@Test
	void actionsJsonDeclaresExactlyTheHandledActions() throws Exception {
		String json = plugin.getActionsJson();
		assertNotNull(json, "actions.json should be found next to the plugin class");
		JsonNode root = new ObjectMapper().readTree(json);
		var ids = new HashSet<String>();
		for (JsonNode action : root.get("actions")) {
			String id = action.get("id").asString();
			ids.add(id);
			String doc = action.get("doc").asString();
			assertNotNull(S3FilePanelPlugin.class.getResource(doc), id + ": missing doc " + doc);
			String expected = id.equals(S3Actions.WRITE) || id.equals(S3Actions.COPY) ? "write" : "read";
			assertEquals(expected, action.get("class").asString(), id);
		}
		assertEquals(S3Actions.IDS, ids);
	}

	// ------------------------------------------------------------------
	// Profiles and buckets
	// ------------------------------------------------------------------

	@Test
	void listsProfilesWithoutCredentials() {
		Map<String, Object> result = ok(S3Actions.PROFILES_LIST, Map.of());
		List<Map<String, Object>> profiles = get(result, "profiles");
		assertEquals(List.of("Main", "Remote", "Scoped"), profiles.stream().map(p -> p.get("name")).toList());
		Map<String, Object> scopedEntry = profiles.get(2);
		assertEquals("assets", scopedEntry.get("bucket"));
		assertEquals("web/", scopedEntry.get("prefix"));
		assertEquals("access-key", scopedEntry.get("auth"));
		assertFalse(profiles.toString().contains("AKIA"), "no key ids in the result");
	}

	@Test
	void listsBuckets() {
		s3.bucket("assets").bucket("logs");
		Map<String, Object> result = ok(S3Actions.BUCKETS_LIST, Map.of("profile", "main"));
		List<Map<String, Object>> buckets = get(result, "buckets");
		assertEquals(List.of("assets", "logs"), buckets.stream().map(b -> b.get("name")).toList());
	}

	@Test
	void unknownProfileNamesTheSavedOnes() {
		String error = error(S3Actions.BUCKETS_LIST, Map.of("profile", "nope"));
		assertTrue(error.contains("Main") && error.contains("Scoped"), error);
	}

	@Test
	void bucketIsRequiredForAProfileNotTiedToOne() {
		String error = error(S3Actions.LIST, Map.of("profile", "Main"));
		assertTrue(error.contains("s3.buckets.list"), error);
	}

	// ------------------------------------------------------------------
	// s3.list
	// ------------------------------------------------------------------

	@Test
	void listsFoldersAndObjects() {
		s3.put("logs", "a.txt", "a").put("logs", "web/1.log", "1").put("logs", "web/2026/2.log", "2");
		Map<String, Object> root = ok(S3Actions.LIST, Map.of("profile", "Main", "bucket", "logs"));
		assertEquals(List.of("a.txt", "web/"), keys(root));

		Map<String, Object> web = ok(S3Actions.LIST, Map.of("profile", "Main", "bucket", "logs", "prefix", "web/"));
		assertEquals(List.of("web/1.log", "web/2026/"), keys(web));
		List<Map<String, Object>> entries = get(web, "entries");
		assertEquals("object", entries.get(0).get("type"));
		assertEquals(1L, entries.get(0).get("size"));
		assertEquals("folder", entries.get(1).get("type"));

		Map<String, Object> all = ok(S3Actions.LIST,
				Map.of("profile", "Main", "bucket", "logs", "prefix", "web/", "recursive", true));
		assertEquals(List.of("web/1.log", "web/2026/2.log"), keys(all));
	}

	@Test
	void pagesThroughContinuationTokens() {
		s3.put("logs", "a", "1").put("logs", "b", "2").put("logs", "c", "3");
		Map<String, Object> first = ok(S3Actions.LIST, Map.of("profile", "Main", "bucket", "logs", "limit", 2));
		assertEquals(List.of("a", "b"), keys(first));
		assertEquals(true, first.get("truncated"));

		Map<String, Object> second = ok(S3Actions.LIST, Map.of("profile", "Main", "bucket", "logs", "limit", 2,
				"continuationToken", first.get("nextContinuationToken")));
		assertEquals(List.of("c"), keys(second));
		assertEquals(false, second.get("truncated"));
		assertNull(second.get("nextContinuationToken"));
	}

	@Test
	void aBucketScopedProfileDefaultsToItsBucketAndPrefix() {
		s3.put("assets", "web/logo.png", "png").put("assets", "other/x", "x");
		Map<String, Object> result = ok(S3Actions.LIST, Map.of("profile", "Scoped"));
		assertEquals("assets", result.get("bucket"));
		assertEquals("web/", result.get("prefix"));
		assertEquals(List.of("web/logo.png"), keys(result));
	}

	// ------------------------------------------------------------------
	// s3.head / s3.read
	// ------------------------------------------------------------------

	@Test
	void headReportsSize() {
		s3.put("logs", "a.txt", "hello");
		Map<String, Object> result = ok(S3Actions.HEAD, Map.of("profile", "Main", "bucket", "logs", "key", "/a.txt"));
		assertEquals("a.txt", result.get("key"));
		assertEquals(5L, result.get("size"));
	}

	@Test
	void missingObjectSuggestsListing() {
		s3.bucket("logs");
		String error = error(S3Actions.HEAD, Map.of("profile", "Main", "bucket", "logs", "key", "nope.txt"));
		assertTrue(error.contains("s3.list"), error);
	}

	@Test
	void readsTextBinaryAndTruncates() {
		s3.put("logs", "a.txt", "hello world").put("logs", "b.bin", new byte[] { 0, 1, 2, (byte) 0xff });
		Map<String, Object> text = ok(S3Actions.READ, Map.of("profile", "Main", "bucket", "logs", "key", "a.txt"));
		assertEquals("utf-8", text.get("encoding"));
		assertEquals("hello world", text.get("content"));
		assertEquals(false, text.get("truncated"));

		Map<String, Object> binary = ok(S3Actions.READ, Map.of("profile", "Main", "bucket", "logs", "key", "b.bin"));
		assertEquals("base64", binary.get("encoding"));
		assertEquals(Base64.getEncoder().encodeToString(new byte[] { 0, 1, 2, (byte) 0xff }), binary.get("content"));

		Map<String, Object> cut = ok(S3Actions.READ,
				Map.of("profile", "Main", "bucket", "logs", "key", "a.txt", "maxBytes", 5));
		assertEquals("hello", cut.get("content"));
		assertEquals(true, cut.get("truncated"));
		assertEquals(11L, cut.get("size"));
	}

	// ------------------------------------------------------------------
	// s3.write
	// ------------------------------------------------------------------

	@Test
	void writesANewObjectWithoutAsking() {
		s3.bucket("cfg");
		Recording callback = run(S3Actions.WRITE,
				Map.of("profile", "Main", "bucket", "cfg", "key", "flags.json", "content", "{}"), false);
		assertNull(callback.error, callback.error);
		assertTrue(callback.confirmTitles.isEmpty());
		assertEquals(true, callback.result.get("created"));
		assertEquals("{}", s3.text("cfg", "flags.json"));
	}

	@Test
	void overwritingAsksWithADiff() {
		s3.put("cfg", "flags.json", "beta=false\n");
		Recording approved = run(S3Actions.WRITE,
				Map.of("profile", "Main", "bucket", "cfg", "key", "flags.json", "content", "beta=true\n"), true);
		assertNull(approved.error, approved.error);
		assertEquals(1, approved.confirmTitles.size());
		String diff = approved.confirmDiffs.get(0);
		assertTrue(diff.contains("-beta=false") && diff.contains("+beta=true"), diff);
		assertEquals(false, approved.result.get("created"));
		assertEquals("beta=true\n", s3.text("cfg", "flags.json"));
	}

	@Test
	void declinedOverwriteWritesNothing() {
		s3.put("cfg", "flags.json", "beta=false\n");
		Recording declined = run(S3Actions.WRITE,
				Map.of("profile", "Main", "bucket", "cfg", "key", "flags.json", "content", "beta=true\n"), false);
		assertNotNull(declined.error);
		assertTrue(declined.error.contains("not approved"), declined.error);
		assertEquals("beta=false\n", s3.text("cfg", "flags.json"));
		assertTrue(s3.uploads.isEmpty());
	}

	@Test
	void writingTheSameContentChangesNothing() {
		s3.put("cfg", "flags.json", "same");
		Recording callback = run(S3Actions.WRITE,
				Map.of("profile", "Main", "bucket", "cfg", "key", "flags.json", "content", "same"), false);
		assertNull(callback.error, callback.error);
		assertEquals(true, callback.result.get("unchanged"));
		assertTrue(callback.confirmTitles.isEmpty());
		assertTrue(s3.uploads.isEmpty());
	}

	@Test
	void writesBase64Content() {
		s3.bucket("cfg");
		ok(S3Actions.WRITE, Map.of("profile", "Main", "bucket", "cfg", "key", "b.bin", "content",
				Base64.getEncoder().encodeToString("bytes".getBytes()), "encoding", "base64"));
		assertEquals("bytes", s3.text("cfg", "b.bin"));
	}

	// ------------------------------------------------------------------
	// s3.copy
	// ------------------------------------------------------------------

	@Test
	void copiesServerSideWithinAProfile() {
		s3.put("logs", "a.txt", "hello").bucket("backup");
		Map<String, Object> result = ok(S3Actions.COPY, Map.of("fromProfile", "Main", "fromBucket", "logs",
				"fromKey", "a.txt", "toBucket", "backup", "toKey", "2026/a.txt"));
		assertEquals(true, result.get("serverSide"));
		assertEquals(List.of("/logs/a.txt"), s3.copies);
		assertEquals("hello", s3.text("backup", "2026/a.txt"));
	}

	@Test
	void copiesBetweenProfilesThroughThisMachine() {
		s3.put("logs", "a.txt", "hello");
		other.bucket("archive");
		Map<String, Object> result = ok(S3Actions.COPY, Map.of("fromProfile", "Main", "fromBucket", "logs",
				"fromKey", "a.txt", "toProfile", "Remote", "toBucket", "archive", "toKey", "a.txt"));
		assertEquals(false, result.get("serverSide"));
		assertEquals(5L, result.get("bytes"));
		assertEquals("hello", other.text("archive", "a.txt"));
	}

	@Test
	void copyNeverReplacesSilently() {
		s3.put("logs", "a.txt", "new").put("logs", "b.txt", "old");
		String error = error(S3Actions.COPY,
				Map.of("fromProfile", "Main", "fromBucket", "logs", "fromKey", "a.txt", "toKey", "b.txt"));
		assertTrue(error.contains("overwrite: true"), error);
		assertEquals("old", s3.text("logs", "b.txt"));

		Recording declined = run(S3Actions.COPY, Map.of("fromProfile", "Main", "fromBucket", "logs", "fromKey", "a.txt",
				"toKey", "b.txt", "overwrite", true), false);
		assertNotNull(declined.error);
		assertEquals("old", s3.text("logs", "b.txt"));

		Recording approved = run(S3Actions.COPY, Map.of("fromProfile", "Main", "fromBucket", "logs", "fromKey", "a.txt",
				"toKey", "b.txt", "overwrite", true), true);
		assertNull(approved.error, approved.error);
		assertEquals(true, approved.result.get("replaced"));
		assertEquals("new", s3.text("logs", "b.txt"));
	}

	@Test
	void copyRefusesFoldersAndItself() {
		s3.put("logs", "a.txt", "x");
		String folder = error(S3Actions.COPY,
				Map.of("fromProfile", "Main", "fromBucket", "logs", "fromKey", "web/", "toKey", "x/"));
		assertTrue(folder.contains("recursive"), folder);
		String self = error(S3Actions.COPY,
				Map.of("fromProfile", "Main", "fromBucket", "logs", "fromKey", "a.txt", "toKey", "a.txt"));
		assertTrue(self.contains("onto itself"), self);
	}
}
