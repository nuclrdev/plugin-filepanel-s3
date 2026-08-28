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
package dev.nuclr.plugin.core.panel.s3.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers wildcard matching for the find dialog.
 *
 * <p>The trap here is that the characters users type in file names — dots above all — are also
 * regular-expression metacharacters. A pattern like {@code report.2026.csv} must match that one file
 * and not every name with any character in those positions.
 */
class S3FindRequestTest {

	private static S3FindRequest request(String pattern, boolean caseSensitive) {
		return new S3FindRequest("profile", "bucket", "prefix/", pattern, true, caseSensitive);
	}

	@Test
	@DisplayName("A star matches any run of characters")
	void starMatchesAnything() {

		Pattern pattern = request("*.log", true).compilePattern();

		assertTrue(S3FindRequest.matches(pattern, "app.log"));
		assertTrue(S3FindRequest.matches(pattern, "a.b.log"));
		assertTrue(S3FindRequest.matches(pattern, ".log"));
		assertFalse(S3FindRequest.matches(pattern, "app.log.gz"));
		assertFalse(S3FindRequest.matches(pattern, "applog"));
	}

	@Test
	@DisplayName("A question mark matches exactly one character")
	void questionMarkMatchesOne() {

		Pattern pattern = request("log?.txt", true).compilePattern();

		assertTrue(S3FindRequest.matches(pattern, "log1.txt"));
		assertFalse(S3FindRequest.matches(pattern, "log.txt"));
		assertFalse(S3FindRequest.matches(pattern, "log12.txt"));
	}

	@Test
	@DisplayName("Dots are literal, not wildcards")
	void dotsAreLiteral() {

		Pattern pattern = request("report.2026.csv", true).compilePattern();

		assertTrue(S3FindRequest.matches(pattern, "report.2026.csv"));
		assertFalse(S3FindRequest.matches(pattern, "reportX2026Ycsv"),
				"an unescaped dot would match any character here");
	}

	@Test
	@DisplayName("Regular-expression metacharacters in a name are matched literally")
	void metacharactersAreEscaped() {

		Pattern pattern = request("data(1)+[final].txt", true).compilePattern();

		assertTrue(S3FindRequest.matches(pattern, "data(1)+[final].txt"));
		assertFalse(S3FindRequest.matches(pattern, "data1final.txt"));
	}

	@Test
	@DisplayName("Case sensitivity is honoured in both directions")
	void caseSensitivity() {

		assertFalse(S3FindRequest.matches(request("readme*", true).compilePattern(), "README.md"));
		assertTrue(S3FindRequest.matches(request("readme*", false).compilePattern(), "README.md"));
	}

	@Test
	@DisplayName("A blank or bare-star pattern compiles to no filter at all")
	void matchEverythingNeedsNoPattern() {

		assertNull(request("*", true).compilePattern());
		assertNull(request("", true).compilePattern());
		assertNull(request("   ", true).compilePattern());
		assertNull(request(null, true).compilePattern());

		// A null pattern is the "match everything" signal, so matches() must accept it.
		assertTrue(S3FindRequest.matches(null, "anything at all"));
	}

	@Test
	@DisplayName("A star in the middle spans any depth of name")
	void starInTheMiddle() {

		Pattern pattern = request("app-*-2026.log", false).compilePattern();

		assertTrue(S3FindRequest.matches(pattern, "app-server-2026.log"));
		assertTrue(S3FindRequest.matches(pattern, "app--2026.log"));
		assertFalse(S3FindRequest.matches(pattern, "app-server-2025.log"));
	}

	@Test
	@DisplayName("The title and scope describe what is being searched")
	void describesItself() {

		S3FindRequest recursive = new S3FindRequest("p", "my-bucket", "logs/", "*.log", true, false);
		assertEquals("Find: *.log in s3://my-bucket/logs/", recursive.title());
		assertTrue(recursive.describeScope().contains("and below"));

		S3FindRequest shallow = new S3FindRequest("p", "my-bucket", "logs/", "*.log", false, true);
		assertTrue(shallow.describeScope().contains("this level only"));
		assertTrue(shallow.describeScope().contains("case sensitive"));
	}

	@Test
	@DisplayName("The glob translation escapes what a regular expression would otherwise read")
	void globTranslation() {
		assertEquals(".*\\.log", S3FindRequest.globToRegex("*.log"));
		assertEquals("a.b", S3FindRequest.globToRegex("a?b"));
		assertEquals("\\(x\\)", S3FindRequest.globToRegex("(x)"));
	}
}
