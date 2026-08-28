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

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.S3Resource;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.ui.DeleteDialogs;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;
import dev.nuclr.plugin.core.panel.s3.ui.ProgressDialog;
import lombok.extern.slf4j.Slf4j;

/**
 * F8 delete: remove the selected objects, and everything beneath the selected folders.
 *
 * <p>Two things distinguish this from the local panel's delete. A folder is not a thing that can be
 * removed — it is a prefix, and deleting it means deleting every key under it — so the confirmation
 * counts those keys first and says how many objects are really at stake rather than naming a folder
 * and hiding the scale. And the removal itself is batched a thousand keys to a request, because a
 * request per key against a prefix holding fifty thousand of them is the difference between a few
 * seconds and several minutes.
 *
 * <p>The whole run happens under a cancellable progress dialog; a per-object failure asks whether to
 * skip it or abort, matching the local panel.
 */
@Slf4j
public final class S3DeleteService {

	/** Create a delete service. */
	public S3DeleteService() {
	}

	/**
	 * Confirm and delete the selection, blocking until it finishes or is cancelled.
	 *
	 * @param selectedResources the marked rows
	 * @param focusedResource   the row under the cursor
	 * @return how many objects were removed; {@code 0} when nothing was confirmed
	 */
	public int delete(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		List<NuclrResource> targets = Selections.s3Targets(selectedResources, focusedResource);
		if (targets.isEmpty()) {
			Dialogs.info("Delete", "Select one or more objects or folders to delete.");
			return 0;
		}

		S3Client client = S3Clients.byProfileId(S3Resource.profileId(targets.get(0)));
		if (client == null) {
			Dialogs.error("Delete", "The connection profile for this panel is no longer available.");
			return 0;
		}

		// Work out what the selection really amounts to before asking: a single folder row can stand
		// for any number of objects, and the user deserves to know which before saying yes.
		Plan plan = plan(client, targets);
		if (plan == null) {
			return 0; // the walk failed and has already been reported
		}
		if (plan.keys().isEmpty()) {
			Dialogs.info("Delete", "There is nothing to delete: the selection is empty.");
			return 0;
		}
		if (!DeleteDialogs.confirmDelete(targets, plan.keys().size())) {
			return 0;
		}

		int[] deleted = {0};
		ProgressDialog.run("Delete", callback -> deleted[0] = run(client, plan, callback));
		return deleted[0];
	}

	/** What a delete will actually remove: the bucket, and every key under the selection. */
	private record Plan(String bucket, List<String> keys) {}

	/** Expand folder rows into the keys beneath them, reporting a walk that fails. */
	private Plan plan(S3Client client, List<NuclrResource> targets) {

		String bucket = S3Resource.bucketName(targets.get(0));
		var keys = new ArrayList<String>();

		for (NuclrResource target : targets) {

			if (S3Resource.isObject(target)) {
				String key = S3Resource.objectKey(target);
				if (key != null) {
					keys.add(key);
				}
				continue;
			}

			String prefix = S3Resource.objectPrefix(target);
			S3Result<List<S3ObjectEntry>> contents = S3Walk.collect(client, bucket, prefix, null);
			if (!contents.isOk()) {
				Dialogs.error("Delete", "Could not list " + target.getName() + " to delete it:\n"
						+ contents.errorOrNull().describe());
				return null;
			}
			for (S3ObjectEntry entry : contents.orNull()) {
				keys.add(entry.key());
			}
			// The prefix's own placeholder object, if one exists, is what keeps an emptied folder
			// visible in the panel; it has to go too.
			keys.add(prefix);
		}

		return new Plan(bucket, keys);
	}

	/** Delete the planned keys in batches, reporting progress and honouring cancellation. */
	private int run(S3Client client, Plan plan, NuclrPluginCallback callback) {

		int deleted = 0;
		List<String> keys = plan.keys();

		for (int start = 0; start < keys.size(); start += S3Client.DELETE_BATCH_SIZE) {

			if (callback.isCancelled()) {
				break;
			}
			List<String> batch = keys.subList(start, Math.min(start + S3Client.DELETE_BATCH_SIZE, keys.size()));
			callback.onStart("Deleting " + (start + 1) + "–" + (start + batch.size()) + " of " + keys.size());
			callback.onProgress(start, keys.size());

			S3Result<Void> result = client.deleteObjects(plan.bucket(), batch);
			if (!result.isOk()) {
				String label = batch.size() == 1 ? batch.get(0) : batch.size() + " objects";
				if (!DeleteDialogs.error(label, result.errorOrNull())) {
					log.info("S3 delete aborted by the user after {} object(s)", deleted);
					return deleted;
				}
				continue;
			}
			deleted += batch.size();
		}

		callback.onProgress(keys.size(), keys.size());
		log.info("S3 delete finished: {} object(s) removed", deleted);
		return deleted;
	}
}
