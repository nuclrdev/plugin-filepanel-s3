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

/**
 * One bucket in the account, as reported by a bucket listing.
 *
 * @param name    the bucket name
 * @param created creation timestamp in epoch milliseconds, or {@code -1} when the endpoint omits it
 * @param region  the region the bucket lives in, when the endpoint reports it; otherwise {@code null}
 */
public record S3BucketEntry(String name, long created, String region) {
}
