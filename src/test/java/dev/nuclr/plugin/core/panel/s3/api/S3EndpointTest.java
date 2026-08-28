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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;

/**
 * Covers request addressing: which host a call goes to, where the bucket sits in the URL, and which
 * region the signature is scoped to.
 *
 * <p>Getting this wrong fails in ways that are hard to read from the outside — a bucket in the wrong
 * region is refused as a signature mismatch, and a dotted bucket name in a host label fails TLS
 * verification rather than saying anything about buckets — so the rules are pinned here.
 */
class S3EndpointTest {

	private S3Profile profile;

	@BeforeEach
	void reset() {
		S3Endpoint.clearBucketRegions();
		profile = new S3Profile();
	}

	@Test
	@DisplayName("AWS puts the bucket in the host and the key in the path")
	void awsUsesVirtualHostAddressing() {

		var endpoint = new S3Endpoint(profile, "eu-west-1");
		S3Endpoint.Target target = endpoint.target("my-bucket", "photos/cat.jpg", Map.of());

		assertEquals("https://my-bucket.s3.eu-west-1.amazonaws.com/photos/cat.jpg", target.uri().toString());
		assertEquals("my-bucket.s3.eu-west-1.amazonaws.com", target.host());
		assertEquals("/photos/cat.jpg", target.canonicalPath());
		assertFalse(endpoint.usesPathStyle("my-bucket"));
	}

	@Test
	@DisplayName("A bucket name that cannot be a host label falls back to the path")
	void dottedBucketFallsBackToPathStyle() {

		// A wildcard certificate covers one label, so "my.bucket.s3.amazonaws.com" would fail
		// verification. Such a bucket has to go in the path whatever the profile prefers.
		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.Target target = endpoint.target("my.bucket.example", "a.txt", Map.of());

		assertTrue(endpoint.usesPathStyle("my.bucket.example"));
		assertEquals("https://s3.us-east-1.amazonaws.com/my.bucket.example/a.txt", target.uri().toString());
	}

	@Test
	@DisplayName("An upper-case bucket name also falls back to the path")
	void upperCaseBucketFallsBackToPathStyle() {
		var endpoint = new S3Endpoint(profile, "us-east-1");
		assertTrue(endpoint.usesPathStyle("Legacy-Bucket"));
	}

	@Test
	@DisplayName("A profile that asks for path style gets it")
	void pathStyleIsHonoured() {

		profile.setPathStyleAccess(true);
		var endpoint = new S3Endpoint(profile, "us-west-2");
		S3Endpoint.Target target = endpoint.target("my-bucket", "a/b.txt", Map.of());

		assertEquals("https://s3.us-west-2.amazonaws.com/my-bucket/a/b.txt", target.uri().toString());
	}

	@Test
	@DisplayName("A custom endpoint is used as given, with the bucket in the path")
	void customEndpointUsesPathStyle() {

		profile.setEndpoint("https://minio.example.com:9000");
		profile.setPathStyleAccess(true);
		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.Target target = endpoint.target("data", "report.csv", Map.of());

		assertEquals("https://minio.example.com:9000/data/report.csv", target.uri().toString());
		assertEquals("minio.example.com:9000", target.host(), "a non-default port is part of the signed Host");
	}

	@Test
	@DisplayName("An endpoint without a scheme is assumed to be HTTPS")
	void endpointSchemeIsAssumed() {
		assertEquals("https://storage.example.com", S3Endpoint.normaliseEndpoint("storage.example.com"));
		assertEquals("https://storage.example.com", S3Endpoint.normaliseEndpoint("https://storage.example.com/"));
		assertEquals("http://localhost:9000", S3Endpoint.normaliseEndpoint("http://localhost:9000/"));
	}

	@Test
	@DisplayName("A discovered bucket region overrides the profile's for that bucket only")
	void discoveredRegionIsUsedForThatBucket() {

		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.recordBucketRegion("far-away", "ap-southeast-2");

		assertEquals("ap-southeast-2", endpoint.signingRegion("far-away"));
		assertEquals("us-east-1", endpoint.signingRegion("nearby"));
		assertEquals("us-east-1", endpoint.signingRegion(null), "an account-level call uses the profile's region");

		S3Endpoint.Target target = endpoint.target("far-away", "a.txt", Map.of());
		assertEquals("https://far-away.s3.ap-southeast-2.amazonaws.com/a.txt", target.uri().toString());
		assertEquals("ap-southeast-2", target.region());
	}

	@Test
	@DisplayName("Listing the account's buckets addresses the region endpoint with no bucket")
	void accountLevelCallHasNoBucket() {

		var endpoint = new S3Endpoint(profile, "eu-central-1");
		S3Endpoint.Target target = endpoint.target(null, null, Map.of());

		assertEquals("https://s3.eu-central-1.amazonaws.com/", target.uri().toString());
		assertEquals("/", target.canonicalPath());
	}

	@Test
	@DisplayName("A bucket root addresses the bucket with a bare slash")
	void bucketRootHasTrailingSlash() {

		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.Target target = endpoint.target("my-bucket", null, Map.of());

		assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/", target.uri().toString());
		assertEquals("/", target.canonicalPath());
	}

	@Test
	@DisplayName("Query parameters are encoded and sorted into the URL")
	void queryIsAppended() {

		var query = new LinkedHashMap<String, String>();
		query.put("prefix", "my folder/");
		query.put("list-type", "2");

		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.Target target = endpoint.target("my-bucket", null, query);

		assertEquals("https://my-bucket.s3.us-east-1.amazonaws.com/?list-type=2&prefix=my%20folder%2F",
				target.uri().toString());
	}

	@Test
	@DisplayName("A key with characters needing escapes produces a URL that matches what was signed")
	void awkwardKeysAreEncodedConsistently() {

		var endpoint = new S3Endpoint(profile, "us-east-1");
		S3Endpoint.Target target = endpoint.target("my-bucket", "reports/2026 Q1/a+b&c.txt", Map.of());

		// The canonical path and the URL path have to be byte-identical, or the signature covers a
		// different request than the one that is sent.
		assertEquals("/reports/2026%20Q1/a%2Bb%26c.txt", target.canonicalPath());
		assertTrue(target.uri().toString().endsWith(target.canonicalPath()));
	}
}
