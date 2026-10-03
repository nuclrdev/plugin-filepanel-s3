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
package dev.nuclr.plugin.core.panel.s3.actions;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Just enough of S3's REST API, path-style, for the actions to run against: list
 * buckets, ListObjectsV2 with delimiter and paging, HEAD, GET, PUT and server-side
 * copy. Objects live in memory; signatures are not checked.
 */
final class FakeS3 implements AutoCloseable {

	static final Instant MODIFIED = Instant.parse("2026-10-03T10:00:00Z");

	/** bucket -> key -> content */
	final Map<String, NavigableMap<String, byte[]>> buckets = new ConcurrentHashMap<>();
	/** Every x-amz-copy-source the server was asked to copy from. */
	final List<String> copies = new CopyOnWriteArrayList<>();
	/** Every PUT that uploaded content, as bucket/key. */
	final List<String> uploads = new CopyOnWriteArrayList<>();

	private final HttpServer server;

	FakeS3() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	String endpoint() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	FakeS3 bucket(String name) {
		buckets.computeIfAbsent(name, n -> new ConcurrentSkipListMap<>());
		return this;
	}

	FakeS3 put(String bucket, String key, String content) {
		return put(bucket, key, content.getBytes(StandardCharsets.UTF_8));
	}

	FakeS3 put(String bucket, String key, byte[] content) {
		bucket(bucket);
		buckets.get(bucket).put(key, content);
		return this;
	}

	String text(String bucket, String key) {
		byte[] content = buckets.getOrDefault(bucket, new ConcurrentSkipListMap<>()).get(key);
		return content == null ? null : new String(content, StandardCharsets.UTF_8);
	}

