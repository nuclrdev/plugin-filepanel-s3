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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Thin bridge to the AWS CLI, used for the two credential sources that cannot be produced by
 * reading a file: IAM Identity Center (SSO) sign-in and anything a profile delegates elsewhere
 * (an assumed role, or an external {@code credential_process}).
 *
 * <p>{@code aws configure export-credentials} does all of that work — it resolves the profile
 * however the profile says to, refreshing an SSO token from the local cache when one is still
 * valid, and prints the resulting temporary keys as JSON. That is a far better deal than
 * reimplementing the SSO OIDC device flow and STS in the plugin, and it keeps a single source of
 * truth for a login the user has probably already done in their terminal.
 *
 * <p>The CLI is only ever consulted for profiles that need it: a profile with plain keys in
 * {@code ~/.aws/credentials} is read directly and never shells out, so users without the CLI
 * installed are unaffected. When the CLI is needed and missing, or the SSO session has expired,
 * the failure is classified so the panel can say something useful rather than "command failed".
 */
@Slf4j
public final class AwsCli {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Long enough for an SSO token refresh round trip, short enough not to hang the panel. */
	private static final long TIMEOUT_SECONDS = 60;

	/** How long the interactive browser sign-in may take before we stop waiting on it. */
	private static final long LOGIN_TIMEOUT_SECONDS = 300;

	/** Raised when the AWS CLI is not installed or not on the PATH. */
	public static final class NotFoundException extends IOException {
		private static final long serialVersionUID = 1L;

		NotFoundException(String message) {
			super(message);
		}
	}

	/** Raised when the profile authenticates through SSO and the cached session has expired. */
	public static final class SsoLoginRequiredException extends IOException {
		private static final long serialVersionUID = 1L;

		private final String profileName;

		SsoLoginRequiredException(String profileName, String message) {
			super(message);
			this.profileName = profileName;
		}

		/**
		 * The profile that needs signing in again.
		 *
		 * @return the AWS profile name
		 */
		public String profileName() {
			return profileName;
		}
	}

	private AwsCli() {}

	/**
	 * Whether the AWS CLI can be found, so callers can offer SSO only when it would actually work.
	 *
	 * @return {@code true} when an {@code aws} executable is on the PATH
	 */
	public static boolean isAvailable() {
		try {
			executable();
			return true;
		} catch (NotFoundException e) {
			return false;
		}
	}

	/**
	 * Resolve one profile's credentials by asking the AWS CLI to export them.
	 *
	 * @param profileName the profile to resolve; {@code null} or blank means the CLI's default
	 * @return the temporary (usually) credentials the CLI produced
	 * @throws NotFoundException          if the AWS CLI is not installed
	 * @throws SsoLoginRequiredException  if the profile uses SSO and the session has expired
	 * @throws IOException                if the CLI failed for any other reason
	 */
	public static AwsCredentials exportCredentials(String profileName) throws IOException {

		var command = new ArrayList<String>();
		command.add(executable());
		command.add("configure");
		command.add("export-credentials");
		if (profileName != null && !profileName.isBlank()) {
			command.add("--profile");
			command.add(profileName);
		}
		command.add("--format");
		command.add("process");

		Result result = run(command, TIMEOUT_SECONDS);
		if (result.exitCode() != 0) {
			throw classify(profileName, result);
		}

		try {
			JsonNode json = MAPPER.readTree(result.stdout());
			String accessKeyId = text(json, "AccessKeyId");
			String secretAccessKey = text(json, "SecretAccessKey");
			if (accessKeyId == null || secretAccessKey == null) {
				throw new IOException("The AWS CLI returned no credentials for profile " + describe(profileName));
			}
			return new AwsCredentials(accessKeyId, secretAccessKey, text(json, "SessionToken"),
					parseExpiry(text(json, "Expiration")));
		} catch (RuntimeException e) {
			throw new IOException("Could not read the AWS CLI credential output: " + e.getMessage(), e);
		}
	}

