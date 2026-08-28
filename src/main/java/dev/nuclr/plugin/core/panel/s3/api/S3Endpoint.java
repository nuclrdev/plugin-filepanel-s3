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

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;

/**
 * Works out the URL and signing region for a request: which host to talk to, whether the bucket
 * belongs in the host name or the path, and which region the signature must be scoped to.
 *
 * <p>Three things make this less trivial than it sounds. Buckets live in regions, and a request
 * signed for the wrong one is refused — but the panel does not know a bucket's region until it has
 * asked, so regions discovered from a redirect are remembered here and reused. AWS prefers the
 * bucket in the host name ({@code bucket.s3.eu-west-1.amazonaws.com}), while most S3-compatible
 * servers only accept it in the path. And a bucket whose name is not DNS-safe (dots, upper case)
 * cannot go in a host name at all, whatever the preference says.
 *
 * <p>Discovered regions are cached process-wide: both panel sides browsing the same bucket share
 * one lookup, and the redirect is paid once per bucket per session.
 */
public final class S3Endpoint {

	/** Bucket names that may appear in a host name: DNS-safe, no dots, no upper case. */
	private static final Pattern HOST_SAFE_BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");

	/** Bucket name to the region S3 told us it lives in. */
	private static final Map<String, String> BUCKET_REGIONS = new ConcurrentHashMap<>();

	private final S3Profile profile;

	private final String defaultRegion;

	/**
	 * Create endpoint resolution for one profile.
	 *
	 * @param profile       the connection profile, which supplies any custom endpoint and addressing style
	 * @param defaultRegion the region to sign with when a bucket's own region is not yet known
	 */
	public S3Endpoint(S3Profile profile, String defaultRegion) {
		this.profile = profile;
		this.defaultRegion = defaultRegion == null || defaultRegion.isBlank()
				? S3Profile.DEFAULT_REGION
				: defaultRegion.trim();
	}

	/**
	 * Remember the region a bucket lives in, learned from a redirect or a location query.
	 *
	 * @param bucket the bucket name
	 * @param region the region it lives in
	 */
	public static void recordBucketRegion(String bucket, String region) {
		if (bucket == null || bucket.isBlank() || region == null || region.isBlank()) {
			return;
		}
		BUCKET_REGIONS.put(bucket, region.trim());
	}

	/** Forget every learned bucket region; called when the plugin unloads. */
	public static void clearBucketRegions() {
		BUCKET_REGIONS.clear();
	}

	/**
	 * The region to sign a request for this bucket with: the one S3 told us, else the profile's.
	 *
	 * @param bucket the bucket name, or {@code null} for an account-level call such as listing buckets
	 * @return the signing region
	 */
	public String signingRegion(String bucket) {
		if (bucket == null || bucket.isBlank()) {
			return defaultRegion;
		}
		return BUCKET_REGIONS.getOrDefault(bucket, defaultRegion);
	}

	/**
	 * A fully-addressed request target.
	 *
	 * @param uri           the URL to send the request to
	 * @param host          the {@code Host} header value, which the signature covers
	 * @param canonicalPath the encoded path exactly as it appears in the URL, for the canonical request
	 * @param region        the region this request must be signed for
	 */
	public record Target(URI uri, String host, String canonicalPath, String region) {}

	/**
	 * Build the target for a request.
	 *
	 * @param bucket the bucket, or {@code null} for an account-level call
	 * @param key    the object key, or {@code null} when addressing the bucket itself
	 * @param query  query parameters, unencoded; may be empty
	 * @return where and how to send the request
	 */
	public Target target(String bucket, String key, Map<String, String> query) {

		String base = baseUrl(bucket);
		String path = pathFor(bucket, key);
		String canonicalPath = SigV4.encodePath(path);

		var url = new StringBuilder(base).append(canonicalPath);
		String canonicalQuery = SigV4.canonicalQuery(query);
		if (!canonicalQuery.isEmpty()) {
			url.append('?').append(canonicalQuery);
		}

		URI uri = URI.create(url.toString());
		String host = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ':' + uri.getPort();
		return new Target(uri, host, canonicalPath, signingRegion(bucket));
	}

	/**
	 * Whether the bucket goes in the path rather than the host for this profile and bucket.
	 *
	 * @param bucket the bucket name
	 * @return {@code true} when path-style addressing is used
	 */
	public boolean usesPathStyle(String bucket) {
		if (profile.isPathStyleAccess() || profile.hasCustomEndpoint()) {
			return true;
		}
		// A bucket with dots or upper case cannot be a host label, whatever the profile prefers:
		// the wildcard certificate would not match and the request would fail TLS verification.
		return bucket != null && !HOST_SAFE_BUCKET.matcher(bucket).matches();
	}

	/** The scheme and authority the request goes to, with the bucket folded in for host-style access. */
	private String baseUrl(String bucket) {

		if (profile.hasCustomEndpoint()) {
			String endpoint = normaliseEndpoint(profile.getEndpoint());
			if (bucket == null || bucket.isBlank() || profile.isPathStyleAccess()) {
				return endpoint;
			}
			if (!HOST_SAFE_BUCKET.matcher(bucket).matches()) {
				return endpoint;
			}
			int schemeEnd = endpoint.indexOf("://");
			return endpoint.substring(0, schemeEnd + 3) + bucket + '.' + endpoint.substring(schemeEnd + 3);
		}

		String region = signingRegion(bucket);
		String awsHost = "s3." + region + ".amazonaws.com";
		if (bucket == null || bucket.isBlank() || usesPathStyle(bucket)) {
			return "https://" + awsHost;
		}
		return "https://" + bucket + '.' + awsHost;
	}

	/** The unencoded path: {@code /bucket/key} for path style, {@code /key} when the bucket is the host. */
	private String pathFor(String bucket, String key) {
		boolean bucketInPath = bucket != null && !bucket.isBlank() && usesPathStyle(bucket);
		var path = new StringBuilder();
		if (bucketInPath) {
			path.append('/').append(bucket);
		}
		if (key != null && !key.isEmpty()) {
			path.append('/').append(key);
		} else {
			// Addressing a bucket or the account itself: the path is just a slash, or the bucket
			// segment followed by one.
			path.append('/');
		}
		return path.toString();
	}

	/** Accept an endpoint with or without a scheme or trailing slash, and normalise it. */
	static String normaliseEndpoint(String endpoint) {
		String trimmed = endpoint.trim();
		if (!trimmed.toLowerCase(Locale.ROOT).startsWith("http://")
				&& !trimmed.toLowerCase(Locale.ROOT).startsWith("https://")) {
			trimmed = "https://" + trimmed;
		}
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
