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
package dev.nuclr.plugin.core.panel.s3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.s3.api.S3BucketEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3Endpoint;
import dev.nuclr.plugin.core.panel.s3.api.S3ObjectEntry;
import dev.nuclr.plugin.core.panel.s3.api.S3Result;
import dev.nuclr.plugin.core.panel.s3.api.S3Xml;
import dev.nuclr.plugin.core.panel.s3.auth.AwsCli;
import dev.nuclr.plugin.core.panel.s3.auth.AwsConfigFiles;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.find.S3FindDialog;
import dev.nuclr.plugin.core.panel.s3.find.S3FindRequest;
import dev.nuclr.plugin.core.panel.s3.find.S3FindResultsWindow;
import dev.nuclr.plugin.core.panel.s3.find.S3FindService;
import dev.nuclr.plugin.core.panel.s3.service.S3CopyService;
import dev.nuclr.plugin.core.panel.s3.service.S3DeleteService;
import dev.nuclr.plugin.core.panel.s3.service.S3MakeFolderService;
import dev.nuclr.plugin.core.panel.s3.service.S3RenameService;
import dev.nuclr.plugin.core.panel.s3.service.S3Walk;
import dev.nuclr.plugin.core.panel.s3.service.Selections;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;
import dev.nuclr.plugin.core.panel.s3.ui.ProfileDialog;
import lombok.extern.slf4j.Slf4j;

/**
 * The S3 file panel: browse and manage Amazon S3 and S3-compatible object storage from a commander
 * pane.
 *
 * <p>Navigation has four levels. The {@code S3} root lists the saved connection profiles; opening
 * one lists its buckets (or goes straight into a bucket, for a profile scoped to one); a bucket
 * lists its objects and folders; and a folder is just a key prefix, listed the same way. Every row
 * is a {@link S3Resource} with no local path, which keeps the local filesystem plugin from claiming
 * it and routes navigation back here.
 *
 * <p>One instance backs one pane, so each side keeps its own location, but the clients, credentials
 * and discovered bucket regions live in the process-wide {@link S3Clients}. Opening the same profile
 * on both sides therefore authenticates once, and a copy between the two panes can be a server-side
 * copy rather than a round trip through this machine.
 *
 * <p>Listings are paged rather than loaded whole: a prefix holding more keys than one request
 * returns ends with a "Load more" row that fetches the next page. A bucket with a million objects
 * opens as quickly as one with ten.
 */
@Slf4j
public class S3FilePanelPlugin implements FilePanelNuclrPlugin {

	/** The plugin id, matching the manifest. */
	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.s3";

	// The cross-plugin action protocol, mirroring filepanel-fs, filepanel-zip and filepanel-net.
	// The commander does not put these constants on the SDK classpath, so they are spelled out here.
	private static final String ACTION_PATH_OPENED = "filepanel.path.opened";
	private static final String ACTION_COPY = "filepanel.copy";
	private static final String ACTION_MOVE = "filepanel.move";
	private static final String ACTION_ACCEPT_COPY = "accept.copy";
	private static final String ACTION_ACCEPT_MOVE = "accept.move";
	private static final String ACTION_DELETE = "filepanel.delete";
	private static final String ACTION_DELETE_PERMANENT = "filepanel.deletePermanent";
	private static final String ACTION_MAKE_FOLDER = "filepanel.makeFolder";
	private static final String ACTION_VIEW = "filepanel.view";
	private static final String ACTION_FIND = "find";
	private static final String ACTION_REFRESH_PANEL = "refresh.panel";

	// Actions this plugin contributes itself.
	private static final String ACTION_PROFILE_NEW = "s3.profile.new";
	private static final String ACTION_PROFILE_EDIT = "s3.profile.edit";
	private static final String ACTION_PROFILE_REMOVE = "s3.profile.remove";
	private static final String ACTION_SSO_LOGIN = "s3.sso.login";
	private static final String ACTION_COPY_URL = "s3.copy.url";

	private final String uuid = UUID.randomUUID().toString();

	private final AwsConfigFiles awsFiles = AwsConfigFiles.user();

	private NuclrPluginContext context;

	private boolean focused;

	/** Where this pane currently is: the root, a profile, a bucket, a folder, or search results. */
	private NuclrResource currentResource;

	/**
	 * The rows of the object listing shown right now, excluding {@code ..}. Kept so a make-folder can
	 * check for duplicates and an incoming copy can spot clashes without listing the prefix again.
	 */
	private final List<NuclrResource> currentRows = new ArrayList<>();

