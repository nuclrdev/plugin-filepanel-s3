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
package dev.nuclr.plugin.core.panel.s3.service;

/**
 * How one step of a transfer ended.
 *
 * <p>{@link #CANCELLED} is distinct from {@link #FAILED} on purpose: cancelling stops the whole
 * operation and is not worth reporting back to the user, who asked for it, while a failure is
 * recorded and the rest of the transfer carries on.
 */
enum Outcome {

	/** The step finished, or had nothing to do. */
	OK,

	/** The user cancelled; the caller should stop rather than continue with the next item. */
	CANCELLED,

	/** The step failed and has already been recorded in the failure list. */
	FAILED
}
