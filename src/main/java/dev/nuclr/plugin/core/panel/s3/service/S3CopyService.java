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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3Resource;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.ui.ConflictDialog;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;
import dev.nuclr.plugin.core.panel.s3.ui.ProgressDialog;
import dev.nuclr.plugin.core.panel.s3.ui.TransferSetupDialog;
import lombok.extern.slf4j.Slf4j;

/**
 * F5 copy, in all three directions a two-pane manager can mean by it.
 *
 * <ul>
 *   <li><b>Out of S3</b> — objects and folders into the other panel's local directory. Folders are
 *       walked and recreated as real directories, so copying a prefix down gives back the tree it
 *       looked like in the panel.</li>
 *   <li><b>Into S3</b> — local files and directories into the bucket prefix this panel has open,
 *       with directory structure becoming key structure.</li>
 *   <li><b>S3 to S3</b> — when both panels are S3, the copy happens inside the service. The bytes
 *       never touch this machine, which for a large object is the difference between seconds and
 *       an afternoon on a home connection. Only within one profile, though: across profiles the
 *       credentials differ, so it falls back to a download and upload.</li>
 * </ul>
 *
 * <p>Name clashes go through {@link ConflictDialog} with the same Overwrite / Skip / Rename / Append
 * / Cancel choices as the local file panel. Everything runs off the event dispatch thread under a
 * cancellable {@link ProgressDialog}, and each item's mark is cleared as it completes.
 */
@Slf4j
public final class S3CopyService {

	/** Whether this run copies or moves, which changes only the wording and the cleanup. */
	private final boolean moving;

	/**
	 * Create a service for one operation.
	 *
	 * @param moving {@code true} for F6 move (delete the source after a successful transfer),
	 *               {@code false} for F5 copy
	 */
	public S3CopyService(boolean moving) {
		this.moving = moving;
	}

	private String title() {
		return moving ? "Move" : "Copy";
	}

	// -------------------------------------------------------------------------
	// Out of S3
	// -------------------------------------------------------------------------

	/**
	 * Copy the selected objects and folders into whatever the other panel has open.
	 *
	 * @param other             the plugin driving the other panel
	 * @param selectedResources the marked rows
	 * @param focusedResource   the row under the cursor
	 * @param context           the plugin context, for unmarking and refreshing
	 * @param sourceUuid        this panel's id, whose marks are cleared as items complete
	 * @return {@code true} when anything was transferred, so the caller can refresh
	 */
	public boolean copyOut(BaseNuclrPlugin other, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, NuclrPluginContext context, String sourceUuid) {

		List<NuclrResource> sources = Selections.s3Targets(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Dialogs.error(title(), "Select one or more objects or folders to " + title().toLowerCase(java.util.Locale.ROOT) + ".");
			return false;
		}

		NuclrResource s3Destination = Selections.s3Directory(other);
		if (s3Destination != null) {
			return copyWithinS3(sources, s3Destination, context, sourceUuid, other);
		}

		Path localDestination = Selections.localDirectory(other);
		if (localDestination == null) {
			Dialogs.error(title(), title() + " is only supported to a local folder or another S3 panel.");
			return false;
		}

		TransferSetupDialog.Download options =
				TransferSetupDialog.showDownload(title(), Selections.header(sources), localDestination);
		if (options == null) {
			return false;
		}
		if (!Files.isDirectory(options.destination())) {
			Dialogs.error(title(), "The destination is not a folder:\n" + options.destination());
			return false;
		}

		var failures = new ArrayList<String>();
		int[] transferred = {0};
		ProgressDialog.run(title(), callback -> transferred[0] = runDownload(
				sources, options.destination(), options.existing(), options.concurrency(),
				callback, failures, context, sourceUuid));

		Selections.refreshPanel(context, other.uuid());
		reportFailures(failures);
		return transferred[0] > 0;
	}