	// =========================================================================
	// Lifecycle
	// =========================================================================

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.currentResource = S3Resource.root();
		log.info("S3 file panel plugin loaded");
	}

	@Override
	public void init() {
		// Profiles and credentials are resolved lazily, on the first navigation into one.
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void unload() {
		context = null;
		currentRows.clear();
		S3Clients.clear();
		S3TempFiles.cleanup();
		log.info("S3 file panel plugin unloaded");
	}

	@Override
	public void closeResource() {
		currentRows.clear();
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	// =========================================================================
	// Drive selector and routing
	// =========================================================================

	@Override
	public MenuItemsHolder getPluginMenuItems() {

		var item = new MenuItem();
		item.setText("S3");
		item.setUuid(S3Resource.ROOT_UUID);
		item.setPath(S3Resource.root());

		var holder = new MenuItemsHolder();
		holder.setTitle("Amazon S3");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return S3Resource.isS3Resource(resource);
	}

	// =========================================================================
	// Listing
	// =========================================================================

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {

		if (resourceToOpen == null || !supports(resourceToOpen) || isCancelled(cancelled)) {
			return null;
		}

		// Anything but "load more" abandons the listing being paged through.
		if (!S3Resource.isLoadMore(resourceToOpen)) {
			currentRows.clear();
		}

		if (S3Resource.isSearchResults(resourceToOpen)) {
			this.currentResource = resourceToOpen;
			return listSearchResults(resourceToOpen, cancelled, sink);
		}

		if (S3Resource.isRoot(resourceToOpen)) {
			// Adopt a clean root: the incoming resource may be the ".." row, and the location bar
			// should not read "..".
			this.currentResource = S3Resource.root();
			return listProfiles(cancelled, sink);
		}

		if (S3Resource.isProfile(resourceToOpen)) {
			return openProfile(S3Resource.profileId(resourceToOpen), cancelled, sink);
		}

		if (S3Resource.isInsideBucket(resourceToOpen)) {
			String profileId = S3Resource.profileId(resourceToOpen);
			String bucket = S3Resource.bucketName(resourceToOpen);
			String prefix = S3Resource.objectPrefix(resourceToOpen);
			this.currentResource = S3Resource.objectDir(profileId, bucket, prefix,
					S3Resource.folderLabel(bucket, prefix));
			return listObjects(profileId, bucket, prefix, null, cancelled, sink);
		}

		if (S3Resource.isLoadMore(resourceToOpen)) {
			String profileId = S3Resource.profileId(resourceToOpen);
			String bucket = S3Resource.bucketName(resourceToOpen);
			String prefix = S3Resource.objectPrefix(resourceToOpen);
			this.currentResource = S3Resource.objectDir(profileId, bucket, prefix,
					S3Resource.folderLabel(bucket, prefix));
			return listObjects(profileId, bucket, prefix, S3Resource.continuation(resourceToOpen), cancelled, sink);
		}

		return null;
	}

	/** The root: every saved connection profile. */
	private NuclrResourceData listProfiles(AtomicBoolean cancelled, EntrySink sink) {

		var data = newData(S3Resource.PROFILE_COLUMNS, sink);

		List<S3Profile> profiles = S3Clients.store().load();
		for (S3Profile profile : profiles) {
			if (isCancelled(cancelled)) {
				break;
			}
			add(data, sink, S3Resource.profile(profile));
		}

		log.info("S3 profile listing: {} profile(s)", profiles.size());
		return data;
	}

	/**
	 * Open a profile: its buckets, or straight into the bucket it is scoped to.
	 *
	 * <p>The bucket-scoped case is not a shortcut but a necessity. Credentials are frequently granted
	 * read and write on one bucket and nothing else, and such credentials cannot list the account's
	 * buckets at all — so a profile that names its bucket skips the listing that would only fail.
	 */
	private NuclrResourceData openProfile(String profileId, AtomicBoolean cancelled, EntrySink sink) {

		S3Profile profile = S3Clients.store().byId(profileId);
		if (profile == null) {
			Dialogs.error("S3", "That connection profile is no longer saved. It may have been removed.");
			this.currentResource = S3Resource.root();
			return listProfiles(cancelled, sink);
		}

		if (profile.isBucketScoped()) {
			String prefix = profile.effectivePrefix();
			this.currentResource = S3Resource.objectDir(profileId, profile.getBucket(), prefix,
					S3Resource.folderLabel(profile.getBucket(), prefix));
			return listObjects(profileId, profile.getBucket(), prefix, null, cancelled, sink);
		}

		this.currentResource = S3Resource.profileRef(profileId);
		return listBuckets(profile, cancelled, sink);
	}

	/** A profile's buckets. */
	private NuclrResourceData listBuckets(S3Profile profile, AtomicBoolean cancelled, EntrySink sink) {

		var data = newData(S3Resource.BUCKET_COLUMNS, sink);
		add(data, sink, S3Resource.parentToRoot());

		S3Client client = S3Clients.get(profile);
		S3Result<List<S3BucketEntry>> buckets = client.listBuckets();

		if (!buckets.isOk()) {
			reportListingFailure(profile, buckets.errorOrNull());
			return data;
		}

		for (S3BucketEntry bucket : buckets.orNull()) {
			if (isCancelled(cancelled)) {
				break;
			}
			// Remember each bucket's region when the listing reports it, so the first request to
			// that bucket is signed correctly and skips the redirect.
			S3Endpoint.recordBucketRegion(bucket.name(), bucket.region());
			add(data, sink, S3Resource.bucket(profile.getId(), bucket));
		}

		log.info("S3 bucket listing for {}: {} bucket(s)", profile.displayName(), buckets.orNull().size());
		return data;
	}

	/**
	 * One page of a bucket prefix. A first page starts with {@code ..}; a later page re-emits the
	 * rows already shown and appends the new ones, because the commander re-renders the whole
	 * listing rather than appending to it.
	 */
	private NuclrResourceData listObjects(String profileId, String bucket, String prefix, String continuation,
			AtomicBoolean cancelled, EntrySink sink) {

		var data = newData(S3Resource.OBJECT_COLUMNS, sink);

		S3Client client = S3Clients.byProfileId(profileId);
		if (client == null) {
			Dialogs.error("S3", "That connection profile is no longer saved. It may have been removed.");
			return data;
		}

		if (continuation == null) {
			currentRows.clear();
			NuclrResource parent = S3Resource.objectParent(profileId, bucket, prefix);
			add(data, sink, parent);
		} else {
			// Re-emit what is already on screen, then append this page below it.
			NuclrResource parent = S3Resource.objectParent(profileId, bucket, prefix);
			add(data, sink, parent);
			for (NuclrResource row : currentRows) {
				add(data, sink, row);
			}
		}

		S3Result<S3Xml.ListPage> page = client.listObjects(bucket, prefix, continuation);
		if (!page.isOk()) {
			reportListingFailure(S3Clients.store().byId(profileId), page.errorOrNull());
			return data;
		}

		for (S3ObjectEntry entry : page.orNull().entries()) {
			if (isCancelled(cancelled)) {
				break;
			}
			NuclrResource row = entry.folder()
					? S3Resource.objectDir(profileId, bucket, entry.key(), entry.name())
					: S3Resource.object(profileId, bucket, entry);
			currentRows.add(row);
			add(data, sink, row);
		}

		if (page.orNull().hasMore()) {
			// A transient row, recomputed on each render rather than accumulated into currentRows.
			add(data, sink, S3Resource.loadMore(profileId, bucket, prefix,
					page.orNull().nextContinuationToken()));
		}

		log.info("S3 listing s3://{}/{}: +{} row(s), more={}",
				bucket, prefix, page.orNull().entries().size(), page.orNull().hasMore());
		return data;
	}

	/** A temporary panel of search hits: a synthetic {@code ..} back to the origin, then the hits. */
	private NuclrResourceData listSearchResults(NuclrResource root, AtomicBoolean cancelled, EntrySink sink) {

		var data = newData(S3Resource.OBJECT_COLUMNS, sink);

		NuclrResource origin = S3Resource.searchOrigin(root);
		if (S3Resource.isInsideBucket(origin)) {
			add(data, sink, S3Resource.objectDir(S3Resource.profileId(origin), S3Resource.bucketName(origin),
					S3Resource.objectPrefix(origin), ".."));
		}

		for (NuclrResource hit : S3Resource.searchHits(root)) {
			if (isCancelled(cancelled)) {
				break;
			}
			// Show the full s3:// path in the Name column: hits come from all over the bucket, and
			// a bare file name would not say which one is which.
			hit.getMetadata().put("Name", hit.getFullPath() != null ? hit.getFullPath() : hit.getName());
			add(data, sink, hit);
			currentRows.add(hit);
		}
		return data;
	}

	/**
	 * Explain a listing that failed, and offer the one-click fix where there is one.
	 *
	 * <p>An expired SSO session is the case worth special handling: it is both common and entirely
	 * recoverable, and telling the user to go and run a command in a terminal when the panel could
	 * run it for them would be a poor trade.
	 */
	private void reportListingFailure(S3Profile profile, S3Error error) {

		if (error instanceof S3Error.Cancelled) {
			return;
		}

		if (profile != null && profile.getAuthMode() == S3Profile.AuthMode.SSO
				&& error instanceof S3Error.CredentialsUnavailable) {
			boolean signIn = Dialogs.confirm("S3",
					error.describe() + "\n\nSign in to " + profile.displayName() + " now?");
			if (signIn) {
				ssoLogin(profile);
			}
			return;
		}

		if (error instanceof S3Error.AccessDenied && profile != null && !profile.isBucketScoped()) {
			Dialogs.error("S3", "These credentials cannot list the account's buckets.\n\n"
					+ error.describe()
					+ "\n\nIf they are scoped to one bucket, edit the profile and name that bucket "
					+ "so the panel opens it directly.");
			return;
		}

		Dialogs.error("S3", error);
	}

	// =========================================================================
	// Function keys and context menu
	// =========================================================================

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {

		var items = new ArrayList<NuclrMenuResource>();

		if (S3Resource.isRoot(currentResource)) {
			items.add(menu("Edit profile", "F4", ACTION_PROFILE_EDIT));
			items.add(menu("New profile", "F7", ACTION_PROFILE_NEW));
			items.add(menu("Remove profile", "F8", ACTION_PROFILE_REMOVE));
			items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
			return items;
		}

		if (S3Resource.isProfile(currentResource)) {
			items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
			items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
			return items;
		}

		if (S3Resource.isSearchResults(currentResource)) {
			items.add(menu("View", "F3", ACTION_VIEW));
			items.add(menu("Copy", "F5", ACTION_COPY));
			items.add(menu("Name", "Ctrl+F3", "filepanel.sort:name:Name"));
			return items;
		}

		if (S3Resource.isInsideBucket(currentResource)) {
			items.add(menu("View", "F3", ACTION_VIEW));
			items.add(menu("Copy", "F5", ACTION_COPY));
			items.add(menu(resource != null && resource.isFolder() ? "Move" : "Rename/Move", "F6", ACTION_MOVE));
			items.add(menu("Make Folder", "F7", ACTION_MAKE_FOLDER));
			items.add(menu("Delete", "F8", ACTION_DELETE));
			items.add(menu("Find", "Alt+F7", ACTION_FIND));
			addSortMenuItems(items);
			return items;
		}

		return items;
	}

	/**
	 * The Ctrl+F3..F12 sort slots. Only criteria a listing genuinely reports are offered: an object
	 * has a size and a modification time, but S3 tells us nothing about when a key was created or
	 * last read, so those slots would sort by a stub.
	 */
	private static void addSortMenuItems(List<NuclrMenuResource> items) {
		items.add(sortByColumn("Name", "Ctrl+F3", "name"));
		items.add(sortByColumn("Extension", "Ctrl+F4", "ext"));
		items.add(sortByColumn("Modified", "Ctrl+F5", "modified"));
		items.add(sortByColumn("Size", "Ctrl+F6", "size"));
		items.add(menu("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource,
			List<NuclrResource> selectedResources) {

		if (S3Resource.isRoot(currentResource)) {
			var items = new ArrayList<NuclrContextMenuItem>();
			items.add(NuclrContextMenuItem.builder().label("New profile")
					.actionType(ACTION_PROFILE_NEW).iconKey("server-add").build());
			items.add(NuclrContextMenuItem.builder().label("Edit profile")
					.actionType(ACTION_PROFILE_EDIT).iconKey("edit").build());
			S3Profile profile = profileOf(focusedResource);
			if (profile != null && profile.getAuthMode() == S3Profile.AuthMode.SSO) {
				items.add(NuclrContextMenuItem.builder().label("Sign in (SSO)")
						.actionType(ACTION_SSO_LOGIN).iconKey("key").build());
			}
			items.add(NuclrContextMenuItem.separator());
			items.add(NuclrContextMenuItem.builder().label("Remove profile")
					.actionType(ACTION_PROFILE_REMOVE).iconKey("delete").destructive(true).build());
			return items;
		}

		if (!S3Resource.isInsideBucket(currentResource) && !S3Resource.isSearchResults(currentResource)) {
			return List.of();
		}

		var items = new ArrayList<NuclrContextMenuItem>();
		if (focusedResource != null && !"..".equals(focusedResource.getName())) {
			items.add(NuclrContextMenuItem.builder().label("Copy S3 URL")
					.actionType(ACTION_COPY_URL).iconKey("copy").build());
			items.add(NuclrContextMenuItem.separator());
		}
		items.add(NuclrContextMenuItem.builder().label("Make Folder")
				.actionType(ACTION_MAKE_FOLDER).iconKey("folder-add").build());
		items.add(NuclrContextMenuItem.builder().label("Find")
				.actionType(ACTION_FIND).iconKey("search").build());
		items.add(NuclrContextMenuItem.separator());
		items.add(NuclrContextMenuItem.builder().label("Delete")
				.actionType(ACTION_DELETE).iconKey("delete").destructive(true).build());
		return items;
	}

	// =========================================================================
	// Actions
	// =========================================================================

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		switch (actionType) {
			case ACTION_PROFILE_NEW -> newProfile(data);
			case ACTION_PROFILE_EDIT -> editProfile(focusedResource, data);
			case ACTION_PROFILE_REMOVE -> removeProfile(focusedResource, data);
			case ACTION_SSO_LOGIN -> ssoLogin(profileOf(focusedResource));
			case ACTION_COPY_URL -> copyUrlToClipboard(focusedResource);
			case ACTION_PATH_OPENED -> { /* activation is handled by navigation itself */ }
			case ACTION_COPY -> bridge(other, ACTION_ACCEPT_COPY, selectedResources, focusedResource, data, callback);
			case ACTION_MOVE -> bridgeMove(other, selectedResources, focusedResource, data, callback);
			case ACTION_ACCEPT_COPY -> acceptTransfer(false, selectedResources, focusedResource);
			case ACTION_ACCEPT_MOVE -> acceptTransfer(true, selectedResources, focusedResource);
			case ACTION_DELETE, ACTION_DELETE_PERMANENT -> delete(selectedResources, focusedResource);
			case ACTION_MAKE_FOLDER -> makeFolder(data);
			case ACTION_FIND -> openFindDialog();
			case ACTION_REFRESH_PANEL -> refreshCurrentListing();
			default -> log.debug("S3 panel ignoring unhandled action [{}]", actionType);
		}
	}

	// -------------------------------------------------------------------------
	// Profile management
	// -------------------------------------------------------------------------

	private void newProfile(Map<String, Object> data) {

		ProfileDialog.Result result =
				ProfileDialog.show(Dialogs.activeWindow(), "New S3 profile", new S3Profile(), awsFiles);
		if (result == null) {
			return;
		}
		if (!saveProfile(result)) {
			return;
		}
		requestRefresh(data, S3Resource.profile(result.profile()));
	}

	private void editProfile(NuclrResource focusedResource, Map<String, Object> data) {

		S3Profile existing = profileOf(focusedResource);
		if (existing == null) {
			Dialogs.error("Edit profile", "Select a profile to edit.");
			return;
		}

		ProfileDialog.Result result =
				ProfileDialog.show(Dialogs.activeWindow(), "Edit S3 profile", existing, awsFiles);
		if (result == null) {
			return;
		}

		// The endpoint, region or credentials may all have changed: drop everything cached about
		// this profile so the next open uses the new settings rather than the old session.
		S3Clients.forget(existing.getId());

		if (!saveProfile(result)) {
			return;
		}
		requestRefresh(data, S3Resource.profile(result.profile()));
	}

	private boolean saveProfile(ProfileDialog.Result result) {
		try {
			S3Clients.store().upsert(result.profile());
		} catch (IOException e) {
			log.warn("Could not save the S3 profile {}: {}", result.profile().displayName(), e.getMessage());
			Dialogs.error("S3 profile", "Could not save the profile:\n" + e.getMessage());
			return false;
		}
		// A secret typed in the dialog is cached for this session and never written to the file.
		if (result.secretAccessKey() != null) {
			dev.nuclr.plugin.core.panel.s3.auth.SecretCache.put(
					result.profile().getId(), result.secretAccessKey(), result.sessionToken());
		}
		return true;
	}

	private void removeProfile(NuclrResource focusedResource, Map<String, Object> data) {

		S3Profile profile = profileOf(focusedResource);
		if (profile == null) {
			Dialogs.error("Remove profile", "Select a profile to remove.");
			return;
		}
		if (!Dialogs.confirm("Remove profile",
				"Remove the connection profile " + profile.displayName() + "?\n\n"
						+ "This only removes the saved connection. Nothing in the bucket is touched.")) {
			return;
		}

		try {
			S3Clients.store().remove(profile.getId());
		} catch (IOException e) {
			Dialogs.error("Remove profile", "Could not remove the profile:\n" + e.getMessage());
			return;
		}
		S3Clients.forget(profile.getId());
		requestRefresh(data, null);
	}

	/**
	 * Run an interactive SSO sign-in for a profile, off the event dispatch thread since it waits on
	 * a browser round trip.
	 */
	private void ssoLogin(S3Profile profile) {

		if (profile == null) {
			Dialogs.error("Sign in", "Select an SSO profile to sign in.");
			return;
		}
		if (profile.getAuthMode() != S3Profile.AuthMode.SSO
				&& profile.getAuthMode() != S3Profile.AuthMode.AWS_PROFILE) {
			Dialogs.info("Sign in", profile.displayName() + " does not use SSO, so there is nothing to sign in to.");
			return;
		}

		Thread.ofVirtual().name("s3-sso-login").start(() -> {
			try {
				log.info("Starting an SSO sign-in for {}", profile.displayName());
				AwsCli.ssoLogin(profile.getAwsProfileName());
				S3Clients.resolver().invalidate(profile);
				Dialogs.info("Sign in", "Signed in to " + profile.displayName()
						+ ".\n\nOpen the profile again to browse it.");
			} catch (IOException e) {
				log.warn("SSO sign-in failed for {}: {}", profile.displayName(), e.getMessage());
				Dialogs.error("Sign in", "Could not sign in:\n" + e.getMessage());
			}
		});
	}

	/** Put a row's {@code s3://} URL on the clipboard — the form every AWS tool accepts. */
	private void copyUrlToClipboard(NuclrResource resource) {

		if (resource == null || resource.getFullPath() == null) {
			return;
		}
		try {
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
					new java.awt.datatransfer.StringSelection(resource.getFullPath()), null);
			log.info("Copied {} to the clipboard", resource.getFullPath());
		} catch (RuntimeException e) {
			log.warn("Could not copy to the clipboard: {}", e.getMessage());
			Dialogs.error("Copy S3 URL", "Could not copy to the clipboard: " + e.getMessage());
		}
	}

	// -------------------------------------------------------------------------
	// Transfers
	// -------------------------------------------------------------------------

	/**
	 * Hand a copy to the other panel, or take it ourselves when there is no other panel to hand it
	 * to — the same bridging protocol the local and archive panels use.
	 */
	private void bridge(BaseNuclrPlugin other, String acceptAction, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (!S3Resource.isInsideBucket(currentResource) && !S3Resource.isSearchResults(currentResource)) {
			return;
		}

		// Copying out of S3 to a local folder, or to another S3 panel, is ours to run: we are the
		// side that knows how to read the source.
		if (other != null && !other.uuid().equals(uuid) && !(other instanceof QuickViewNuclrPlugin)) {
			boolean moving = ACTION_ACCEPT_MOVE.equals(acceptAction);
			if (new S3CopyService(moving).copyOut(other, selectedResources, focusedResource, context, uuid)) {
				refreshCurrentListing();
				requestRefresh(data, null);
			}
			return;
		}

		this.act(null, acceptAction, selectedResources, focusedResource, data, callback);
	}

	/** F6 with both panels on the same folder means an in-place rename, as it does elsewhere. */
	private void bridgeMove(BaseNuclrPlugin other, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (!S3Resource.isInsideBucket(currentResource)) {
			return;
		}

		if (other == null || other.uuid().equals(uuid) || other instanceof QuickViewNuclrPlugin) {
			if (new S3RenameService().rename(focusedResource)) {
				refreshCurrentListing();
				requestRefresh(data, null);
			}
			return;
		}

		bridge(other, ACTION_ACCEPT_MOVE, selectedResources, focusedResource, data, callback);
	}

	/** Take an incoming transfer: upload the other panel's selection into the prefix open here. */
	private void acceptTransfer(boolean moving, List<NuclrResource> selectedResources,
			NuclrResource focusedResource) {

		boolean transferred = new S3CopyService(moving).acceptCopy(
				selectedResources, focusedResource, currentResource, context, uuid, currentRowsByName());
		if (transferred) {
			refreshCurrentListing();
		}
	}

	// -------------------------------------------------------------------------
	// Delete, make folder, find
	// -------------------------------------------------------------------------

	private void delete(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		if (!S3Resource.isInsideBucket(currentResource)) {
			return;
		}
		int deleted = new S3DeleteService().delete(selectedResources, focusedResource);
		if (deleted > 0) {
			refreshCurrentListing();
			Selections.refreshPanel(context, uuid);
		}
	}

	private void makeFolder(Map<String, Object> data) {

		if (!S3Resource.isInsideBucket(currentResource)) {
			Dialogs.info("Make Folder", "Open a bucket to create a folder in it.");
			return;
		}

		String profileId = S3Resource.profileId(currentResource);
		String bucket = S3Resource.bucketName(currentResource);
		String prefix = S3Resource.objectPrefix(currentResource);
		S3Client client = S3Clients.byProfileId(profileId);
		if (client == null) {
			Dialogs.error("Make Folder", "The connection profile for this panel is no longer available.");
			return;
		}

		String created = new S3MakeFolderService().makeFolder(client, bucket, prefix, currentRowNames());
		if (created == null) {
			return; // cancelled, rejected, or failed — already explained
		}

		refreshCurrentListing();
		requestRefresh(data, S3Resource.objectDir(profileId, bucket, prefix + created + '/', created + "/"));
	}

	private void openFindDialog() {

		if (!S3Resource.isInsideBucket(currentResource)) {
			Dialogs.info("Find files", "Open a bucket to search it.");
			return;
		}

		NuclrResource origin = currentResource;
		S3FindRequest request = S3FindDialog.show(S3Resource.profileId(origin),
				S3Resource.bucketName(origin), S3Resource.objectPrefix(origin));
		if (request == null) {
			return;
		}

		var results = new S3FindResultsWindow(Dialogs.mainWindow(), request,
				this::navigateToResult,
				hits -> openResultsInTempPanel(hits, request, origin));
		S3FindService.SearchHandle handle = new S3FindService().search(request, results);
		results.bind(handle);
		results.setVisible(true);
	}

	/** Take the panel to a search hit: open its folder, with the cursor on the hit. */
	private void navigateToResult(NuclrResource resource) {

		if (context == null || context.getEventBus() == null) {
			return;
		}

		var payload = new HashMap<String, Object>();
		if (S3Resource.isObject(resource)) {
			String key = S3Resource.objectKey(resource);
			payload.put("resource", S3Resource.objectDir(S3Resource.profileId(resource),
					S3Resource.bucketName(resource), S3Resource.keyPrefix(key), ""));
			payload.put("selectChild", resource);
		} else if (S3Resource.isObjectDir(resource)) {
			payload.put("resource", resource);
		} else {
			return;
		}
		context.getEventBus().emit(this, ACTION_PATH_OPENED, payload);
	}

	/** Send a whole result set to a temporary panel in this pane. */
	private void openResultsInTempPanel(List<NuclrResource> hits, S3FindRequest request, NuclrResource origin) {

		if (context == null || context.getEventBus() == null) {
			return;
		}
		var payload = new HashMap<String, Object>();
		payload.put("resource", S3Resource.searchResults(hits, request.title(), origin));
		context.getEventBus().emit(this, ACTION_PATH_OPENED, payload);
	}

	// =========================================================================
	// Recursive walk (folder sizes, and anything else that needs the subtree)
	// =========================================================================

	@Override
	public void walkDescendants(NuclrResource resource, Consumer<NuclrResource> visitor, AtomicBoolean cancelled,
			boolean recursive) throws IOException {

		if (!S3Resource.isInsideBucket(resource)) {
			throw new IOException("Only a bucket or folder can be walked.");
		}

		String profileId = S3Resource.profileId(resource);
		String bucket = S3Resource.bucketName(resource);
		String prefix = S3Resource.objectPrefix(resource);
		S3Client client = S3Clients.byProfileId(profileId);
		if (client == null) {
			throw new IOException("The connection profile for this panel is no longer available.");
		}

		if (!recursive) {
			S3Result<S3Xml.ListPage> page = client.listObjects(bucket, prefix, null);
			if (!page.isOk()) {
				throw new IOException(page.errorOrNull().describe());
			}
			for (S3ObjectEntry entry : page.orNull().entries()) {
				visitor.accept(entry.folder()
						? S3Resource.objectDir(profileId, bucket, entry.key(), entry.name())
						: S3Resource.object(profileId, bucket, entry));
			}
			return;
		}

		S3Result<Void> outcome = S3Walk.walk(client, bucket, prefix,
				entry -> visitor.accept(S3Resource.object(profileId, bucket, entry)),
				() -> cancelled != null && cancelled.get());
		if (!outcome.isOk() && !outcome.isCancelled()) {
			throw new IOException(outcome.errorOrNull().describe());
		}
	}

	// =========================================================================
	// Display text
	// =========================================================================

	@Override
	public String getCurrentLocationDisplayText() {

		if (S3Resource.isSearchResults(currentResource)) {
			return "S3: " + S3Resource.searchTitle(currentResource);
		}
		if (S3Resource.isInsideBucket(currentResource)) {
			return "S3: s3://" + S3Resource.bucketName(currentResource) + '/'
					+ S3Resource.objectPrefix(currentResource);
		}
		if (S3Resource.isProfile(currentResource)) {
			S3Profile profile = S3Clients.store().byId(S3Resource.profileId(currentResource));
			return "S3: " + (profile == null ? "Buckets" : profile.displayName());
		}
		return "S3: Connections";
	}

	@Override
	public String getWindowTitle() {
		return getCurrentLocationDisplayText();
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {

		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			NuclrResource only = selectedResources.get(0);
			return only.isFolder()
					? only.getName()
					: only.getName() + "   " + S3Resource.formatSize(only.getLength());
		}

		long bytes = 0;
		for (NuclrResource resource : selectedResources) {
			bytes += Math.max(resource.getLength(), 0);
		}
		return selectedResources.size() + " items selected, " + S3Resource.formatSize(bytes);
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private NuclrResourceData newData(List<String> columns, EntrySink sink) {
		var data = new NuclrResourceData();
		data.setColumnNames(columns);
		if (sink != null) {
			sink.columns(columns);
		}
		return data;
	}

	/** Append an entry to both the returned listing and the streaming sink, when there is one. */
	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource entry) {
		data.getEntries().add(entry);
		if (sink != null) {
			sink.add(entry);
		}
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	/** The profile a root row stands for, or {@code null} when the row is not a profile. */
	private S3Profile profileOf(NuclrResource resource) {
		String profileId = S3Resource.profileId(resource);
		return profileId == null ? null : S3Clients.store().byId(profileId);
	}

	/** The names in the current listing, for the make-folder duplicate check. */
	private Set<String> currentRowNames() {
		var names = new HashSet<String>();
		for (NuclrResource row : currentRows) {
			if (row != null && !"..".equals(row.getName())) {
				names.add(row.getName());
			}
		}
		return names;
	}

	/** Name to row for the current listing, for an incoming transfer's clash check. */
	private Map<String, NuclrResource> currentRowsByName() {
		var byName = new HashMap<String, NuclrResource>();
		for (NuclrResource row : currentRows) {
			if (row != null && !"..".equals(row.getName())) {
				byName.put(row.getName(), row);
			}
		}
		return byName;
	}

	/**
	 * Drop what this panel is showing so the commander's reload fetches it again. There is no
	 * listing cache to invalidate — S3 listings are always live — so this only resets the paging
	 * state, which is what a Ctrl+R after adding an object needs.
	 */
	private void refreshCurrentListing() {
		currentRows.clear();
	}

	/**
	 * Ask the commander to reload this panel after the action returns, optionally putting the cursor
	 * on a particular row.
	 */
	private void requestRefresh(Map<String, Object> data, NuclrResource selectChild) {
		if (data == null) {
			return;
		}
		try {
			data.put("result.refresh", true);
			if (selectChild != null) {
				data.put("result.refresh.selected.resource", selectChild);
			}
		} catch (UnsupportedOperationException e) {
			// Some callers pass an immutable payload; the panel still reloads on its own.
			log.debug("The action payload is immutable, so no row will be pre-selected.");
		}
	}

	private static NuclrMenuResource menu(String name, String functionKey, String eventType) {
		return new NuclrMenuResource(name, functionKey, eventType);
	}

	private static NuclrMenuResource sortByColumn(String columnName, String functionKey, String criterion) {
		return menu(columnName, functionKey, "filepanel.sort:" + criterion + ':' + columnName);
	}
}
