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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.api.S3BucketEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import lombok.extern.slf4j.Slf4j;

/**
 * The virtual, path-less resource behind every row of the S3 panel.
 *
 * <p>There are six kinds, matching the levels the panel navigates: the {@code S3} root that lists
 * saved profiles, one entry per profile, a bucket, a folder inside a bucket (an object-key prefix),
 * a leaf object, and two synthetic rows — "load more" for a listing that ran past its page, and a
 * search-results container.
 *
 * <p>Every one carries {@link #MARKER} so {@link S3FilePanelPlugin#supports} can claim it, and every
 * one has a {@code null} {@link #getPath() path}. That absence is deliberate and load-bearing: it
 * keeps the local filesystem plugin from claiming these rows, and it is what routes navigation back
 * here. An object stays path-less even after its bytes are sitting in a local temporary file, so
 * pressing Enter still opens it through this plugin rather than handing the temporary file to a
 * desktop application.
 */
@Slf4j
public final class S3Resource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	/** Metadata flag marking a resource as belonging to the S3 panel. */
	static final String MARKER = "nuclr.s3.panel";

	/** Metadata key naming which kind of resource this is. */
	static final String KIND = "nuclr.s3.kind";

	static final String KIND_ROOT = "root";
	static final String KIND_PROFILE = "profile";
	static final String KIND_BUCKET = "bucket";
	static final String KIND_OBJECT_DIR = "object-dir";
	static final String KIND_OBJECT = "object";
	static final String KIND_LOAD_MORE = "load-more";
	static final String KIND_SEARCH_RESULTS = "search-results";

	/** Metadata: the owning connection profile's id, carried by everything below the root. */
	static final String PROFILE_ID = "nuclr.s3.profileId";

	/** Metadata: the bucket name, on bucket, folder, object and load-more resources. */
	static final String BUCKET = "nuclr.s3.bucket";

	/** Metadata: the object-key prefix, on bucket, folder and load-more resources. */
	static final String PREFIX = "nuclr.s3.prefix";

	/** Metadata: the full object key, on object resources. */
	static final String OBJECT_KEY = "nuclr.s3.objectKey";

	/** Metadata: the continuation token a load-more row resumes from. */
	static final String CONTINUATION = "nuclr.s3.continuation";

	/** Metadata on a search-results root: the hits, the panel title, and where the search started. */
	private static final String SEARCH_HITS = "nuclr.s3.search.hits";
	private static final String SEARCH_TITLE = "nuclr.s3.search.title";
	private static final String SEARCH_ORIGIN = "nuclr.s3.search.origin";

	/** Stable identifier of the single S3 root mount. */
	public static final String ROOT_UUID = "s3://";

	/** Columns for the profile list at the root. */
	public static final List<String> PROFILE_COLUMNS = List.of("Name", "Location", "Region", "Authentication");

	/** Columns for the bucket list inside a profile. */
	public static final List<String> BUCKET_COLUMNS = List.of("Name", "Created", "Region");

	/** Columns for an object listing inside a bucket. */
	public static final List<String> OBJECT_COLUMNS = List.of("Name", "Size", "Modified", "Storage class");

	private static final DateTimeFormatter DISPLAY_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final LocalDateTime EPOCH = LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);

	private S3Resource() {
		super(null);
		getMetadata().put(MARKER, Boolean.TRUE);
		// Non-null timestamps so the panel's date sort comparators never trip over a virtual row.
		setCreatedDateTime(EPOCH);
		setLastModifiedDateTime(EPOCH);
		setLastAccessDateTime(EPOCH);
	}

	// -------------------------------------------------------------------------
	// Factories
	// -------------------------------------------------------------------------

	/**
	 * The single S3 root — the mount shown in the Alt+F1 / Alt+F2 drive selector, which lists the
	 * saved connection profiles.
	 *
	 * @return the root resource
	 */
	public static S3Resource root() {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID);
		resource.setFullPath(ROOT_UUID);
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_ROOT);
		resource.rename("S3");
		return resource;
	}

	/**
	 * The synthetic {@code ..} entry leading from a profile back to the profile list.
	 *
	 * @return the parent navigation resource
	 */
	public static S3Resource parentToRoot() {
		S3Resource resource = root();
		resource.rename("..");
		return resource;
	}

	/**
	 * One saved connection profile, shown at the root and navigable into its buckets.
	 *
	 * @param profile the profile
	 * @return the profile row
	 */
	public static S3Resource profile(S3Profile profile) {
		S3Resource resource = profileRef(profile.getId());
		resource.rename(profile.displayName());
		resource.getMetadata().put("Location", profile.address());
		resource.getMetadata().put("Region", profile.effectiveRegion());
		resource.getMetadata().put("Authentication", profile.describeAuth());
		return resource;
	}

	/**
	 * A bare reference to a profile by id, used as the panel's current location and as the
	 * {@code ..} target coming back from a bucket.
	 *
	 * @param profileId the profile id
	 * @return the profile reference
	 */
	public static S3Resource profileRef(String profileId) {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID + "profile/" + profileId);
		resource.setFullPath(resource.getUuid());
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_PROFILE);
		resource.getMetadata().put(PROFILE_ID, profileId);
		resource.rename(profileId);
		return resource;
	}

	/**
	 * The synthetic {@code ..} entry leading from a bucket listing back to the bucket list.
	 *
	 * @param profileId the owning profile id
	 * @return the parent navigation resource
	 */
	public static S3Resource parentToProfile(String profileId) {
		S3Resource resource = profileRef(profileId);
		resource.rename("..");
		return resource;
	}

	/**
	 * A bucket row, navigable into its contents.
	 *
	 * @param profileId the owning profile id
	 * @param bucket    the bucket
	 * @return the bucket row
	 */
	public static S3Resource bucket(String profileId, S3BucketEntry bucket) {
		S3Resource resource = bucketRef(profileId, bucket.name());
		resource.getMetadata().put("Created", formatTimestamp(bucket.created()));
		resource.getMetadata().put("Region", bucket.region() == null ? "-" : bucket.region());
		return resource;
	}

	/**
	 * A bare reference to a bucket root.
	 *
	 * @param profileId the owning profile id
	 * @param bucket    the bucket name
	 * @return the bucket reference
	 */
	public static S3Resource bucketRef(String profileId, String bucket) {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID + profileId + '/' + bucket);
		resource.setFullPath("s3://" + bucket);
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_BUCKET);
		resource.getMetadata().put(PROFILE_ID, profileId);
		resource.getMetadata().put(BUCKET, bucket);
		resource.getMetadata().put(PREFIX, "");
		resource.rename(bucket);
		resource.getMetadata().put("Created", "-");
		resource.getMetadata().put("Region", "-");
		return resource;
	}

	/**
	 * A folder inside a bucket, identified by an object-key prefix. Used for sub-folders, for the
	 * panel's current location, and for the {@code ..} row.
	 *
	 * @param profileId   the owning profile id
	 * @param bucket      the bucket name
	 * @param prefix      the key prefix, ending in {@code /}, or {@code ""} for the bucket root
	 * @param displayName the label to show
	 * @return the folder row
	 */
	public static S3Resource objectDir(String profileId, String bucket, String prefix, String displayName) {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID + profileId + '/' + bucket + '/' + prefix);
		resource.setFullPath("s3://" + bucket + '/' + prefix);
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_OBJECT_DIR);
		resource.getMetadata().put(PROFILE_ID, profileId);
		resource.getMetadata().put(BUCKET, bucket);
		resource.getMetadata().put(PREFIX, prefix);
		resource.rename(displayName);
		resource.getMetadata().put("Size", "-");
		resource.getMetadata().put("Modified", "-");
		resource.getMetadata().put("Storage class", "-");
		return resource;
	}

	/**
	 * A leaf object row.
	 *
	 * @param profileId the owning profile id
	 * @param bucket    the bucket name
	 * @param entry     the object as the listing reported it
	 * @return the object row
	 */
	public static S3Resource object(String profileId, String bucket, S3ObjectEntry entry) {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID + profileId + '/' + bucket + '/' + entry.key());
		resource.setFullPath("s3://" + bucket + '/' + entry.key());
		resource.setFolder(false);
		resource.getMetadata().put(KIND, KIND_OBJECT);
		resource.getMetadata().put(PROFILE_ID, profileId);
		resource.getMetadata().put(BUCKET, bucket);
		resource.getMetadata().put(OBJECT_KEY, entry.key());
		resource.rename(entry.name());
		resource.setLength(Math.max(entry.size(), 0));
		if (entry.lastModified() > 0) {
			LocalDateTime modified = LocalDateTime.ofInstant(
					java.time.Instant.ofEpochMilli(entry.lastModified()), ZoneId.systemDefault());
			resource.setLastModifiedDateTime(modified);
			resource.setCreatedDateTime(modified);
		}
		resource.getMetadata().put("Size", formatSize(entry.size()));
		resource.getMetadata().put("Modified", formatTimestamp(entry.lastModified()));
		resource.getMetadata().put("Storage class",
				entry.storageClass() == null || entry.storageClass().isBlank() ? "STANDARD" : entry.storageClass());
		return resource;
	}

	/**
	 * The {@code ..} target for an object listing: the parent prefix, or the bucket list when
	 * already at the bucket root.
	 *
	 * @param profileId the owning profile id
	 * @param bucket    the bucket name
	 * @param prefix    the prefix currently open
	 * @return the parent navigation resource
	 */
	public static S3Resource objectParent(String profileId, String bucket, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return parentToProfile(profileId);
		}
		return objectDir(profileId, bucket, parentPrefix(prefix), "..");
	}

	/**
	 * The synthetic row that fetches the next page of the listing it sits at the end of.
	 *
	 * @param profileId    the owning profile id
	 * @param bucket       the bucket name
	 * @param prefix       the prefix being listed
	 * @param continuation the continuation token to resume from
	 * @return the load-more row
	 */
	public static S3Resource loadMore(String profileId, String bucket, String prefix, String continuation) {
		S3Resource resource = new S3Resource();
		resource.setUuid(ROOT_UUID + profileId + '/' + bucket + '/' + prefix + " load-more");
		resource.setFullPath(resource.getUuid());
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_LOAD_MORE);
		resource.getMetadata().put(PROFILE_ID, profileId);
		resource.getMetadata().put(BUCKET, bucket);
		resource.getMetadata().put(PREFIX, prefix);
		resource.getMetadata().put(CONTINUATION, continuation);
		resource.rename("▼ Load more…");
		resource.getMetadata().put("Size", "-");
		resource.getMetadata().put("Modified", "-");
		resource.getMetadata().put("Storage class", "-");
		return resource;
	}

	/**
	 * A synthetic search-results folder whose children are the given hits rather than a real
	 * listing. Navigating to it shows the results as a temporary panel; {@code ..} returns to
	 * {@code origin}.
	 *
	 * @param hits   the matching resources
	 * @param title  the panel title
	 * @param origin the folder the search started from
	 * @return the search-results root
	 */
	public static S3Resource searchResults(List<NuclrResource> hits, String title, NuclrResource origin) {
		S3Resource resource = new S3Resource();
		String label = title == null || title.isBlank() ? "Search results" : title;
		resource.setUuid(ROOT_UUID + "search/" + UUID.randomUUID());
		resource.setFullPath(resource.getUuid());
		resource.setFolder(true);
		resource.getMetadata().put(KIND, KIND_SEARCH_RESULTS);
		resource.getMetadata().put(SEARCH_HITS, new ArrayList<>(hits));
		resource.getMetadata().put(SEARCH_TITLE, label);
		if (origin != null) {
			resource.getMetadata().put(SEARCH_ORIGIN, origin);
		}
		resource.rename(label);
		return resource;
	}

	// -------------------------------------------------------------------------
	// Content
	// -------------------------------------------------------------------------

	/**
	 * Stream this object's content.
	 *
	 * <p>The object is downloaded to a local temporary file on first access and served from
	 * {@link S3TempFiles} afterwards. The temporary file is deliberately <b>not</b> adopted as this
	 * resource's {@link #getPath() path}: staying path-less is what keeps the resource routed to
	 * this plugin, so Enter opens it here rather than handing the download to the desktop. The
	 * host's own quick-view providers select by {@link #getName() name} and read through this
	 * stream, so no S3-specific viewer is needed.
	 *
	 * @param options open options passed through to the local file
	 * @return the object's content
	 * @throws Exception if the object cannot be downloaded
	 */
	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (!KIND_OBJECT.equals(getMetadata().get(KIND))) {
			return super.openInputStream(options);
		}
		return Files.newInputStream(materialize(), options);
	}

	/**
	 * Download this object once and return the local file backing it.
	 *
	 * @return the local file holding the object's bytes
	 * @throws IOException if the object cannot be downloaded
	 */
	public synchronized Path materialize() throws IOException {

		String profileId = profileId(this);
		String bucket = bucketName(this);
		String key = objectKey(this);
		if (profileId == null || bucket == null || key == null) {
			throw new IOException("Not a downloadable S3 object: " + getName());
		}

		String cacheKey = S3TempFiles.cacheKey(profileId, bucket, key);
		Path cached = S3TempFiles.cached(cacheKey);
		if (cached != null) {
			return cached;
		}

		S3Client client = S3Clients.byProfileId(profileId);
		if (client == null) {
			throw new IOException("The connection profile for " + getName() + " is no longer available.");
		}

		long startNanos = System.nanoTime();
		Path temp = Files.createTempFile("nuclr-s3-", "-" + S3TempFiles.sanitize(getName()));
		S3Result<Long> result = client.downloadToFile(bucket, key, temp, null, null);
		if (!result.isOk()) {
			Files.deleteIfExists(temp);
			throw new IOException("Could not download s3://" + bucket + '/' + key + ": "
					+ result.errorOrNull().describe());
		}

		S3TempFiles.register(cacheKey, temp);
		log.info("Downloaded s3://{}/{} for viewing in {} ms", bucket, key,
				(System.nanoTime() - startNanos) / 1_000_000L);
		return temp;
	}

	// -------------------------------------------------------------------------
	// Predicates and accessors
	// -------------------------------------------------------------------------

	/**
	 * Whether a resource belongs to this panel.
	 *
	 * @param resource any resource
	 * @return {@code true} when the S3 panel owns it
	 */
	public static boolean isS3Resource(NuclrResource resource) {
		return resource != null
				&& resource.getPath() == null
				&& Boolean.TRUE.equals(resource.getMetadata().get(MARKER));
	}

	/**
	 * Whether this is the S3 root (the profile list).
	 *
	 * @param resource the resource
	 * @return {@code true} for the root
	 */
	public static boolean isRoot(NuclrResource resource) {
		return isKind(resource, KIND_ROOT);
	}

	/**
	 * Whether this is a connection profile entry.
	 *
	 * @param resource the resource
	 * @return {@code true} for a profile
	 */
	public static boolean isProfile(NuclrResource resource) {
		return isKind(resource, KIND_PROFILE);
	}

	/**
	 * Whether this is a bucket root.
	 *
	 * @param resource the resource
	 * @return {@code true} for a bucket
	 */
	public static boolean isBucket(NuclrResource resource) {
		return isKind(resource, KIND_BUCKET);
	}

	/**
	 * Whether this is a folder inside a bucket.
	 *
	 * @param resource the resource
	 * @return {@code true} for an object-key prefix
	 */
	public static boolean isObjectDir(NuclrResource resource) {
		return isKind(resource, KIND_OBJECT_DIR);
	}

	/**
	 * Whether this is a leaf object.
	 *
	 * @param resource the resource
	 * @return {@code true} for an object
	 */
	public static boolean isObject(NuclrResource resource) {
		return isKind(resource, KIND_OBJECT);
	}

	/**
	 * Whether this is the synthetic "load more" row.
	 *
	 * @param resource the resource
	 * @return {@code true} for a load-more row
	 */
	public static boolean isLoadMore(NuclrResource resource) {
		return isKind(resource, KIND_LOAD_MORE);
	}

	/**
	 * Whether this is a search-results container.
	 *
	 * @param resource the resource
	 * @return {@code true} for search results
	 */
	public static boolean isSearchResults(NuclrResource resource) {
		return isKind(resource, KIND_SEARCH_RESULTS);
	}

	/**
	 * Whether this resource is somewhere inside a bucket — a bucket root or a folder in one.
	 *
	 * @param resource the resource
	 * @return {@code true} when object operations apply
	 */
	public static boolean isInsideBucket(NuclrResource resource) {
		return isBucket(resource) || isObjectDir(resource);
	}

	/**
	 * The owning profile id.
	 *
	 * @param resource the resource
	 * @return the profile id, or {@code null}
	 */
	public static String profileId(NuclrResource resource) {
		return metaString(resource, PROFILE_ID);
	}

	/**
	 * The bucket name.
	 *
	 * @param resource the resource
	 * @return the bucket name, or {@code null}
	 */
	public static String bucketName(NuclrResource resource) {
		return metaString(resource, BUCKET);
	}

	/**
	 * The object-key prefix of a bucket, folder or load-more resource.
	 *
	 * @param resource the resource
	 * @return the prefix, never {@code null}
	 */
	public static String objectPrefix(NuclrResource resource) {
		String prefix = metaString(resource, PREFIX);
		return prefix == null ? "" : prefix;
	}

	/**
	 * The full object key of an object resource.
	 *
	 * @param resource the resource
	 * @return the key, or {@code null}
	 */
	public static String objectKey(NuclrResource resource) {
		return metaString(resource, OBJECT_KEY);
	}

	/**
	 * The continuation token a load-more row resumes from.
	 *
	 * @param resource the resource
	 * @return the token, or {@code null}
	 */
	public static String continuation(NuclrResource resource) {
		return metaString(resource, CONTINUATION);
	}

	/**
	 * The hits carried by a search-results container.
	 *
	 * @param resource the search-results resource
	 * @return the hits, never {@code null}
	 */
	@SuppressWarnings("unchecked")
	public static List<NuclrResource> searchHits(NuclrResource resource) {
		Object value = resource == null ? null : resource.getMetadata().get(SEARCH_HITS);
		return value instanceof List<?> list ? (List<NuclrResource>) list : List.of();
	}

	/**
	 * The title of a search-results container.
	 *
	 * @param resource the search-results resource
	 * @return the title
	 */
	public static String searchTitle(NuclrResource resource) {
		String title = metaString(resource, SEARCH_TITLE);
		return title == null ? "Search results" : title;
	}

	/**
	 * The folder a search started from.
	 *
	 * @param resource the search-results resource
	 * @return the origin folder, or {@code null}
	 */
	public static NuclrResource searchOrigin(NuclrResource resource) {
		Object value = resource == null ? null : resource.getMetadata().get(SEARCH_ORIGIN);
		return value instanceof NuclrResource origin ? origin : null;
	}

	// -------------------------------------------------------------------------
	// Key helpers
	// -------------------------------------------------------------------------

	/**
	 * The parent of a prefix: {@code "a/b/c/"} becomes {@code "a/b/"}, and {@code "a/"} becomes
	 * {@code ""}.
	 *
	 * @param prefix the prefix
	 * @return the parent prefix
	 */
	public static String parentPrefix(String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return "";
		}
		String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
		int slash = trimmed.lastIndexOf('/');
		return slash < 0 ? "" : trimmed.substring(0, slash + 1);
	}

	/**
	 * The prefix an object key sits in: {@code "a/b/c.txt"} becomes {@code "a/b/"}.
	 *
	 * @param key the object key
	 * @return the containing prefix
	 */
	public static String keyPrefix(String key) {
		if (key == null) {
			return "";
		}
		int slash = key.lastIndexOf('/');
		return slash < 0 ? "" : key.substring(0, slash + 1);
	}

	/**
	 * The label for a folder: the bucket name at the root, otherwise the last segment with its
	 * trailing slash.
	 *
	 * @param bucket the bucket name
	 * @param prefix the prefix
	 * @return the display label
	 */
	public static String folderLabel(String bucket, String prefix) {
		if (prefix == null || prefix.isEmpty()) {
			return bucket;
		}
		return S3ObjectEntry.lastSegment(prefix) + "/";
	}

	// -------------------------------------------------------------------------
	// Formatting
	// -------------------------------------------------------------------------

	/**
	 * Format a byte count the way the panel shows sizes.
	 *
	 * @param bytes the size, or a negative value when unknown
	 * @return the formatted size
	 */
	public static String formatSize(long bytes) {
		if (bytes < 0) {
			return "-";
		}
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = {"KB", "MB", "GB", "TB", "PB"};
		double value = bytes;
		int unit = -1;
		do {
			value /= 1024.0;
			unit++;
		} while (value >= 1024 && unit < units.length - 1);
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}

	/**
	 * Format an epoch-millisecond timestamp for display.
	 *
	 * @param epochMillis the timestamp, or a negative value when unknown
	 * @return the formatted timestamp
	 */
	public static String formatTimestamp(long epochMillis) {
		if (epochMillis <= 0) {
			return "-";
		}
		return DISPLAY_STAMP.format(
				LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
	}

	/** Set the name and keep the "Name" display column in sync with it. */
	private void rename(String newName) {
		setName(newName);
		getMetadata().put("Name", newName);
	}

	private static boolean isKind(NuclrResource resource, String kind) {
		return resource != null && kind.equals(resource.getMetadata().get(KIND));
	}

	private static String metaString(NuclrResource resource, String key) {
		if (resource == null) {
			return null;
		}
		Object value = resource.getMetadata().get(key);
		return value == null ? null : value.toString();
	}
}