	/**
	 * Download each selected object, and each object beneath each selected folder.
	 *
	 * <p>Two phases. The first walks the selection and settles every question that needs a person:
	 * which keys exist, where each one lands, what to do about a clash. The second moves the bytes,
	 * several at a time. Keeping them apart is what makes the parallelism safe — by the time
	 * anything runs concurrently there is nothing left to ask.
	 */
	private int runDownload(List<NuclrResource> sources, Path destination, ConflictDialog.Action existingMode,
			int concurrency, NuclrPluginCallback callback, List<String> failures,
			NuclrPluginContext context, String sourceUuid) {

		var conflicts = new ConflictDialog();
		var tasks = new ArrayList<ParallelTransfers.Task>();
		var groups = new ArrayList<NuclrResource>();
		var groupClients = new ArrayList<S3Client>();

		callback.onStart("Preparing " + title().toLowerCase(java.util.Locale.ROOT) + "…");

		for (NuclrResource source : sources) {

			if (callback.isCancelled()) {
				return 0;
			}

			S3Client client = S3Clients.byProfileId(S3Resource.profileId(source));
			if (client == null) {
				failures.add(source.getName() + " (its connection profile is gone)");
				continue;
			}

			int group = groups.size();
			Outcome planned = S3Resource.isObjectDir(source)
					? planFolderDownload(client, source, destination, existingMode, conflicts, group, tasks,
							failures, callback)
					: planObjectDownload(client, S3Resource.bucketName(source), S3Resource.objectKey(source),
							source.getName(), destination, source.getLength(), existingMode, conflicts,
							group, tasks, failures);

			if (planned == Outcome.CANCELLED) {
				return 0;
			}
			if (planned == Outcome.OK) {
				groups.add(source);
				groupClients.add(client);
			}
		}

		ParallelTransfers.Summary summary =
				ParallelTransfers.run(tasks, concurrency, callback, title() + "ing");

		int transferred = 0;
		for (int group = 0; group < groups.size(); group++) {
			if (!summary.groupSucceeded(group)) {
				continue;
			}
			transferred++;
			// For a move, the mark stays put when the source could not be removed: the item is
			// still there, and the panel should keep showing it as selected.
			if (!moving || deleteSource(groupClients.get(group), groups.get(group), failures)) {
				Selections.unmark(context, sourceUuid, groups.get(group));
			}
		}

		if (summary.cancelled()) {
			log.info("S3 {} cancelled after {} item(s)", title().toLowerCase(java.util.Locale.ROOT), transferred);
			return transferred;
		}

		log.info("S3 {} out finished: {} item(s) transferred, {} failed",
				title().toLowerCase(java.util.Locale.ROOT), transferred, failures.size());
		return transferred;
	}

	/** Plan a bucket prefix as a local directory tree: make the folders, queue the objects. */
	private Outcome planFolderDownload(S3Client client, NuclrResource folder, Path destination,
			ConflictDialog.Action existingMode, ConflictDialog conflicts, int group,
			List<ParallelTransfers.Task> tasks, List<String> failures, NuclrPluginCallback callback) {

		String bucket = S3Resource.bucketName(folder);
		String prefix = S3Resource.objectPrefix(folder);
		String folderName = stripSlash(folder.getName());

		// Listing a deep prefix is itself slow enough to want cancelling.
		S3Result<List<S3ObjectEntry>> contents = S3Walk.collect(client, bucket, prefix, callback::isCancelled);
		if (contents.isCancelled()) {
			return Outcome.CANCELLED;
		}
		if (!contents.isOk()) {
			failures.add(folder.getName() + " (" + contents.errorOrNull().describe() + ')');
			return Outcome.FAILED;
		}

		Path root = destination.resolve(folderName);
		try {
			Files.createDirectories(root);
		} catch (IOException e) {
			failures.add(folder.getName() + " (" + e.getMessage() + ')');
			return Outcome.FAILED;
		}

		for (S3ObjectEntry entry : contents.orNull()) {

			String relative = S3Walk.relativeKey(entry.key(), prefix);
			if (relative.isEmpty()) {
				continue;
			}

			Path target = root.resolve(relative.replace('/', java.io.File.separatorChar));
			if (entry.key().endsWith("/")) {
				// A folder placeholder: make the directory, there are no bytes to fetch.
				try {
					Files.createDirectories(target);
				} catch (IOException e) {
					failures.add(relative + " (" + e.getMessage() + ')');
				}
				continue;
			}

			try {
				Files.createDirectories(target.getParent());
			} catch (IOException e) {
				failures.add(relative + " (" + e.getMessage() + ')');
				continue;
			}

			Outcome planned = planObjectDownload(client, bucket, entry.key(),
					target.getFileName().toString(), target.getParent(), entry.size(),
					existingMode, conflicts, group, tasks, failures);
			if (planned == Outcome.CANCELLED) {
				return Outcome.CANCELLED;
			}
		}

		return Outcome.OK;
	}

