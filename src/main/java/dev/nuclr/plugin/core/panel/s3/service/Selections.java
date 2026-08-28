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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Resource;

/**
 * The small rules every panel operation shares about <em>what</em> it acts on.
 *
 * <p>The commander hands an action both the marked rows and the row under the cursor. The convention
 * across the panels is that marks win when there are any, and the cursor row is the target when there
 * are none — so pressing F8 with nothing marked deletes what you are looking at, which is what a user
 * of a two-pane file manager expects. The synthetic rows ({@code ..} and "load more") are never
 * targets, whether or not a marking gesture happened to include them.
 */
public final class Selections {

	private Selections() {}

	/**
	 * The rows an action should operate on: the marked ones, or the cursor row when nothing is
	 * marked. Synthetic navigation rows are dropped.
	 *
	 * @param selectedResources the marked rows, possibly empty
	 * @param focusedResource   the row under the cursor
	 * @return the rows to act on, never {@code null}
	 */
	public static List<NuclrResource> targets(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		var chosen = new ArrayList<NuclrResource>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			chosen.addAll(selectedResources);
		} else if (focusedResource != null) {
			chosen.add(focusedResource);
		}

		var targets = new ArrayList<NuclrResource>(chosen.size());
		for (NuclrResource resource : chosen) {
			if (resource == null || "..".equals(resource.getName()) || S3Resource.isLoadMore(resource)) {
				continue;
			}
			targets.add(resource);
		}
		return targets;
	}

	/**
	 * The S3 objects and folders among the targets — what a copy, move or delete out of this panel
	 * can act on.
	 *
	 * @param selectedResources the marked rows
	 * @param focusedResource   the row under the cursor
	 * @return the S3 rows to act on
	 */
	public static List<NuclrResource> s3Targets(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		var targets = new ArrayList<NuclrResource>();
		for (NuclrResource resource : targets(selectedResources, focusedResource)) {
			if (S3Resource.isObject(resource) || S3Resource.isObjectDir(resource)) {
				targets.add(resource);
			}
		}
		return targets;
	}

	/**
	 * The other panel's current folder as a local directory, or {@code null} when it is not one —
	 * which is how a transfer discovers whether it can write there at all.
	 *
	 * @param other the plugin driving the other panel
	 * @return the local directory, or {@code null}
	 */
	public static Path localDirectory(BaseNuclrPlugin other) {
		if (other == null) {
			return null;
		}
		NuclrResource current = other.getCurrentResource();
		Path path = current == null ? null : current.getPath();
		return path != null && Files.isDirectory(path) ? path : null;
	}

	/**
	 * The S3 folder the other panel has open, when the other panel is also an S3 panel sitting
	 * inside a bucket. That is what makes a server-side copy possible instead of pulling the bytes
	 * down and pushing them back up.
	 *
	 * @param other the plugin driving the other panel
	 * @return the other panel's bucket folder, or {@code null}
	 */
	public static NuclrResource s3Directory(BaseNuclrPlugin other) {
		if (other == null) {
			return null;
		}
		NuclrResource current = other.getCurrentResource();
		return S3Resource.isInsideBucket(current) ? current : null;
	}

	/**
	 * A short description of a transfer set for a dialog header: the single name, or a count.
	 *
	 * @param resources what is being transferred
	 * @return the header text
	 */
	public static String header(List<? extends NuclrResource> resources) {
		return resources.size() == 1 ? resources.get(0).getName() : resources.size() + " items";
	}

	/**
	 * Ask the commander to clear a row's mark, now that the row has been dealt with. Copying a
	 * hundred files and watching the marks clear one by one is how the user sees progress in the
	 * panel itself, not just in the dialog.
	 *
	 * @param context   the plugin context supplying the event bus
	 * @param panelUuid the panel holding the mark
	 * @param resource  the row to unmark
	 */
	public static void unmark(NuclrPluginContext context, String panelUuid, NuclrResource resource) {
		if (context == null || context.getEventBus() == null || panelUuid == null
				|| resource == null || resource.getUuid() == null) {
			return;
		}
		context.getEventBus().emit("filepanel.unmark.entry",
				Map.of("plugin.uuid", panelUuid, "entry.uuid", resource.getUuid()), null);
	}

	/**
	 * Ask the commander to reload a panel, after something changed underneath it.
	 *
	 * @param context   the plugin context supplying the event bus
	 * @param panelUuid the panel to refresh
	 */
	public static void refreshPanel(NuclrPluginContext context, String panelUuid) {
		if (context == null || context.getEventBus() == null || panelUuid == null) {
			return;
		}
		context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", panelUuid), null);
	}
}
