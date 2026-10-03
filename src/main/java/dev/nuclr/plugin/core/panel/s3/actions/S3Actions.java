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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.S3TempFiles;
import dev.nuclr.plugin.core.panel.s3.api.S3BucketEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3Endpoint;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.api.S3Xml;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.S3ProfileStore;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the actions this plugin declares in {@code actions.json}: listing saved profiles,
 * buckets and objects, reading and writing objects, and copying them.
 *
 * <p>Actions need no pane, selection or open resource, so they behave the same on a
 * headless plugin instance. They share the panels' client registry ({@link S3Clients}),
 * so an agent and a person browsing the same profile resolve its credentials once, are
 * asked for a secret once, and learn each bucket's region once.
 *
 * <p>Keys are always full object keys, whatever prefix a bucket-scoped profile opens
 * at; a profile's bucket and prefix are only defaults.
 *
 * <p>Error messages go back to whoever asked - often an agent - so they say what to
 * do next, not just what went wrong.
 *
 * <p>This class is the only place the plugin uses SDK API newer than its manifest's
 * {@code platformSdkVersion} ({@code onResult}, {@code confirm}); only Commanders
 * that have that API run actions.
 */
@Slf4j
public final class S3Actions {

	public static final String PROFILES_LIST = "s3.profiles.list";
	public static final String BUCKETS_LIST = "s3.buckets.list";
	public static final String LIST = "s3.list";
	public static final String HEAD = "s3.head";
	public static final String READ = "s3.read";
	public static final String WRITE = "s3.write";
	public static final String COPY = "s3.copy";

	/** Every action id this class handles; must match actions.json exactly. */
	public static final Set<String> IDS = Set.of(PROFILES_LIST, BUCKETS_LIST, LIST, HEAD, READ, WRITE, COPY);

	static final int DEFAULT_LIST_LIMIT = 1000;
	/** S3 returns at most 1000 keys per page. */
	static final int MAX_LIST_LIMIT = 1000;
	static final int DEFAULT_READ_BYTES = 256 * 1024;
	static final int MAX_READ_BYTES = 4 * 1024 * 1024;
	/** Objects larger than this are overwritten without a diff in the confirmation. */
	static final long MAX_DIFF_BYTES = 1024 * 1024;
	/** The largest object S3 copies server-side in one request; larger ones go through this machine. */
	static final long MAX_SERVER_SIDE_COPY = 5L * 1024 * 1024 * 1024;

	private static final S3Actions STANDARD = new S3Actions(S3Clients::store, S3Clients::get);

	private final Supplier<S3ProfileStore> store;
	private final Function<S3Profile, S3Client> clients;

	/**
	 * @param store   where saved profiles are read from
	 * @param clients the client for a profile
	 */
	public S3Actions(Supplier<S3ProfileStore> store, Function<S3Profile, S3Client> clients) {
		this.store = store;
		this.clients = clients;
	}

	/**
	 * Return the instance backed by the user's saved profiles and the shared client
	 * registry.
	 *
	 * @return the standard instance
	 */
	public static S3Actions standard() {
		return STANDARD;
	}

	/**
	 * Return whether {@code actionType} is one of this plugin's declared actions.
	 *
	 * @param actionType the action id passed to {@code act}
	 * @return {@code true} if {@link #run} handles it
	 */
	public static boolean handles(String actionType) {
		return actionType != null && IDS.contains(actionType);
	}

	/**
	 * Run an action and report through the callback: {@code onResult} then
	 * {@code onComplete} on success, {@code onError} otherwise.
	 *
	 * @param id       the action id
	 * @param args     the arguments, already validated against the action's schema
	 * @param callback progress, result, approvals and cancellation
	 */
	public void run(String id, Map<String, Object> args, NuclrPluginCallback callback) {
		Map<String, Object> arguments = args == null ? Map.of() : args;
		try {
			Map<String, Object> result = switch (id) {
				case PROFILES_LIST -> profilesList();
				case BUCKETS_LIST -> bucketsList(arguments);
				case LIST -> list(arguments);
				case HEAD -> head(arguments);
				case READ -> read(arguments, callback);
				case WRITE -> write(arguments, callback);
				case COPY -> copy(arguments, callback);
				default -> throw new ActionException("Unknown action '" + id + "'.");
			};
			callback.onResult(result);
			callback.onComplete();
		} catch (ActionException e) {
			callback.onError(e.getMessage(), e);
		} catch (IOException | RuntimeException e) {
			log.warn("Action {} failed: {}", id, e.toString());
			callback.onError(id + " failed: " + describe(e), e);
		}
	}

