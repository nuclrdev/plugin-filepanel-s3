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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the response readers against the document shapes S3 and its compatible services actually
 * return — including the awkward ones that a naive reader gets wrong.
 */
class S3XmlTest {

	private static ByteArrayInputStream stream(String xml) {
		return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("A listing splits objects from common prefixes")
	void parsesObjectsAndFolders() throws Exception {

		S3Xml.ListPage page = S3Xml.parseListObjects(stream("""
				<?xml version="1.0" encoding="UTF-8"?>
				<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
				  <Name>my-bucket</Name>
				  <Prefix>logs/</Prefix>
				  <IsTruncated>false</IsTruncated>
				  <Contents>
				    <Key>logs/app.log</Key>
				    <LastModified>2026-03-14T10:15:30.000Z</LastModified>
				    <ETag>&quot;9bb58f26192e4ba00f01e2e7b136bbd8&quot;</ETag>
				    <Size>4096</Size>
				    <StorageClass>STANDARD</StorageClass>
				  </Contents>
				  <CommonPrefixes><Prefix>logs/2026/</Prefix></CommonPrefixes>
				</ListBucketResult>
				"""), "logs/");

		assertEquals(2, page.entries().size());
		assertFalse(page.hasMore());

		S3ObjectEntry object = page.entries().get(0);
		assertEquals("app.log", object.name());
		assertEquals("logs/app.log", object.key());
		assertFalse(object.folder());
		assertEquals(4096, object.size());
		assertEquals("STANDARD", object.storageClass());
		assertEquals("9bb58f26192e4ba00f01e2e7b136bbd8", object.etag(), "the ETag's quotes are not part of it");

		S3ObjectEntry folder = page.entries().get(1);
		assertEquals("2026/", folder.name());
		assertEquals("logs/2026/", folder.key());
		assertTrue(folder.folder());
	}

	@Test
	@DisplayName("The prefix that was requested is not mistaken for a folder of its own")
	void requestEchoIsNotAFolder() throws Exception {

		// <Prefix> appears twice in a listing: once echoing the request, once per common prefix.
		// Treating the echo as a folder would put every listing inside itself.
		S3Xml.ListPage page = S3Xml.parseListObjects(stream("""
				<ListBucketResult>
				  <Name>my-bucket</Name>
				  <Prefix>logs/</Prefix>
				  <CommonPrefixes><Prefix>logs/2026/</Prefix></CommonPrefixes>
				</ListBucketResult>
				"""), "logs/");

		assertEquals(1, page.entries().size());
		assertEquals("logs/2026/", page.entries().get(0).key());
	}

	@Test
	@DisplayName("A zero-byte placeholder key is shown as a folder, not an empty file")
	void placeholderKeyBecomesAFolder() throws Exception {

		// This is what the AWS console writes when you "create a folder"; listing it as a 0-byte
		// file would be technically true and completely unhelpful.
		S3Xml.ListPage page = S3Xml.parseListObjects(stream("""
				<ListBucketResult>
				  <Prefix></Prefix>
				  <Contents>
				    <Key>drafts/</Key>
				    <Size>0</Size>
				    <StorageClass>STANDARD</StorageClass>
				  </Contents>
				</ListBucketResult>
				"""), "");

		assertEquals(1, page.entries().size());
		assertTrue(page.entries().get(0).folder());
		assertEquals("drafts/", page.entries().get(0).name());
	}

	@Test
	@DisplayName("A folder's own placeholder is not listed inside itself")
	void ownPlaceholderIsSkipped() throws Exception {

		S3Xml.ListPage page = S3Xml.parseListObjects(stream("""
				<ListBucketResult>
				  <Prefix>drafts/</Prefix>
				  <Contents><Key>drafts/</Key><Size>0</Size></Contents>
				  <Contents><Key>drafts/note.txt</Key><Size>12</Size></Contents>
				</ListBucketResult>
				"""), "drafts/");

		assertEquals(1, page.entries().size());
		assertEquals("note.txt", page.entries().get(0).name());
	}

	@Test
	@DisplayName("A truncated listing reports its continuation token")
	void continuationTokenIsRead() throws Exception {

		S3Xml.ListPage page = S3Xml.parseListObjects(stream("""
				<ListBucketResult>
				  <Prefix></Prefix>
				  <IsTruncated>true</IsTruncated>
				  <NextContinuationToken>1ueGcxLPRx1Tr</NextContinuationToken>
				  <Contents><Key>a.txt</Key><Size>1</Size></Contents>
				</ListBucketResult>
				"""), "");

		assertTrue(page.hasMore());
		assertEquals("1ueGcxLPRx1Tr", page.nextContinuationToken());
	}

	@Test
	@DisplayName("A listing parses when the service declares no namespace")
	void namespaceIsIgnored() throws Exception {

		// Several S3-compatible services omit the namespace declaration; matching on the qualified
		// name would silently return nothing for them.
		S3Xml.ListPage page = S3Xml.parseListObjects(stream(
				"<ListBucketResult><Prefix></Prefix>"
						+ "<Contents><Key>x.bin</Key><Size>7</Size></Contents></ListBucketResult>"), "");

		assertEquals(1, page.entries().size());
		assertEquals("x.bin", page.entries().get(0).name());
	}

	@Test
	@DisplayName("Bucket listings carry names, creation dates and regions")
	void parsesBuckets() throws Exception {

		List<S3BucketEntry> buckets = S3Xml.parseListBuckets(stream("""
				<ListAllMyBucketsResult>
				  <Owner><ID>abc</ID><DisplayName>owner</DisplayName></Owner>
				  <Buckets>
				    <Bucket>
				      <Name>photos</Name>
				      <CreationDate>2024-01-02T03:04:05.000Z</CreationDate>
				      <BucketRegion>eu-west-1</BucketRegion>
				    </Bucket>
				    <Bucket><Name>backups</Name><CreationDate>2025-06-07T08:09:10.000Z</CreationDate></Bucket>
				  </Buckets>
				</ListAllMyBucketsResult>
				"""));

		assertEquals(2, buckets.size());
		assertEquals("photos", buckets.get(0).name());
		assertEquals("eu-west-1", buckets.get(0).region());
		assertTrue(buckets.get(0).created() > 0);
		assertNull(buckets.get(1).region(), "a bucket listing need not report a region");
	}

	@Test
	@DisplayName("The owner's DisplayName is not mistaken for a bucket name")
	void ownerIsNotABucket() throws Exception {

		// <Name> appears inside <Owner> too on some services; a reader that matches on the element
		// name alone invents a bucket out of the account owner.
		List<S3BucketEntry> buckets = S3Xml.parseListBuckets(stream("""
				<ListAllMyBucketsResult>
				  <Owner><ID>abc</ID><Name>Account Owner</Name></Owner>
				  <Buckets><Bucket><Name>real-bucket</Name></Bucket></Buckets>
				</ListAllMyBucketsResult>
				"""));

		assertEquals(1, buckets.size());
		assertEquals("real-bucket", buckets.get(0).name());
	}

	@Test
	@DisplayName("An empty LocationConstraint means us-east-1")
	void emptyLocationMeansUsEast1() {
		assertEquals("us-east-1", S3Xml.parseBucketLocation(
				"<LocationConstraint xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>"
						.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("The legacy EU location is normalised to eu-west-1")
	void legacyEuLocationIsNormalised() {
		assertEquals("eu-west-1", S3Xml.parseBucketLocation(
				"<LocationConstraint>EU</LocationConstraint>".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("A named region comes back as itself")
	void namedLocationIsReturned() {
		assertEquals("ap-southeast-2", S3Xml.parseBucketLocation(
				"<LocationConstraint>ap-southeast-2</LocationConstraint>".getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("An error document yields its code and message")
	void parsesErrorDocument() {

		S3Xml.ErrorDocument error = S3Xml.parseError("""
				<?xml version="1.0" encoding="UTF-8"?>
				<Error>
				  <Code>NoSuchKey</Code>
				  <Message>The specified key does not exist.</Message>
				  <Key>missing.txt</Key>
				  <RequestId>656c76696e</RequestId>
				</Error>
				""".getBytes(StandardCharsets.UTF_8));

		assertNotNull(error);
		assertEquals("NoSuchKey", error.code());
		assertEquals("The specified key does not exist.", error.message());
	}

	@Test
	@DisplayName("A body that is not an error document reads as no error")
	void nonErrorBodiesAreNotErrors() {
		assertNull(S3Xml.parseError(new byte[0]));
		assertNull(S3Xml.parseError("<CopyObjectResult><ETag>\"abc\"</ETag></CopyObjectResult>"
				.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	@DisplayName("An error hidden inside a successful completion body is still found")
	void errorInsideSuccessBodyIsFound() {

		// Completing a multipart upload can answer HTTP 200 and carry a failure in the body; a
		// client that trusts the status alone reports a broken upload as finished.
		S3Xml.ErrorDocument error = S3Xml.parseError("""
				<Error><Code>InternalError</Code><Message>We encountered an internal error.</Message></Error>
				""".getBytes(StandardCharsets.UTF_8));

		assertNotNull(error);
		assertEquals("InternalError", error.code());
	}

	@Test
	@DisplayName("The multipart completion body lists parts in order with quoted ETags")
	void buildsCompletionBody() {

		String xml = new String(S3Xml.completeMultipartBody(List.of("aaa", "bbb")), StandardCharsets.UTF_8);

		assertEquals("<CompleteMultipartUpload>"
				+ "<Part><PartNumber>1</PartNumber><ETag>&quot;aaa&quot;</ETag></Part>"
				+ "<Part><PartNumber>2</PartNumber><ETag>&quot;bbb&quot;</ETag></Part>"
				+ "</CompleteMultipartUpload>", xml);
	}

	@Test
	@DisplayName("Delete bodies escape the XML characters a key may legally contain")
	void deleteBodyEscapesKeys() {

		String xml = new String(S3Xml.deleteObjectsBody(List.of("a&b.txt", "<odd>.txt")), StandardCharsets.UTF_8);

		assertTrue(xml.contains("<Key>a&amp;b.txt</Key>"));
		assertTrue(xml.contains("<Key>&lt;odd&gt;.txt</Key>"));
		assertTrue(xml.contains("<Quiet>true</Quiet>"));
	}

	@Test
	@DisplayName("An unparseable timestamp does not break a listing")
	void badTimestampIsTolerated() throws Exception {

		S3Xml.ListPage page = S3Xml.parseListObjects(stream(
				"<ListBucketResult><Prefix></Prefix><Contents><Key>a</Key>"
						+ "<LastModified>not-a-date</LastModified><Size>1</Size></Contents></ListBucketResult>"), "");

		assertEquals(1, page.entries().size());
		assertEquals(-1, page.entries().get(0).lastModified());
	}

	@Test
	@DisplayName("Keys and prefixes reduce to their last segment")
	void lastSegment() {
		assertEquals("c.txt", S3ObjectEntry.lastSegment("a/b/c.txt"));
		assertEquals("b", S3ObjectEntry.lastSegment("a/b/"));
		assertEquals("solo.txt", S3ObjectEntry.lastSegment("solo.txt"));
		assertEquals("", S3ObjectEntry.lastSegment(""));
	}
}
