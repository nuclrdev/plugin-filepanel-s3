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
package dev.nuclr.plugin.core.panel.s3.find;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.S3Resource;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.api.S3Xml;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs an {@link S3FindRequest}, streaming matches to a listener as they are found.
 *
 * <p>Searching a bucket means listing it, and a bucket can hold an unbounded number of keys — so
 * results are emitted page by page rather than gathered and returned at the end. The user sees the
 * first matches within a moment of starting, and can stop as soon as they spot what they were after
 * instead of waiting for a walk of the whole prefix to finish.
 *
 * <p>Every callback is delivered on the event dispatch thread, so the listener can touch its widgets
 * directly. Cancellation is cooperative and checked between pages.
 */
@Slf4j
public final class S3FindService {

	/** One listing page per request; the service maximum, and the fewest round trips. */
	private static final int PAGE_SIZE = 1000;

	/** Report progress every so many keys, rather than on each one, to keep the dispatch thread free. */
	private static final int PROGRESS_EVERY = 500;

	/** Create the service. */
	public S3FindService() {
	}

	/** Receives search results; every callback arrives on the event dispatch thread. */
	public interface Listener {

		/**
		 * A matching object or folder was found.
		 *
		 * @param resource the hit, ready to show in a panel
		 */
		void onMatch(NuclrResource resource);

		/**
		 * Periodic progress.
		 *
		 * @param scanned how many keys have been examined
		 * @param matched how many matched
		 */
		void onProgress(long scanned, long matched);

		/**
		 * The search ended.
		 *
		 * @param scanned   how many keys were examined
		 * @param matched   how many matched
		 * @param cancelled whether the user stopped it
		 * @param error     what went wrong, or {@code null} when it simply finished
		 */
		void onComplete(long scanned, long matched, boolean cancelled, S3Error error);
	}

	/** A running search, which the results window can stop. */
	public static final class SearchHandle {

		private final AtomicBoolean cancelled = new AtomicBoolean(false);

		/** Stop the search at the next page boundary. */
		public void cancel() {
			cancelled.set(true);
		}

		/**
		 * Whether the search has been asked to stop.
		 *
		 * @return {@code true} once cancelled
		 */
		public boolean isCancelled() {
			return cancelled.get();
		}
	}

	/**
	 * Start a search on a background thread.
	 *
	 * @param request  what to look for
	 * @param listener receives matches and progress
	 * @return a handle for cancelling the search
	 */
	public SearchHandle search(S3FindRequest request, Listener listener) {
		var handle = new SearchHandle();
		Thread.ofVirtual().name("s3-find").start(() -> run(request, listener, handle));
		return handle;
	}

	private void run(S3FindRequest request, Listener listener, SearchHandle handle) {

		var scanned = new AtomicLong();
		var matched = new AtomicLong();
		S3Error failure = null;

		S3Client client = S3Clients.byProfileId(request.profileId());
		if (client == null) {
			complete(listener, 0, 0, false,
					new S3Error.CredentialsUnavailable("The connection profile is no longer available."));
			return;
		}

		Pattern pattern = request.compilePattern();
		// A recursive search drops the delimiter so one pass covers the whole subtree; a
		// single-level search keeps it, so sub-folders come back as folders rather than being
		// descended into.
		String delimiter = request.recursive() ? null : "/";
		String continuation = null;

		do {
			if (handle.isCancelled()) {
				break;
			}

			S3Result<S3Xml.ListPage> page = client.listObjects(
					request.bucket(), request.prefix(), continuation, delimiter, PAGE_SIZE);
			if (!page.isOk()) {
				failure = page.errorOrNull();
				log.warn("S3 find failed under s3://{}/{}: {}",
						request.bucket(), request.prefix(), failure.describe());
				break;
			}

			for (S3ObjectEntry entry : page.orNull().entries()) {

				if (handle.isCancelled()) {
					break;
				}
				long total = scanned.incrementAndGet();

				String name = entry.folder()
						? S3ObjectEntry.lastSegment(entry.key())
						: entry.name();
				if (!name.isEmpty() && S3FindRequest.matches(pattern, name)) {
					NuclrResource hit = entry.folder()
							? S3Resource.objectDir(request.profileId(), request.bucket(), entry.key(), name + "/")
							: S3Resource.object(request.profileId(), request.bucket(), entry);
					matched.incrementAndGet();
					SwingUtilities.invokeLater(() -> listener.onMatch(hit));
				}

				if (total % PROGRESS_EVERY == 0) {
					SwingUtilities.invokeLater(() -> listener.onProgress(scanned.get(), matched.get()));
				}
			}

			continuation = page.orNull().nextContinuationToken();

		} while (continuation != null && !continuation.isBlank());

		complete(listener, scanned.get(), matched.get(), handle.isCancelled(), failure);
	}

	private static void complete(Listener listener, long scanned, long matched, boolean cancelled, S3Error error) {
		SwingUtilities.invokeLater(() -> listener.onComplete(scanned, matched, cancelled, error));
	}
}
