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
package dev.nuclr.plugin.core.panel.s3.auth;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for the secrets the S3 panel must never write to disk: the secret access key (and
 * any session token) typed into a profile's connect prompt.
 *
 * <p>Secrets live here for the lifetime of the process and no longer. That is the whole point — a
 * profile file that gets synced, backed up or shared carries an access key id and a region, which
 * are not secrets, and nothing that would let anyone read the bucket. The user re-enters the secret
 * once per session, exactly as the Net panel asks for an SSH password.
 *
 * <p>The store is process-wide and static so both panel sides share one entry: opening the same
 * profile left and right prompts once, and a credential dropped after an authentication failure is
 * dropped for both.
 */
public final class SecretCache {

	/** Profile id to the secret access key entered for it. */
	private static final Map<String, String> SECRET_KEYS = new ConcurrentHashMap<>();

	/** Profile id to the session token entered alongside the secret, for temporary credentials. */
	private static final Map<String, String> SESSION_TOKENS = new ConcurrentHashMap<>();

	private SecretCache() {}

	/**
	 * The cached secret access key for a profile.
	 *
	 * @param profileId the profile id
	 * @return the secret, or {@code null} when nothing has been entered this session
	 */
	public static String secretKey(String profileId) {
		return profileId == null ? null : SECRET_KEYS.get(profileId);
	}

	/**
	 * The cached session token for a profile.
	 *
	 * @param profileId the profile id
	 * @return the session token, or {@code null} when none was entered
	 */
	public static String sessionToken(String profileId) {
		return profileId == null ? null : SESSION_TOKENS.get(profileId);
	}

	/**
	 * Remember the secret entered for a profile, for the rest of this session.
	 *
	 * @param profileId    the profile id
	 * @param secretKey    the secret access key; a blank value clears the entry
	 * @param sessionToken the session token, or {@code null} for long-lived keys
	 */
	public static void put(String profileId, String secretKey, String sessionToken) {
		if (profileId == null) {
			return;
		}
		if (secretKey == null || secretKey.isBlank()) {
			SECRET_KEYS.remove(profileId);
		} else {
			SECRET_KEYS.put(profileId, secretKey);
		}
		if (sessionToken == null || sessionToken.isBlank()) {
			SESSION_TOKENS.remove(profileId);
		} else {
			SESSION_TOKENS.put(profileId, sessionToken);
		}
	}

	/**
	 * Forget one profile's secret, so the next connection prompts again. Called after the endpoint
	 * rejects the credentials, and whenever the profile is edited or removed.
	 *
	 * @param profileId the profile id
	 */
	public static void drop(String profileId) {
		if (profileId == null) {
			return;
		}
		SECRET_KEYS.remove(profileId);
		SESSION_TOKENS.remove(profileId);
	}

	/** Forget every cached secret; called when the plugin unloads. */
	public static void clear() {
		SECRET_KEYS.clear();
		SESSION_TOKENS.clear();
	}
}
