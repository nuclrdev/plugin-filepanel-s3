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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;

/** Covers the promises the transfer runner makes to the copy service. */
class ParallelTransfersTest {

	@Test
	@DisplayName("Every task runs, and a group whose tasks all succeed is reported complete")
	void runsEveryTask() {

		var ran = new AtomicInteger();
		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(task("item-" + i, 0, 10, progress -> {
				ran.incrementAndGet();
				return Outcome.OK;
			}));
		}

		ParallelTransfers.Summary summary = ParallelTransfers.run(tasks, 4, new RecordingCallback(), "Copying");

		assertEquals(20, ran.get());
		assertFalse(summary.cancelled());
		assertTrue(summary.groupSucceeded(0));
	}

	@Test
	@DisplayName("Transfers really do overlap")
	void runsTasksConcurrently() throws Exception {

		// Each task waits for four to arrive. If they ran one at a time this would never trip and
		// the latch await would time out, which is the point: it fails when parallelism is lost.
		var arrived = new CountDownLatch(4);
		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tasks.add(task("item-" + i, 0, 0, progress -> {
				arrived.countDown();
				try {
					return arrived.await(5, TimeUnit.SECONDS) ? Outcome.OK : Outcome.FAILED;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return Outcome.FAILED;
				}
			}));
		}

		ParallelTransfers.Summary summary = ParallelTransfers.run(tasks, 4, new RecordingCallback(), "Copying");

		assertTrue(summary.groupSucceeded(0), "all four ran at once");
	}

	@Test
	@DisplayName("Concurrency never exceeds the limit asked for")
	void respectsTheConcurrencyLimit() {

		var inFlight = new AtomicInteger();
		var highWater = new AtomicInteger();

		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			tasks.add(task("item-" + i, 0, 0, progress -> {
				int now = inFlight.incrementAndGet();
				highWater.accumulateAndGet(now, Math::max);
				try {
					Thread.sleep(5);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				inFlight.decrementAndGet();
				return Outcome.OK;
			}));
		}

		ParallelTransfers.run(tasks, 3, new RecordingCallback(), "Copying");

		assertTrue(highWater.get() <= 3, "saw " + highWater.get() + " transfers at once, limit was 3");
		assertTrue(highWater.get() > 1, "nothing overlapped at all");
	}

	@Test
	@DisplayName("A group with one failing task is not reported complete")
	void aFailingTaskFailsItsGroup() {

		List<ParallelTransfers.Task> tasks = List.of(
				task("good", 0, 10, progress -> Outcome.OK),
				task("bad", 0, 10, progress -> Outcome.FAILED),
				task("other", 1, 10, progress -> Outcome.OK));

		ParallelTransfers.Summary summary = ParallelTransfers.run(tasks, 4, new RecordingCallback(), "Copying");

		assertFalse(summary.groupSucceeded(0), "one failure spoils its group");
		assertTrue(summary.groupSucceeded(1), "an unrelated group is unaffected");
	}

	@Test
	@DisplayName("Cancelling stops the run and completes no further group")
	void cancellationStopsTheRun() {

		var callback = new RecordingCallback();
		var started = new AtomicInteger();

		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			final int group = i;
			tasks.add(task("item-" + i, group, 10, progress -> {
				if (started.incrementAndGet() == 1) {
					callback.cancel();
				}
				return Outcome.OK;
			}));
		}

		ParallelTransfers.Summary summary = ParallelTransfers.run(tasks, 2, callback, "Copying");

		assertTrue(summary.cancelled());
		assertTrue(started.get() < tasks.size(), "cancelling must stop the queue being drained");
	}

	@Test
	@DisplayName("A task returning cancelled stops the run")
	void aCancelledTaskStopsTheRun() {

		var started = new AtomicInteger();
		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			tasks.add(task("item-" + i, i, 10, progress -> {
				started.incrementAndGet();
				return Outcome.CANCELLED;
			}));
		}

		assertTrue(ParallelTransfers.run(tasks, 1, new RecordingCallback(), "Copying").cancelled());
		assertEquals(1, started.get());
	}

	@Test
	@DisplayName("A task that throws fails its group instead of killing the run")
	void survivesAThrowingTask() {

		List<ParallelTransfers.Task> tasks = List.of(
				task("boom", 0, 10, progress -> {
					throw new IllegalStateException("something gave way");
				}),
				task("fine", 1, 10, progress -> Outcome.OK));

		ParallelTransfers.Summary summary = ParallelTransfers.run(tasks, 2, new RecordingCallback(), "Copying");

		assertFalse(summary.groupSucceeded(0));
		assertTrue(summary.groupSucceeded(1), "one bad task must not take the rest with it");
	}

	@Test
	@DisplayName("Aggregate progress adds up to the planned total")
	void aggregatesProgressToTheTotal() {

		var callback = new RecordingCallback();
		List<ParallelTransfers.Task> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(task("item-" + i, 0, 100, progress -> {
				progress.onBytes(50, 100);
				progress.onBytes(100, 100);
				return Outcome.OK;
			}));
		}

		ParallelTransfers.run(tasks, 4, callback, "Copying");

		assertEquals(1000, callback.lastCurrent.get());
		assertEquals(1000, callback.lastTotal.get());
	}

	@Test
	@DisplayName("A task that reports nothing still moves the bar by its size")
	void countsSilentTasks() {

		var callback = new RecordingCallback();
		List<ParallelTransfers.Task> tasks = List.of(
				task("quiet", 0, 500, progress -> Outcome.OK),
				task("failed", 0, 500, progress -> Outcome.FAILED));

		ParallelTransfers.run(tasks, 2, callback, "Copying");

		assertEquals(1000, callback.lastCurrent.get(), "the bar must still reach the end");
	}

	@Test
	@DisplayName("An empty plan is not an error")
	void handlesAnEmptyPlan() {
		ParallelTransfers.Summary summary = ParallelTransfers.run(List.of(), 4, new RecordingCallback(), "Copying");
		assertFalse(summary.cancelled());
	}

	// -------------------------------------------------------------------------

	private interface Body {
		Outcome run(S3Client.ProgressListener progress);
	}

	private static ParallelTransfers.Task task(String name, int group, long size, Body body) {
		return new ParallelTransfers.Task() {

			@Override
			public String name() {
				return name;
			}

			@Override
			public int group() {
				return group;
			}

			@Override
			public long size() {
				return size;
			}

			@Override
			public Outcome transfer(S3Client.ProgressListener progress,
					java.util.function.BooleanSupplier cancelled) {
				return body.run(progress);
			}
		};
	}

	/** Stands in for the progress dialog, remembering the last thing it was told. */
	private static final class RecordingCallback implements NuclrPluginCallback {

		private final AtomicBoolean cancelled = new AtomicBoolean();
		private final AtomicLong lastCurrent = new AtomicLong();
		private final AtomicLong lastTotal = new AtomicLong();

		void cancel() {
			cancelled.set(true);
		}

		@Override
		public void onStart(String description) {
			// Nothing to do; the label is not under test.
		}

		@Override
		public void onProgress(long current, long total) {
			lastCurrent.set(current);
			lastTotal.set(total);
		}

		@Override
		public void onComplete() {
			// Not used by the runner.
		}

		@Override
		public void onError(String description, Exception e) {
			// Not used by the runner.
		}

		@Override
		public boolean isCancelled() {
			return cancelled.get();
		}
	}
}
