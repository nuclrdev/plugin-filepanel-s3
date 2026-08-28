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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Reader for the AWS shared configuration files, {@code ~/.aws/credentials} and {@code ~/.aws/config}.
 *
 * <p>Most developers are already logged in to AWS on their machine, and this is where that login
 * lives. Reading it directly means the panel can offer a list of the profiles the user already has
 * rather than asking them to retype keys that are sitting on disk a directory away.
 *
 * <p>The format is INI-like but not quite INI: {@code config} prefixes non-default sections with
 * {@code profile } while {@code credentials} does not, values may be indented continuation lines
 * (nested settings such as {@code s3 =} followed by indented keys), and comments start with
 * {@code #} or {@code ;}. Nested blocks are flattened to {@code parent.child} keys, which is enough
 * for everything the panel reads. A malformed file is treated as empty rather than fatal: a broken
 * config elsewhere in the user's tooling should not stop them typing keys in by hand.
 *
 * <p>The locations honour {@code AWS_SHARED_CREDENTIALS_FILE} and {@code AWS_CONFIG_FILE}, matching
 * the AWS CLI, so a user with a non-standard layout is not silently shown nothing.
 */
@Slf4j
public final class AwsConfigFiles {

	private final Path credentialsFile;

	private final Path configFile;

	/**
	 * Create a reader over explicit file locations, as tests do.
	 *
	 * @param credentialsFile the {@code credentials} file
	 * @param configFile      the {@code config} file
	 */
	public AwsConfigFiles(Path credentialsFile, Path configFile) {
		this.credentialsFile = credentialsFile;
		this.configFile = configFile;
	}

	/**
	 * Create a reader over the user's real AWS files, honouring the CLI's location overrides.
	 *
	 * @return a reader over {@code ~/.aws/credentials} and {@code ~/.aws/config}
	 */
	public static AwsConfigFiles user() {
		return new AwsConfigFiles(
				pathFromEnvOrDefault("AWS_SHARED_CREDENTIALS_FILE", "credentials"),
				pathFromEnvOrDefault("AWS_CONFIG_FILE", "config"));
	}

	private static Path pathFromEnvOrDefault(String environmentVariable, String defaultName) {
		String override = System.getenv(environmentVariable);
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return Path.of(System.getProperty("user.home"), ".aws", defaultName);
	}

	/**
	 * The settings of one named profile, merged across both files.
	 *
	 * @param name     the profile name as the user would pass to {@code --profile}
	 * @param settings the merged key/value settings, lower-cased keys
	 */
	public record Profile(String name, Map<String, String> settings) {

		/**
		 * Look up one setting.
		 *
		 * @param key the setting name
		 * @return the value, or {@code null} when the profile does not set it
		 */
		public String get(String key) {
			return settings.get(key.toLowerCase(Locale.ROOT));
		}

		/**
		 * Whether this profile carries a usable long-lived key pair written directly into the file.
		 *
		 * @return {@code true} when both {@code aws_access_key_id} and {@code aws_secret_access_key} are set
		 */
		public boolean hasStaticKeys() {
			return notBlank(get("aws_access_key_id")) && notBlank(get("aws_secret_access_key"));
		}

		/**
		 * Whether resolving this profile means going through the AWS CLI: it authenticates with SSO,
		 * assumes a role, or delegates to an external {@code credential_process}. None of those can be
		 * done by reading the file alone.
		 *
		 * @return {@code true} when the AWS CLI has to produce the credentials
		 */
		public boolean needsCli() {
			return notBlank(get("sso_start_url"))
					|| notBlank(get("sso_session"))
					|| notBlank(get("sso_account_id"))
					|| notBlank(get("role_arn"))
					|| notBlank(get("credential_process"));
		}

		/**
		 * Whether this profile signs in through IAM Identity Center (AWS SSO).
		 *
		 * @return {@code true} for an SSO profile
		 */
		public boolean isSso() {
			return notBlank(get("sso_start_url")) || notBlank(get("sso_session")) || notBlank(get("sso_account_id"));
		}

		/**
		 * The region this profile is configured for.
		 *
		 * @return the region, or {@code null} when the profile does not set one
		 */
		public String region() {
			return get("region");
		}

		/**
		 * Read the long-lived keys written into this profile.
		 *
		 * @return the credentials, or {@code null} when the profile has no static keys
		 */
		public AwsCredentials staticCredentials() {
			if (!hasStaticKeys()) {
				return null;
			}
			String token = get("aws_session_token");
			return new AwsCredentials(get("aws_access_key_id"), get("aws_secret_access_key"),
					notBlank(token) ? token : null, null);
		}

		/** A short human-readable note about how this profile authenticates, for the profile picker. */
		public String describeAuth() {
			if (isSso()) {
				return "SSO";
			}
			if (notBlank(get("role_arn"))) {
				return "Assumed role";
			}
			if (notBlank(get("credential_process"))) {
				return "Credential process";
			}
			if (hasStaticKeys()) {
				return "Access keys";
			}
			return "Unknown";
		}

		private static boolean notBlank(String value) {
			return value != null && !value.isBlank();
		}
	}

	/**
	 * Load every profile named in either file, merged so that a profile split across {@code config}
	 * (region, SSO settings) and {@code credentials} (keys) comes back whole. Values in
	 * {@code credentials} win, matching the CLI's own precedence.
	 *
	 * @return profiles in the order first encountered, never {@code null}
	 */
	public List<Profile> profiles() {

		var merged = new LinkedHashMap<String, Map<String, String>>();

		for (Map.Entry<String, Map<String, String>> entry : parse(configFile).entrySet()) {
			// "profile dev" in config is the profile named "dev"; "default" has no prefix.
			String name = entry.getKey().startsWith("profile ") ? entry.getKey().substring(8).trim() : entry.getKey();
			merged.computeIfAbsent(name, key -> new LinkedHashMap<>()).putAll(entry.getValue());
		}
		for (Map.Entry<String, Map<String, String>> entry : parse(credentialsFile).entrySet()) {
			merged.computeIfAbsent(entry.getKey(), key -> new LinkedHashMap<>()).putAll(entry.getValue());
		}

		var profiles = new ArrayList<Profile>(merged.size());
		for (Map.Entry<String, Map<String, String>> entry : merged.entrySet()) {
			profiles.add(new Profile(entry.getKey(), Map.copyOf(entry.getValue())));
		}
		return profiles;
	}

	/**
	 * Look up one profile by name.
	 *
	 * @param name the profile name
	 * @return the merged profile, or {@code null} when neither file mentions it
	 */
	public Profile profile(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		for (Profile profile : profiles()) {
			if (profile.name().equals(name)) {
				return profile;
			}
		}
		return null;
	}

	/**
	 * Whether either file exists at all, so callers can tell "no profiles configured" apart from
	 * "profiles configured but none usable".
	 *
	 * @return {@code true} when at least one of the two files is present
	 */
	public boolean anyFileExists() {
		return Files.isRegularFile(credentialsFile) || Files.isRegularFile(configFile);
	}

	/**
	 * Parse one INI-like file into section name to settings.
	 *
	 * <p>Nested blocks (a key whose value is empty followed by indented keys) are flattened to
	 * {@code parent.child}. Unreadable or malformed files yield an empty map.
	 */
	static Map<String, Map<String, String>> parse(Path file) {

		if (file == null || !Files.isRegularFile(file)) {
			return Map.of();
		}

		List<String> lines;
		try {
			lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			log.warn("Cannot read AWS config file [{}]: {}", file, e.getMessage());
			return Map.of();
		}

		var sections = new LinkedHashMap<String, Map<String, String>>();
		Map<String, String> current = null;
		String nestedParent = null;

		for (String rawLine : lines) {

			String line = stripComment(rawLine);
			if (line.isBlank()) {
				continue;
			}

			String trimmed = line.trim();
			if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
				String section = trimmed.substring(1, trimmed.length() - 1).trim();
				current = sections.computeIfAbsent(section, key -> new LinkedHashMap<>());
				nestedParent = null;
				continue;
			}

			if (current == null) {
				continue; // a setting before any section header; nothing sensible to attach it to
			}

			int equals = trimmed.indexOf('=');
			if (equals < 0) {
				continue;
			}
			String key = trimmed.substring(0, equals).trim().toLowerCase(Locale.ROOT);
			String value = trimmed.substring(equals + 1).trim();

			// An indented line under a key that had no value belongs to that key's nested block.
			boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
			if (indented && nestedParent != null) {
				current.put(nestedParent + '.' + key, value);
				continue;
			}

			if (value.isEmpty()) {
				nestedParent = key;
				continue;
			}
			nestedParent = null;
			current.put(key, value);
		}

		return sections;
	}

	/** Drop a trailing {@code #} or {@code ;} comment, keeping any leading indentation intact. */
	private static String stripComment(String line) {
		if (line == null) {
			return "";
		}
		int hash = line.indexOf('#');
		int semi = line.indexOf(';');
		int cut = hash < 0 ? semi : (semi < 0 ? hash : Math.min(hash, semi));
		return cut < 0 ? line : line.substring(0, cut);
	}
}
