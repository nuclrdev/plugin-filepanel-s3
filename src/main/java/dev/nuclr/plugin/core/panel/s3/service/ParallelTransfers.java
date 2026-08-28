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
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a planned list of transfers several at a time.
 *
 * <p>A single transfer rarely saturates a link: most of its life is spent waiting on a round trip,
 * not on bandwidth, which is why copying a thousand small objects one after another is so much
 * slower than the connection can actually go. Running several at once fills that dead time.
 *
 * <p>The list is <em>planned</em> before it gets here. Everything that needs to ask the user
 * something — a name clash, an overwrite — has already been decided on one thread, so nothing in
 * here can put two modal dialogs on screen at once. What remains is pure byte-moving, which is
 * exactly what parallelises safely.
 *
 * <p>Progress is aggregated rather than per-file, because with several transfers in flight a
 * per-file bar would jump around meaninglessly. Each task reports its own bytes; this class sums
 * the deltas and publishes a total, throttled so a hundred concurrent callbacks cannot flood the
 * event dispatch thread.
 */
@Slf4j
final class ParallelTransfers {

	/** How often the aggregate progress is pushed to the UI, at most. */
	private static final long PUBLISH_INTERVAL_MILLIS = 60;

	private ParallelTransfers() {}

	/** One planned byte-move: everything about it is already decided. */
	interface Task {

		/** The name to show and to blame in a failure message. */
		String name();

		/** The group this task belongs to, so a caller can tell which selected item succeeded. */
		int group();

		/** The expected size in bytes, for the aggregate progress. Zero when it is not known. */
		long size();

		/**
		 * Move the bytes.
		 *
		 * @param progress  receives this task's own running byte count
		 * @param cancelled polled during the transfer, so a large file can be abandoned part-way
		 *                  rather than only between tasks
		 * @return how it ended; failures must already be recorded by the task itself
		 */
		Outcome transfer(S3Client.ProgressListener progress, java.util.function.BooleanSupplier cancelled);
	}

	/**
	 * What a run produced.
	 *
	 * @param cancelled        whether the run stopped early because the user cancelled
	 * @param succeededGroups  the groups whose every planned task finished successfully
	 */
	record Summary(boolean cancelled, java.util.Set<Integer> succeededGroups) {

		/**
		 * Whether every task planned for a group actually finished.
		 *
		 * <p>A group is only complete when all of its tasks ran and all of them succeeded, which is
		 * what makes this safe to hang a move on: a cancelled run leaves tasks unrun, and their
		 * group must not be reported as transferred, or the source would be deleted after a copy
		 * that never happened.
		 */
		boolean groupSucceeded(int group) {
			return succeededGroups.contains(group);
		}
	}

	/**
	 * Run every task, at most {@code concurrency} at a time, stopping early if the user cancels.
	 *
	 * @param tasks       the planned transfers
	 * @param concurrency how many to run at once; values below one are treated as one
	 * @param callback    the progress callback driving the dialog
	 * @param label       what to call the operation in the progress line, such as {@code "Copying"}
	 * @return a summary of what happened
	 */
	static Summary run(List<Task> tasks, int concurrency, NuclrPluginCallback callback, String label) {

		var succeeded = new ConcurrentHashMap<Integer, AtomicInteger>();
		var planned = new java.util.HashMap<Integer, Integer>();

		long totalBytes = 0;
		for (Task task : tasks) {
			totalBytes += Math.max(task.size(), 0);
			planned.merge(task.group(), 1, Integer::sum);
		}

		if (tasks.isEmpty()) {
			return new Summary(false, java.util.Set.of());
		}

		var queue = new ConcurrentLinkedQueue<>(tasks);
		var cancelled = new AtomicBoolean();
		var completed = new AtomicInteger();
		var transferred = new AtomicLong();
		var lastPublish = new AtomicLong();

		int workers = Math.max(1, Math.min(concurrency, tasks.size()));
		var threads = new ArrayList<Thread>(workers);

		log.info("Running {} transfer(s) {} at a time", tasks.size(), workers);

		final long plannedBytes = totalBytes;
		final int taskCount = tasks.size();
		for (int i = 0; i < workers; i++) {
			threads.add(Thread.ofVirtual().name("s3-transfer-" + i).start(() -> drain(
					queue, cancelled, callback, succeeded, completed, transferred, lastPublish,
					plannedBytes, taskCount, label)));
		}

		for (Thread thread : threads) {
			try {
				thread.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				cancelled.set(true);
			}
		}

		// One last publish so the bar lands on the real total rather than wherever throttling left it.
		if (!cancelled.get()) {
			callback.onProgress(transferred.get(), totalBytes);
		}

		// Only a group whose every planned task actually finished counts as done.
		var complete = new java.util.HashSet<Integer>();
		for (var entry : planned.entrySet()) {
			AtomicInteger done = succeeded.get(entry.getKey());
			if (done != null && done.get() == entry.getValue()) {
				complete.add(entry.getKey());
			}
		}
		return new Summary(cancelled.get() || callback.isCancelled(), complete);
	}

