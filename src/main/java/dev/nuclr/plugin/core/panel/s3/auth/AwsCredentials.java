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

import java.time.Instant;

/**
 * One set of AWS credentials ready to sign a request with.
 *
 * <p>Covers both shapes S3 accepts: long-lived keys (an access key id and secret, no session
 * token, no expiry) and temporary credentials from STS, SSO or an instance role, which carry a
 * session token and stop working at {@link #expiresAt}. The resolver treats an expiring set as
 * stale a little before that instant so a request never goes out with credentials that die
 * mid-flight.
 *
 * <p>This record is deliberately never persisted: it exists in memory for the life of the session
 * and nothing writes it to disk.
 *
 * @param accessKeyId     the access key id
 * @param secretAccessKey the secret access key
 * @param sessionToken    the session token for temporary credentials, or {@code null} for long-lived keys
 * @param expiresAt       when temporary credentials stop working, or {@code null} if they do not expire
 */
public record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken, Instant expiresAt) {

	/** Refresh this far ahead of the stated expiry, so an in-flight request cannot outlive its credentials. */
	private static final long EXPIRY_MARGIN_SECONDS = 120;

	/**
	 * Long-lived credentials with no session token and no expiry.
	 *
	 * @param accessKeyId     the access key id
	 * @param secretAccessKey the secret access key
	 * @return the credentials
	 */
	public static AwsCredentials of(String accessKeyId, String secretAccessKey) {
		return new AwsCredentials(accessKeyId, secretAccessKey, null, null);
	}

	/**
	 * Whether these credentials are usable: both key parts present and, if temporary, not yet
	 * inside the refresh margin ahead of their expiry.
	 *
	 * @return {@code true} when the credentials can still sign a request
	 */
	public boolean isUsable() {
		if (accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank()) {
			return false;
		}
		return expiresAt == null || Instant.now().plusSeconds(EXPIRY_MARGIN_SECONDS).isBefore(expiresAt);
	}

	/**
	 * Whether these credentials came from a source that hands out temporary, refreshable material.
	 *
	 * @return {@code true} when a session token is present
	 */
	public boolean isTemporary() {
		return sessionToken != null && !sessionToken.isBlank();
	}

	/** Never render the secret, whatever logs this. */
	@Override
	public String toString() {
		return "AwsCredentials[accessKeyId=" + accessKeyId
				+ ", temporary=" + isTemporary()
				+ ", expiresAt=" + expiresAt + "]";
	}
}