	// =========================================================================
	// s3.profiles.list
	// =========================================================================

	private Map<String, Object> profilesList() {
		var profiles = new ArrayList<Map<String, Object>>();
		for (S3Profile profile : store.get().load()) {
			var entry = new LinkedHashMap<String, Object>();
			entry.put("id", profile.getId());
			entry.put("name", profile.displayName());
			entry.put("auth", switch (profile.getAuthMode()) {
				case ACCESS_KEY -> "access-key";
				case AWS_PROFILE -> "aws-profile";
				case SSO -> "sso";
				case ENVIRONMENT -> "environment";
			});
			entry.put("region", profile.effectiveRegion());
			entry.put("endpoint", profile.hasCustomEndpoint() ? profile.getEndpoint() : null);
			entry.put("bucket", profile.isBucketScoped() ? profile.getBucket() : null);
			entry.put("prefix", profile.isBucketScoped() ? profile.effectivePrefix() : null);
			profiles.add(entry);
		}
		profiles.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
		return Map.of("profiles", profiles);
	}

	// =========================================================================
	// s3.buckets.list
	// =========================================================================

	private Map<String, Object> bucketsList(Map<String, Object> args) throws ActionException {
		S3Profile profile = profile(text(args, "profile", true));
		S3Result<List<S3BucketEntry>> listed = clients.apply(profile).listBuckets();

		var buckets = new ArrayList<Map<String, Object>>();
		var result = new LinkedHashMap<String, Object>();
		if (!listed.isOk()) {
			// Credentials made for one bucket often may not list buckets at all; the
			// profile still says which bucket it is for.
			if (listed.errorOrNull() instanceof S3Error.AccessDenied && profile.isBucketScoped()) {
				buckets.add(bucketEntry(profile.getBucket(), -1, null));
				result.put("buckets", buckets);
				result.put("note", "These credentials may not list buckets; this is the bucket the profile is for.");
				return result;
			}
			throw failure(profile, listed.errorOrNull(), null);
		}
		for (S3BucketEntry bucket : listed.orNull()) {
			S3Endpoint.recordBucketRegion(bucket.name(), bucket.region());
			buckets.add(bucketEntry(bucket.name(), bucket.created(), bucket.region()));
		}
		result.put("buckets", buckets);
		return result;
	}

	private static Map<String, Object> bucketEntry(String name, long created, String region) {
		var entry = new LinkedHashMap<String, Object>();
		entry.put("name", name);
		entry.put("created", timestamp(created));
		entry.put("region", region);
		return entry;
	}

	// =========================================================================
	// s3.list
	// =========================================================================

	private Map<String, Object> list(Map<String, Object> args) throws ActionException {
		S3Profile profile = profile(text(args, "profile", true));
		String bucketArg = text(args, "bucket", false);
		String bucket = bucket(profile, bucketArg);
		String prefix = text(args, "prefix", false);
		if (prefix == null) {
			// The profile's own prefix is only a default for its own bucket.
			prefix = bucketArg == null && profile.isBucketScoped() ? profile.effectivePrefix() : "";
		}
		boolean recursive = flag(args, "recursive", false);
		int limit = integer(args, "limit", DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT);
		String token = text(args, "continuationToken", false);

		S3Result<S3Xml.ListPage> page = clients.apply(profile)
				.listObjects(bucket, prefix, token, recursive ? null : "/", limit);
		if (!page.isOk()) {
			throw failure(profile, page.errorOrNull(), "s3://" + bucket + "/" + prefix);
		}

		var entries = new ArrayList<Map<String, Object>>();
		for (S3ObjectEntry object : page.orNull().entries()) {
			var entry = new LinkedHashMap<String, Object>();
			entry.put("key", object.key());
			entry.put("type", object.folder() ? "folder" : "object");
			entry.put("size", object.folder() ? null : object.size());
			entry.put("lastModified", object.folder() ? null : timestamp(object.lastModified()));
			entry.put("storageClass", object.storageClass());
			entries.add(entry);
		}

		var result = new LinkedHashMap<String, Object>();
		result.put("bucket", bucket);
		result.put("prefix", prefix);
		result.put("entries", entries);
		result.put("nextContinuationToken", page.orNull().hasMore() ? page.orNull().nextContinuationToken() : null);
		result.put("truncated", page.orNull().hasMore());
		return result;
	}

