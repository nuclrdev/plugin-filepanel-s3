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
package dev.nuclr.plugin.core.panel.s3.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials;

/**
 * AWS Signature Version 4 request signing.
 *
 * <p>This is the whole authentication story for the S3 panel: every call the plugin makes carries
 * an {@code Authorization: AWS4-HMAC-SHA256} header produced here. Signing is implemented directly
 * rather than pulled in from the AWS SDK so the plugin bundle stays dependency-free (the SDK would
 * add tens of megabytes to a signed ZIP) and so any S3-compatible endpoint — MinIO, Cloudflare R2,
 * Wasabi, Backblaze B2, DigitalOcean Spaces — works through the same code path.
 *
 * <p>The signature covers the method, the canonical path and query, a chosen set of headers, and a
 * hash of the payload. For streamed bodies (uploads and downloads of arbitrary size) the payload
 * hash is {@link #UNSIGNED_PAYLOAD}, which S3 accepts over HTTPS and which spares us buffering a
 * multi-gigabyte object just to hash it. Requests with a small, known body (the multi-object delete
 * XML, for instance) hash their bytes properly.
 *
 * <p>Instances are immutable and stateless; {@link #sign} may be called concurrently.
 */
public final class SigV4 {

	/** Payload hash used for streamed bodies, which S3 accepts in place of a real digest over TLS. */
	public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";

	/** SHA-256 of the empty byte array — the payload hash for bodyless requests (GET, HEAD, DELETE). */
	public static final String EMPTY_BODY_SHA256 =
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

	private static final String ALGORITHM = "AWS4-HMAC-SHA256";

	private static final DateTimeFormatter AMZ_DATE_TIME =
			DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

	private static final DateTimeFormatter AMZ_DATE =
			DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

	private final String region;

	private final String service;

	/**
	 * Create a signer for one region and service.
	 *
	 * @param region  the AWS region the request is addressed to (e.g. {@code eu-west-1})
	 * @param service the service name in the credential scope; {@code s3} for object requests
	 */
	public SigV4(String region, String service) {
		this.region = region;
		this.service = service;
	}

	/**
	 * Create a signer for S3 in the given region.
	 *
	 * @param region the AWS region
	 * @return a signer scoped to {@code s3} in that region
	 */
	public static SigV4 forS3(String region) {
		return new SigV4(region, "s3");
	}

	/**
	 * The material a signed request needs beyond its URI: the headers to send, already including
	 * {@code Authorization}, {@code x-amz-date}, {@code x-amz-content-sha256} and (when the
	 * credentials are temporary) {@code x-amz-security-token}.
	 *
	 * @param headers          header name to value, ready to be copied onto the outgoing request
	 * @param canonicalRequest the canonical request that was signed, kept for diagnostics and tests
	 * @param stringToSign     the string-to-sign that was hashed, kept for diagnostics and tests
	 */
	public record Signed(Map<String, String> headers, String canonicalRequest, String stringToSign) {}

	/**
	 * Sign one request.
	 *
	 * <p>{@code headers} must contain at least {@code Host}; anything else passed in is signed too,
	 * so a caller that sets {@code Content-Type} or {@code x-amz-copy-source} gets it covered by the
	 * signature. Header names are matched case-insensitively, as the specification requires.
	 *
	 * @param method        HTTP method, upper case
	 * @param canonicalPath the URI path, already percent-encoded by {@link #encodePath}
	 * @param query         query parameters (unencoded names and values); may be empty
	 * @param headers       headers to sign; must include {@code Host}
	 * @param payloadSha256 hex SHA-256 of the body, {@link #EMPTY_BODY_SHA256}, or {@link #UNSIGNED_PAYLOAD}
	 * @param credentials   the access key, secret and optional session token to sign with
	 * @param when          the request timestamp; drives both the {@code x-amz-date} header and the scope
	 * @return the headers to send, plus the intermediate strings for diagnostics
	 */
	public Signed sign(String method, String canonicalPath, Map<String, String> query,
			Map<String, String> headers, String payloadSha256, AwsCredentials credentials, Instant when) {

		String amzDateTime = AMZ_DATE_TIME.format(when);
		String amzDate = AMZ_DATE.format(when);

		// Case-insensitive ordering, because the canonical form is built from lower-cased names.
		var signedHeaders = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		signedHeaders.putAll(headers);
		signedHeaders.put("x-amz-date", amzDateTime);
		signedHeaders.put("x-amz-content-sha256", payloadSha256);
		if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
			signedHeaders.put("x-amz-security-token", credentials.sessionToken());
		}

		String canonicalHeaders = canonicalHeaders(signedHeaders);
		String signedHeaderNames = signedHeaderNames(signedHeaders);

		String canonicalRequest = method + '\n'
				+ canonicalPath + '\n'
				+ canonicalQuery(query) + '\n'
				+ canonicalHeaders + '\n'
				+ signedHeaderNames + '\n'
				+ payloadSha256;

		String scope = amzDate + '/' + region + '/' + service + "/aws4_request";
		String stringToSign = ALGORITHM + '\n'
				+ amzDateTime + '\n'
				+ scope + '\n'
				+ hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

