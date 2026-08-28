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

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.xml.stream.XMLStreamException;

import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.auth.AwsCli;
import dev.nuclr.plugin.core.panel.s3.auth.AwsCredentials;
import dev.nuclr.plugin.core.panel.s3.auth.CredentialsResolver;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import lombok.extern.slf4j.Slf4j;

/**
 * The S3 REST client behind the panel: listing, transfer, copy and delete, spoken directly to the
 * service over HTTPS with {@link SigV4}-signed requests.
 *
 * <p>One instance serves one {@link S3Profile}. It owns nothing expensive — the {@link HttpClient}
 * is shared process-wide so connections and TLS sessions are pooled across panels and operations,
 * which is what makes a second quick view of a neighbouring object feel instant.
 *
 * <p>Two kinds of retry are built into every call, because both are routine rather than exceptional:
 *
 * <ul>
 *   <li><b>Wrong region.</b> A bucket's region is not known until S3 says so. A request to the wrong
 *       one comes back as a redirect carrying {@code x-amz-bucket-region}; that region is recorded
 *       in {@link S3Endpoint} and the call is re-signed and repeated. Every later request to that
 *       bucket goes straight to the right endpoint.</li>
 *   <li><b>Stale credentials.</b> An SSO token or instance-role credential can expire mid-session.
 *       A rejection makes the client drop the cached credentials, resolve fresh ones, and try once
 *       more — the user only sees a prompt when re-resolution actually needs one.</li>
 * </ul>
 *
 * <p>Bodies are streamed in both directions and signed as {@link SigV4#UNSIGNED_PAYLOAD}, so an
 * object of any size transfers without being held in memory. Uploads above
 * {@value #MULTIPART_THRESHOLD_BYTES} bytes switch to a multipart upload, which is both faster and
 * the only way past the 5 GB single-request limit.
 */
@Slf4j
public final class S3Client {

	/** Shared across every profile and panel: pooled connections, warm TLS. */
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	private static final Duration LIST_TIMEOUT = Duration.ofSeconds(60);

	private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(30);

