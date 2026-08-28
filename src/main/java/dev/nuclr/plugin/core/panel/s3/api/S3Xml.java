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
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import lombok.extern.slf4j.Slf4j;

/**
 * Readers for the handful of XML documents the S3 REST API returns.
 *
 * <p>Pull-parsed with the JDK's own StAX rather than a mapping library, which keeps the plugin
 * dependency-free and lets a listing of a hundred thousand keys stream through in constant memory.
 * External entity resolution and DTD support are switched off on the factory: these documents come
 * off the network, and an XML parser that fetches whatever a document tells it to is an easy way to
 * turn a hostile endpoint into a file read on the user's machine.
 *
 * <p>Element names are matched by local name, ignoring the namespace, because S3-compatible servers
 * vary in whether they declare {@code http://s3.amazonaws.com/doc/2006-03-01/} at all.
 */
@Slf4j
public final class S3Xml {

	private static final XMLInputFactory FACTORY = createFactory();

	private S3Xml() {}

	private static XMLInputFactory createFactory() {
		XMLInputFactory factory = XMLInputFactory.newInstance();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
		factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
		return factory;
	}

	/**
	 * One page of a bucket listing.
	 *
	 * @param entries               the objects and folders on this page, in listing order
	 * @param nextContinuationToken the token to pass to fetch the next page, or {@code null} when done
	 */
	public record ListPage(List<S3ObjectEntry> entries, String nextContinuationToken) {

		/**
		 * Whether more pages remain.
		 *
		 * @return {@code true} when a continuation token was returned
		 */
		public boolean hasMore() {
			return nextContinuationToken != null && !nextContinuationToken.isBlank();
		}
	}

	/**
	 * An error document, which S3 returns both as a failure body and — for a completed multipart
	 * upload — inside an otherwise successful response.
	 *
	 * @param code    the S3 error code, such as {@code NoSuchKey}
	 * @param message the human-readable message
	 */
	public record ErrorDocument(String code, String message) {}

