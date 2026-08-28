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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Tracks the local copies of objects downloaded for viewing.
 *
 * <p>Quick view (Ctrl+Q), F3 view and F4 edit all need a real file on disk; S3 gives a stream. Each
 * object is downloaded once and remembered under its {@code bucket/key}, so paging the cursor back
 * over a file you looked at a moment ago is instant instead of another round trip. The cache
 * survives listing rebuilds, because it is keyed by the object rather than by the row.
 *
 * <p>Every file is marked delete-on-exit as a backstop and removed eagerly when the plugin unloads.
 */
@Slf4j
public final class S3TempFiles {

	private static final Set<Path> FILES = ConcurrentHashMap.newKeySet();

	private static final Map<String, Path> BY_OBJECT = new ConcurrentHashMap<>();

	private S3TempFiles() {}

	/**
	 * Build the cache key for an object.
	 *
	 * @param profileId the profile the object belongs to; the same key can exist in two accounts
	 * @param bucket    the bucket
	 * @param key       the object key
	 * @return the cache key
	 */
	public static String cacheKey(String profileId, String bucket, String key) {
		return profileId + '/' + bucket + '/' + key;
	}

	/**
	 * The local file already downloaded for an object.
	 *
	 * @param cacheKey the key from {@link #cacheKey}
	 * @return the file, or {@code null} when it has not been downloaded or has since gone
	 */
	public static Path cached(String cacheKey) {
		Path file = BY_OBJECT.get(cacheKey);
		if (file != null && Files.exists(file)) {
			return file;
		}
		if (file != null) {
			BY_OBJECT.remove(cacheKey, file); // stale entry; it will be fetched again
		}
		return null;
	}

	/**
	 * Record a downloaded file and schedule it for deletion.
	 *
	 * @param cacheKey the key from {@link #cacheKey}
	 * @param file     the local file
	 */
	public static void register(String cacheKey, Path file) {
		FILES.add(file);
		BY_OBJECT.put(cacheKey, file);
		file.toFile().deleteOnExit();
	}

	/**
	 * Forget one object's local copy, so the next view fetches it again. Used after an edit is
	 * uploaded, when the cached bytes are no longer what the bucket holds.
	 *
	 * @param cacheKey the key from {@link #cacheKey}
	 */
	public static void invalidate(String cacheKey) {
		Path file = BY_OBJECT.remove(cacheKey);
		if (file == null) {
			return;
		}
		FILES.remove(file);
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			log.debug("Could not delete the cached copy of {}: {}", cacheKey, e.getMessage());
		}
	}

	/** Delete every tracked temporary file (best-effort) and clear the registry. */
	public static void cleanup() {
		for (Path file : FILES) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				log.warn("Could not delete the S3 temporary file {}: {}", file, e.getMessage());
			}
		}
		FILES.clear();
		BY_OBJECT.clear();
	}

	/**
	 * Make an object name safe to use as a temporary-file suffix while keeping its extension, so a
	 * viewer that selects by file type still recognises it.
	 *
	 * @param name the object's display name
	 * @return a sanitised name
	 */
	public static String sanitize(String name) {
		if (name == null || name.isBlank()) {
			return "object";
		}
		return name.replaceAll("[^A-Za-z0-9._-]", "_");
	}
}
