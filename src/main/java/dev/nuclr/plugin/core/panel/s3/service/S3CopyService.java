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
				sources, options.destination(), options.existing(), callback, failures, context, sourceUuid));

		Selections.refreshPanel(context, other.uuid());
		reportFailures(failures);
		return transferred[0] > 0;
	}

	/** Download each selected object, and each object beneath each selected folder. */
	private int runDownload(List<NuclrResource> sources, Path destination, ConflictDialog.Action existingMode,
			NuclrPluginCallback callback, List<String> failures, NuclrPluginContext context, String sourceUuid) {

		var conflicts = new ConflictDialog();
		int transferred = 0;

		for (int i = 0; i < sources.size(); i++) {

			if (callback.isCancelled()) {
				break;
			}
			NuclrResource source = sources.get(i);
			String profileId = S3Resource.profileId(source);
			String bucket = S3Resource.bucketName(source);
			S3Client client = S3Clients.byProfileId(profileId);
			if (client == null) {
				failures.add(source.getName() + " (its connection profile is gone)");
				continue;
			}

			callback.onStart(title() + "ing " + source.getName() + " (" + (i + 1) + '/' + sources.size() + ')');

			Outcome outcome = S3Resource.isObjectDir(source)
					? downloadFolder(client, source, destination, existingMode, conflicts, callback, failures)
					: downloadObject(client, bucket, S3Resource.objectKey(source), source.getName(),
							destination, existingMode, conflicts, callback, failures);

			switch (outcome) {
				case OK -> {
					transferred++;
					// For a move, the mark stays put when the source could not be removed: the item
					// is still there, and the panel should keep showing it as selected.
					if (!moving || deleteSource(client, source, failures)) {
						Selections.unmark(context, sourceUuid, source);
					}
				}
				case CANCELLED -> {
					log.info("S3 {} cancelled after {} item(s)", title().toLowerCase(java.util.Locale.ROOT), transferred);
					return transferred;
				}
				case FAILED -> { /* already recorded in failures */ }
			}
		}

		log.info("S3 {} out finished: {} item(s) transferred, {} failed",
				title().toLowerCase(java.util.Locale.ROOT), transferred, failures.size());
		return transferred;
	}

	/** Recreate a bucket prefix as a local directory tree. */
	private Outcome downloadFolder(S3Client client, NuclrResource folder, Path destination,
			ConflictDialog.Action existingMode, ConflictDialog conflicts, NuclrPluginCallback callback,
			List<String> failures) {

		String bucket = S3Resource.bucketName(folder);
		String prefix = S3Resource.objectPrefix(folder);
		String folderName = stripSlash(folder.getName());

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

			if (callback.isCancelled()) {
				return Outcome.CANCELLED;
			}
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

			callback.onStart(title() + "ing " + relative);
			Outcome outcome = downloadObject(client, bucket, entry.key(), target.getFileName().toString(),
					target.getParent(), existingMode, conflicts, callback, failures);
			if (outcome == Outcome.CANCELLED) {
				return Outcome.CANCELLED;
			}
		}

		return Outcome.OK;
	}

	/** Download one object into a local directory, resolving any clash first. */
	private Outcome downloadObject(S3Client client, String bucket, String key, String name, Path directory,
			ConflictDialog.Action existingMode, ConflictDialog conflicts, NuclrPluginCallback callback,
			List<String> failures) {

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

		// Download beside the target and move it into place, so an interrupted transfer never
		// leaves a half-written file wearing the real name.
		Path temp;
		try {
			temp = Files.createTempFile(directory, "nuclr-s3-", ".part");
		} catch (IOException e) {
			failures.add(name + " (" + e.getMessage() + ')');
			return Outcome.FAILED;
		}

		try {
			S3Result<Long> result = client.downloadToFile(bucket, key, temp, callback::onProgress, callback::isCancelled);
			if (result.isCancelled()) {
				return Outcome.CANCELLED;
			}
			if (!result.isOk()) {
				failures.add(name + " (" + result.errorOrNull().describe() + ')');
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
			failures.add(name + " (" + e.getMessage() + ')');
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
				client, bucket, prefix, sources, options.existing(), existing, callback, failures));

		if (uploaded[0] > 0) {
			Selections.refreshPanel(context, destinationUuid);
		}
		reportFailures(failures);
		return uploaded[0] > 0;
	}

	private int runUpload(S3Client client, String bucket, String prefix, List<NuclrResource> sources,
			ConflictDialog.Action existingMode, Map<String, NuclrResource> existingByName,
			NuclrPluginCallback callback, List<String> failures) {

		var conflicts = new ConflictDialog();
		Set<String> taken = new HashSet<>(existingByName.keySet());
		int uploaded = 0;

		for (int i = 0; i < sources.size(); i++) {

			if (callback.isCancelled()) {
				break;
			}
			NuclrResource source = sources.get(i);
			String name = source.getName();
			callback.onStart(title() + "ing " + name + " (" + (i + 1) + '/' + sources.size() + ')');

			if (source.isFolder()) {
				Outcome outcome = uploadDirectory(client, bucket, prefix, source, callback, failures);
				if (outcome == Outcome.CANCELLED) {
					return uploaded;
				}
				if (outcome == Outcome.OK) {
					uploaded++;
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
					return uploaded;
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

			Outcome outcome = uploadOne(client, source, bucket, prefix + name, callback, failures);
			switch (outcome) {
				case OK -> {
					uploaded++;
					taken.add(name);
				}
				case CANCELLED -> {
					return uploaded;
				}
				case FAILED -> { /* already recorded */ }
			}
		}

		log.info("S3 {} in finished: {} item(s) uploaded, {} failed",
				title().toLowerCase(java.util.Locale.ROOT), uploaded, failures.size());
		return uploaded;
	}

	/** Upload a local directory tree, its structure becoming key structure. */
	private Outcome uploadDirectory(S3Client client, String bucket, String prefix, NuclrResource source,
			NuclrPluginCallback callback, List<String> failures) {

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

			if (callback.isCancelled()) {
				return Outcome.CANCELLED;
			}
			String relative = directory.relativize(file).toString().replace(java.io.File.separatorChar, '/');
			String key = rootPrefix + relative;
			callback.onStart(title() + "ing " + relative);

			long size;
			try {
				size = Files.size(file);
			} catch (IOException e) {
				failures.add(relative + " (" + e.getMessage() + ')');
				continue;
			}

			S3Result<Long> result = client.upload(bucket, key, () -> Files.newInputStream(file), size,
					callback::onProgress, callback::isCancelled);
			if (result.isCancelled()) {
				return Outcome.CANCELLED;
			}
			if (!result.isOk()) {
				failures.add(relative + " (" + result.errorOrNull().describe() + ')');
			}
		}

		return Outcome.OK;
	}

	/** Upload one source resource's content to a key. */
	private Outcome uploadOne(S3Client client, NuclrResource source, String bucket, String key,
			NuclrPluginCallback callback, List<String> failures) {

		S3Client.BodySource body = () -> {
			try {
				return source.openInputStream();
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException(e);
			}
		};

		S3Result<Long> result = client.upload(bucket, key, body, sourceSize(source),
				callback::onProgress, callback::isCancelled);
		if (result.isCancelled()) {
			return Outcome.CANCELLED;
		}
		if (!result.isOk()) {
			failures.add(source.getName() + " (" + result.errorOrNull().describe() + ')');
			return Outcome.FAILED;
		}
		return Outcome.OK;
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
				client, sources, destinationBucket, destinationPrefix, callback, failures, context, sourceUuid));

		if (copied[0] > 0) {
			Selections.refreshPanel(context, other.uuid());
		}
		reportFailures(failures);
		return copied[0] > 0;
	}

	private int runServerSideCopy(S3Client client, List<NuclrResource> sources, String destinationBucket,
			String destinationPrefix, NuclrPluginCallback callback, List<String> failures,
			NuclrPluginContext context, String sourceUuid) {

		int copied = 0;

		for (int i = 0; i < sources.size(); i++) {

			if (callback.isCancelled()) {
				break;
			}
			NuclrResource source = sources.get(i);
			String sourceBucket = S3Resource.bucketName(source);
			callback.onStart(title() + "ing " + source.getName() + " (" + (i + 1) + '/' + sources.size() + ')');
			callback.onProgress(i, sources.size());

			boolean ok;
			if (S3Resource.isObjectDir(source)) {
				ok = copyPrefixServerSide(client, sourceBucket, S3Resource.objectPrefix(source),
						destinationBucket, destinationPrefix + stripSlash(source.getName()) + '/',
						callback, failures);
			} else {
				String key = S3Resource.objectKey(source);
				S3Result<Void> result = client.copyObject(sourceBucket, key,
						destinationBucket, destinationPrefix + source.getName());
				ok = result.isOk();
				if (!ok) {
					failures.add(source.getName() + " (" + result.errorOrNull().describe() + ')');
				}
			}

			if (ok) {
				copied++;
				// As above: a source that could not be removed keeps its mark.
				if (!moving || deleteSource(client, source, failures)) {
					Selections.unmark(context, sourceUuid, source);
				}
			}
		}

		callback.onProgress(sources.size(), sources.size());
		log.info("S3 server-side {} finished: {} item(s), {} failed",
				title().toLowerCase(java.util.Locale.ROOT), copied, failures.size());
		return copied;
	}

	/** Copy every object under one prefix to another, key by key. */
	private boolean copyPrefixServerSide(S3Client client, String sourceBucket, String sourcePrefix,
			String destinationBucket, String destinationPrefix, NuclrPluginCallback callback,
			List<String> failures) {

		S3Result<List<S3ObjectEntry>> contents = S3Walk.collect(client, sourceBucket, sourcePrefix, callback::isCancelled);
		if (!contents.isOk()) {
			failures.add(sourcePrefix + " (" + contents.errorOrNull().describe() + ')');
			return false;
		}

		boolean allCopied = true;
		for (S3ObjectEntry entry : contents.orNull()) {
			if (callback.isCancelled()) {
				return false;
			}
			String relative = S3Walk.relativeKey(entry.key(), sourcePrefix);
			if (relative.isEmpty()) {
				continue;
			}
			callback.onStart(title() + "ing " + relative);
			S3Result<Void> result = client.copyObject(sourceBucket, entry.key(),
					destinationBucket, destinationPrefix + relative);
			if (!result.isOk()) {
				failures.add(relative + " (" + result.errorOrNull().describe() + ')');
				allCopied = false;
			}
		}
		return allCopied;
	}

	// -------------------------------------------------------------------------
	// Shared
	// -------------------------------------------------------------------------

	private enum Outcome { OK, CANCELLED, FAILED }

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
