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

/**
 * Everything that can go wrong talking to S3, classified into the cases the panel reacts to
 * differently.
 *
 * <p>The distinction that earns its keep is {@link NotAuthorized} versus {@link AccessDenied}: the
 * first means the credentials themselves are wrong or expired, so the panel drops them and asks
 * again; the second means they are perfectly good but the policy attached to them does not allow
 * this call, so re-prompting would only annoy the user. S3 signals these with different error codes
 * and the client maps them here.
 */
public sealed interface S3Error
		permits S3Error.NotAuthorized, S3Error.AccessDenied, S3Error.NoSuchBucket, S3Error.NoSuchKey,
				S3Error.Network, S3Error.RequestFailed, S3Error.Cancelled, S3Error.CredentialsUnavailable {

	/**
	 * The credentials were rejected: a bad key, a bad signature, or an expired token. Worth dropping
	 * the cached credentials and asking again.
	 *
	 * @param detail what the endpoint said
	 */
	record NotAuthorized(String detail) implements S3Error {}

	/**
	 * The credentials are valid but not permitted to make this call. Re-prompting would not help;
	 * the user needs a different policy.
	 *
	 * @param detail what the endpoint said
	 */
	record AccessDenied(String detail) implements S3Error {}

	/**
	 * The bucket does not exist, or is not visible to these credentials.
	 *
	 * @param bucket the bucket that was addressed
	 */
	record NoSuchBucket(String bucket) implements S3Error {}

	/**
	 * The object key does not exist.
	 *
	 * @param key the key that was addressed
	 */
	record NoSuchKey(String key) implements S3Error {}

	/**
	 * The request never reached the endpoint, or the connection broke mid-way.
	 *
	 * @param detail the transport failure
	 */
	record Network(String detail) implements S3Error {}

	/**
	 * The endpoint answered, but with a failure this plugin does not treat specially.
	 *
	 * @param status  the HTTP status
	 * @param code    the S3 error code, when the body carried one
	 * @param message the S3 error message, when the body carried one
	 */
	record RequestFailed(int status, String code, String message) implements S3Error {}

	/** The user cancelled the operation. Nothing to report. */
	record Cancelled() implements S3Error {}

	/**
	 * No credentials could be obtained at all — no keys entered, no AWS profile, an expired SSO
	 * session, nothing in the environment.
	 *
	 * @param detail why the resolver gave up, phrased for the user
	 */
	record CredentialsUnavailable(String detail) implements S3Error {}

	/**
	 * A one-line description fit to show a user.
	 *
	 * @return the message
	 */
	default String describe() {
		return switch (this) {
			case NotAuthorized error -> "The credentials were rejected: " + blankToDefault(error.detail(), "check the access key and secret.");
			case AccessDenied error -> "Access denied: " + blankToDefault(error.detail(), "these credentials are not allowed to do that.");
			case NoSuchBucket error -> "No such bucket: " + error.bucket();
			case NoSuchKey error -> "No such object: " + error.key();
			case Network error -> "Could not reach the endpoint: " + error.detail();
			case RequestFailed error -> describeRequestFailure(error);
			case Cancelled ignored -> "Cancelled.";
			case CredentialsUnavailable error -> error.detail();
		};
	}

	private static String describeRequestFailure(RequestFailed error) {
		var text = new StringBuilder("The request failed (HTTP ").append(error.status()).append(')');
		if (error.code() != null && !error.code().isBlank()) {
			text.append(": ").append(error.code());
		}
		if (error.message() != null && !error.message().isBlank()) {
			text.append(" — ").append(error.message());
		}
		return text.toString();
	}

	private static String blankToDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}
}