	/** One worker: take tasks until the queue is empty or the run is stopping. */
	private static void drain(Queue<Task> queue, AtomicBoolean cancelled, NuclrPluginCallback callback,
			java.util.Map<Integer, AtomicInteger> succeeded, AtomicInteger completed, AtomicLong transferred,
			AtomicLong lastPublish, long totalBytes, int taskCount, String label) {

		Task task;
		while ((task = queue.poll()) != null) {

			if (cancelled.get() || callback.isCancelled()) {
				cancelled.set(true);
				return;
			}

			// Each task reports its own cumulative count; only the growth since the last report
			// belongs in the shared total.
			var reported = new AtomicLong();
			S3Client.ProgressListener progress = (bytes, ignoredTotal) -> {
				long delta = bytes - reported.getAndSet(bytes);
				if (delta != 0) {
					publish(transferred.addAndGet(delta), totalBytes, lastPublish, callback, false);
				}
			};

			// A transfer already under way must notice a cancellation too, or pressing Cancel on
			// a single large file would appear to do nothing until it finished.
			java.util.function.BooleanSupplier stopping = () -> cancelled.get() || callback.isCancelled();

			Outcome outcome;
			try {
				outcome = task.transfer(progress, stopping);
			} catch (RuntimeException e) {
				log.warn("Transfer of {} failed: {}", task.name(), e.getMessage(), e);
				outcome = Outcome.FAILED;
			}

			if (outcome == Outcome.CANCELLED) {
				cancelled.set(true);
				return;
			}
			if (outcome == Outcome.OK) {
				succeeded.computeIfAbsent(task.group(), group -> new AtomicInteger()).incrementAndGet();
			}

			// A task that failed part-way, or reported nothing at all, must still leave the bar
			// consistent: count its whole declared size as dealt with either way.
			long remaining = Math.max(task.size(), 0) - reported.get();
			if (remaining > 0) {
				transferred.addAndGet(remaining);
			}

			int done = completed.incrementAndGet();
			callback.onStart(label + ' ' + done + '/' + taskCount + " — " + task.name());
			publish(transferred.get(), totalBytes, lastPublish, callback, true);
		}
	}

	/**
	 * Push the aggregate to the dialog, no more often than the publish interval.
	 *
	 * <p>Every callback hop lands on the event dispatch thread. Left unthrottled, a dozen workers
	 * reporting every buffer would spend the UI's time redrawing a progress bar instead of
	 * repainting the window the user is looking at.
	 */
	private static void publish(long current, long total, AtomicLong lastPublish,
			NuclrPluginCallback callback, boolean force) {

		long now = System.currentTimeMillis();
		long previous = lastPublish.get();

		if (!force && now - previous < PUBLISH_INTERVAL_MILLIS) {
			return;
		}
		if (!lastPublish.compareAndSet(previous, now)) {
			return; // another worker is publishing this tick; one report is enough
		}
		callback.onProgress(current, total);
	}
}
