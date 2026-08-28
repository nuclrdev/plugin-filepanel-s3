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
package dev.nuclr.plugin.core.panel.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;

/**
 * Covers the resource model: the key arithmetic that turns a flat namespace into navigable folders,
 * and the invariants the panel's routing depends on.
 */
class S3ResourceTest {

	private static final String PROFILE = "profile-1";

	@Test
	@DisplayName("Every S3 row is path-less, which is what routes it back to this plugin")
	void resourcesArePathLess() {

		// A row with a local path would be claimed by the filesystem plugin, and activating an
		// object would open a temporary file instead of navigating here.
		assertNull(S3Resource.root().getPath());
		assertNull(S3Resource.profileRef(PROFILE).getPath());
		assertNull(S3Resource.bucketRef(PROFILE, "my-bucket").getPath());
		assertNull(S3Resource.objectDir(PROFILE, "my-bucket", "a/", "a/").getPath());
		assertNull(S3Resource.object(PROFILE, "my-bucket",
				S3ObjectEntry.object("a/b.txt", 1, -1, "STANDARD", null)).getPath());

		assertTrue(S3Resource.isS3Resource(S3Resource.root()));
		assertTrue(S3Resource.isS3Resource(S3Resource.bucketRef(PROFILE, "my-bucket")));
	}

	@Test
	@DisplayName("Each kind is recognised as itself and not as the others")
	void kindsAreDistinct() {

		assertTrue(S3Resource.isRoot(S3Resource.root()));
		assertTrue(S3Resource.isProfile(S3Resource.profileRef(PROFILE)));
		assertTrue(S3Resource.isBucket(S3Resource.bucketRef(PROFILE, "b")));
		assertTrue(S3Resource.isObjectDir(S3Resource.objectDir(PROFILE, "b", "a/", "a/")));
		assertTrue(S3Resource.isLoadMore(S3Resource.loadMore(PROFILE, "b", "a/", "token")));

		assertFalse(S3Resource.isObject(S3Resource.bucketRef(PROFILE, "b")));
		assertFalse(S3Resource.isBucket(S3Resource.objectDir(PROFILE, "b", "a/", "a/")));
	}

	@Test
	@DisplayName("A bucket and a folder both count as inside a bucket")
	void insideBucketCoversBothLevels() {

		assertTrue(S3Resource.isInsideBucket(S3Resource.bucketRef(PROFILE, "b")));
		assertTrue(S3Resource.isInsideBucket(S3Resource.objectDir(PROFILE, "b", "a/", "a/")));
		assertFalse(S3Resource.isInsideBucket(S3Resource.root()));
		assertFalse(S3Resource.isInsideBucket(S3Resource.profileRef(PROFILE)));
	}

	@Test
	@DisplayName("An object row carries its key, size and timestamp")
	void objectRowCarriesItsMetadata() {

		long modified = 1_772_000_000_000L;
		var resource = S3Resource.object(PROFILE, "my-bucket",
				S3ObjectEntry.object("reports/q1.pdf", 2048, modified, "GLACIER", "etag"));

		assertEquals("q1.pdf", resource.getName());
		assertEquals("reports/q1.pdf", S3Resource.objectKey(resource));
		assertEquals("my-bucket", S3Resource.bucketName(resource));
		assertEquals(PROFILE, S3Resource.profileId(resource));
		assertEquals("s3://my-bucket/reports/q1.pdf", resource.getFullPath());
		assertEquals(2048, resource.getLength());
		assertEquals("GLACIER", resource.getMetadata().get("Storage class"));
		assertFalse(resource.isFolder());
	}

	@Test
	@DisplayName("An object with no reported storage class is shown as STANDARD")
	void missingStorageClassDefaults() {

		var resource = S3Resource.object(PROFILE, "b", S3ObjectEntry.object("a.txt", 1, -1, null, null));

		assertEquals("STANDARD", resource.getMetadata().get("Storage class"));
	}

	@Test
	@DisplayName("Going up from a folder lands on its parent prefix")
	void parentOfAFolderIsItsPrefix() {

		var parent = S3Resource.objectParent(PROFILE, "b", "logs/2026/03/");

		assertTrue(S3Resource.isObjectDir(parent));
		assertEquals("logs/2026/", S3Resource.objectPrefix(parent));
		assertEquals("..", parent.getName());
	}