	/**
	 * Run an interactive {@code aws sso login}, which opens the user's browser to sign in.
	 *
	 * <p>Blocks until the sign-in completes or times out, so callers must be off the event dispatch
	 * thread. On success the credentials are then available through {@link #exportCredentials}.
	 *
	 * @param profileName the profile to sign in; {@code null} or blank means the CLI's default
	 * @throws NotFoundException if the AWS CLI is not installed
	 * @throws IOException       if the sign-in failed or timed out
	 */
	public static void ssoLogin(String profileName) throws IOException {

		var command = new ArrayList<String>();
		command.add(executable());
		command.add("sso");
		command.add("login");
		if (profileName != null && !profileName.isBlank()) {
			command.add("--profile");
			command.add(profileName);
		}

		Result result = run(command, LOGIN_TIMEOUT_SECONDS);
		if (result.exitCode() != 0) {
			throw new IOException("aws sso login failed for profile " + describe(profileName) + ": "
					+ firstLine(result.stderr()));
		}
		log.info("Completed aws sso login for profile {}", describe(profileName));
	}

	// -------------------------------------------------------------------------
	// Process plumbing
	// -------------------------------------------------------------------------

	private record Result(int exitCode, String stdout, String stderr) {}

	private static Result run(List<String> command, long timeoutSeconds) throws IOException {

		Process process;
		try {
			process = new ProcessBuilder(command).start();
		} catch (IOException e) {
			throw new NotFoundException("Could not start the AWS CLI: " + e.getMessage());
		}

		// Drain both pipes concurrently: a full stderr buffer would otherwise deadlock the process.
		var stdout = new AtomicReference<>("");
		var stderr = new AtomicReference<>("");
		Thread outDrain = Thread.ofVirtual().start(() -> stdout.set(read(process.getInputStream())));
		Thread errDrain = Thread.ofVirtual().start(() -> stderr.set(read(process.getErrorStream())));

		try {
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("The AWS CLI did not respond within " + timeoutSeconds + " seconds");
			}
			outDrain.join();
			errDrain.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while waiting for the AWS CLI", e);
		}

		return new Result(process.exitValue(), stdout.get(), stderr.get());
	}

	private static String read(java.io.InputStream stream) {
		try (stream) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Turn a non-zero CLI exit into the most specific failure we can. An expired SSO session is
	 * worth telling apart from everything else, because the fix is one command the panel can offer
	 * to run for the user.
	 */
	private static IOException classify(String profileName, Result result) {
		String detail = result.stderr() == null ? "" : result.stderr().toLowerCase(Locale.ROOT);
		boolean ssoExpired = detail.contains("sso session")
				|| detail.contains("sso-session")
				|| detail.contains("error loading sso token")
				|| detail.contains("token has expired")
				|| detail.contains("aws sso login");
		if (ssoExpired) {
			return new SsoLoginRequiredException(profileName,
					"The SSO session for profile " + describe(profileName) + " has expired.");
		}
		return new IOException("aws configure export-credentials failed for profile "
				+ describe(profileName) + ": " + firstLine(result.stderr()));
	}

	/**
	 * Locate the {@code aws} executable: the PATH first, then the usual install locations, since a
	 * desktop application does not always inherit the shell PATH a terminal would have.
	 */
	static String executable() throws NotFoundException {

		String override = System.getProperty("nuclr.s3.awsCli");
		if (override != null && !override.isBlank()) {
			return override;
		}

		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String name = windows ? "aws.exe" : "aws";

		String path = System.getenv("PATH");
		if (path != null) {
			for (String directory : path.split(java.io.File.pathSeparator)) {
				if (directory.isBlank()) {
					continue;
				}
				Path candidate = Path.of(directory.trim(), name);
				if (Files.isRegularFile(candidate)) {
					return candidate.toString();
				}
			}
		}

		List<String> fallbacks = windows
				? List.of("C:\\Program Files\\Amazon\\AWSCLIV2\\aws.exe")
				: List.of("/usr/local/bin/aws", "/usr/bin/aws", "/opt/homebrew/bin/aws");
		for (String fallback : fallbacks) {
			if (Files.isRegularFile(Path.of(fallback))) {
				return fallback;
			}
		}

		throw new NotFoundException("The AWS CLI was not found on the PATH.");
	}

	private static Instant parseExpiry(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (RuntimeException e) {
			log.debug("Unrecognised AWS CLI expiry timestamp [{}]", value);
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		String text = node.path(field).asText("");
		return text.isBlank() ? null : text;
	}

	private static String describe(String profileName) {
		return profileName == null || profileName.isBlank() ? "default" : profileName;
	}

	private static String firstLine(String text) {
		if (text == null || text.isBlank()) {
			return "no output";
		}
		String stripped = text.strip();
		int newline = stripped.indexOf('\n');
		String line = newline < 0 ? stripped : stripped.substring(0, newline);
		return line.length() <= 300 ? line : line.substring(0, 300) + "…";
	}
}
