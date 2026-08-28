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

import java.io.Serializable;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * A saved S3 connection profile: one bucket namespace the panel knows how to reach, and how to
 * authenticate against it.
 *
 * <p>Persisted to {@code ~/.nuclr/s3/profiles.json} by {@link S3ProfileStore}. The
 * <b>secret access key is deliberately not a field of this class</b> — it is prompted for and held
 * in memory for the session (see {@link SecretCache}), the same rule the Net panel applies to SSH
 * passwords. Everything here is either public (an access key id, a region, an endpoint) or a
 * pointer to credentials that live somewhere else entirely (an AWS profile name), which is what
 * keeps the profile file safe to sync.
 *
 * <p>A profile may opt into {@link #isRememberSecret() remembering} its secret so it is asked for
 * once rather than once per session. Even then the secret is not written here: it goes to
 * {@link SecretStore}, a separate, encrypted, owner-only file. This class carries the preference,
 * never the key.
 *
 * <p>The four authentication modes cover how people actually reach S3: keys typed in, the AWS
 * files they already have, an SSO sign-in through their identity provider, and the ambient
 * environment on a machine that already has a role.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class S3Profile implements Serializable {

	private static final long serialVersionUID = 1L;

	/** The region assumed when a profile names none; also where bucket listing starts. */
	public static final String DEFAULT_REGION = "us-east-1";

	/** How the panel obtains credentials for this profile. */
	public enum AuthMode {

		/**
		 * An access key id stored in the profile plus a secret access key entered at connect time
		 * and cached in memory. Optionally a session token, for temporary STS credentials.
		 */
		ACCESS_KEY,

		/**
		 * A named profile from {@code ~/.aws/credentials} and {@code ~/.aws/config}. Static keys are
		 * read straight from the file; profiles that assume a role or run a credential process are
		 * resolved through the AWS CLI.
		 */
		AWS_PROFILE,

		/**
		 * IAM Identity Center: a browser sign-in against a named AWS profile, resolved (and
		 * refreshed) through the AWS CLI. The only mode with an interactive login.
		 */
		SSO,

		/**
		 * Whatever the machine already provides: {@code AWS_ACCESS_KEY_ID} and friends, a container
		 * task role, or the EC2 instance role. Nothing to configure and nothing to type.
		 */
		ENVIRONMENT
	}

	/** Stable unique profile identifier. */
	private String id = UUID.randomUUID().toString();

	/** Optional display label; falls back to a description of the endpoint when blank. */
	private String name = "";

	/** How to authenticate. */
	private AuthMode authMode = AuthMode.ACCESS_KEY;

	/** Access key id for {@link AuthMode#ACCESS_KEY}; the secret is never stored here. */
	private String accessKeyId = "";

	/**
	 * Whether the secret access key may be saved on this machine, so it is asked for once rather
	 * than once per session.
	 *
	 * <p>Only the flag lives in this file — it is a preference, not a secret. The key itself goes
	 * to {@link SecretStore}, which is deliberately a different file with different handling.
	 */
	private boolean rememberSecret;

	/** The {@code ~/.aws} profile name for {@link AuthMode#AWS_PROFILE} and {@link AuthMode#SSO}. */
	private String awsProfileName = "";

	/** Region used for signing and for the initial bucket listing. */
	private String region = DEFAULT_REGION;

	/**
	 * Custom endpoint for an S3-compatible service ({@code https://minio.example.com:9000},
	 * {@code https://<account>.r2.cloudflarestorage.com}). Blank means real AWS S3.
	 */
	private String endpoint = "";

	/**
	 * Address buckets as a path segment ({@code endpoint/bucket/key}) rather than a host prefix
	 * ({@code bucket.endpoint/key}). Required by most S3-compatible servers; AWS itself prefers the
	 * host form, which this defaults to.
	 */
	private boolean pathStyleAccess;

	/**
	 * Bucket to open directly instead of listing buckets. Useful when the credentials may read a
	 * bucket but are not allowed {@code s3:ListAllMyBuckets}, which is a common least-privilege setup.
	 */
	private String bucket = "";

	/** Key prefix to open inside {@link #bucket}; blank means the bucket root. */
	private String prefix = "";

	/** Creates a profile with a fresh id and defaults. */
	public S3Profile() {
	}

	/**
	 * The label shown for this profile in the panel and menus.
	 *
	 * @return the profile name, or a description of what it points at when unnamed
	 */
	public String displayName() {
		if (name != null && !name.isBlank()) {
			return name;
		}
		return address();
	}

	/**
	 * A short description of where this profile points, used when it has no name of its own.
	 *
	 * @return {@code s3://bucket} for a bucket-scoped profile, the endpoint host for a custom
	 *         endpoint, otherwise {@code S3 (region)}
	 */
	public String address() {
		if (bucket != null && !bucket.isBlank()) {
			return "s3://" + bucket + (prefix == null || prefix.isBlank() ? "" : "/" + prefix);
		}
		if (endpoint != null && !endpoint.isBlank()) {
			return endpoint;
		}
		return "S3 (" + effectiveRegion() + ")";
	}

	/**
	 * The region to sign with, never blank.
	 *
	 * @return the configured region, or {@value #DEFAULT_REGION}
	 */
	public String effectiveRegion() {
		return region == null || region.isBlank() ? DEFAULT_REGION : region.trim();
	}

	/**
	 * The key prefix to open, normalised to end with {@code /} (or be empty).
	 *
	 * @return the initial prefix
	 */
	public String effectivePrefix() {
		if (prefix == null || prefix.isBlank()) {
			return "";
		}
		String trimmed = prefix.trim();
		while (trimmed.startsWith("/")) {
			trimmed = trimmed.substring(1);
		}
		return trimmed.isEmpty() || trimmed.endsWith("/") ? trimmed : trimmed + "/";
	}

	/**
	 * Whether this profile talks to an S3-compatible service rather than AWS itself.
	 *
	 * @return {@code true} when a custom endpoint is set
	 */
	public boolean hasCustomEndpoint() {
		return endpoint != null && !endpoint.isBlank();
	}

	/**
	 * Whether this profile opens one bucket directly instead of listing the account's buckets.
	 *
	 * @return {@code true} when a bucket is pinned
	 */
	public boolean isBucketScoped() {
		return bucket != null && !bucket.isBlank();
	}

	/**
	 * A short description of how this profile authenticates, shown as a column in the profile list.
	 *
	 * @return the human-readable authentication description
	 */
	public String describeAuth() {
		return switch (authMode) {
			case ACCESS_KEY -> accessKeyId == null || accessKeyId.isBlank() ? "Access key" : accessKeyId;
			case AWS_PROFILE -> "AWS profile: " + blankToDash(awsProfileName);
			case SSO -> "SSO: " + blankToDash(awsProfileName);
			case ENVIRONMENT -> "Environment / instance role";
		};
	}

	private static String blankToDash(String value) {
		return value == null || value.isBlank() ? "default" : value;
	}
}