	@Override
	public void close() {
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getPath();
			String method = exchange.getRequestMethod();
			Map<String, String> query = query(exchange.getRequestURI().getRawQuery());

			if (path.equals("/")) {
				listBuckets(exchange);
				return;
			}
			String rest = path.substring(1);
			int slash = rest.indexOf('/');
			String bucket = slash < 0 ? rest : rest.substring(0, slash);
			String key = slash < 0 ? "" : rest.substring(slash + 1);
			NavigableMap<String, byte[]> objects = buckets.get(bucket);
			if (objects == null) {
				error(exchange, 404, "NoSuchBucket", bucket, !method.equals("HEAD"));
				return;
			}
			if (key.isEmpty()) {
				listObjects(exchange, bucket, objects, query);
				return;
			}
			switch (method) {
				case "HEAD" -> head(exchange, objects.get(key));
				case "GET" -> get(exchange, objects.get(key), key);
				case "PUT" -> put(exchange, bucket, key);
				default -> error(exchange, 405, "MethodNotAllowed", method, true);
			}
		}
	}

	private void listBuckets(HttpExchange exchange) throws IOException {
		var xml = new StringBuilder("<ListAllMyBucketsResult><Buckets>");
		for (String name : new java.util.TreeSet<>(buckets.keySet())) {
			xml.append("<Bucket><Name>").append(escape(name)).append("</Name><CreationDate>2026-01-01T00:00:00.000Z")
					.append("</CreationDate></Bucket>");
		}
		xml.append("</Buckets></ListAllMyBucketsResult>");
		send(exchange, 200, xml.toString());
	}

	private void listObjects(HttpExchange exchange, String bucket, NavigableMap<String, byte[]> objects,
			Map<String, String> query) throws IOException {
		String prefix = query.getOrDefault("prefix", "");
		String delimiter = query.get("delimiter");
		int maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000"));
		int start = Integer.parseInt(query.getOrDefault("continuation-token", "0"));

		// Objects and common prefixes in key order, as S3 returns them.
		var entries = new ArrayList<String[]>();
		String lastPrefix = null;
		for (var object : objects.tailMap(prefix, true).entrySet()) {
			String key = object.getKey();
			if (!key.startsWith(prefix)) {
				break;
			}
			int cut = delimiter == null ? -1 : key.indexOf(delimiter, prefix.length());
			if (cut >= 0) {
				String common = key.substring(0, cut + delimiter.length());
				if (!common.equals(lastPrefix)) {
					entries.add(new String[] { "prefix", common });
					lastPrefix = common;
				}
			} else {
				entries.add(new String[] { "object", key, String.valueOf(object.getValue().length) });
			}
		}

		int end = Math.min(entries.size(), start + maxKeys);
		var xml = new StringBuilder("<ListBucketResult><Name>").append(escape(bucket)).append("</Name><Prefix>")
				.append(escape(prefix)).append("</Prefix><IsTruncated>").append(end < entries.size())
				.append("</IsTruncated>");
		if (end < entries.size()) {
			xml.append("<NextContinuationToken>").append(end).append("</NextContinuationToken>");
		}
		for (String[] entry : entries.subList(start, end)) {
			if (entry[0].equals("prefix")) {
				xml.append("<CommonPrefixes><Prefix>").append(escape(entry[1])).append("</Prefix></CommonPrefixes>");
			} else {
				xml.append("<Contents><Key>").append(escape(entry[1])).append("</Key><LastModified>")
						.append(MODIFIED).append("</LastModified><ETag>\"etag\"</ETag><Size>").append(entry[2])
						.append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
			}
		}
		xml.append("</ListBucketResult>");
		send(exchange, 200, xml.toString());
	}

	private void head(HttpExchange exchange, byte[] content) throws IOException {
		if (content == null) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}
		exchange.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
		exchange.getResponseHeaders().set("Last-Modified",
				DateTimeFormatter.RFC_1123_DATE_TIME.format(MODIFIED.atZone(ZoneOffset.UTC)));
		exchange.getResponseHeaders().set("ETag", "\"etag\"");
		exchange.sendResponseHeaders(200, -1);
	}

	private void get(HttpExchange exchange, byte[] content, String key) throws IOException {
		if (content == null) {
			error(exchange, 404, "NoSuchKey", key, true);
			return;
		}
		exchange.sendResponseHeaders(200, content.length == 0 ? -1 : content.length);
		exchange.getResponseBody().write(content);
	}

	private void put(HttpExchange exchange, String bucket, String key) throws IOException {
		String copySource = exchange.getRequestHeaders().getFirst("x-amz-copy-source");
		if (copySource != null) {
			String source = URLDecoder.decode(copySource.replace("+", "%2B"), StandardCharsets.UTF_8);
			copies.add(source);
			String sourcePath = source.startsWith("/") ? source.substring(1) : source;
			int slash = sourcePath.indexOf('/');
			byte[] content = buckets.getOrDefault(sourcePath.substring(0, slash), new ConcurrentSkipListMap<>())
					.get(sourcePath.substring(slash + 1));
			if (content == null) {
				error(exchange, 404, "NoSuchKey", source, true);
				return;
			}
			buckets.get(bucket).put(key, content);
			send(exchange, 200, "<CopyObjectResult><ETag>\"etag\"</ETag></CopyObjectResult>");
			return;
		}
		buckets.get(bucket).put(key, exchange.getRequestBody().readAllBytes());
		uploads.add(bucket + "/" + key);
		exchange.getResponseHeaders().set("ETag", "\"etag\"");
		exchange.sendResponseHeaders(200, -1);
	}

	private static void error(HttpExchange exchange, int status, String code, String resource, boolean body)
			throws IOException {
		if (!body) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		send(exchange, status, "<Error><Code>" + code + "</Code><Message>" + escape(resource) + "</Message></Error>");
	}

	private static void send(HttpExchange exchange, int status, String xml) throws IOException {
		byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/xml");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	private static Map<String, String> query(String raw) {
		var query = new HashMap<String, String>();
		if (raw == null || raw.isEmpty()) {
			return query;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String name = eq < 0 ? pair : pair.substring(0, eq);
			String value = eq < 0 ? "" : pair.substring(eq + 1);
			query.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
					URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8));
		}
		return query;
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
