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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * The ambient credential sources: environment variables, and the role attached to the machine the
 * commander is running on.
 *
 * <p>This is what makes the "Environment / instance role" profile mode work — a user on an EC2
 * instance or in a container with a task role can open the panel and browse straight away, with
 * nothing configured and nothing typed. Environment variables are checked first (they are what a
 * shell {@code export} or a CI runner sets), then the container credential endpoint, then the
 * instance metadata service.
 *
 * <p>Instance metadata is fetched with IMDSv2 — a token request followed by the credential request
 * — because IMDSv1 is switched off on hardened instances. Every lookup here has a short timeout:
 * off an instance the metadata address simply does not answer, and the panel must not hang waiting
 * to discover that.
 */
@Slf4j
public final class EnvironmentCredentials {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The link-local metadata address; unreachable anywhere but an EC2 instance. */
	private static final String IMDS_BASE = "http://169.254.169.254";

	/** Short, because the common case off-instance is no answer at all. */
	private static final Duration IMDS_TIMEOUT = Duration.ofSeconds(2);

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(IMDS_TIMEOUT)
			.build();

	private EnvironmentCredentials() {}

	/**
	 * Resolve credentials from the ambient environment.
	 *
	 * @return the credentials found, or {@code null} when the environment provides none
	 */
	public static AwsCredentials resolve() {

		AwsCredentials fromEnvironment = fromEnvironment();
		if (fromEnvironment != null) {
			return fromEnvironment;
		}

		AwsCredentials fromContainer = fromContainer();
		if (fromContainer != null) {
			return fromContainer;
		}

		return fromInstanceMetadata();
	}

	/**
	 * The region the environment declares, so a profile in environment mode need not repeat it.
	 *
	 * @return the region from {@code AWS_REGION} or {@code AWS_DEFAULT_REGION}, or {@code null}
	 */
	public static String region() {
		String region = System.getenv("AWS_REGION");
		if (region == null || region.isBlank()) {
			region = System.getenv("AWS_DEFAULT_REGION");
		}
		return region == null || region.isBlank() ? null : region.trim();
	}

	/** {@code AWS_ACCESS_KEY_ID} and friends, the way every AWS tool reads them. */
	private static AwsCredentials fromEnvironment() {
		String accessKeyId = System.getenv("AWS_ACCESS_KEY_ID");
		String secretAccessKey = System.getenv("AWS_SECRET_ACCESS_KEY");
		if (accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank()) {
			return null;
		}
		String sessionToken = System.getenv("AWS_SESSION_TOKEN");
		log.info("Using AWS credentials from the environment for key {}", accessKeyId);
		return new AwsCredentials(accessKeyId.trim(), secretAccessKey.trim(),
				sessionToken == null || sessionToken.isBlank() ? null : sessionToken.trim(), null);
	}

	/** The ECS / EKS task-role endpoint, addressed by a relative or full URI in the environment. */
	private static AwsCredentials fromContainer() {

		String relative = System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
		String full = System.getenv("AWS_CONTAINER_CREDENTIALS_FULL_URI");
		if ((relative == null || relative.isBlank()) && (full == null || full.isBlank())) {
			return null;
		}

		String uri = relative != null && !relative.isBlank() ? "http://169.254.170.2" + relative : full;
		var request = HttpRequest.newBuilder(URI.create(uri)).timeout(IMDS_TIMEOUT).GET();
		String authorization = System.getenv("AWS_CONTAINER_AUTHORIZATION_TOKEN");
		if (authorization != null && !authorization.isBlank()) {
			request.header("Authorization", authorization);
		}

		try {
			HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				log.debug("Container credential endpoint returned HTTP {}", response.statusCode());
				return null;
			}
			return parse(response.body());
		} catch (IOException | RuntimeException e) {
			log.debug("Container credential endpoint unreachable: {}", e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** IMDSv2: fetch a session token, then the first role's credentials. */
	private static AwsCredentials fromInstanceMetadata() {

		String token = imdsToken();
		if (token == null) {
			return null;
		}

		String role = imdsGet("/latest/meta-data/iam/security-credentials/", token);
		if (role == null || role.isBlank()) {
			return null;
		}
		// The listing is one role per line; an instance profile carries exactly one.
		String firstRole = role.strip().lines().findFirst().orElse("").trim();
		if (firstRole.isEmpty()) {
			return null;
		}

		String body = imdsGet("/latest/meta-data/iam/security-credentials/" + firstRole, token);
		if (body == null) {
			return null;
		}
		AwsCredentials credentials = parse(body);
		if (credentials != null) {
			log.info("Using the EC2 instance role {} for AWS credentials", firstRole);
		}
		return credentials;
	}

	private static String imdsToken() {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(IMDS_BASE + "/latest/api/token"))
					.timeout(IMDS_TIMEOUT)
					.header("X-aws-ec2-metadata-token-ttl-seconds", "300")
					.PUT(HttpRequest.BodyPublishers.noBody())
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 ? response.body().strip() : null;
		} catch (IOException | RuntimeException e) {
			log.debug("Instance metadata service unreachable: {}", e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static String imdsGet(String path, String token) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(IMDS_BASE + path))
					.timeout(IMDS_TIMEOUT)
					.header("X-aws-ec2-metadata-token", token)
					.GET()
					.build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			return response.statusCode() == 200 ? response.body() : null;
		} catch (IOException | RuntimeException e) {
			log.debug("Instance metadata read of {} failed: {}", path, e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** Both the container and instance endpoints answer with the same JSON shape. */
	private static AwsCredentials parse(String body) {
		try {
			JsonNode json = MAPPER.readTree(body);
			String accessKeyId = text(json, "AccessKeyId");
			String secretAccessKey = text(json, "SecretAccessKey");
			if (accessKeyId == null || secretAccessKey == null) {
				return null;
			}
			return new AwsCredentials(accessKeyId, secretAccessKey, text(json, "Token"),
					parseExpiry(text(json, "Expiration")));
		} catch (RuntimeException e) {
			log.debug("Could not parse a credential document: {}", e.getMessage());
			return null;
		}
	}

	private static Instant parseExpiry(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (RuntimeException e) {
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
}