	/**
	 * Settle where one object will land, and queue the fetch.
	 *
	 * <p>Everything interactive happens here, on the planning thread: the clash is resolved and the
	 * final name chosen, so what comes out the other side is a task that only has to move bytes.
	 */
	private Outcome planObjectDownload(S3Client client, String bucket, String key, String name, Path directory,
			long size, ConflictDialog.Action existingMode, ConflictDialog conflicts, int group,
			List<ParallelTransfers.Task> tasks, List<String> failures) {

		if (key == null) {
			failures.add(name + " (not a downloadable object)");
			return Outcome.FAILED;
		}

		Path target = directory.resolve(name);
		boolean append = false;

		if (Files.exists(target)) {
			Predicate<String> existsHere = candidate -> Files.exists(directory.resolve(candidate));
			ConflictDialog.Action action = existingMode;
			String renameName = null;

			if (action == null) {
				ConflictDialog.Resolution resolution = conflicts.resolve(
						target.toString(),
						describeObject(client, bucket, key),
						ConflictDialog.pathDetail(target),
						ConflictDialog.autoRenameName(name, existsHere));
				action = resolution.action();
				renameName = resolution.renameName();
			}

			switch (action) {
				case CANCEL -> {
					return Outcome.CANCELLED;
				}
				case SKIP -> {
					return Outcome.OK;
				}
				case RENAME -> {
					String chosen = renameName != null ? renameName : ConflictDialog.autoRenameName(name, existsHere);
					target = directory.resolve(chosen);
					// A typed or remembered name can still clash; number it once more.
					if (Files.exists(target)) {
						target = directory.resolve(ConflictDialog.autoRenameName(chosen, existsHere));
					}
				}
				case APPEND -> append = true;
				case OVERWRITE -> { /* write straight over it */ }
			}
		}

		final Path plannedTarget = target;
		final boolean appendMode = append;

		tasks.add(new ParallelTransfers.Task() {

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
			public Outcome transfer(S3Client.ProgressListener progress, BooleanSupplier cancelled) {
				return fetchObject(client, bucket, key, name, directory, plannedTarget, appendMode,
						progress, cancelled, failures);
			}
		});

		return Outcome.OK;
	}

