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

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns a saved {@link S3Profile} into credentials a request can be signed with, and keeps the
 * result around until it goes stale.
 *
 * <p>This is the single place that knows what each authentication mode means:
 *
 * <ul>
 *   <li>{@link S3Profile.AuthMode#ACCESS_KEY} — the access key id from the profile, the secret from
 *       {@link SecretCache}, prompting once per session when it is not there yet.</li>
 *   <li>{@link S3Profile.AuthMode#AWS_PROFILE} — the named profile in {@code ~/.aws}. Plain keys are
 *       read from the file; a profile that assumes a role or runs a credential process is handed to
 *       the AWS CLI, which knows how to do that.</li>
 *   <li>{@link S3Profile.AuthMode#SSO} — always the AWS CLI, which refreshes the IAM Identity Center
 *       token from its own cache. An expired session surfaces as
 *       {@link AwsCli.SsoLoginRequiredException} so the caller can offer to sign in.</li>
 *   <li>{@link S3Profile.AuthMode#ENVIRONMENT} — environment variables, then a container task role,
 *       then the EC2 instance role.</li>
 * </ul>
 *
 * <p>Results are cached per profile id. Temporary credentials carry an expiry and are re-resolved
 * shortly before it, so a long browsing session across an SSO token boundary refreshes on its own
 * rather than failing. {@link #invalidate} drops an entry after the endpoint rejects it, which is
 * what turns a 403 into a re-prompt instead of a dead panel.
 */
@Slf4j
public final class CredentialsResolver {

	/** Profile id to the last resolved, still-usable credentials. */
	private static final Map<String, AwsCredentials> CACHE = new ConcurrentHashMap<>();

	private final AwsConfigFiles awsFiles;

	private final SecretStore secrets;

	/** Create a resolver reading the user's real AWS configuration files and saved secrets. */
	public CredentialsResolver() {
		this(AwsConfigFiles.user(), SecretStore.defaultStore());
	}

	/**
	 * Create a resolver over explicit AWS configuration files, as tests do.
	 *
	 * @param awsFiles the shared-configuration reader to consult
	 */
	public CredentialsResolver(AwsConfigFiles awsFiles) {
		this(awsFiles, SecretStore.defaultStore());
	}

	/**
	 * Create a resolver over explicit AWS configuration files and secret store, as tests do.
	 *
	 * @param awsFiles the shared-configuration reader to consult
	 * @param secrets  where remembered secrets are kept
	 */
	public CredentialsResolver(AwsConfigFiles awsFiles, SecretStore secrets) {
		this.awsFiles = awsFiles;
		this.secrets = secrets;
	}

	/**
	 * Resolve credentials for a profile, using the cached set when it is still good.
	 *
	 * <p>May block: it can shell out to the AWS CLI, reach the instance metadata service, or put a
	 * modal prompt on screen, so callers must be off the event dispatch thread.
	 *
	 * @param profile the profile to authenticate
	 * @return usable credentials, never {@code null}
	 * @throws IOException when no credentials could be obtained; the message is fit to show a user
	 */
	public AwsCredentials resolve(S3Profile profile) throws IOException {

		AwsCredentials cached = CACHE.get(profile.getId());
		if (cached != null && cached.isUsable()) {
			return cached;
		}
		if (cached != null) {
			log.info("Credentials for S3 profile {} expired; re-resolving", profile.displayName());
			CACHE.remove(profile.getId());
		}

		AwsCredentials resolved = switch (profile.getAuthMode()) {
			case ACCESS_KEY -> fromAccessKey(profile);
			case AWS_PROFILE -> fromAwsProfile(profile);
			case SSO -> fromSso(profile);
			case ENVIRONMENT -> fromEnvironment(profile);
		};

		CACHE.put(profile.getId(), resolved);
		return resolved;
	}

	/**
	 * Drop the cached credentials for a profile so the next request resolves them again — and, for
	 * a typed secret, asks for it again.
	 *
	 * @param profile the profile whose credentials were rejected
	 */
	public void invalidate(S3Profile profile) {
		if (profile == null) {
			return;
		}
		CACHE.remove(profile.getId());
		if (profile.getAuthMode() == S3Profile.AuthMode.ACCESS_KEY) {
			SecretCache.drop(profile.getId());
		}
	}

	/**
	 * Drop everything held for a profile whose credentials the endpoint has just <em>rejected</em>,
	 * including any secret saved on this machine.
	 *
	 * <p>Distinct from {@link #invalidate} on purpose. Invalidating is routine — a profile was
	 * edited, a session ended — and must leave a saved secret alone, or editing a profile's region
	 * would silently throw its key away. A rejection is different: a saved key that no longer works
	 * is worse than none, because it would be re-offered on every attempt instead of the user being
	 * asked for the new one.
	 *
	 * @param profile the profile whose credentials were rejected
	 */
	public void rejected(S3Profile profile) {

		if (profile == null) {
			return;
		}
		invalidate(profile);

		if (profile.getAuthMode() != S3Profile.AuthMode.ACCESS_KEY) {
			return;
		}
		try {
			if (secrets.has(profile.getId())) {
				log.info("Forgetting the saved secret for {}: the endpoint rejected it", profile.displayName());
				secrets.remove(profile.getId());
			}
		} catch (IOException e) {
			log.warn("Could not forget the saved secret for {}: {}", profile.displayName(), e.getMessage());
		}
	}

	/**
	 * Drop every cached credential; called when the plugin unloads.
	 */
	public static void clear() {
		CACHE.clear();
	}

	/**
	 * The region a profile should sign with, taking the profile's own setting first and falling back
	 * to whatever its credential source declares (the {@code ~/.aws} profile's region, or the
	 * environment's).
	 *
	 * @param profile the profile
	 * @return a non-blank region
	 */
	public String region(S3Profile profile) {

		if (profile.getRegion() != null && !profile.getRegion().isBlank()) {
			return profile.getRegion().trim();
		}

		if (profile.getAuthMode() == S3Profile.AuthMode.AWS_PROFILE
				|| profile.getAuthMode() == S3Profile.AuthMode.SSO) {
			AwsConfigFiles.Profile aws = awsFiles.profile(profile.getAwsProfileName());
			if (aws != null && aws.region() != null && !aws.region().isBlank()) {
				return aws.region().trim();
			}
		}

		if (profile.getAuthMode() == S3Profile.AuthMode.ENVIRONMENT) {
			String environmentRegion = EnvironmentCredentials.region();
			if (environmentRegion != null) {
				return environmentRegion;
			}
		}

		return S3Profile.DEFAULT_REGION;
	}

	// -------------------------------------------------------------------------
	// Per-mode resolution
	// -------------------------------------------------------------------------

	/** Access key id from the profile, secret from the session cache or a prompt. */
	private AwsCredentials fromAccessKey(S3Profile profile) throws IOException {

		String accessKeyId = profile.getAccessKeyId();
		if (accessKeyId == null || accessKeyId.isBlank()) {
			throw new IOException("Profile " + profile.displayName() + " has no access key id. Edit it to add one.");
		}

		String secret = SecretCache.secretKey(profile.getId());
		String sessionToken = SecretCache.sessionToken(profile.getId());

		// Anything remembered on this machine counts as already entered, which is the whole point
		// of remembering it: the prompt below is never reached on the second run. Deliberately not
		// gated on the profile's own flag — the store only ever holds what the user asked it to
		// hold, and one source of truth cannot disagree with itself.
		if (secret == null || secret.isBlank()) {
			SecretStore.Entry saved = secrets.get(profile.getId());
			if (saved != null && saved.secretAccessKey() != null && !saved.secretAccessKey().isBlank()) {
				secret = saved.secretAccessKey();
				sessionToken = saved.sessionToken();
				SecretCache.put(profile.getId(), secret, sessionToken);
			}
		}

		if (secret == null || secret.isBlank()) {
			SecretPrompt.Entry entered = SecretPrompt.ask(profile, false);
			if (entered == null) {
				throw new IOException("No secret access key was entered for " + profile.displayName() + ".");
			}
			secret = entered.secretAccessKey();
			sessionToken = entered.sessionToken();
			SecretCache.put(profile.getId(), secret, sessionToken);
			remember(profile, entered.remember(), secret, sessionToken);
		}

		return new AwsCredentials(accessKeyId.trim(), secret, sessionToken, null);
	}

	/**
	 * Act on the choice made in the prompt: save the secret for next time, or make sure nothing
	 * stale is left behind for a profile the user has decided not to remember.
	 *
	 * <p>A failure to write is reported to the log and no further: the credentials in hand are
	 * good, and the panel opening is worth more to the user than the convenience they asked for.
	 */
	private void remember(S3Profile profile, boolean remember, String secret, String sessionToken) {

		try {
			if (remember) {
				secrets.put(profile.getId(), secret, sessionToken);
				log.info("Saved the secret access key for {} on this machine", profile.displayName());
			} else {
				secrets.remove(profile.getId());
			}
		} catch (IOException e) {
			log.warn("Could not save the secret access key for {}: {}", profile.displayName(), e.getMessage());
		}
	}

	/** A named {@code ~/.aws} profile: keys straight from the file, anything else through the CLI. */
	private AwsCredentials fromAwsProfile(S3Profile profile) throws IOException {

		String name = profile.getAwsProfileName();
		AwsConfigFiles.Profile aws = awsFiles.profile(name);

		if (aws == null) {
			if (!awsFiles.anyFileExists()) {
				throw new IOException("No AWS configuration was found in ~/.aws. "
						+ "Run 'aws configure', or switch this profile to access-key authentication.");
			}
			throw new IOException("AWS profile '" + describe(name) + "' was not found in ~/.aws.");
		}

		if (aws.hasStaticKeys()) {
			log.info("Resolved S3 profile {} from AWS profile '{}' keys", profile.displayName(), describe(name));
			return aws.staticCredentials();
		}

		if (aws.needsCli()) {
			return viaCli(profile, name);
		}

		throw new IOException("AWS profile '" + describe(name) + "' has no credentials configured.");
	}

	/** SSO always goes through the CLI, which owns the token cache and its refresh. */
	private AwsCredentials fromSso(S3Profile profile) throws IOException {
		return viaCli(profile, profile.getAwsProfileName());
	}

	private AwsCredentials viaCli(S3Profile profile, String awsProfileName) throws IOException {
		AwsCredentials credentials = AwsCli.exportCredentials(awsProfileName);
		log.info("Resolved S3 profile {} through the AWS CLI (profile '{}', expires {})",
				profile.displayName(), describe(awsProfileName), credentials.expiresAt());
		return credentials;
	}

	/** Whatever the machine already has: environment, container role, instance role. */
	private AwsCredentials fromEnvironment(S3Profile profile) throws IOException {
		AwsCredentials credentials = EnvironmentCredentials.resolve();
		if (credentials == null) {
			throw new IOException("No AWS credentials were found in the environment for "
					+ profile.displayName() + ". Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY, "
					+ "or switch this profile to another authentication mode.");
		}
		return credentials;
	}

	private static String describe(String profileName) {
		return profileName == null || profileName.isBlank() ? "default" : profileName;
	}
}
