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

import java.util.regex.Pattern;

/**
 * One "Find files" request: a filename pattern matched against the objects under a prefix.
 *
 * <p>The search is by <b>name only</b>. S3's listing returns keys and metadata, never content, so a
 * content search would mean downloading every object in the prefix — the panel does not pretend to
 * offer one.
 *
 * @param profileId     the connection profile to search through
 * @param bucket        the bucket to search
 * @param prefix        where to start, ending in {@code /}, or {@code ""} for the bucket root
 * @param namePattern   a shell-style wildcard using {@code *} and {@code ?}; blank or {@code *} matches everything
 * @param recursive     whether to search the whole subtree or only the immediate level
 * @param caseSensitive whether the name match respects case
 */
public record S3FindRequest(
		String profileId,
		String bucket,
		String prefix,
		String namePattern,
		boolean recursive,
		boolean caseSensitive) {

	/**
	 * The title shown on the results window and on a results panel.
	 *
	 * @return the title
	 */
	public String title() {
		String pattern = namePattern == null || namePattern.isBlank() ? "*" : namePattern;
		return "Find: " + pattern + " in s3://" + bucket + '/' + prefix;
	}

	/**
	 * Compile the name pattern into a matcher.
	 *
	 * @return the compiled pattern, or {@code null} when the pattern matches everything
	 */
	public Pattern compilePattern() {
		if (namePattern == null || namePattern.isBlank() || namePattern.equals("*")) {
			return null;
		}
		return Pattern.compile(globToRegex(namePattern), caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
	}

	/**
	 * Whether a name matches this request's pattern.
	 *
	 * @param pattern the compiled pattern, or {@code null} to match everything
	 * @param name    the candidate name
	 * @return {@code true} when it matches
	 */
	public static boolean matches(Pattern pattern, String name) {
		return pattern == null || pattern.matcher(name).matches();
	}

	/**
	 * Translate a shell-style wildcard into a regular expression, escaping everything else so a
	 * pattern like {@code report.2026.*} means what a user expects rather than treating the dots as
	 * wildcards of their own.
	 *
	 * @param glob the wildcard pattern
	 * @return the equivalent regular expression
	 */
	static String globToRegex(String glob) {
		var regex = new StringBuilder(glob.length() + 8);
		for (int i = 0; i < glob.length(); i++) {
			char character = glob.charAt(i);
			switch (character) {
				case '*' -> regex.append(".*");
				case '?' -> regex.append('.');
				case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' ->
						regex.append('\\').append(character);
				default -> regex.append(character);
			}
		}
		return regex.toString();
	}

	/**
	 * A short description of the scope, for the results window's status line.
	 *
	 * @return the scope description
	 */
	public String describeScope() {
		return "s3://" + bucket + '/' + prefix
				+ (recursive ? " and below" : " (this level only)")
				+ (caseSensitive ? ", case sensitive" : "");
	}
}
