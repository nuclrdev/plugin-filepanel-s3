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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.auth.CredentialsResolver;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.SecretCache;

/**
 * Covers the client's pure decisions — the ones that do not need a network round trip: how an upload
 * is split into parts, what content type a key implies, and how a result carries success or failure.
 */
class S3ClientTest {

	@Test
	@DisplayName("An ordinary large upload uses the default part size")
	void defaultPartSize() {
		assertEquals(S3Client.MULTIPART_PART_BYTES, S3Client.partSizeFor(100L * 1024 * 1024));
		assertEquals(S3Client.MULTIPART_PART_BYTES, S3Client.partSizeFor(10L * 1024 * 1024 * 1024));
	}

	@Test
	@DisplayName("A very large upload grows its parts to stay within the 10,000-part limit")
	void partSizeGrowsForHugeUploads() {

		// 16 MB parts would need over 10,000 parts past ~160 GB, which S3 refuses; the part size has
		// to grow rather than the upload failing at the last part.
		long fiveTerabytes = 5L * 1024 * 1024 * 1024 * 1024;
		long partSize = S3Client.partSizeFor(fiveTerabytes);

		assertTrue(partSize > S3Client.MULTIPART_PART_BYTES);
		assertTrue(fiveTerabytes / partSize < 10_000,
				"the chosen part size must keep the upload under the part limit");
	}

	@Test
	@DisplayName("Content types are guessed from the key's extension")
	void contentTypeFromExtension() {
		assertEquals("text/plain; charset=utf-8", S3Client.contentType("notes/readme.txt"));
		assertEquals("application/json", S3Client.contentType("data.json"));
		assertEquals("image/png", S3Client.contentType("img/LOGO.PNG"));
		assertEquals("application/pdf", S3Client.contentType("reports/q1.pdf"));
		assertEquals("application/octet-stream", S3Client.contentType("archive.bin"));
		assertEquals("application/octet-stream", S3Client.contentType("no-extension"));
		assertEquals("application/octet-stream", S3Client.contentType(null));
	}

	@Test
	@DisplayName("A successful result carries its value and no error")
	void okResult() {

		S3Result<String> result = S3Result.ok("value");

		assertTrue(result.isOk());
		assertEquals("value", result.orNull());
		assertNull(result.errorOrNull());
		assertFalse(result.isCancelled());
	}

	@Test
	@DisplayName("A failed result carries its error and no value")
	void errResult() {

		S3Result<String> result = S3Result.err(new S3Error.NoSuchKey("a.txt"));

		assertFalse(result.isOk());
		assertNull(result.orNull());
		assertNotNull(result.errorOrNull());
		assertTrue(result.errorOrNull() instanceof S3Error.NoSuchKey);
	}

	@Test
	@DisplayName("A cancellation is recognisable without unwrapping the error")
	void cancellationIsRecognisable() {
		assertTrue(S3Result.err(new S3Error.Cancelled()).isCancelled());
		assertFalse(S3Result.err(new S3Error.AccessDenied("nope")).isCancelled());
	}

	@Test
	@DisplayName("An error can be re-typed to propagate out of a call with a different result type")
	void errorsPropagateAcrossTypes() {

		S3Result<String> failure = S3Result.err(new S3Error.NoSuchBucket("gone"));
		S3Result<Integer> propagated = failure.propagate();

		assertFalse(propagated.isOk());
		assertTrue(propagated.errorOrNull() instanceof S3Error.NoSuchBucket);
	}

	@Test
	@DisplayName("Propagating a success is a programming error, not a silent no-op")
	void propagatingSuccessThrows() {
		assertThrows(IllegalStateException.class, () -> S3Result.ok("value").propagate());
	}

	@Test
	@DisplayName("Every error describes itself in terms a user can act on")
	void errorsDescribeThemselves() {

		assertTrue(new S3Error.NotAuthorized("bad signature").describe().contains("rejected"));
		assertTrue(new S3Error.AccessDenied(null).describe().contains("not allowed"));
		assertTrue(new S3Error.NoSuchBucket("gone").describe().contains("gone"));
		assertTrue(new S3Error.NoSuchKey("a.txt").describe().contains("a.txt"));
		assertTrue(new S3Error.Network("connection reset").describe().contains("connection reset"));
		assertTrue(new S3Error.RequestFailed(500, "InternalError", "oops").describe().contains("500"));
		assertTrue(new S3Error.CredentialsUnavailable("no keys").describe().contains("no keys"));
	}

