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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials;

/**
 * Checks the signer against the vectors AWS publishes with the Signature Version 4 specification.
 *
 * <p>Signing is the one part of this plugin with no forgiving failure mode: a signature that is
 * wrong by one byte is refused with the same opaque {@code SignatureDoesNotMatch} as a wrong
 * password, and no amount of retrying helps. The published vectors pin the exact canonical request,
 * string-to-sign and signature for a known key and timestamp, so a regression anywhere in the
 * encoding, header ordering or key derivation shows up here rather than as an unexplainable 403
 * against a real bucket.
 */
class SigV4Test {

	/** The example credentials from the specification's test suite. */
	private static final AwsCredentials TEST_CREDENTIALS =
			AwsCredentials.of("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");

	private static final Instant TEST_TIME = Instant.parse("2015-08-30T12:36:00Z");

	@Test
	@DisplayName("get-vanilla: canonical request matches the published form")
	void canonicalRequestMatchesVector() {

		SigV4.Signed signed = new SigV4("us-east-1", "service").sign(
				"GET", "/", Map.of(), Map.of("Host", "example.amazonaws.com"),
				SigV4.EMPTY_BODY_SHA256, TEST_CREDENTIALS, TEST_TIME);

		assertEquals("""
				GET
				/

				host:example.amazonaws.com
				x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
				x-amz-date:20150830T123600Z

				host;x-amz-content-sha256;x-amz-date
				e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855""",
				signed.canonicalRequest());
	}

	@Test
	@DisplayName("get-vanilla: string-to-sign carries the algorithm, timestamp and scope")
	void stringToSignMatchesVector() {

		SigV4.Signed signed = new SigV4("us-east-1", "service").sign(
				"GET", "/", Map.of(), Map.of("Host", "example.amazonaws.com"),
				SigV4.EMPTY_BODY_SHA256, TEST_CREDENTIALS, TEST_TIME);

		String[] lines = signed.stringToSign().split("\n");
		assertEquals("AWS4-HMAC-SHA256", lines[0]);
		assertEquals("20150830T123600Z", lines[1]);
		assertEquals("20150830/us-east-1/service/aws4_request", lines[2]);
		assertEquals(64, lines[3].length(), "the fourth line is a hex SHA-256 of the canonical request");
	}

	@Test
	@DisplayName("The signing key derivation matches the published intermediate values")
	void signingKeyMatchesVector() {

		// The specification's key-derivation example: the same secret, date, region and service
		// produce this signing key, published as a byte sequence.
		byte[] key = new SigV4("us-east-1", "iam")
				.signingKey("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "20150830");

		assertEquals("c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9",
				java.util.HexFormat.of().formatHex(key));
	}

	@Test
	@DisplayName("A session token is signed as x-amz-security-token")
	void sessionTokenIsSigned() {

		var temporary = new AwsCredentials("ASIAEXAMPLE", "secret", "session-token-value", null);
		SigV4.Signed signed = SigV4.forS3("eu-west-1").sign(
				"GET", "/", Map.of(), Map.of("Host", "bucket.s3.eu-west-1.amazonaws.com"),
				SigV4.UNSIGNED_PAYLOAD, temporary, TEST_TIME);

		assertEquals("session-token-value", signed.headers().get("x-amz-security-token"));
		assertTrue(signed.canonicalRequest().contains("x-amz-security-token:session-token-value"));
		assertTrue(signed.headers().get("Authorization").contains("x-amz-security-token"),
				"the token header must appear in SignedHeaders or the signature is rejected");
	}

	@Test
	@DisplayName("Host is signed but never sent, because the HTTP client sets it itself")
	void hostIsSignedButNotReturned() {

		SigV4.Signed signed = SigV4.forS3("us-east-1").sign(
				"GET", "/", Map.of(), Map.of("Host", "example.amazonaws.com"),
				SigV4.EMPTY_BODY_SHA256, TEST_CREDENTIALS, TEST_TIME);

		assertTrue(signed.canonicalRequest().contains("host:example.amazonaws.com"));
		assertTrue(signed.headers().keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Host")));
	}

	@Test
	@DisplayName("Query parameters are sorted and encoded before signing")
	void queryIsCanonicalised() {

		var query = new LinkedHashMap<String, String>();
		query.put("prefix", "photos/2026 summer/");
		query.put("list-type", "2");
		query.put("delimiter", "/");

		assertEquals("delimiter=%2F&list-type=2&prefix=photos%2F2026%20summer%2F",
				SigV4.canonicalQuery(query));
	}

	@Test
	@DisplayName("Path encoding escapes every reserved character but keeps the separators")
	void pathEncodingKeepsSeparators() {
		assertEquals("/bucket/a%20b/c%2Bd.txt", SigV4.encodePath("/bucket/a b/c+d.txt"));
		assertEquals("/", SigV4.encodePath("/"));
		assertEquals("/folder/", SigV4.encodePath("/folder/"));
	}

	@Test
	@DisplayName("Encoding uses percent-escapes rather than the form-encoding of URLEncoder")
	void encodingIsRfc3986() {
		// URLEncoder would produce "+" here, which signs a different string than S3 computes.
		assertEquals("a%20b", SigV4.encode("a b"));
		// URLEncoder leaves "*" alone; the signature requires it escaped.
		assertEquals("%2A", SigV4.encode("*"));
		// The unreserved set survives untouched.
		assertEquals("aZ0-_.~", SigV4.encode("aZ0-_.~"));
		// Multi-byte characters are escaped per UTF-8 byte.
		assertEquals("%C3%A9", SigV4.encode("é"));
	}

	@Test
	@DisplayName("Header values are trimmed and their internal whitespace collapsed")
	void headerValuesAreNormalised() {

		SigV4.Signed signed = SigV4.forS3("us-east-1").sign(
				"PUT", "/key", Map.of(),
				Map.of("Host", "example.amazonaws.com", "Content-Type", "  text/plain;   charset=utf-8  "),
				SigV4.UNSIGNED_PAYLOAD, TEST_CREDENTIALS, TEST_TIME);

		assertTrue(signed.canonicalRequest().contains("content-type:text/plain; charset=utf-8\n"),
				"canonical headers collapse runs of whitespace: " + signed.canonicalRequest());
	}

	@Test
	@DisplayName("The empty-body hash constant is the SHA-256 of no bytes")
	void emptyBodyHashIsCorrect() {
		assertEquals(SigV4.EMPTY_BODY_SHA256, SigV4.sha256Hex(new byte[0]));
	}
}
