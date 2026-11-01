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
package dev.nuclr.plugin.core.panel.s3.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import dev.nuclr.platform.NuclrCredentialException;
import dev.nuclr.platform.NuclrCredentialStore;

/**
 * The OS credential store, as the host's contract describes it, in memory, with the entry size
 * Windows allows. {@link #failWrites} stands in for a locked or missing keychain.
 */
final class InMemoryCredentialStore implements NuclrCredentialStore {

	final Map<String, String> entries = new LinkedHashMap<>();

	/** Refuse every write whose key ends with this, or every write when empty; {@code null} for none. */
	String failWrites;

	@Override
	public Optional<String> get(String key) {
		return Optional.ofNullable(entries.get(key));
	}

	@Override
	public void set(String key, String secret) throws NuclrCredentialException {
		if (secret == null || secret.isEmpty()) {
			throw new IllegalArgumentException("A nonempty secret is required.");
		}
		// Windows Credential Manager: UTF-16 in at most 2560 bytes.
		if (secret.length() > 1280) {
			throw new NuclrCredentialException(NuclrCredentialException.Reason.ACCESS_FAILED, "Too long for Windows.");
		}
		if (failWrites != null && key.endsWith(failWrites)) {
			throw new NuclrCredentialException(NuclrCredentialException.Reason.ACCESS_FAILED, "The keychain is locked.");
		}
		entries.put(key, secret);
	}

	@Override
	public void delete(String key) {
		entries.remove(key);
	}
}