	@Test
	@DisplayName("Credentials know when they have gone stale")
	void credentialExpiry() {

		var permanent = dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials.of("AKIA", "secret");
		assertTrue(permanent.isUsable());
		assertFalse(permanent.isTemporary());

		var fresh = new dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials(
				"ASIA", "secret", "token", java.time.Instant.now().plusSeconds(3600));
		assertTrue(fresh.isUsable());
		assertTrue(fresh.isTemporary());

		var expired = new dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials(
				"ASIA", "secret", "token", java.time.Instant.now().minusSeconds(1));
		assertFalse(expired.isUsable());

		// Credentials inside the refresh margin are treated as stale, so a request cannot go out
		// with material that dies while it is in flight.
		var expiringNow = new dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials(
				"ASIA", "secret", "token", java.time.Instant.now().plusSeconds(30));
		assertFalse(expiringNow.isUsable());

		var incomplete = new dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials("AKIA", "", null, null);
		assertFalse(incomplete.isUsable());
	}

	@Test
	@DisplayName("Credentials never print their secret")
	void credentialsDoNotLeakTheSecret() {

		var credentials = new dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials(
				"AKIAEXAMPLE", "the-secret-value", "the-token-value", null);

		String text = credentials.toString();

		assertFalse(text.contains("the-secret-value"));
		assertFalse(text.contains("the-token-value"));
		assertTrue(text.contains("AKIAEXAMPLE"));
	}

	@Test
	@DisplayName("An empty file uploads as an empty object rather than failing")
	void uploadsAZeroLengthObject() throws Exception {

		// A zero content length is not a missing one: the PUT still has to go out, and it has to
		// carry Content-Length: 0 rather than being rejected before it is built.
		var bodyLength = new AtomicInteger(-1);
		var contentLength = new AtomicReference<String>();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			bodyLength.set(exchange.getRequestBody().readAllBytes().length);
			contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();

		try {
			S3Client client = clientFor(server.getAddress().getPort());

			S3Result<Long> result = client.upload("bucket", "empty.txt",
					() -> new ByteArrayInputStream(new byte[0]), 0, null, null);

			assertTrue(result.isOk(), () -> "upload failed: " + result.errorOrNull());
			assertEquals(0L, result.orNull());
			assertEquals(0, bodyLength.get());
			assertEquals("0", contentLength.get());
		} finally {
			server.stop(0);
			SecretCache.clear();
			CredentialsResolver.clear();
		}
	}

	@Test
	@DisplayName("A file with content still uploads its bytes")
	void uploadsANonEmptyObject() throws Exception {

		var bodyLength = new AtomicInteger(-1);

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			bodyLength.set(exchange.getRequestBody().readAllBytes().length);
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();

		try {
			S3Client client = clientFor(server.getAddress().getPort());
			byte[] content = "hello".getBytes();

			S3Result<Long> result = client.upload("bucket", "hello.txt",
					() -> new ByteArrayInputStream(content), content.length, null, null);

			assertTrue(result.isOk(), () -> "upload failed: " + result.errorOrNull());
			assertEquals(content.length, bodyLength.get());
		} finally {
			server.stop(0);
			SecretCache.clear();
			CredentialsResolver.clear();
		}
	}

	@Test
	@DisplayName("A folder marker is still written as a zero-byte object")
	void createsAFolderMarker() throws Exception {

		// The folder placeholder shares the empty-body path with an empty file upload, so it is
		// worth pinning that it still goes out as a PUT with no content.
		var method = new AtomicReference<String>();
		var contentLength = new AtomicReference<String>();
		var bodyLength = new AtomicInteger(-1);

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			method.set(exchange.getRequestMethod());
			contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
			bodyLength.set(exchange.getRequestBody().readAllBytes().length);
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();

		try {
			S3Client client = clientFor(server.getAddress().getPort());

			S3Result<Void> result = client.createFolder("bucket", "reports/");

			assertTrue(result.isOk(), () -> "createFolder failed: " + result.errorOrNull());
			assertEquals("PUT", method.get());
			assertEquals("0", contentLength.get());
			assertEquals(0, bodyLength.get());
		} finally {
			server.stop(0);
			SecretCache.clear();
			CredentialsResolver.clear();
		}
	}

	/** A client pointed at a local endpoint, signing with a throwaway key. */
	private static S3Client clientFor(int port) {

		S3Profile profile = new S3Profile();
		profile.setId("s3-client-test");
		profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
		profile.setAccessKeyId("AKIAEXAMPLEEXAMPLE");
		profile.setRegion("us-east-1");
		profile.setEndpoint("http://127.0.0.1:" + port);
		profile.setPathStyleAccess(true);

		SecretCache.put(profile.getId(), "not-a-real-secret", null);
		CredentialsResolver.clear();

		return new S3Client(profile, new CredentialsResolver());
	}
}