	/**
	 * Fetch one object to its already-decided target. Safe to run beside others: it touches only its
	 * own files and asks nothing.
	 */
	private Outcome fetchObject(S3Client client, String bucket, String key, String name, Path directory,
			Path target, boolean append, S3Client.ProgressListener progress, BooleanSupplier cancelled,
			List<String> failures) {

		// Download beside the target and move it into place, so an interrupted transfer never
		// leaves a half-written file wearing the real name.
		Path temp;
		try {
			temp = Files.createTempFile(directory, "nuclr-s3-", ".part");
		} catch (IOException e) {
			record(failures, name, e.getMessage());
			return Outcome.FAILED;
		}

		try {
			S3Result<Long> result = client.downloadToFile(bucket, key, temp, progress, cancelled);
			if (result.isCancelled()) {
				return Outcome.CANCELLED;
			}
			if (!result.isOk()) {
				record(failures, name, result.errorOrNull().describe());
				return Outcome.FAILED;
			}

			if (append) {
				try (InputStream in = Files.newInputStream(temp)) {
					Files.write(target, in.readAllBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			} else {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			return Outcome.OK;

		} catch (IOException e) {
			record(failures, name, e.getMessage());
			return Outcome.FAILED;
		} finally {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException e) {
				log.debug("Could not remove the temporary file {}: {}", temp, e.getMessage());
			}
		}
	}

	// -------------------------------------------------------------------------
	// Into S3
	// -------------------------------------------------------------------------

	/**
	 * Accept a transfer the other panel started: upload its selection into the prefix open here.
	 *
	 * @param selectedResources the marked rows in the source panel
	 * @param focusedResource   the source panel's cursor row
	 * @param currentResource   this panel's open bucket folder
	 * @param context           the plugin context
	 * @param destinationUuid   this panel's id, refreshed when the upload finishes
	 * @param existingByName    the names already in this listing, for the clash check
	 * @return {@code true} when anything was uploaded
	 */
	public boolean acceptCopy(List<NuclrResource> selectedResources, NuclrResource focusedResource,
			NuclrResource currentResource, NuclrPluginContext context, String destinationUuid,
			Map<String, NuclrResource> existingByName) {

		if (!S3Resource.isInsideBucket(currentResource)) {
			Dialogs.error(title(), "Open a bucket to " + title().toLowerCase(java.util.Locale.ROOT) + " files into it.");
			return false;
		}

		String profileId = S3Resource.profileId(currentResource);
		String bucket = S3Resource.bucketName(currentResource);
		String prefix = S3Resource.objectPrefix(currentResource);
		S3Client client = S3Clients.byProfileId(profileId);
		if (client == null) {
			Dialogs.error(title(), "The connection profile for this panel is no longer available.");
			return false;
		}

		List<NuclrResource> sources = Selections.targets(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Dialogs.error(title(), "There is nothing here to " + title().toLowerCase(java.util.Locale.ROOT) + ".");
			return false;
		}

		TransferSetupDialog.Upload options = TransferSetupDialog.showUpload(
				title(), Selections.header(sources), "s3://" + bucket + '/' + prefix);
		if (options == null) {
			return false;
		}

		var failures = new ArrayList<String>();
		int[] uploaded = {0};
		Map<String, NuclrResource> existing = existingByName == null ? Map.of() : existingByName;

		ProgressDialog.run(title(), callback -> uploaded[0] = runUpload(
				client, bucket, prefix, sources, options.existing(), existing, options.concurrency(),
				callback, failures));

		if (uploaded[0] > 0) {
			Selections.refreshPanel(context, destinationUuid);
		}
		reportFailures(failures);
		return uploaded[0] > 0;
	}

	/**
	 * Upload each selected file, and each file beneath each selected folder.
	 *
	 * <p>Planned first, then run several at a time, for the same reason as the download side: name
	 * clashes are settled on one thread while the dialog can still be shown, and what reaches the
	 * workers is a list of keys to write.
	 */
	private int runUpload(S3Client client, String bucket, String prefix, List<NuclrResource> sources,
			ConflictDialog.Action existingMode, Map<String, NuclrResource> existingByName, int concurrency,
			NuclrPluginCallback callback, List<String> failures) {

		var conflicts = new ConflictDialog();
		Set<String> taken = new HashSet<>(existingByName.keySet());
		var tasks = new ArrayList<ParallelTransfers.Task>();
		var groups = new ArrayList<NuclrResource>();

		callback.onStart("Preparing " + title().toLowerCase(java.util.Locale.ROOT) + "…");

		for (NuclrResource source : sources) {

			if (callback.isCancelled()) {
				return 0;
			}

			String name = source.getName();
			int group = groups.size();

			if (source.isFolder()) {
				Outcome planned = planDirectoryUpload(client, bucket, prefix, source, group, tasks, failures);
				if (planned == Outcome.CANCELLED) {
					return 0;
				}
				if (planned == Outcome.OK) {
					groups.add(source);
					taken.add(name);
				}
				continue;
			}

			Predicate<String> takenHere = taken::contains;
			if (takenHere.test(name)) {
				ConflictDialog.Action action = existingMode;
				String renameName = null;

				if (action == null) {
					ConflictDialog.Resolution resolution = conflicts.resolve(
							"s3://" + bucket + '/' + prefix + name,
							sourceDetail(source),
							existingDetail(existingByName.get(name)),
							ConflictDialog.autoRenameName(name, takenHere));
					action = resolution.action();
					renameName = resolution.renameName();
				}

				if (action == ConflictDialog.Action.CANCEL) {
					return 0;
				}
				if (action == ConflictDialog.Action.SKIP) {
					continue;
				}
				if (action == ConflictDialog.Action.RENAME) {
					name = renameName != null ? renameName : ConflictDialog.autoRenameName(name, takenHere);
					if (takenHere.test(name)) {
						name = ConflictDialog.autoRenameName(name, takenHere);
					}
				}
				// Objects cannot be appended to, so Append and Overwrite both replace the key.
			}

			taken.add(name);
			groups.add(source);
			queueUpload(client, source, bucket, prefix + name, source.getName(), sourceSize(source),
					group, tasks, failures);
		}

		ParallelTransfers.Summary summary =
				ParallelTransfers.run(tasks, concurrency, callback, title() + "ing");

		int uploaded = 0;
		for (int group = 0; group < groups.size(); group++) {
			if (summary.groupSucceeded(group)) {
				uploaded++;
			}
		}

		if (summary.cancelled()) {
			log.info("S3 {} cancelled after {} item(s)", title().toLowerCase(java.util.Locale.ROOT), uploaded);
			return uploaded;
		}

		log.info("S3 {} in finished: {} item(s) uploaded, {} failed",
				title().toLowerCase(java.util.Locale.ROOT), uploaded, failures.size());
		return uploaded;
	}

	/** Plan a local directory tree, its structure becoming key structure. */
	private Outcome planDirectoryUpload(S3Client client, String bucket, String prefix, NuclrResource source,
			int group, List<ParallelTransfers.Task> tasks, List<String> failures) {

		Path directory = source.getPath();
		if (directory == null || !Files.isDirectory(directory)) {
			failures.add(source.getName() + " (not a readable folder)");
			return Outcome.FAILED;
		}

		String rootPrefix = prefix + source.getName() + '/';
		var files = new ArrayList<Path>();
		try (var walk = Files.walk(directory)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		} catch (IOException e) {
			failures.add(source.getName() + " (" + e.getMessage() + ')');
			return Outcome.FAILED;
		}

		if (files.isEmpty()) {
			// Nothing to upload, but the folder itself should still appear at the destination.
			S3Result<Void> created = client.createFolder(bucket, rootPrefix);
			if (!created.isOk()) {
				failures.add(source.getName() + " (" + created.errorOrNull().describe() + ')');
				return Outcome.FAILED;
			}
			return Outcome.OK;
		}

		for (Path file : files) {

			String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');

			long size;
			try {
				size = Files.size(file);
			} catch (IOException e) {
				failures.add(relative + " (" + e.getMessage() + ')');
				continue;
			}

			queueUpload(client, null, bucket, rootPrefix + relative, relative, size, group, tasks, failures,
					() -> Files.newInputStream(file));
		}

		return Outcome.OK;
	}

	/** Queue one upload whose bytes come from a resource. */
	private void queueUpload(S3Client client, NuclrResource source, String bucket, String key, String name,
			long size, int group, List<ParallelTransfers.Task> tasks, List<String> failures) {

		queueUpload(client, source, bucket, key, name, size, group, tasks, failures, () -> {
			try {
				return source.openInputStream();
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException(e);
			}
		});
	}

	/**
	 * Queue one upload.
	 *
	 * <p>The body supplier is opened afresh inside the task, on whichever worker picks it up, and
	 * again on a retry — which is why it is a supplier and not a stream.
	 */
	private void queueUpload(S3Client client, NuclrResource source, String bucket, String key, String name,
			long size, int group, List<ParallelTransfers.Task> tasks, List<String> failures,
			S3Client.BodySource body) {

		tasks.add(new ParallelTransfers.Task() {

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
			public Outcome transfer(S3Client.ProgressListener progress, BooleanSupplier cancelled) {
				S3Result<Long> result = client.upload(bucket, key, body, size, progress, cancelled);
				if (result.isCancelled()) {
					return Outcome.CANCELLED;
				}
				if (!result.isOk()) {
					record(failures, name, result.errorOrNull().describe());
					return Outcome.FAILED;
				}
				return Outcome.OK;
			}
		});
	}

	// -------------------------------------------------------------------------
	// S3 to S3
	// -------------------------------------------------------------------------

	/**
	 * Copy between two S3 panels. Within one profile this is a server-side copy; across profiles the
	 * credentials differ, so the bytes have to come down and go back up.
	 */
	private boolean copyWithinS3(List<NuclrResource> sources, NuclrResource destination,
			NuclrPluginContext context, String sourceUuid, BaseNuclrPlugin other) {

		String sourceProfileId = S3Resource.profileId(sources.get(0));
		String destinationProfileId = S3Resource.profileId(destination);

		if (!java.util.Objects.equals(sourceProfileId, destinationProfileId)) {
			Dialogs.error(title(), title() + " between two different S3 profiles is not supported yet.\n"
					+ title() + " to a local folder first, then up to the other profile.");
			return false;
		}

		S3Client client = S3Clients.byProfileId(sourceProfileId);
		if (client == null) {
			Dialogs.error(title(), "The connection profile for this panel is no longer available.");
			return false;
		}

		String destinationBucket = S3Resource.bucketName(destination);
		String destinationPrefix = S3Resource.objectPrefix(destination);

		TransferSetupDialog.Upload options = TransferSetupDialog.showUpload(title(),
				Selections.header(sources), "s3://" + destinationBucket + '/' + destinationPrefix);
		if (options == null) {
			return false;
		}

		var failures = new ArrayList<String>();
		int[] copied = {0};

		ProgressDialog.run(title(), callback -> copied[0] = runServerSideCopy(
				client, sources, destinationBucket, destinationPrefix, options.concurrency(),
				callback, failures, context, sourceUuid));

		if (copied[0] > 0) {
			Selections.refreshPanel(context, other.uuid());
		}
		reportFailures(failures);
		return copied[0] > 0;
	}

	/**
	 * Copy keys within S3 without moving bytes through this machine.
	 *
	 * <p>Each key is one short request that spends nearly all its time waiting on the service, so
	 * this is where running several at once pays best: a prefix of a thousand small objects goes
	 * from a thousand serial round trips to a fraction of that in wall-clock time.
	 */
	private int runServerSideCopy(S3Client client, List<NuclrResource> sources, String destinationBucket,
			String destinationPrefix, int concurrency, NuclrPluginCallback callback, List<String> failures,
			NuclrPluginContext context, String sourceUuid) {

		var tasks = new ArrayList<ParallelTransfers.Task>();
		var groups = new ArrayList<NuclrResource>();

		callback.onStart("Preparing " + title().toLowerCase(java.util.Locale.ROOT) + "…");

		for (NuclrResource source : sources) {

			if (callback.isCancelled()) {
				return 0;
			}

			String sourceBucket = S3Resource.bucketName(source);
			int group = groups.size();

			if (S3Resource.isObjectDir(source)) {
				Outcome planned = planPrefixServerSideCopy(client, sourceBucket, S3Resource.objectPrefix(source),
						destinationBucket, destinationPrefix + stripSlash(source.getName()) + '/',
						group, tasks, failures, callback);
				if (planned == Outcome.FAILED) {
					continue;
				}
			} else {
				queueServerSideCopy(client, sourceBucket, S3Resource.objectKey(source), destinationBucket,
						destinationPrefix + source.getName(), source.getName(), group, tasks, failures);
			}
			groups.add(source);
		}

		ParallelTransfers.Summary summary =
				ParallelTransfers.run(tasks, concurrency, callback, title() + "ing");

		int copied = 0;
		for (int group = 0; group < groups.size(); group++) {
			if (!summary.groupSucceeded(group)) {
				continue;
			}
			copied++;
			// As above: a source that could not be removed keeps its mark.
			if (!moving || deleteSource(client, groups.get(group), failures)) {
				Selections.unmark(context, sourceUuid, groups.get(group));
			}
		}

		log.info("S3 server-side {} finished: {} item(s), {} failed",
				title().toLowerCase(java.util.Locale.ROOT), copied, failures.size());
		return copied;
	}

	/** Queue every object under one prefix for copying to another. */
	private Outcome planPrefixServerSideCopy(S3Client client, String sourceBucket, String sourcePrefix,
			String destinationBucket, String destinationPrefix, int group,
			List<ParallelTransfers.Task> tasks, List<String> failures, NuclrPluginCallback callback) {

		S3Result<List<S3ObjectEntry>> contents =
				S3Walk.collect(client, sourceBucket, sourcePrefix, callback::isCancelled);
		if (!contents.isOk()) {
			failures.add(sourcePrefix + " (" + contents.errorOrNull().describe() + ')');
			return Outcome.FAILED;
		}

		for (S3ObjectEntry entry : contents.orNull()) {
			String relative = S3Walk.relativeKey(entry.key(), sourcePrefix);
			if (relative.isEmpty()) {
				continue;
			}
			queueServerSideCopy(client, sourceBucket, entry.key(), destinationBucket,
					destinationPrefix + relative, relative, group, tasks, failures);
		}

		return Outcome.OK;
	}

	/** Queue one key-to-key copy. */
	private void queueServerSideCopy(S3Client client, String sourceBucket, String sourceKey,
			String destinationBucket, String destinationKey, String name, int group,
			List<ParallelTransfers.Task> tasks, List<String> failures) {

		tasks.add(new ParallelTransfers.Task() {

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
				// The bytes never come through this machine, so there is nothing to weigh a
				// progress bar with; the run is measured in keys instead.
				return 0;
			}

			@Override
			public Outcome transfer(S3Client.ProgressListener progress, BooleanSupplier cancelled) {
				if (sourceKey == null) {
					record(failures, name, "not a copyable object");
					return Outcome.FAILED;
				}
				S3Result<Void> result = client.copyObject(sourceBucket, sourceKey, destinationBucket, destinationKey);
				if (!result.isOk()) {
					record(failures, name, result.errorOrNull().describe());
					return Outcome.FAILED;
				}
				return Outcome.OK;
			}
		});
	}

	// -------------------------------------------------------------------------
	// Shared
	// -------------------------------------------------------------------------

	/**
	 * Record a failure from a worker thread.
	 *
	 * <p>The failure list is built on the planning thread and then appended to by several workers
	 * at once, so every write to it goes through here.
	 */
	private static void record(List<String> failures, String name, String reason) {
		synchronized (failures) {
			failures.add(name + " (" + reason + ')');
		}
	}

	/** For a move: remove the source once the copy landed. A failure here is reported, not fatal. */
	private boolean deleteSource(S3Client client, NuclrResource source, List<String> failures) {

		String bucket = S3Resource.bucketName(source);

		if (S3Resource.isObjectDir(source)) {
			String prefix = S3Resource.objectPrefix(source);
			S3Result<List<S3ObjectEntry>> contents = S3Walk.collect(client, bucket, prefix, null);
			if (!contents.isOk()) {
				failures.add(source.getName() + " (copied, but could not be removed: "
						+ contents.errorOrNull().describe() + ')');
				return false;
			}
			var keys = new ArrayList<String>();
			for (S3ObjectEntry entry : contents.orNull()) {
				keys.add(entry.key());
			}
			return deleteKeys(client, bucket, keys, source.getName(), failures);
		}

		S3Result<Void> result = client.deleteObject(bucket, S3Resource.objectKey(source));
		if (!result.isOk()) {
			failures.add(source.getName() + " (copied, but could not be removed: "
					+ result.errorOrNull().describe() + ')');
			return false;
		}
		return true;
	}

	private boolean deleteKeys(S3Client client, String bucket, List<String> keys, String label,
			List<String> failures) {

		for (int start = 0; start < keys.size(); start += S3Client.DELETE_BATCH_SIZE) {
			List<String> batch = keys.subList(start, Math.min(start + S3Client.DELETE_BATCH_SIZE, keys.size()));
			S3Result<Void> result = client.deleteObjects(bucket, batch);
			if (!result.isOk()) {
				failures.add(label + " (copied, but could not be removed: "
						+ result.errorOrNull().describe() + ')');
				return false;
			}
		}
		return true;
	}

	/** The "New" row for a clash where the incoming item is an S3 object. */
	private static String describeObject(S3Client client, String bucket, String key) {
		S3Result<S3ObjectEntry> head = client.headObject(bucket, key);
		if (!head.isOk()) {
			return "";
		}
		S3ObjectEntry entry = head.orNull();
		return entry.size() + "   " + S3Resource.formatTimestamp(entry.lastModified());
	}

	/** The "New" row for a clash where the incoming item is a local file. */
	private static String sourceDetail(NuclrResource source) {
		Path path = source.getPath();
		return path != null ? ConflictDialog.pathDetail(path) : String.valueOf(Math.max(source.getLength(), 0));
	}

	/** The "Existing" row for a clash, from the listing metadata of the object already there. */
	private static String existingDetail(NuclrResource existing) {
		if (existing == null) {
			return "";
		}
		Object size = existing.getMetadata().get("Size");
		Object modified = existing.getMetadata().get("Modified");
		return (size == null ? "" : size) + "   " + (modified == null ? "" : modified);
	}

	/** The size of a source: the real file size when there is one, else the row's declared length. */
	private static long sourceSize(NuclrResource source) {
		Path path = source.getPath();
		if (path != null) {
			try {
				return Files.size(path);
			} catch (IOException e) {
				log.debug("Could not size {}: {}", path, e.getMessage());
			}
		}
		return Math.max(source.getLength(), 0);
	}

	private static String stripSlash(String name) {
		return name != null && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
	}

	private void reportFailures(List<String> failures) {
		if (failures.isEmpty()) {
			return;
		}
		int shown = Math.min(failures.size(), 12);
		var message = new StringBuilder("Some items could not be " + title().toLowerCase(java.util.Locale.ROOT) + "d:\n\n");
		for (int i = 0; i < shown; i++) {
			message.append(failures.get(i)).append('\n');
		}
		if (failures.size() > shown) {
			message.append("… and ").append(failures.size() - shown).append(" more");
		}
		Dialogs.error(title(), message.toString());
	}
}