		byte[] signingKey = signingKey(credentials.secretAccessKey(), amzDate);
		String signature = hex(hmac(signingKey, stringToSign));

		String authorization = ALGORITHM
				+ " Credential=" + credentials.accessKeyId() + '/' + scope
				+ ", SignedHeaders=" + signedHeaderNames
				+ ", Signature=" + signature;

		var out = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
		out.putAll(signedHeaders);
		out.put("Authorization", authorization);
		// Host is set by the HTTP client itself and rejected as a caller-supplied header, so it is
		// signed but never handed back for sending.
		out.remove("Host");
		return new Signed(out, canonicalRequest, stringToSign);
	}

	/**
	 * Derive the date/region/service signing key. Exposed so tests can check it against the
	 * key-derivation vector published with the signature specification.
	 *
	 * @param secretAccessKey the raw secret access key
	 * @param amzDate         the {@code yyyyMMdd} scope date
	 * @return the 32-byte signing key
	 */
	public byte[] signingKey(String secretAccessKey, String amzDate) {
		byte[] kDate = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), amzDate);
		byte[] kRegion = hmac(kDate, region);
		byte[] kService = hmac(kRegion, service);
		return hmac(kService, "aws4_request");
	}

	// -------------------------------------------------------------------------
	// Canonicalisation
	// -------------------------------------------------------------------------

	/** {@code name:trimmed-value} lines, lower-cased names, sorted, one per line. */
	private static String canonicalHeaders(Map<String, String> headers) {
		var sorted = new TreeMap<String, String>();
		for (Map.Entry<String, String> entry : headers.entrySet()) {
			sorted.put(entry.getKey().toLowerCase(Locale.ROOT), collapse(entry.getValue()));
		}
		var text = new StringBuilder();
		for (Map.Entry<String, String> entry : sorted.entrySet()) {
			text.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
		}
		return text.toString();
	}

	/** Lower-cased header names, sorted, joined with semicolons. */
	private static String signedHeaderNames(Map<String, String> headers) {
		var names = new ArrayList<String>(headers.size());
		for (String name : headers.keySet()) {
			names.add(name.toLowerCase(Locale.ROOT));
		}
		names.sort(null);
		return String.join(";", names);
	}

	/** Trim, and collapse runs of internal whitespace to a single space, as the specification requires. */
	private static String collapse(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ");
	}

	/**
	 * Build the canonical query string: {@code name=value} pairs, both encoded, sorted, joined
	 * with {@code &}.
	 *
	 * @param query unencoded query parameters
	 * @return the canonical query string, empty when there are no parameters
	 */
	static String canonicalQuery(Map<String, String> query) {
		if (query == null || query.isEmpty()) {
			return "";
		}
		var pairs = new ArrayList<String>(query.size());
		for (Map.Entry<String, String> entry : query.entrySet()) {
			pairs.add(encode(entry.getKey()) + '=' + encode(entry.getValue() == null ? "" : entry.getValue()));
		}
		pairs.sort(null);
		return String.join("&", pairs);
	}

	/**
	 * Percent-encode a URI path for signing: every segment encoded, the separators left intact.
	 *
	 * <p>S3 is signed with a <em>single</em> round of encoding (unlike most AWS services, which
	 * normalise and encode twice), so an object key containing {@code %} or {@code +} survives the
	 * round trip only if the same encoding is used for both the canonical request and the URI
	 * actually sent. Callers therefore build the request URI from this method's output too.
	 *
	 * @param path an unencoded path beginning with {@code /}
	 * @return the encoded path, safe to place in both the canonical request and the URI
	 */
	public static String encodePath(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		String[] segments = path.split("/", -1);
		var out = new StringBuilder();
		for (int i = 0; i < segments.length; i++) {
			if (i > 0) {
				out.append('/');
			}
			out.append(encode(segments[i]));
		}
		return out.toString();
	}

	/**
	 * Percent-encode one value per RFC 3986, leaving only the unreserved set alone.
	 *
	 * <p>Deliberately not {@code URLEncoder}: that produces {@code +} for a space and leaves
	 * {@code *} unescaped, both of which break the signature.
	 *
	 * @param value the raw value
	 * @return the encoded value
	 */
	public static String encode(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		var out = new StringBuilder(bytes.length + 16);
		for (byte b : bytes) {
			int c = b & 0xFF;
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '_' || c == '.' || c == '~') {
				out.append((char) c);
			} else {
				out.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
			}
		}
		return out.toString();
	}

	// -------------------------------------------------------------------------
	// Primitives
	// -------------------------------------------------------------------------

	/**
	 * Hex-encoded SHA-256 of the given bytes, for hashing a request body small enough to hold in
	 * memory.
	 *
	 * @param body the payload bytes
	 * @return the lower-case hex digest
	 */
	public static String sha256Hex(byte[] body) {
		return hex(sha256(body));
	}

	private static byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}
	}

	private static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 is required by the Java platform", e);
		}
	}

	private static String hex(byte[] value) {
		return HexFormat.of().formatHex(value);
	}
}
