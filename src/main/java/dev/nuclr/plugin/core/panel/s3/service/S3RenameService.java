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

import static dev.nuclr.plugin.core.panel.s3.Messages.tr;

import java.util.ArrayList;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3Resource;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;
import dev.nuclr.plugin.core.panel.s3.ui.ProgressDialog;
import lombok.extern.slf4j.Slf4j;

/**
 * F6 rename, when both panels show the same folder — the in-place rename a two-pane manager offers
 * instead of a move to nowhere.
 *
 * <p>S3 cannot rename anything. A rename is a copy to the new key followed by a delete of the old
 * one, which has two consequences worth being honest about: renaming a folder means copying every
 * object beneath it, so a large prefix takes real time; and because the copy happens server-side,
 * that time is spent inside the service rather than on the user's connection.
 *
 * <p>The delete only runs once the copy has succeeded, so an interrupted rename leaves the original
 * intact rather than losing it between the two halves.
 */
@Slf4j
public final class S3RenameService {

	/** Create the service. */
	public S3RenameService() {
	}

	/**
	 * Prompt for a new name and rename the focused object or folder in place.
	 *
	 * @param focusedResource the row under the cursor
	 * @return {@code true} when something was renamed, so the caller can refresh
	 */
	public boolean rename(NuclrResource focusedResource) {

		if (focusedResource == null || "..".equals(focusedResource.getName())
				|| !(S3Resource.isObject(focusedResource) || S3Resource.isObjectDir(focusedResource))) {
			Dialogs.info(tr("common.rename"), tr("rename.select"));
			return false;
		}

		S3Client client = S3Clients.byProfileId(S3Resource.profileId(focusedResource));
		if (client == null) {
			Dialogs.error(tr("common.rename"), tr("common.connection.gone"));
			return false;
		}

		boolean isFolder = S3Resource.isObjectDir(focusedResource);
		String currentName = isFolder ? stripSlash(focusedResource.getName()) : focusedResource.getName();

		String entered = Dialogs.prompt(tr("common.rename"), tr("rename.prompt"), currentName);
		if (entered == null) {
			return false;
		}
		String newName = entered.trim();
		if (newName.isBlank() || newName.equals(currentName)) {
			return false;
		}
		if (S3MakeFolderService.isInvalidName(newName)) {
			Dialogs.error(tr("common.rename"), tr("rename.separators"));
			return false;
		}

		String bucket = S3Resource.bucketName(focusedResource);
		boolean[] renamed = {false};

		if (isFolder) {
			String sourcePrefix = S3Resource.objectPrefix(focusedResource);
			String destinationPrefix = S3Resource.parentPrefix(sourcePrefix) + newName + '/';
			ProgressDialog.run(tr("common.rename"), callback ->
					renamed[0] = renameFolder(client, bucket, sourcePrefix, destinationPrefix, callback));
		} else {
			String sourceKey = S3Resource.objectKey(focusedResource);
			String destinationKey = S3Resource.keyPrefix(sourceKey) + newName;
			ProgressDialog.run(tr("common.rename"), callback ->
					renamed[0] = renameObject(client, bucket, sourceKey, destinationKey, callback));
		}

		return renamed[0];
	}

	/** Copy one key to its new name, then remove the old one. */
	private boolean renameObject(S3Client client, String bucket, String sourceKey, String destinationKey,
			NuclrPluginCallback callback) {

		callback.onStart(tr("rename.progress", S3ObjectEntry.lastSegment(sourceKey)));

		S3Result<Void> copied = client.copyObject(bucket, sourceKey, bucket, destinationKey);
		if (!copied.isOk()) {
			Dialogs.error(tr("common.rename"), tr("rename.object.failed", copied.errorOrNull().display()));
			return false;
		}

		S3Result<Void> deleted = client.deleteObject(bucket, sourceKey);
		if (!deleted.isOk()) {
			// The new key exists, so nothing was lost — but both names are now present, and the
			// user needs to know rather than discover a duplicate later.
			Dialogs.error(tr("common.rename"), tr("rename.object.not.removed", deleted.errorOrNull().display()));
			return true;
		}

		log.info("Renamed s3://{}/{} to {}", bucket, sourceKey, destinationKey);
		return true;
	}

	/** Copy every key under a prefix to the new prefix, then remove the originals. */
	private boolean renameFolder(S3Client client, String bucket, String sourcePrefix, String destinationPrefix,
			NuclrPluginCallback callback) {

		callback.onStart(tr("rename.listing", sourcePrefix));
		S3Result<List<S3ObjectEntry>> contents = S3Walk.collect(client, bucket, sourcePrefix, callback::isCancelled);
		if (contents.isCancelled()) {
			return false;
		}
		if (!contents.isOk()) {
			Dialogs.error(tr("common.rename"), tr("rename.list.failed", contents.errorOrNull().display()));
			return false;
		}

		List<S3ObjectEntry> entries = contents.orNull();
		var copiedKeys = new ArrayList<String>(entries.size());

		for (int i = 0; i < entries.size(); i++) {

			if (callback.isCancelled()) {
				// The copies made so far stay: removing them could destroy an object that was
				// already at the destination. The user sees both prefixes and can decide.
				Dialogs.info(tr("common.rename"), tr("rename.cancelled", "s3://" + bucket + '/' + destinationPrefix));
				return true;
			}

			S3ObjectEntry entry = entries.get(i);
			String relative = S3Walk.relativeKey(entry.key(), sourcePrefix);
			callback.onStart(tr("rename.progress", relative));
			callback.onProgress(i, entries.size());

			S3Result<Void> copied = client.copyObject(bucket, entry.key(), bucket, destinationPrefix + relative);
			if (!copied.isOk()) {
				Dialogs.error(tr("common.rename"), tr("rename.folder.failed", relative, copied.errorOrNull().display()));
				return false;
			}
			copiedKeys.add(entry.key());
		}

		callback.onStart(tr("rename.removing"));
		// The prefix's own placeholder goes too, or the emptied folder stays visible.
		copiedKeys.add(sourcePrefix);

		for (int start = 0; start < copiedKeys.size(); start += S3Client.DELETE_BATCH_SIZE) {
			List<String> batch = copiedKeys.subList(start,
					Math.min(start + S3Client.DELETE_BATCH_SIZE, copiedKeys.size()));
			S3Result<Void> deleted = client.deleteObjects(bucket, batch);
			if (!deleted.isOk()) {
				Dialogs.error(tr("common.rename"), tr("rename.folder.not.removed", deleted.errorOrNull().display()));
				return true;
			}
		}

		callback.onProgress(entries.size(), entries.size());
		log.info("Renamed s3://{}/{} to {} ({} object(s))", bucket, sourcePrefix, destinationPrefix, entries.size());
		return true;
	}

	private static String stripSlash(String name) {
		return name != null && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
	}
}
