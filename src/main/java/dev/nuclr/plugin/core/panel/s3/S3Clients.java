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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.plugin.core.panel.s3.api.S3Client;
import dev.nuclr.plugin.core.panel.s3.api.S3Endpoint;
import dev.nuclr.plugin.core.panel.s3.auth.CredentialsResolver;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.S3ProfileStore;
import dev.nuclr.plugin.core.panel.s3.auth.SecretCache;

/**
 * The process-wide registry of live {@link S3Client}s, one per connection profile.
 *
 * <p>Both panel sides draw on this, which is the point: opening the same profile left and right
 * shares one client, so credentials are resolved once, the secret is prompted for once, and a
 * bucket's region — discovered the hard way, through a redirect — is learned once. It also lets a
 * resource downloaded for quick view find its way back to the client that can fetch it without the
 * panel having to thread one through every call.
 *
 * <p>Entries are dropped when a profile is edited or removed, so the next open picks up the new
 * settings rather than quietly using the old ones.
 */
public final class S3Clients {

	private static final Map<String, S3Client> CLIENTS = new ConcurrentHashMap<>();

	private static final CredentialsResolver RESOLVER = new CredentialsResolver();

	private static volatile S3ProfileStore store = S3ProfileStore.defaultStore();

	private S3Clients() {}

	/**
	 * The profile store the panel reads and writes.
	 *
	 * @return the shared store
	 */
	public static S3ProfileStore store() {
		return store;
	}

	/**
	 * Point the registry at a different profile store, as tests do.
	 *
	 * @param replacement the store to use
	 */
	public static void useStore(S3ProfileStore replacement) {
		store = replacement;
		CLIENTS.clear();
	}

	/**
	 * The credential resolver shared by every client.
	 *
	 * @return the resolver
	 */
	public static CredentialsResolver resolver() {
		return RESOLVER;
	}

	/**
	 * The client for a profile, created on first use.
	 *
	 * @param profile the connection profile
	 * @return the client, never {@code null}
	 */
	public static S3Client get(S3Profile profile) {
		return CLIENTS.computeIfAbsent(profile.getId(), id -> new S3Client(profile, RESOLVER));
	}

	/**
	 * The client for a saved profile id, looked up in the store.
	 *
	 * @param profileId the profile id
	 * @return the client, or {@code null} when no such profile is saved
	 */
	public static S3Client byProfileId(String profileId) {
		if (profileId == null) {
			return null;
		}
		S3Client existing = CLIENTS.get(profileId);
		if (existing != null) {
			return existing;
		}
		S3Profile profile = store.byId(profileId);
		return profile == null ? null : get(profile);
	}

	/**
	 * Drop the client, credentials and secret for one profile — everything cached about it. Called
	 * when the profile is edited or removed, so nothing stale survives the change.
	 *
	 * @param profileId the profile id
	 */
	public static void forget(String profileId) {
		if (profileId == null) {
			return;
		}
		CLIENTS.remove(profileId);
		SecretCache.drop(profileId);
		S3Profile profile = store.byId(profileId);
		if (profile != null) {
			RESOLVER.invalidate(profile);
		}
	}

	/** Drop every client, credential and learned region; called when the plugin unloads. */
	public static void clear() {
		CLIENTS.clear();
		CredentialsResolver.clear();
		SecretCache.clear();
		S3Endpoint.clearBucketRegions();
	}
}
