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
package dev.nuclr.plugin.core.panel.s3.api;

/**
 * One immediate child of a bucket path: either an object or a common prefix.
 *
 * <p>S3 has no directories. A listing with a {@code /} delimiter reports the keys directly under a
 * prefix as objects and everything deeper as "common prefixes", and it is those common prefixes the
 * panel renders as folders. A zero-byte object whose key ends in {@code /} — the placeholder the
 * AWS console writes when you "create a folder" — is also treated as a folder rather than shown as
 * an empty file.
 *
 * @param name         the display name: the last path segment, with a trailing {@code /} for folders
 * @param key          the full object key; for a folder, the prefix it stands for
 * @param folder       whether this entry is navigable
 * @param size         the object size in bytes, or {@code -1} for a folder
 * @param lastModified the object's last-modified timestamp in epoch milliseconds, or {@code -1} when unknown
 * @param storageClass the storage class ({@code STANDARD}, {@code GLACIER}, …), or {@code null} for a folder
 * @param etag         the entity tag, quotes stripped, or {@code null} for a folder
 */
public record S3ObjectEntry(
		String name,
		String key,
		boolean folder,
		long size,
		long lastModified,
		String storageClass,
		String etag) {

	/**
	 * Build a folder entry for a common prefix.
	 *
	 * @param prefix the full prefix, ending in {@code /}
	 * @return the folder entry
	 */
	public static S3ObjectEntry folder(String prefix) {
		return new S3ObjectEntry(lastSegment(prefix) + "/", prefix, true, -1, -1, null, null);
	}

	/**
	 * Build an object entry.
	 *
	 * @param key          the full object key
	 * @param size         the size in bytes
	 * @param lastModified the last-modified timestamp in epoch milliseconds, or {@code -1}
	 * @param storageClass the storage class
	 * @param etag         the entity tag
	 * @return the object entry
	 */
	public static S3ObjectEntry object(String key, long size, long lastModified, String storageClass, String etag) {
		return new S3ObjectEntry(lastSegment(key), key, false, size, lastModified, storageClass, etag);
	}

	/**
	 * The last path segment of a key, ignoring a trailing slash.
	 *
	 * @param key the object key or prefix
	 * @return the final segment, empty when the key is
	 */
	public static String lastSegment(String key) {
		if (key == null || key.isEmpty()) {
			return "";
		}
		String trimmed = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
		int slash = trimmed.lastIndexOf('/');
		return slash < 0 ? trimmed : trimmed.substring(slash + 1);
	}
}