	/**
	 * Parse one page of a {@code ListObjectsV2} response.
	 *
	 * <p>Common prefixes become folder entries and {@code Contents} become object entries, except
	 * for the zero-byte placeholder whose key is the listing prefix itself: that is the marker the
	 * console writes for an empty folder, and showing it as a file inside its own folder would be
	 * nonsense.
	 *
	 * @param body   the response body
	 * @param prefix the prefix that was listed, so its own placeholder can be skipped
	 * @return the entries and any continuation token
	 * @throws XMLStreamException if the document is not readable XML
	 */
	public static ListPage parseListObjects(InputStream body, String prefix) throws XMLStreamException {

		var entries = new ArrayList<S3ObjectEntry>();
		String continuationToken = null;

		XMLStreamReader reader = FACTORY.createXMLStreamReader(body);
		try {
			String key = null;
			String storageClass = null;
			String etag = null;
			long size = -1;
			long lastModified = -1;
			boolean inContents = false;

			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					String name = reader.getLocalName();
					switch (name) {
						case "Contents" -> {
							inContents = true;
							key = null;
							storageClass = null;
							etag = null;
							size = -1;
							lastModified = -1;
						}
						case "Key" -> {
							if (inContents) {
								key = reader.getElementText();
							}
						}
						case "Size" -> size = parseLong(reader.getElementText(), -1);
						case "LastModified" -> lastModified = parseTimestamp(reader.getElementText());
						case "StorageClass" -> storageClass = reader.getElementText();
						case "ETag" -> etag = unquote(reader.getElementText());
						case "Prefix" -> {
							// Prefix appears twice: once as the request echo, once per common prefix.
							// Only the one nested inside CommonPrefixes is a folder, and that is the
							// only one that differs from the requested prefix.
							String value = reader.getElementText();
							if (value != null && !value.isEmpty() && !value.equals(prefix)) {
								entries.add(S3ObjectEntry.folder(value));
							}
						}
						case "NextContinuationToken" -> continuationToken = reader.getElementText();
						default -> { /* every other element is metadata the panel does not show */ }
					}
				} else if (event == XMLStreamConstants.END_ELEMENT && "Contents".equals(reader.getLocalName())) {
					inContents = false;
					if (key != null && !key.isEmpty() && !key.equals(prefix)) {
						if (key.endsWith("/")) {
							// A zero-byte placeholder object standing in for a folder.
							entries.add(S3ObjectEntry.folder(key));
						} else {
							entries.add(S3ObjectEntry.object(key, size, lastModified, storageClass, etag));
						}
					}
				}
			}
		} finally {
			closeQuietly(reader);
		}

		return new ListPage(List.copyOf(entries), continuationToken);
	}

	/**
	 * Parse a {@code ListBuckets} response.
	 *
	 * @param body the response body
	 * @return the buckets, in the order returned
	 * @throws XMLStreamException if the document is not readable XML
	 */
	public static List<S3BucketEntry> parseListBuckets(InputStream body) throws XMLStreamException {

		var buckets = new ArrayList<S3BucketEntry>();
		XMLStreamReader reader = FACTORY.createXMLStreamReader(body);
		try {
			String name = null;
			String region = null;
			long created = -1;
			boolean inBucket = false;

			while (reader.hasNext()) {
				int event = reader.next();
				if (event == XMLStreamConstants.START_ELEMENT) {
					switch (reader.getLocalName()) {
						case "Bucket" -> {
							inBucket = true;
							name = null;
							region = null;
							created = -1;
						}
						case "Name" -> {
							if (inBucket) {
								name = reader.getElementText();
							}
						}
						case "CreationDate" -> created = parseTimestamp(reader.getElementText());
						case "BucketRegion" -> region = reader.getElementText();
						default -> { /* Owner and friends are not shown */ }
					}
				} else if (event == XMLStreamConstants.END_ELEMENT && "Bucket".equals(reader.getLocalName())) {
					inBucket = false;
					if (name != null && !name.isBlank()) {
						buckets.add(new S3BucketEntry(name, created, region));
					}
				}
			}
		} finally {
			closeQuietly(reader);
		}
		return List.copyOf(buckets);
	}

	/**
	 * Read the region out of a {@code GetBucketLocation} response.
	 *
	 * <p>The historical quirk: {@code us-east-1} is reported as an empty {@code LocationConstraint},
	 * and {@code EU} is the legacy spelling of {@code eu-west-1}.
	 *
	 * @param body the response body
	 * @return the region name
	 */
	public static String parseBucketLocation(byte[] body) {
		String value = firstElementText(body, "LocationConstraint");
		if (value == null || value.isBlank()) {
			return "us-east-1";
		}
		return "EU".equals(value) ? "eu-west-1" : value.trim();
	}

	/**
	 * Read the upload id out of a {@code CreateMultipartUpload} response.
	 *
	 * @param body the response body
	 * @return the upload id, or {@code null} when the document does not carry one
	 */
	public static String parseUploadId(byte[] body) {
		return firstElementText(body, "UploadId");
	}

	/**
	 * Parse an error document, if the body is one.
	 *
	 * <p>Used for failure bodies and also for successful multipart completions: S3 can answer that
	 * call with HTTP 200 and an error document inside, so a client that only checks the status code
	 * will happily report a broken upload as finished.
	 *
	 * @param body the response body, possibly empty or not XML at all
	 * @return the error, or {@code null} when the body is not an error document
	 */
	public static ErrorDocument parseError(byte[] body) {

		if (body == null || body.length == 0) {
			return null;
		}
		String text = new String(body, StandardCharsets.UTF_8);
		if (!text.contains("<Error")) {
			return null;
		}

		String code = null;
		String message = null;
		try {
			XMLStreamReader reader = FACTORY.createXMLStreamReader(new StringReader(text));
			try {
				while (reader.hasNext()) {
					if (reader.next() == XMLStreamConstants.START_ELEMENT) {
						switch (reader.getLocalName()) {
							case "Code" -> code = reader.getElementText();
							case "Message" -> message = reader.getElementText();
							default -> { /* Resource, RequestId and friends add nothing for the user */ }
						}
					}
				}
			} finally {
				closeQuietly(reader);
			}
		} catch (XMLStreamException e) {
			log.debug("Could not parse an S3 error document: {}", e.getMessage());
			return null;
		}

		return code == null && message == null ? null : new ErrorDocument(code, message);
	}

	/**
	 * Build the {@code CompleteMultipartUpload} request body.
	 *
	 * @param etags the part entity tags, in part order starting at part 1
	 * @return the XML document as UTF-8 bytes
	 */
	public static byte[] completeMultipartBody(List<String> etags) {
		var xml = new StringBuilder("<CompleteMultipartUpload>");
		for (int i = 0; i < etags.size(); i++) {
			xml.append("<Part><PartNumber>").append(i + 1).append("</PartNumber><ETag>")
					.append(escape(quote(etags.get(i))))
					.append("</ETag></Part>");
		}
		xml.append("</CompleteMultipartUpload>");
		return xml.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Build a multi-object {@code Delete} request body.
	 *
	 * @param keys the object keys to delete
	 * @return the XML document as UTF-8 bytes
	 */
	public static byte[] deleteObjectsBody(List<String> keys) {
		var xml = new StringBuilder("<Delete><Quiet>true</Quiet>");
		for (String key : keys) {
			xml.append("<Object><Key>").append(escape(key)).append("</Key></Object>");
		}
		xml.append("</Delete>");
		return xml.toString().getBytes(StandardCharsets.UTF_8);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/** The text of the first element with this local name, or {@code null} if there is none. */
	private static String firstElementText(byte[] body, String localName) {
		if (body == null || body.length == 0) {
			return null;
		}
		try {
			XMLStreamReader reader = FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
			try {
				while (reader.hasNext()) {
					if (reader.next() == XMLStreamConstants.START_ELEMENT
							&& localName.equals(reader.getLocalName())) {
						return reader.getElementText();
					}
				}
			} finally {
				closeQuietly(reader);
			}
		} catch (XMLStreamException e) {
			log.debug("Could not read <{}> from an S3 response: {}", localName, e.getMessage());
		}
		return null;
	}

	/** ISO-8601 to epoch milliseconds, or {@code -1} when the value is missing or unparseable. */
	static long parseTimestamp(String value) {
		if (value == null || value.isBlank()) {
			return -1;
		}
		try {
			return Instant.parse(value.trim()).toEpochMilli();
		} catch (DateTimeParseException e) {
			log.debug("Unrecognised S3 timestamp [{}]", value);
			return -1;
		}
	}

	private static long parseLong(String value, long fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** Entity tags come back quoted; the quotes are not part of the value. */
	static String unquote(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed;
	}

	/** Put the quotes back on an entity tag for a request that echoes one. */
	private static String quote(String etag) {
		if (etag == null) {
			return "\"\"";
		}
		String trimmed = etag.trim();
		return trimmed.startsWith("\"") ? trimmed : '"' + trimmed + '"';
	}

	/** Escape the five XML entities, so a key containing {@code &} or {@code <} survives the round trip. */
	static String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private static void closeQuietly(XMLStreamReader reader) {
		try {
			reader.close();
		} catch (XMLStreamException e) {
			log.debug("Could not close an XML reader: {}", e.getMessage());
		}
	}
}