	private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);

	private static final int BUFFER_BYTES = 256 * 1024;

	/** Uploads larger than this go through a multipart upload rather than a single PUT. */
	static final long MULTIPART_THRESHOLD_BYTES = 64L * 1024 * 1024;

	/** Part size for multipart uploads; grown when the object would need more than 10,000 parts. */
	static final long MULTIPART_PART_BYTES = 16L * 1024 * 1024;

	/** S3 permits at most this many parts in one multipart upload. */
	private static final int MULTIPART_MAX_PARTS = 10_000;

	/** How many keys one listing page requests; the service maximum. */
	private static final int PAGE_SIZE = 1000;

	/** Delete this many objects per multi-object delete request; the service maximum. */
	public static final int DELETE_BATCH_SIZE = 1000;

	private final S3Profile profile;

	private final CredentialsResolver resolver;

	private final S3Endpoint endpoint;

	/**
	 * Create a client for one profile.
	 *
	 * @param profile  the connection profile
	 * @param resolver the credential resolver to authenticate through
	 */
	public S3Client(S3Profile profile, CredentialsResolver resolver) {
		this.profile = profile;
		this.resolver = resolver;
		this.endpoint = new S3Endpoint(profile, resolver.region(profile));
	}

	/**
	 * The profile this client serves.
	 *
	 * @return the profile
	 */
	public S3Profile profile() {
		return profile;
	}

	/** Receives running byte counts during a transfer; {@code total} is {@code -1} when unknown. */
	@FunctionalInterface
	public interface ProgressListener {

		/**
		 * Report transfer progress.
		 *
		 * @param transferred bytes moved so far
		 * @param total       the expected total, or {@code -1} when it is not known
		 */
		void onBytes(long transferred, long total);
	}

	/** Supplies a fresh stream of the content to upload, re-opened for each attempt. */
	@FunctionalInterface
	public interface BodySource {

		/**
		 * Open the content.
		 *
		 * @return a fresh stream positioned at the start
		 * @throws IOException if the content cannot be opened
		 */
		InputStream open() throws IOException;
	}

	// -------------------------------------------------------------------------
	// Listing
	// -------------------------------------------------------------------------

	/**
	 * List the buckets these credentials can see.
	 *
	 * @return the buckets, or an error — commonly {@link S3Error.AccessDenied} for credentials that
	 *         may read a bucket but are not granted {@code s3:ListAllMyBuckets}
	 */
	public S3Result<List<S3BucketEntry>> listBuckets() {

		S3Result<Response> response = execute(new Call("GET", null, null, Map.of(), Map.of(),
				Body.empty(), LIST_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}

		try {
			return S3Result.ok(S3Xml.parseListBuckets(new ByteArrayInputStream(response.orNull().body())));
		} catch (XMLStreamException e) {
			return S3Result.err(new S3Error.RequestFailed(200, "MalformedResponse", e.getMessage()));
		}
	}

	/**
	 * List one page of the immediate children of a prefix.
	 *
	 * <p>Uses a {@code /} delimiter, so the result is the objects directly under {@code prefix} plus
	 * one folder entry per deeper path — the shape a file panel wants, rather than every key in the
	 * subtree.
	 *
	 * @param bucket            the bucket to list
	 * @param prefix            the key prefix, ending in {@code /}, or {@code ""} for the bucket root
	 * @param continuationToken the token from the previous page, or {@code null} for the first page
	 * @return the page of entries and any token for the page after it
	 */
	public S3Result<S3Xml.ListPage> listObjects(String bucket, String prefix, String continuationToken) {
		return listObjects(bucket, prefix, continuationToken, "/", PAGE_SIZE);
	}

	/**
	 * List one page of keys, with control over the delimiter — the recursive form (no delimiter) is
	 * what the find and folder-size walks use.
	 *
	 * @param bucket            the bucket to list
	 * @param prefix            the key prefix to list under
	 * @param continuationToken the token from the previous page, or {@code null} for the first page
	 * @param delimiter         {@code "/"} to collapse deeper keys into folders, or {@code null} to
	 *                          return every key in the subtree
	 * @param maxKeys           how many keys to request in this page
	 * @return the page of entries and any token for the page after it
	 */
	public S3Result<S3Xml.ListPage> listObjects(String bucket, String prefix, String continuationToken,
			String delimiter, int maxKeys) {

		var query = new LinkedHashMap<String, String>();
		query.put("list-type", "2");
		query.put("prefix", prefix == null ? "" : prefix);
		query.put("max-keys", String.valueOf(maxKeys));
		if (delimiter != null && !delimiter.isEmpty()) {
			query.put("delimiter", delimiter);
		}
		if (continuationToken != null && !continuationToken.isBlank()) {
			query.put("continuation-token", continuationToken);
		}
		if (profile.isBucketScoped()) {
			// A bucket-scoped profile may hold credentials without ListAllMyBuckets; asking S3 to
			// echo the owner would then fail a listing that would otherwise work.
			query.put("fetch-owner", "false");
		}

		S3Result<Response> response = execute(
				new Call("GET", bucket, null, query, Map.of(), Body.empty(), LIST_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}

		try {
			return S3Result.ok(S3Xml.parseListObjects(
					new ByteArrayInputStream(response.orNull().body()), prefix == null ? "" : prefix));
		} catch (XMLStreamException e) {
			return S3Result.err(new S3Error.RequestFailed(200, "MalformedResponse", e.getMessage()));
		}
	}

	/**
	 * Metadata for one object, without fetching it.
	 *
	 * @param bucket the bucket
	 * @param key    the object key
	 * @return the object's size and last-modified time, or an error
	 */
	public S3Result<S3ObjectEntry> headObject(String bucket, String key) {

		S3Result<Response> response = execute(
				new Call("HEAD", bucket, key, Map.of(), Map.of(), Body.empty(), SHORT_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}

		Response head = response.orNull();
		long size = head.headerAsLong("content-length", -1);
		long modified = S3Xml.parseTimestamp(head.header("x-amz-meta-last-modified"));
		if (modified < 0) {
			modified = parseHttpDate(head.header("last-modified"));
		}
		return S3Result.ok(S3ObjectEntry.object(key, size, modified,
				head.header("x-amz-storage-class"), S3Xml.unquote(head.header("etag"))));
	}

	// -------------------------------------------------------------------------
	// Download
	// -------------------------------------------------------------------------

	/**
	 * Download an object to a local file, overwriting it.
	 *
	 * <p>Streams straight to disk so an object of any size can be fetched, reporting progress as it
	 * goes and stopping promptly when {@code cancelled} turns true. A cancelled download leaves a
	 * partial file behind, which the caller is expected to discard.
	 *
	 * @param bucket      the bucket
	 * @param key         the object key
	 * @param destination the local file to write
	 * @param listener    receives byte progress, or {@code null}
	 * @param cancelled   polled between buffers, or {@code null}
	 * @return the number of bytes written, or an error
	 */
	public S3Result<Long> downloadToFile(String bucket, String key, Path destination,
			ProgressListener listener, BooleanSupplier cancelled) {

		var call = new Call("GET", bucket, key, Map.of(), Map.of(), Body.empty(), TRANSFER_TIMEOUT);
		return executeStreaming(call, (status, headers, stream) -> {
			long total = headers.firstValueAsLong("content-length").orElse(-1L);
			long written = streamToFile(stream, destination, total, listener, cancelled);
			if (written < 0) {
				return S3Result.err(new S3Error.Cancelled());
			}
			return S3Result.ok(written);
		});
	}

	/**
	 * Open an object for reading.
	 *
	 * <p>The caller owns the returned stream and must close it — until then the underlying
	 * connection is held out of the pool.
	 *
	 * @param bucket the bucket
	 * @param key    the object key
	 * @return the object's content, or an error
	 */
	public S3Result<InputStream> openObject(String bucket, String key) {
		var call = new Call("GET", bucket, key, Map.of(), Map.of(), Body.empty(), TRANSFER_TIMEOUT);
		return executeStreaming(call, (status, headers, stream) -> S3Result.ok(stream));
	}

	// -------------------------------------------------------------------------
	// Upload
	// -------------------------------------------------------------------------

	/**
	 * Upload content to an object key, choosing a single request or a multipart upload by size.
	 *
	 * @param bucket    the destination bucket
	 * @param key       the destination object key
	 * @param body      supplies the content; may be re-opened if the request is retried
	 * @param size      the content length in bytes, or {@code -1} when unknown
	 * @param listener  receives byte progress, or {@code null}
	 * @param cancelled polled during the transfer, or {@code null}
	 * @return the uploaded byte count, or an error
	 */
	public S3Result<Long> upload(String bucket, String key, BodySource body, long size,
			ProgressListener listener, BooleanSupplier cancelled) {

		if (size > MULTIPART_THRESHOLD_BYTES) {
			return uploadMultipart(bucket, key, body, size, listener, cancelled);
		}
		return uploadSingle(bucket, key, body, size, listener, cancelled);
	}

	/** One PUT carrying the whole object. */
	private S3Result<Long> uploadSingle(String bucket, String key, BodySource body, long size,
			ProgressListener listener, BooleanSupplier cancelled) {

		var headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", contentType(key));

		var call = new Call("PUT", bucket, key, Map.of(), headers,
				Body.streamed(body, size, listener, cancelled), TRANSFER_TIMEOUT);
		S3Result<Response> response = execute(call, cancelled);
		return response.isOk() ? S3Result.ok(Math.max(size, 0)) : response.propagate();
	}

	/**
	 * A multipart upload: create, send the parts, complete. An upload that fails or is cancelled
	 * part-way is aborted, so the parts already sent do not sit in the bucket accruing storage
	 * charges invisibly — incomplete multipart uploads are not listed as objects and are a
	 * well-known way to quietly pay for nothing.
	 */
	private S3Result<Long> uploadMultipart(String bucket, String key, BodySource body, long size,
			ProgressListener listener, BooleanSupplier cancelled) {

		long partSize = partSizeFor(size);

		var createHeaders = new LinkedHashMap<String, String>();
		createHeaders.put("Content-Type", contentType(key));
		S3Result<Response> created = execute(new Call("POST", bucket, key, Map.of("uploads", ""),
				createHeaders, Body.empty(), SHORT_TIMEOUT), cancelled);
		if (!created.isOk()) {
			return created.propagate();
		}

		String uploadId = S3Xml.parseUploadId(created.orNull().body());
		if (uploadId == null || uploadId.isBlank()) {
			return S3Result.err(new S3Error.RequestFailed(200, "MalformedResponse",
					"The endpoint did not return a multipart upload id."));
		}
		log.info("Started multipart upload of s3://{}/{} ({} bytes, {} byte parts)", bucket, key, size, partSize);

		var etags = new ArrayList<String>();
		long uploaded = 0;

		try (InputStream source = body.open()) {
			for (int partNumber = 1; uploaded < size; partNumber++) {

				if (isCancelled(cancelled)) {
					abortMultipart(bucket, key, uploadId);
					return S3Result.err(new S3Error.Cancelled());
				}

				long remaining = size - uploaded;
				long thisPart = Math.min(partSize, remaining);
				byte[] part = source.readNBytes((int) thisPart);
				if (part.length == 0) {
					break; // the source ran short of its declared size; send what we have
				}

				long alreadyUploaded = uploaded;
				var partQuery = new LinkedHashMap<String, String>();
				partQuery.put("partNumber", String.valueOf(partNumber));
				partQuery.put("uploadId", uploadId);

				ProgressListener partListener = listener == null ? null
						: (transferred, ignored) -> listener.onBytes(alreadyUploaded + transferred, size);

				S3Result<Response> partResponse = execute(new Call("PUT", bucket, key, partQuery, Map.of(),
						Body.bytes(part, partListener), TRANSFER_TIMEOUT), cancelled);
				if (!partResponse.isOk()) {
					abortMultipart(bucket, key, uploadId);
					return partResponse.propagate();
				}

				String etag = S3Xml.unquote(partResponse.orNull().header("etag"));
				if (etag == null) {
					abortMultipart(bucket, key, uploadId);
					return S3Result.err(new S3Error.RequestFailed(200, "MalformedResponse",
							"Part " + partNumber + " came back without an ETag."));
				}
				etags.add(etag);
				uploaded += part.length;
				if (listener != null) {
					listener.onBytes(uploaded, size);
				}
			}
		} catch (IOException e) {
			abortMultipart(bucket, key, uploadId);
			return S3Result.err(new S3Error.Network("Could not read the source file: " + e.getMessage()));
		}

		byte[] completeBody = S3Xml.completeMultipartBody(etags);
		var completeHeaders = new LinkedHashMap<String, String>();
		completeHeaders.put("Content-Type", "application/xml");

		S3Result<Response> completed = execute(new Call("POST", bucket, key, Map.of("uploadId", uploadId),
				completeHeaders, Body.bytes(completeBody, null), TRANSFER_TIMEOUT), cancelled);
		if (!completed.isOk()) {
			abortMultipart(bucket, key, uploadId);
			return completed.propagate();
		}

		// Completion is the one call that can answer 200 and still have failed: S3 keeps the
		// connection alive with whitespace while it assembles the object, then writes either a
		// result or an error document into that same successful response.
		S3Xml.ErrorDocument error = S3Xml.parseError(completed.orNull().body());
		if (error != null) {
			abortMultipart(bucket, key, uploadId);
			return S3Result.err(classify(200, error.code(), error.message(), bucket, key));
		}

		log.info("Completed multipart upload of s3://{}/{}: {} part(s), {} bytes", bucket, key, etags.size(), uploaded);
		return S3Result.ok(uploaded);
	}

	/** Part size, grown from the default only when the object would otherwise need too many parts. */
	static long partSizeFor(long size) {
		long partSize = MULTIPART_PART_BYTES;
		while (size / partSize > MULTIPART_MAX_PARTS - 1) {
			partSize *= 2;
		}
		return partSize;
	}

	/** Best-effort cleanup of an abandoned multipart upload; failure here is logged, not surfaced. */
	private void abortMultipart(String bucket, String key, String uploadId) {
		S3Result<Response> response = execute(new Call("DELETE", bucket, key, Map.of("uploadId", uploadId),
				Map.of(), Body.empty(), SHORT_TIMEOUT), null);
		if (response.isOk()) {
			log.info("Aborted the multipart upload of s3://{}/{}", bucket, key);
		} else {
			log.warn("Could not abort the multipart upload of s3://{}/{}: {}",
					bucket, key, response.errorOrNull().describe());
		}
	}

	// -------------------------------------------------------------------------
	// Copy, delete, folders
	// -------------------------------------------------------------------------

	/**
	 * Copy an object inside S3, without moving its bytes through this machine.
	 *
	 * <p>Only valid up to 5 GB and within one endpoint; the panel falls back to a download-and-upload
	 * for anything larger or across profiles.
	 *
	 * @param sourceBucket      the bucket to copy from
	 * @param sourceKey         the key to copy from
	 * @param destinationBucket the bucket to copy to
	 * @param destinationKey    the key to copy to
	 * @return success, or an error
	 */
	public S3Result<Void> copyObject(String sourceBucket, String sourceKey,
			String destinationBucket, String destinationKey) {

		var headers = new LinkedHashMap<String, String>();
		// The copy source is a path, so its separators stay separators; everything else is encoded.
		headers.put("x-amz-copy-source", SigV4.encodePath("/" + sourceBucket + "/" + sourceKey));

		S3Result<Response> response = execute(new Call("PUT", destinationBucket, destinationKey,
				Map.of(), headers, Body.empty(), TRANSFER_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}

		// Like multipart completion, a copy can report failure inside a 200 response.
		S3Xml.ErrorDocument error = S3Xml.parseError(response.orNull().body());
		if (error != null) {
			return S3Result.err(classify(200, error.code(), error.message(), sourceBucket, sourceKey));
		}
		return S3Result.ok(null);
	}

	/**
	 * Delete one object. A key that is already gone counts as success — the caller wanted it absent,
	 * and it is.
	 *
	 * @param bucket the bucket
	 * @param key    the object key
	 * @return success, or an error
	 */
	public S3Result<Void> deleteObject(String bucket, String key) {
		S3Result<Response> response = execute(
				new Call("DELETE", bucket, key, Map.of(), Map.of(), Body.empty(), SHORT_TIMEOUT), null);
		if (!response.isOk() && response.errorOrNull() instanceof S3Error.NoSuchKey) {
			return S3Result.ok(null);
		}
		return response.isOk() ? S3Result.ok(null) : response.propagate();
	}

	/**
	 * Delete up to {@value #DELETE_BATCH_SIZE} objects in one request.
	 *
	 * <p>Far cheaper than a request per key when emptying a folder: a thousand keys go in one round
	 * trip rather than a thousand.
	 *
	 * @param bucket the bucket
	 * @param keys   the keys to delete; at most {@value #DELETE_BATCH_SIZE}
	 * @return success, or an error
	 */
	public S3Result<Void> deleteObjects(String bucket, List<String> keys) {

		if (keys == null || keys.isEmpty()) {
			return S3Result.ok(null);
		}
		if (keys.size() > DELETE_BATCH_SIZE) {
			return S3Result.err(new S3Error.RequestFailed(0, "TooManyKeys",
					"A delete request carries at most " + DELETE_BATCH_SIZE + " keys."));
		}

		byte[] body = S3Xml.deleteObjectsBody(keys);
		var headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/xml");
		// S3 still requires the legacy integrity header on this call and rejects it without one.
		headers.put("Content-MD5", base64Md5(body));

		S3Result<Response> response = execute(new Call("POST", bucket, null, Map.of("delete", ""),
				headers, Body.bytes(body, null), LIST_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}

		S3Xml.ErrorDocument error = S3Xml.parseError(response.orNull().body());
		if (error != null) {
			return S3Result.err(classify(200, error.code(), error.message(), bucket, null));
		}
		return S3Result.ok(null);
	}

	/**
	 * Create a folder: a zero-byte object whose key ends in {@code /}, the same placeholder the AWS
	 * console writes.
	 *
	 * @param bucket    the bucket
	 * @param folderKey the folder key, ending in {@code /}
	 * @return success, or an error
	 */
	public S3Result<Void> createFolder(String bucket, String folderKey) {
		var headers = new LinkedHashMap<String, String>();
		headers.put("Content-Type", "application/x-directory");
		S3Result<Response> response = execute(new Call("PUT", bucket, folderKey, Map.of(), headers,
				Body.bytes(new byte[0], null), SHORT_TIMEOUT), null);
		return response.isOk() ? S3Result.ok(null) : response.propagate();
	}

	/**
	 * Ask which region a bucket lives in and remember the answer.
	 *
	 * @param bucket the bucket
	 * @return the region, or an error
	 */
	public S3Result<String> bucketRegion(String bucket) {
		S3Result<Response> response = execute(new Call("GET", bucket, null, Map.of("location", ""),
				Map.of(), Body.empty(), SHORT_TIMEOUT), null);
		if (!response.isOk()) {
			return response.propagate();
		}
		String region = S3Xml.parseBucketLocation(response.orNull().body());
		S3Endpoint.recordBucketRegion(bucket, region);
		return S3Result.ok(region);
	}

	// -------------------------------------------------------------------------
	// Request execution
	// -------------------------------------------------------------------------

	/** One request to make: everything needed to build, sign and send it. */
	private record Call(String method, String bucket, String key, Map<String, String> query,
			Map<String, String> headers, Body body, Duration timeout) {}

	/** The request body: nothing, a byte array, or a stream that may be re-opened for a retry. */
	private record Body(byte[] bytes, BodySource source, long size,
			ProgressListener listener, BooleanSupplier cancelled) {

		static Body empty() {
			return new Body(null, null, 0, null, null);
		}

		static Body bytes(byte[] value, ProgressListener listener) {
			return new Body(value, null, value.length, listener, null);
		}

		static Body streamed(BodySource source, long size, ProgressListener listener, BooleanSupplier cancelled) {
			return new Body(null, source, size, listener, cancelled);
		}

		boolean isStreamed() {
			return source != null;
		}
	}

	/** A completed response with its body already read into memory. */
	private record Response(int status, HttpHeadersView headers, byte[] body) {

		String header(String name) {
			return headers.first(name);
		}

		long headerAsLong(String name, long fallback) {
			String value = header(name);
			if (value == null || value.isBlank()) {
				return fallback;
			}
			try {
				return Long.parseLong(value.trim());
			} catch (NumberFormatException e) {
				return fallback;
			}
		}
	}

	/** Minimal case-insensitive view over response headers. */
	private record HttpHeadersView(java.net.http.HttpHeaders raw) {

		String first(String name) {
			return raw.firstValue(name).orElse(null);
		}
	}

	/** Consumes a streaming response body. */
	@FunctionalInterface
	private interface StreamConsumer<T> {

		S3Result<T> accept(int status, java.net.http.HttpHeaders headers, InputStream body) throws IOException;
	}

	/**
	 * Send a request whose response body is small enough to hold in memory, retrying once for a
	 * region redirect and once for rejected credentials.
	 */
	private S3Result<Response> execute(Call call, BooleanSupplier cancelled) {

		boolean regionRetried = false;
		boolean credentialsRetried = false;

		while (true) {

			if (isCancelled(cancelled)) {
				return S3Result.err(new S3Error.Cancelled());
			}

			S3Result<HttpRequest> request = buildRequest(call);
			if (!request.isOk()) {
				return request.propagate();
			}

			HttpResponse<byte[]> response;
			try {
				response = HTTP.send(request.orNull(), HttpResponse.BodyHandlers.ofByteArray());
			} catch (IOException e) {
				if (isCancelled(cancelled) || isCancellation(e)) {
					return S3Result.err(new S3Error.Cancelled());
				}
				return S3Result.err(new S3Error.Network(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return S3Result.err(new S3Error.Network("Interrupted while waiting for S3."));
			}

			int status = response.statusCode();
			var headers = new HttpHeadersView(response.headers());

			if (status >= 200 && status < 300) {
				return S3Result.ok(new Response(status, headers, response.body()));
			}

			if (!regionRetried && adoptRedirectRegion(call.bucket(), status, headers)) {
				regionRetried = true;
				continue;
			}

			S3Error error = classifyResponse(call, status, headers, response.body());
			if (!credentialsRetried && error instanceof S3Error.NotAuthorized) {
				credentialsRetried = true;
				resolver.invalidate(profile);
				log.info("S3 rejected the credentials for {}; resolving fresh ones and retrying",
						profile.displayName());
				continue;
			}
			return S3Result.err(error);
		}
	}

	/**
	 * Send a request and hand its body to {@code consumer} as a stream. Shares the retry rules of
	 * {@link #execute}, but the consumer sees the body only on a successful status — a failure body
	 * is read into memory and classified instead.
	 */
	private <T> S3Result<T> executeStreaming(Call call, StreamConsumer<T> consumer) {

		boolean regionRetried = false;
		boolean credentialsRetried = false;

		while (true) {

			S3Result<HttpRequest> request = buildRequest(call);
			if (!request.isOk()) {
				return request.propagate();
			}

			HttpResponse<InputStream> response;
			try {
				response = HTTP.send(request.orNull(), HttpResponse.BodyHandlers.ofInputStream());
			} catch (IOException e) {
				return S3Result.err(new S3Error.Network(e.getMessage()));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return S3Result.err(new S3Error.Network("Interrupted while waiting for S3."));
			}

			int status = response.statusCode();
			var headers = new HttpHeadersView(response.headers());

			if (status >= 200 && status < 300) {
				try {
					return consumer.accept(status, response.headers(), response.body());
				} catch (IOException e) {
					return S3Result.err(new S3Error.Network(e.getMessage()));
				}
			}

			byte[] failureBody = drain(response.body());

			if (!regionRetried && adoptRedirectRegion(call.bucket(), status, headers)) {
				regionRetried = true;
				continue;
			}

			S3Error error = classifyResponse(call, status, headers, failureBody);
			if (!credentialsRetried && error instanceof S3Error.NotAuthorized) {
				credentialsRetried = true;
				resolver.invalidate(profile);
				continue;
			}
			return S3Result.err(error);
		}
	}

	/** Resolve credentials, sign, and assemble the outgoing request. */
	private S3Result<HttpRequest> buildRequest(Call call) {

		AwsCredentials credentials;
		try {
			credentials = resolver.resolve(profile);
		} catch (AwsCli.SsoLoginRequiredException e) {
			return S3Result.err(new S3Error.CredentialsUnavailable(e.getMessage()
					+ " Run 'aws sso login --profile " + describe(profile.getAwsProfileName())
					+ "' or use the panel's Sign in action."));
		} catch (IOException e) {
			return S3Result.err(new S3Error.CredentialsUnavailable(e.getMessage()));
		}

		S3Endpoint.Target target = endpoint.target(call.bucket(), call.key(), call.query());

		var headersToSign = new LinkedHashMap<String, String>();
		headersToSign.put("Host", target.host());
		headersToSign.putAll(call.headers());

		String payloadHash;
		if (call.body().isStreamed()) {
			payloadHash = SigV4.UNSIGNED_PAYLOAD;
		} else if (call.body().bytes() != null && call.body().bytes().length > 0) {
			payloadHash = SigV4.sha256Hex(call.body().bytes());
		} else {
			payloadHash = SigV4.EMPTY_BODY_SHA256;
		}

		SigV4.Signed signed = SigV4.forS3(target.region()).sign(call.method(), target.canonicalPath(),
				call.query(), headersToSign, payloadHash, credentials, Instant.now());

		HttpRequest.Builder builder = HttpRequest.newBuilder(target.uri()).timeout(call.timeout());
		for (Map.Entry<String, String> header : signed.headers().entrySet()) {
			builder.header(header.getKey(), header.getValue());
		}

		HttpRequest.BodyPublisher publisher;
		if (call.body().isStreamed()) {
			InputStream stream;
			try {
				stream = counting(call.body());
			} catch (IOException e) {
				return S3Result.err(new S3Error.Network("Could not open the source content: " + e.getMessage()));
			}
			// A known length lets the client send a fixed Content-Length rather than chunking,
			// which some S3-compatible endpoints require.
			publisher = call.body().size() >= 0
					? HttpRequest.BodyPublishers.fromPublisher(
							HttpRequest.BodyPublishers.ofInputStream(() -> stream), call.body().size())
					: HttpRequest.BodyPublishers.ofInputStream(() -> stream);
		} else if (call.body().bytes() != null) {
			publisher = HttpRequest.BodyPublishers.ofByteArray(call.body().bytes());
		} else {
			publisher = HttpRequest.BodyPublishers.noBody();
		}

		builder.method(call.method(), publisher);
		return S3Result.ok(builder.build());
	}

	/**
	 * If the failure was "wrong region", record the region S3 named and report that the call is
	 * worth repeating.
	 */
	private boolean adoptRedirectRegion(String bucket, int status, HttpHeadersView headers) {

		if (bucket == null || bucket.isBlank()) {
			return false;
		}
		if (status != 301 && status != 307 && status != 400) {
			return false;
		}
		String region = headers.first("x-amz-bucket-region");
		if (region == null || region.isBlank() || region.equals(endpoint.signingRegion(bucket))) {
			return false;
		}
		log.info("Bucket {} lives in {}; re-signing the request for that region", bucket, region);
		S3Endpoint.recordBucketRegion(bucket, region);
		return true;
	}

	/** Turn a failure response into the most specific {@link S3Error} its status and body support. */
	private S3Error classifyResponse(Call call, int status, HttpHeadersView headers, byte[] body) {

		S3Xml.ErrorDocument document = S3Xml.parseError(body);
		String code = document == null ? null : document.code();
		String message = document == null ? null : document.message();

		// HEAD answers carry no body, so the status is all there is to go on.
		if (code == null && "HEAD".equals(call.method())) {
			code = switch (status) {
				case 404 -> "NoSuchKey";
				case 403 -> "AccessDenied";
				case 401 -> "InvalidAccessKeyId";
				default -> null;
			};
		}

		return classify(status, code, message, call.bucket(), call.key());
	}

	/**
	 * Map an S3 error code to a panel error.
	 *
	 * <p>The split that matters: codes about the <em>credentials</em> mean re-authenticating could
	 * help, so they become {@link S3Error.NotAuthorized} and trigger a retry; {@code AccessDenied}
	 * means the credentials are fine but the policy says no, and retrying would achieve nothing but
	 * a second prompt.
	 */
	private static S3Error classify(int status, String code, String message, String bucket, String key) {

		String normalised = code == null ? "" : code.trim();

		return switch (normalised) {
			case "InvalidAccessKeyId", "SignatureDoesNotMatch", "ExpiredToken", "TokenRefreshRequired",
					"InvalidToken", "AuthFailure", "InvalidSecurity", "RequestTimeTooSkewed" ->
					new S3Error.NotAuthorized(message);
			case "AccessDenied", "AllAccessDisabled", "AccountProblem" -> new S3Error.AccessDenied(message);
			case "NoSuchBucket" -> new S3Error.NoSuchBucket(bucket);
			case "NoSuchKey" -> new S3Error.NoSuchKey(key);
			default -> switch (status) {
				case 401 -> new S3Error.NotAuthorized(message);
				case 403 -> new S3Error.AccessDenied(message);
				case 404 -> key != null ? new S3Error.NoSuchKey(key) : new S3Error.NoSuchBucket(bucket);
				default -> new S3Error.RequestFailed(status, normalised.isEmpty() ? null : normalised, message);
			};
		};
	}

	// -------------------------------------------------------------------------
	// Streaming helpers
	// -------------------------------------------------------------------------

	/**
	 * Copy a response body to a file. Returns the byte count, or {@code -1} when it stopped early
	 * because the operation was cancelled.
	 */
	private static long streamToFile(InputStream body, Path destination, long total,
			ProgressListener listener, BooleanSupplier cancelled) throws IOException {

		try (InputStream in = body;
				OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE,
						StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

			byte[] buffer = new byte[BUFFER_BYTES];
			long transferred = 0;
			int read;
			while ((read = in.read(buffer)) >= 0) {
				if (isCancelled(cancelled)) {
					return -1;
				}
				out.write(buffer, 0, read);
				transferred += read;
				if (listener != null) {
					listener.onBytes(transferred, total);
				}
			}
			return transferred;
		}
	}

	/** Raised from an upload's body stream to abort the request when the user cancels. */
	private static final class CancelledUpload extends IOException {
		private static final long serialVersionUID = 1L;
	}

	/** Wrap the upload source so each read reports progress and a cancel aborts the request. */
	private static InputStream counting(Body body) throws IOException {

		InputStream in = body.source().open();
		ProgressListener listener = body.listener();
		BooleanSupplier cancelled = body.cancelled();
		long total = body.size();

		return new FilterInputStream(in) {

			private long transferred;

			@Override
			public int read() throws IOException {
				checkCancelled();
				int value = super.read();
				if (value >= 0) {
					transferred++;
					report();
				}
				return value;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				checkCancelled();
				int read = super.read(buffer, offset, length);
				if (read > 0) {
					transferred += read;
					report();
				}
				return read;
			}

			private void checkCancelled() throws IOException {
				if (isCancelled(cancelled)) {
					throw new CancelledUpload();
				}
			}

			private void report() {
				if (listener != null) {
					listener.onBytes(transferred, total);
				}
			}
		};
	}

	private static boolean isCancellation(Throwable thrown) {
		for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof CancelledUpload) {
				return true;
			}
		}
		return false;
	}

	private static boolean isCancelled(BooleanSupplier cancelled) {
		return cancelled != null && cancelled.getAsBoolean();
	}

	/** Read and close a failure body, bounded so a hostile endpoint cannot make us hold much. */
	private static byte[] drain(InputStream body) {
		try (InputStream in = body) {
			return in.readNBytes(64 * 1024);
		} catch (IOException e) {
			return new byte[0];
		}
	}

	private static String base64Md5(byte[] body) {
		try {
			return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(body));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 is required by the Java platform", e);
		}
	}

	/** RFC 1123 date to epoch milliseconds, or {@code -1}. */
	private static long parseHttpDate(String value) {
		if (value == null || value.isBlank()) {
			return -1;
		}
		try {
			return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
					.parse(value.trim(), java.time.ZonedDateTime::from).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/**
	 * A content type guessed from the key's extension, so a downloaded object opens in the right
	 * application and a browser-facing bucket serves it correctly.
	 */
	static String contentType(String key) {

		String name = key == null ? "" : key.toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		String extension = dot < 0 ? "" : name.substring(dot + 1);

		return switch (extension) {
			case "txt", "log", "md" -> "text/plain; charset=utf-8";
			case "html", "htm" -> "text/html; charset=utf-8";
			case "css" -> "text/css; charset=utf-8";
			case "csv" -> "text/csv; charset=utf-8";
			case "js", "mjs" -> "text/javascript; charset=utf-8";
			case "json" -> "application/json";
			case "xml" -> "application/xml";
			case "yaml", "yml" -> "application/yaml";
			case "pdf" -> "application/pdf";
			case "zip" -> "application/zip";
			case "gz", "tgz" -> "application/gzip";
			case "tar" -> "application/x-tar";
			case "png" -> "image/png";
			case "jpg", "jpeg" -> "image/jpeg";
			case "gif" -> "image/gif";
			case "webp" -> "image/webp";
			case "svg" -> "image/svg+xml";
			case "mp4" -> "video/mp4";
			case "mp3" -> "audio/mpeg";
			default -> "application/octet-stream";
		};
	}

	private static String describe(String awsProfileName) {
		return awsProfileName == null || awsProfileName.isBlank() ? "default" : awsProfileName;
	}
}