	// =========================================================================
	// s3.head
	// =========================================================================

	private Map<String, Object> head(Map<String, Object> args) throws ActionException {
		S3Profile profile = profile(text(args, "profile", true));
		String bucket = bucket(profile, text(args, "bucket", false));
		String key = key(args, "key");

		S3ObjectEntry object = existing(profile, clients.apply(profile), bucket, key);
		if (object == null) {
			throw notFound(bucket, key);
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("bucket", bucket);
		result.put("key", key);
		result.put("size", object.size());
		result.put("lastModified", timestamp(object.lastModified()));
		result.put("storageClass", object.storageClass());
		result.put("etag", object.etag());
		return result;
	}

	// =========================================================================
	// s3.read
	// =========================================================================

	private Map<String, Object> read(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {
		S3Profile profile = profile(text(args, "profile", true));
		String bucket = bucket(profile, text(args, "bucket", false));
		String key = key(args, "key");
		int maxBytes = integer(args, "maxBytes", DEFAULT_READ_BYTES, 1, MAX_READ_BYTES);

		S3Client client = clients.apply(profile);
		S3ObjectEntry object = existing(profile, client, bucket, key);
		if (object == null) {
			throw notFound(bucket, key);
		}
		checkCancelled(callback, "Cancelled.");

		byte[] data;
		boolean truncated;
		S3Result<InputStream> opened = client.openObject(bucket, key);
		if (!opened.isOk()) {
			throw failure(profile, opened.errorOrNull(), "s3://" + bucket + "/" + key);
		}
		try (InputStream in = opened.orNull()) {
			data = in.readNBytes(maxBytes);
			truncated = in.read() != -1;
		}

		String text = decodeUtf8(data, truncated);
		var result = new LinkedHashMap<String, Object>();
		result.put("bucket", bucket);
		result.put("key", key);
		result.put("size", object.size());
		result.put("encoding", text != null ? "utf-8" : "base64");
		result.put("content", text != null ? text : Base64.getEncoder().encodeToString(data));
		result.put("truncated", truncated);
		return result;
	}

	/**
	 * Decode as UTF-8 text, or return {@code null} for binary data. When the data
	 * was cut short, up to three trailing bytes may be a split character and are
	 * dropped rather than counted as binary.
	 */
	static String decodeUtf8(byte[] data, boolean truncated) {
		int maxTrim = truncated ? Math.min(3, data.length) : 0;
		for (int trim = 0; trim <= maxTrim; trim++) {
			try {
				String text = StandardCharsets.UTF_8.newDecoder()
						.onMalformedInput(CodingErrorAction.REPORT)
						.onUnmappableCharacter(CodingErrorAction.REPORT)
						.decode(ByteBuffer.wrap(data, 0, data.length - trim))
						.toString();
				return text.indexOf('\0') >= 0 ? null : text;
			} catch (CharacterCodingException e) {
				// try again without a possibly split last character
			}
		}
		return null;
	}

	// =========================================================================
	// s3.write
	// =========================================================================

	private Map<String, Object> write(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {
		S3Profile profile = profile(text(args, "profile", true));
		String bucket = bucket(profile, text(args, "bucket", false));
		String key = key(args, "key");
		if (key.endsWith("/")) {
			throw new ActionException("'" + key + "' ends in '/', which S3 tools show as a folder; give an object key.");
		}
		if (!(args.get("content") instanceof String content)) {
			throw new ActionException("Missing argument 'content' (the object's full new content).");
		}
		String encoding = text(args, "encoding", false);
		byte[] bytes = encode(content, encoding == null ? "utf-8" : encoding);
		String address = "s3://" + bucket + "/" + key;

		S3Client client = clients.apply(profile);
		S3ObjectEntry existing = existing(profile, client, bucket, key);
		checkCancelled(callback, "Cancelled; nothing was written.");
		if (existing != null) {
			byte[] old = existing.size() <= MAX_DIFF_BYTES ? readAll(profile, client, bucket, key) : null;
			if (old != null && Arrays.equals(old, bytes)) {
				return writeResult(bucket, key, 0, false, true);
			}
			String diff = old == null ? null : unifiedDiff(address, old, bytes);
			String details = "Profile: " + profile.displayName() + "\n"
					+ "Object: " + address + "\n"
					+ "Size: " + existing.size() + " -> " + bytes.length + " bytes"
					+ (diff == null ? "\nNo diff to show: the object is binary or larger than 1 MB." : "")
					+ "\nUnless the bucket keeps versions, the current content cannot be recovered.";
			if (!callback.confirm("Overwrite " + address + "?", details, diff)) {
				throw new ActionException("Not written: overwriting " + address + " was not approved.");
			}
		}

		checkCancelled(callback, "Cancelled; " + address + " was not written.");
		S3Result<Long> uploaded = client.upload(bucket, key, () -> new ByteArrayInputStream(bytes), bytes.length,
				callback::onProgress, callback::isCancelled);
		if (!uploaded.isOk()) {
			if (uploaded.isCancelled()) {
				throw new ActionException("Cancelled; " + address + " was not written.");
			}
			throw failure(profile, uploaded.errorOrNull(), address);
		}
		S3TempFiles.invalidate(S3TempFiles.cacheKey(profile.getId(), bucket, key));
		return writeResult(bucket, key, bytes.length, existing == null, false);
	}

	private static Map<String, Object> writeResult(String bucket, String key, long bytesWritten, boolean created,
			boolean unchanged) {
		var result = new LinkedHashMap<String, Object>();
		result.put("bucket", bucket);
		result.put("key", key);
		result.put("bytesWritten", bytesWritten);
		result.put("created", created);
		result.put("unchanged", unchanged);
		return result;
	}

	private static byte[] encode(String content, String encoding) throws ActionException {
		return switch (encoding) {
			case "utf-8" -> content.getBytes(StandardCharsets.UTF_8);
			case "base64" -> {
				try {
					yield Base64.getDecoder().decode(content);
				} catch (IllegalArgumentException e) {
					throw new ActionException("'content' is not valid base64: " + e.getMessage());
				}
			}
			default -> throw new ActionException("Unknown encoding '" + encoding + "'; use utf-8 or base64.");
		};
	}

	/** A unified diff of two text versions, or {@code null} if either is not text. */
	static String unifiedDiff(String name, byte[] before, byte[] after) {
		String oldText = decodeUtf8(before, false);
		String newText = decodeUtf8(after, false);
		if (oldText == null || newText == null) {
			return null;
		}
		List<String> oldLines = Arrays.asList(oldText.split("\n", -1));
		List<String> newLines = Arrays.asList(newText.split("\n", -1));
		List<String> diff = UnifiedDiffUtils.generateUnifiedDiff(name, name, oldLines,
				DiffUtils.diff(oldLines, newLines), 3);
		return String.join("\n", diff);
	}

	// =========================================================================
	// s3.copy
	// =========================================================================

	private Map<String, Object> copy(Map<String, Object> args, NuclrPluginCallback callback)
			throws ActionException, IOException {
		S3Profile fromProfile = profile(text(args, "fromProfile", true));
		String toProfileArg = text(args, "toProfile", false);
		S3Profile toProfile = toProfileArg == null ? fromProfile : profile(toProfileArg);
		String fromBucket = bucket(fromProfile, text(args, "fromBucket", false));
		String fromKey = key(args, "fromKey");
		String toBucketArg = text(args, "toBucket", false);
		String toBucket = toBucketArg != null ? toBucketArg
				: toProfile != fromProfile && toProfile.isBucketScoped() ? toProfile.getBucket() : fromBucket;
		String toKey = key(args, "toKey");
		boolean overwrite = flag(args, "overwrite", false);

		String from = "s3://" + fromBucket + "/" + fromKey;
		String to = "s3://" + toBucket + "/" + toKey;
		boolean sameProfile = fromProfile.getId().equals(toProfile.getId());
		if (fromKey.endsWith("/") || toKey.endsWith("/")) {
			throw new ActionException("s3.copy copies one object; keys ending in '/' are folders. "
					+ "List the folder with s3.list (recursive: true) and copy each object.");
		}
		if (sameProfile && fromBucket.equals(toBucket) && fromKey.equals(toKey)) {
			throw new ActionException("Cannot copy " + from + " onto itself.");
		}

		S3Client source = clients.apply(fromProfile);
		S3Client target = clients.apply(toProfile);
		S3ObjectEntry object = existing(fromProfile, source, fromBucket, fromKey);
		if (object == null) {
			throw notFound(fromBucket, fromKey);
		}
		S3ObjectEntry existing = existing(toProfile, target, toBucket, toKey);
		if (existing != null) {
			if (!overwrite) {
				throw new ActionException(to + " already exists" + (sameProfile ? "" : " in " + toProfile.displayName())
						+ ". Nothing was copied. Pass overwrite: true to replace it.");
			}
			if (!callback.confirm("Replace " + to + "?",
					"Copying " + from + " (" + fromProfile.displayName() + ", " + object.size() + " bytes)\n"
							+ "over " + to + " (" + toProfile.displayName() + ", " + existing.size() + " bytes).\n"
							+ "Unless the bucket keeps versions, the current content cannot be recovered.",
					null)) {
				throw new ActionException("Nothing was copied: replacing " + to + " was not approved.");
			}
		}

		checkCancelled(callback, "Cancelled; nothing was copied.");
		callback.onStart("Copying " + from + " to " + to);
		boolean serverSide = sameProfile && object.size() <= MAX_SERVER_SIDE_COPY;
		if (serverSide) {
			// S3 copies inside the endpoint; the bytes never pass through this machine.
			S3Result<Void> copied = source.copyObject(fromBucket, fromKey, toBucket, toKey);
			if (!copied.isOk()) {
				throw failure(fromProfile, copied.errorOrNull(), from + " -> " + to);
			}
		} else {
			copyThroughTempFile(fromProfile, source, fromBucket, fromKey, toProfile, target, toBucket, toKey,
					object.size(), callback);
		}
		S3TempFiles.invalidate(S3TempFiles.cacheKey(toProfile.getId(), toBucket, toKey));

		var result = new LinkedHashMap<String, Object>();
		result.put("from", from);
		result.put("to", to);
		result.put("bytes", object.size());
		result.put("serverSide", serverSide);
		result.put("replaced", existing != null);
		return result;
	}

	/**
	 * Copy between profiles - two accounts or endpoints S3 cannot copy between - or
	 * past S3's 5 GB single-copy limit: download to a temporary file, then upload it.
	 * The target is only written by the upload, so a failure or Cancel while
	 * downloading leaves it as it was.
	 */
	private static void copyThroughTempFile(S3Profile fromProfile, S3Client source, String fromBucket, String fromKey,
			S3Profile toProfile, S3Client target, String toBucket, String toKey, long size,
			NuclrPluginCallback callback) throws ActionException, IOException {
		Path temp = Files.createTempFile("nuclr-s3-copy-", ".tmp");
		try {
			S3Result<Long> downloaded = source.downloadToFile(fromBucket, fromKey, temp,
					(done, total) -> callback.onProgress(done, size * 2), callback::isCancelled);
			if (!downloaded.isOk()) {
				if (downloaded.isCancelled()) {
					throw new ActionException("Cancelled while downloading; nothing was copied.");
				}
				throw failure(fromProfile, downloaded.errorOrNull(), "s3://" + fromBucket + "/" + fromKey);
			}
			long length = Files.size(temp);
			S3Result<Long> uploaded = target.upload(toBucket, toKey, () -> Files.newInputStream(temp), length,
					(done, total) -> callback.onProgress(size + done, size * 2), callback::isCancelled);
			if (!uploaded.isOk()) {
				if (uploaded.isCancelled()) {
					throw new ActionException("Cancelled while uploading; s3://" + toBucket + "/" + toKey
							+ " was left as it was.");
				}
				throw failure(toProfile, uploaded.errorOrNull(), "s3://" + toBucket + "/" + toKey);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	// =========================================================================
	// Shared helpers
	// =========================================================================

	/** Find a saved profile by id, or by name ignoring case. */
	S3Profile profile(String reference) throws ActionException {
		List<S3Profile> profiles = store.get().load();
		for (S3Profile profile : profiles) {
			if (profile.getId().equals(reference)) {
				return profile;
			}
		}
		List<S3Profile> byName = profiles.stream()
				.filter(profile -> reference.equalsIgnoreCase(profile.displayName())
						|| reference.equalsIgnoreCase(profile.getName()))
				.toList();
		if (byName.size() == 1) {
			return byName.get(0);
		}
		String names = profiles.stream().map(S3Profile::displayName).collect(Collectors.joining(", "));
		if (byName.isEmpty()) {
			throw new ActionException("No saved S3 profile '" + reference + "'. Saved profiles: "
					+ (names.isEmpty() ? "none - add one in the S3 panel first." : names + "."));
		}
		throw new ActionException("'" + reference + "' matches " + byName.size()
				+ " saved profiles; use the id from s3.profiles.list instead.");
	}

	/** The bucket named, or the profile's own bucket when none is. */
	private static String bucket(S3Profile profile, String bucket) throws ActionException {
		if (bucket != null) {
			return bucket;
		}
		if (profile.isBucketScoped()) {
			return profile.getBucket();
		}
		throw new ActionException("Missing argument 'bucket': profile " + profile.displayName()
				+ " is not tied to one bucket. Use s3.buckets.list to see its buckets.");
	}

	private static String key(Map<String, Object> args, String name) throws ActionException {
		String key = text(args, name, true);
		return key.startsWith("/") ? key.substring(1) : key;
	}

	/** The object's metadata, or {@code null} if there is no such object. */
	private static S3ObjectEntry existing(S3Profile profile, S3Client client, String bucket, String key)
			throws ActionException {
		S3Result<S3ObjectEntry> head = client.headObject(bucket, key);
		if (head.isOk()) {
			return head.orNull();
		}
		if (head.errorOrNull() instanceof S3Error.NoSuchKey) {
			return null;
		}
		throw failure(profile, head.errorOrNull(), "s3://" + bucket + "/" + key);
	}

	private static byte[] readAll(S3Profile profile, S3Client client, String bucket, String key)
			throws ActionException, IOException {
		S3Result<InputStream> opened = client.openObject(bucket, key);
		if (!opened.isOk()) {
			throw failure(profile, opened.errorOrNull(), "s3://" + bucket + "/" + key);
		}
		try (InputStream in = opened.orNull()) {
			return in.readAllBytes();
		}
	}

	private static ActionException notFound(String bucket, String key) {
		return new ActionException("No object s3://" + bucket + "/" + key + ". Keys are full paths inside the bucket; "
				+ "use s3.list to see what there is.");
	}

	/** An S3 error as a message for the caller, with a next step where there is an obvious one. */
	private static ActionException failure(S3Profile profile, S3Error error, String what) {
		if (error == null) {
			return new ActionException("The request failed for an unknown reason.");
		}
		String hint = switch (error) {
			case S3Error.NoSuchBucket ignored -> " Use s3.buckets.list to see the buckets this profile can reach.";
			case S3Error.NotAuthorized ignored -> " Ask the user to check the profile's credentials in the S3 panel.";
			case S3Error.CredentialsUnavailable ignored -> " Ask the user to open the profile in the S3 panel once.";
			default -> "";
		};
		return new ActionException(profile.displayName() + (what == null ? "" : " (" + what + ")") + ": "
				+ error.describe() + hint);
	}

	private static String timestamp(long epochMillis) {
		return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis).toString();
	}

	private static void checkCancelled(NuclrPluginCallback callback, String message) throws ActionException {
		if (callback.isCancelled()) {
			throw new ActionException(message);
		}
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

	static String text(Map<String, Object> args, String name, boolean required) throws ActionException {
		Object value = args.get(name);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		if (value != null && !(value instanceof String)) {
			throw new ActionException("Argument '" + name + "' must be a string.");
		}
		if (required) {
			throw new ActionException("Missing argument '" + name + "'.");
		}
		return null;
	}

	static int integer(Map<String, Object> args, String name, int defaultValue, int min, int max)
			throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
			throw new ActionException("Argument '" + name + "' must be a whole number.");
		}
		long whole = number.longValue();
		if (whole < min || whole > max) {
			throw new ActionException("Argument '" + name + "' must be between " + min + " and " + max + ".");
		}
		return (int) whole;
	}

	static boolean flag(Map<String, Object> args, String name, boolean defaultValue) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Boolean bool)) {
			throw new ActionException("Argument '" + name + "' must be true or false.");
		}
		return bool;
	}

	/** A failure whose message is meant for the caller as it stands. */
	static final class ActionException extends Exception {

		private static final long serialVersionUID = 1L;

		ActionException(String message) {
			super(message);
		}

		ActionException(String message, Throwable cause) {
			super(message, cause);
		}
	}

}