	@Test
	@DisplayName("Going up from a bucket root lands on the bucket list, not on an empty prefix")
	void parentOfABucketRootIsTheProfile() {

		var parent = S3Resource.objectParent(PROFILE, "b", "");

		assertTrue(S3Resource.isProfile(parent));
		assertEquals("..", parent.getName());
		assertEquals(PROFILE, S3Resource.profileId(parent));
	}

	@Test
	@DisplayName("Prefix arithmetic walks up one level at a time")
	void parentPrefix() {
		assertEquals("a/b/", S3Resource.parentPrefix("a/b/c/"));
		assertEquals("", S3Resource.parentPrefix("a/"));
		assertEquals("", S3Resource.parentPrefix(""));
		assertEquals("a/", S3Resource.parentPrefix("a/b"));
	}

	@Test
	@DisplayName("A key reduces to the prefix that contains it")
	void keyPrefix() {
		assertEquals("a/b/", S3Resource.keyPrefix("a/b/c.txt"));
		assertEquals("", S3Resource.keyPrefix("root.txt"));
		assertEquals("", S3Resource.keyPrefix(null));
	}

	@Test
	@DisplayName("A folder is labelled by its own segment, and a bucket root by the bucket")
	void folderLabels() {
		assertEquals("my-bucket", S3Resource.folderLabel("my-bucket", ""));
		assertEquals("2026/", S3Resource.folderLabel("my-bucket", "logs/2026/"));
	}

	@Test
	@DisplayName("Sizes are formatted in human units")
	void sizeFormatting() {
		assertEquals("-", S3Resource.formatSize(-1));
		assertEquals("0 B", S3Resource.formatSize(0));
		assertEquals("512 B", S3Resource.formatSize(512));
		assertEquals("1.0 KB", S3Resource.formatSize(1024));
		assertEquals("1.5 MB", S3Resource.formatSize(1024 * 1024 * 3 / 2));
		assertEquals("2.0 GB", S3Resource.formatSize(2L * 1024 * 1024 * 1024));
	}

	@Test
	@DisplayName("A missing timestamp is shown as a dash rather than 1970")
	void missingTimestampIsADash() {
		assertEquals("-", S3Resource.formatTimestamp(-1));
		assertEquals("-", S3Resource.formatTimestamp(0));
		assertFalse(S3Resource.formatTimestamp(1_772_000_000_000L).equals("-"));
	}

	@Test
	@DisplayName("Two rows for the same object are equal, and rows for different ones are not")
	void identityFollowsTheKey() {

		var first = S3Resource.object(PROFILE, "b", S3ObjectEntry.object("a.txt", 1, -1, null, null));
		var same = S3Resource.object(PROFILE, "b", S3ObjectEntry.object("a.txt", 999, 12345, "GLACIER", "x"));
		var other = S3Resource.object(PROFILE, "b", S3ObjectEntry.object("z.txt", 1, -1, null, null));

		assertEquals(first, same, "identity is the object, not the metadata a listing happened to report");
		assertFalse(first.equals(other));
	}

	@Test
	@DisplayName("The same key in two profiles is two different rows")
	void profilesDoNotCollide() {

		var one = S3Resource.object("profile-a", "b", S3ObjectEntry.object("a.txt", 1, -1, null, null));
		var two = S3Resource.object("profile-b", "b", S3ObjectEntry.object("a.txt", 1, -1, null, null));

		assertFalse(one.equals(two));
	}

	@Test
	@DisplayName("A load-more row remembers where to resume")
	void loadMoreCarriesItsToken() {

		var row = S3Resource.loadMore(PROFILE, "b", "logs/", "token-abc");

		assertEquals("token-abc", S3Resource.continuation(row));
		assertEquals("logs/", S3Resource.objectPrefix(row));
		assertTrue(row.isFolder(), "it has to be navigable for Enter to fetch the next page");
	}

	@Test
	@DisplayName("Search results carry their hits and the folder to return to")
	void searchResultsCarryTheirContext() {

		var origin = S3Resource.objectDir(PROFILE, "b", "logs/", "logs/");
		var hit = S3Resource.object(PROFILE, "b", S3ObjectEntry.object("logs/a.txt", 1, -1, null, null));
		var results = S3Resource.searchResults(java.util.List.of(hit), "Find: *.txt", origin);

		assertTrue(S3Resource.isSearchResults(results));
		assertEquals(1, S3Resource.searchHits(results).size());
		assertEquals("Find: *.txt", S3Resource.searchTitle(results));
		assertEquals(origin, S3Resource.searchOrigin(results));
	}
}
