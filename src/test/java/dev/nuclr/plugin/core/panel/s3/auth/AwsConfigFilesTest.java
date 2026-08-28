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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers reading the user's real AWS configuration, which is where most developers' S3 credentials
 * already live.
 *
 * <p>The format has more corners than it looks: {@code config} prefixes profile sections while
 * {@code credentials} does not, the two files have to merge into one view of a profile, nested
 * settings are written as indented blocks, and comments can trail any line. Reading it wrong shows
 * the user the wrong profiles — or none — so each of those corners is pinned here.
 */
class AwsConfigFilesTest {

	private static Path write(Path directory, String name, String content) throws IOException {
		Path file = directory.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	@Test
	@DisplayName("Credentials and config merge into one view of each profile")
	void mergesBothFiles(@TempDir Path directory) throws IOException {

		Path credentials = write(directory, "credentials", """
				[default]
				aws_access_key_id = AKIAEXAMPLE
				aws_secret_access_key = secret-value

				[work]
				aws_access_key_id = AKIAWORK
				aws_secret_access_key = work-secret
				aws_session_token = temporary-token
				""");

		Path config = write(directory, "config", """
				[default]
				region = eu-west-1
				output = json

				[profile work]
				region = us-east-2
				""");

		var files = new AwsConfigFiles(credentials, config);
		List<AwsConfigFiles.Profile> profiles = files.profiles();
		assertEquals(2, profiles.size());

		AwsConfigFiles.Profile def = files.profile("default");
		assertNotNull(def);
		assertEquals("eu-west-1", def.region(), "region comes from config");
		assertTrue(def.hasStaticKeys(), "keys come from credentials");
		assertEquals("AKIAEXAMPLE", def.staticCredentials().accessKeyId());
		assertNull(def.staticCredentials().sessionToken());

		AwsConfigFiles.Profile work = files.profile("work");
		assertNotNull(work);
		assertEquals("us-east-2", work.region());
		assertEquals("temporary-token", work.staticCredentials().sessionToken());
	}

	@Test
	@DisplayName("The 'profile ' prefix used only in config is stripped")
	void configProfilePrefixIsStripped(@TempDir Path directory) throws IOException {

		Path config = write(directory, "config", """
				[profile staging]
				region = ap-south-1
				""");

		var files = new AwsConfigFiles(directory.resolve("credentials"), config);

		assertNotNull(files.profile("staging"), "the profile is named 'staging', not 'profile staging'");
		assertNull(files.profile("profile staging"));
	}

	@Test
	@DisplayName("An SSO profile is recognised as needing the AWS CLI")
	void ssoProfileNeedsTheCli(@TempDir Path directory) throws IOException {

		Path config = write(directory, "config", """
				[profile sso-dev]
				sso_start_url = https://example.awsapps.com/start
				sso_region = us-east-1
				sso_account_id = 123456789012
				sso_role_name = Developer
				region = eu-west-2
				""");

		AwsConfigFiles.Profile profile =
				new AwsConfigFiles(directory.resolve("credentials"), config).profile("sso-dev");

		assertNotNull(profile);
		assertTrue(profile.isSso());
		assertTrue(profile.needsCli(), "SSO credentials cannot be read from the file alone");
		assertFalse(profile.hasStaticKeys());
		assertEquals("SSO", profile.describeAuth());
		assertEquals("eu-west-2", profile.region());
	}

	@Test
	@DisplayName("A role-assuming profile and a credential_process both need the CLI")
	void delegatingProfilesNeedTheCli(@TempDir Path directory) throws IOException {

		Path config = write(directory, "config", """
				[profile assumed]
				role_arn = arn:aws:iam::123456789012:role/ReadOnly
				source_profile = default

				[profile external]
				credential_process = /usr/local/bin/get-creds --json
				""");

		var files = new AwsConfigFiles(directory.resolve("credentials"), config);

		assertTrue(files.profile("assumed").needsCli());
		assertEquals("Assumed role", files.profile("assumed").describeAuth());
		assertTrue(files.profile("external").needsCli());
		assertEquals("Credential process", files.profile("external").describeAuth());
	}

	@Test
	@DisplayName("Comments and blank lines are ignored")
	void commentsAreIgnored(@TempDir Path directory) throws IOException {

		Path credentials = write(directory, "credentials", """
				# The keys for day-to-day work
				[default]
				aws_access_key_id = AKIAEXAMPLE   ; trailing comment
				aws_secret_access_key = secret-value

				# [disabled]
				# aws_access_key_id = AKIAOLD
				""");

		var files = new AwsConfigFiles(credentials, directory.resolve("config"));

		assertEquals(1, files.profiles().size(), "a commented-out section is not a profile");
		assertEquals("AKIAEXAMPLE", files.profile("default").get("aws_access_key_id"));
	}

	@Test
	@DisplayName("Nested setting blocks flatten to parent.child keys")
	void nestedBlocksAreFlattened(@TempDir Path directory) throws IOException {

		Path config = write(directory, "config", """
				[profile tuned]
				region = us-east-1
				s3 =
				  max_concurrent_requests = 20
				  addressing_style = path
				""");

		AwsConfigFiles.Profile profile =
				new AwsConfigFiles(directory.resolve("credentials"), config).profile("tuned");

		assertEquals("path", profile.get("s3.addressing_style"));
		assertEquals("20", profile.get("s3.max_concurrent_requests"));
		assertEquals("us-east-1", profile.region(), "the nested block does not swallow the next setting");
	}

	@Test
	@DisplayName("Setting names are matched case-insensitively")
	void keysAreCaseInsensitive(@TempDir Path directory) throws IOException {

		Path credentials = write(directory, "credentials", """
				[default]
				AWS_ACCESS_KEY_ID = AKIAEXAMPLE
				aws_secret_access_key = secret
				""");

		AwsConfigFiles.Profile profile =
				new AwsConfigFiles(credentials, directory.resolve("config")).profile("default");

		assertTrue(profile.hasStaticKeys());
		assertEquals("AKIAEXAMPLE", profile.get("aws_access_key_id"));
	}

	@Test
	@DisplayName("Missing files read as empty rather than failing")
	void missingFilesAreEmpty(@TempDir Path directory) {

		var files = new AwsConfigFiles(directory.resolve("nope"), directory.resolve("also-nope"));

		assertTrue(files.profiles().isEmpty());
		assertFalse(files.anyFileExists());
		assertNull(files.profile("default"));
	}

	@Test
	@DisplayName("A file with no section header contributes nothing but does not fail")
	void headerlessFileIsIgnored(@TempDir Path directory) throws IOException {

		Path credentials = write(directory, "credentials", "aws_access_key_id = AKIALOOSE\n");

		var files = new AwsConfigFiles(credentials, directory.resolve("config"));

		assertTrue(files.profiles().isEmpty());
		assertTrue(files.anyFileExists(), "the file is there, it just has nothing usable in it");
	}

	@Test
	@DisplayName("Credentials win over config where both set the same key")
	void credentialsTakePrecedence(@TempDir Path directory) throws IOException {

		Path credentials = write(directory, "credentials", """
				[default]
				aws_access_key_id = FROM-CREDENTIALS
				aws_secret_access_key = secret
				""");
		Path config = write(directory, "config", """
				[default]
				aws_access_key_id = FROM-CONFIG
				""");

		AwsConfigFiles.Profile profile = new AwsConfigFiles(credentials, config).profile("default");

		assertEquals("FROM-CREDENTIALS", profile.get("aws_access_key_id"));
	}
}
