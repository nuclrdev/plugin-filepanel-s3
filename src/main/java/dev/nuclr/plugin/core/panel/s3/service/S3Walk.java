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
package dev.nuclr.plugin.core.panel.s3.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.api.S3Xml;

/**
 * Walks every object under a prefix, page by page.
 *
 * <p>Copying, moving, deleting and sizing a "folder" all mean the same thing in S3: enumerating
 * every key that starts with a prefix. The listing is done without a delimiter, so one pass returns
 * the whole subtree flat rather than a folder at a time, and results are handed to a consumer as
 * each page arrives — a prefix holding a million keys walks in bounded memory, and a cancel takes
 * effect at the next page rather than after the last one.
 */
public final class S3Walk {

	/** One page per request; the service maximum, and the fewest round trips. */
	private static final int PAGE_SIZE = 1000;

	private S3Walk() {}

	/**
	 * Visit every object under a prefix, deepest paths included.
	 *
	 * <p>Folder placeholders — the zero-byte keys ending in {@code /} — are visited too, because a
	 * caller deleting a folder has to remove them as well for the folder to actually disappear.
	 *
	 * @param client    the client to list through
	 * @param bucket    the bucket
	 * @param prefix    the prefix to walk under
	 * @param visitor   receives each object as it is discovered
	 * @param cancelled polled between pages, or {@code null}
	 * @return success, or the error that stopped the walk
	 */
	public static S3Result<Void> walk(S3Client client, String bucket, String prefix,
			Consumer<S3ObjectEntry> visitor, BooleanSupplier cancelled) {

		String continuation = null;
		do {
			if (cancelled != null && cancelled.getAsBoolean()) {
				return S3Result.err(new S3Error.Cancelled());
			}

			S3Result<S3Xml.ListPage> page =
					client.listObjects(bucket, prefix, continuation, null, PAGE_SIZE);
			if (!page.isOk()) {
				return page.propagate();
			}

			for (S3ObjectEntry entry : page.orNull().entries()) {
				visitor.accept(entry);
			}
			continuation = page.orNull().nextContinuationToken();

		} while (continuation != null && !continuation.isBlank());

		return S3Result.ok(null);
	}

	/**
	 * Collect every object under a prefix into a list.
	 *
	 * <p>Convenient when the caller needs the whole set up front — to count it for a confirmation,
	 * or to batch it into delete requests — and the prefix is one a person is plausibly acting on
	 * by hand.
	 *
	 * @param client    the client to list through
	 * @param bucket    the bucket
	 * @param prefix    the prefix to walk under
	 * @param cancelled polled between pages, or {@code null}
	 * @return every object beneath the prefix, or the error that stopped the walk
	 */
	public static S3Result<List<S3ObjectEntry>> collect(S3Client client, String bucket, String prefix,
			BooleanSupplier cancelled) {

		var collected = new ArrayList<S3ObjectEntry>();
		S3Result<Void> outcome = walk(client, bucket, prefix, collected::add, cancelled);
		return outcome.isOk() ? S3Result.ok(List.copyOf(collected)) : outcome.propagate();
	}

	/**
	 * The key of an object relative to the prefix it was found under: {@code logs/2026/a.txt} under
	 * {@code logs/} becomes {@code 2026/a.txt}.
	 *
	 * <p>This is what turns a flat key listing back into a directory tree at the destination.
	 *
	 * @param key    the full object key
	 * @param prefix the prefix it was listed under
	 * @return the key relative to the prefix
	 */
	public static String relativeKey(String key, String prefix) {
		if (prefix == null || prefix.isEmpty() || !key.startsWith(prefix)) {
			return key;
		}
		return key.substring(prefix.length());
	}
}
