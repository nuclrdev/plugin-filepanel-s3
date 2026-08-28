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

import java.util.Set;

import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;
import lombok.extern.slf4j.Slf4j;

/**
 * F7 make folder.
 *
 * <p>S3 has no folders, so this writes the same thing the AWS console does: a zero-byte object whose
 * key ends in {@code /}. Every S3 tool, this panel included, reads such a key as a folder, and it is
 * what lets a new folder be visible and navigable before anything has been put in it.
 *
 * <p>The name rules match the local file panel's — no separators, no {@code .} or {@code ..}, and
 * nothing that already exists in the listing — so the same typing habits carry across panels.
 */
@Slf4j
public final class S3MakeFolderService {

	private static final String TITLE = "Make Folder";

	/** Create the service. */
	public S3MakeFolderService() {
	}

	/**
	 * Prompt for a name and create the folder under a prefix.
	 *
	 * @param client        the client to create through
	 * @param bucket        the bucket
	 * @param prefix        the prefix the folder is created in
	 * @param existingNames the names already shown in this listing, for the duplicate check
	 * @return the new folder's name, or {@code null} when nothing was created
	 */
	public String makeFolder(S3Client client, String bucket, String prefix, Set<String> existingNames) {

		String entered = Dialogs.prompt(TITLE, "Folder name:", null);
		if (entered == null) {
			return null; // cancelled
		}

		String folderName = entered.trim();
		if (folderName.isBlank()) {
			return null;
		}
		if (isInvalidName(folderName)) {
			Dialogs.error(TITLE, "A folder name cannot contain path separators.");
			return null;
		}
		if (existingNames != null
				&& (existingNames.contains(folderName) || existingNames.contains(folderName + "/"))) {
			Dialogs.error(TITLE, "Something with that name already exists here.");
			return null;
		}

		String folderKey = prefix + folderName + "/";
		S3Result<Void> result = client.createFolder(bucket, folderKey);
		if (!result.isOk()) {
			log.warn("Could not create s3://{}/{}: {}", bucket, folderKey, result.errorOrNull().describe());
			Dialogs.error(TITLE, "Could not create the folder:\n" + result.errorOrNull().describe());
			return null;
		}

		log.info("Created s3://{}/{}", bucket, folderKey);
		return folderName;
	}

	/**
	 * Whether a name is unusable as a single folder segment.
	 *
	 * @param folderName the proposed name
	 * @return {@code true} when it must be rejected
	 */
	static boolean isInvalidName(String folderName) {
		return folderName.equals(".")
				|| folderName.equals("..")
				|| folderName.indexOf('/') >= 0
				|| folderName.indexOf('\\') >= 0
				|| folderName.indexOf('\0') >= 0;
	}
}
