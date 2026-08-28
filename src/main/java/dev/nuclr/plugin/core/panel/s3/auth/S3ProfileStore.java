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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * JSON persistence for {@link S3Profile} connection profiles.
 *
 * <p>The production store lives at {@code ~/.nuclr/s3/profiles.json}; a custom file can be supplied
 * for tests. Nothing secret is written here — see {@link S3Profile} — so the file is safe to sync
 * or check into a dotfiles repository. A profile that opts into remembering its secret records only
 * that preference; the key itself is held by {@link SecretStore} in a separate file that is not
 * safe to sync, and is not part of this one.
 *
 * <p>Loading is deliberately forgiving: a corrupt or half-written file yields an empty list rather
 * than an error, duplicate ids collapse, and a profile missing its id gets a fresh one. A broken
 * profile file should cost the user their saved list, not their ability to use the panel.
 */
@Slf4j
public class S3ProfileStore {

	private final ObjectMapper mapper = new ObjectMapper();

	private final Path file;

	/**
	 * Create a store over the given JSON file.
	 *
	 * @param file the backing file; created on first save
	 */
	public S3ProfileStore(Path file) {
		this.file = file;
	}

	/**
	 * Create the production store at {@code ~/.nuclr/s3/profiles.json}.
	 *
	 * @return the default store
	 */
	public static S3ProfileStore defaultStore() {
		return new S3ProfileStore(Path.of(System.getProperty("user.home"), ".nuclr", "s3", "profiles.json"));
	}

	/**
	 * The backing file location, shown to the user in the panel.
	 *
	 * @return the JSON file path
	 */
	public Path file() {
		return file;
	}

	/**
	 * Load all profiles, sorted by display name. A missing or unreadable file yields an empty list.
	 *
	 * @return the saved profiles, never {@code null}
	 */
	public synchronized List<S3Profile> load() {

		if (!Files.isRegularFile(file)) {
			return List.of();
		}

		try {
			List<S3Profile> raw = mapper.readValue(file.toFile(), new TypeReference<List<S3Profile>>() {});
			var byId = new LinkedHashMap<String, S3Profile>();
			for (S3Profile profile : raw) {
				if (profile == null) {
					continue;
				}
				if (profile.getId() == null || profile.getId().isBlank()) {
					profile.setId(UUID.randomUUID().toString());
				}
				if (profile.getAuthMode() == null) {
					profile.setAuthMode(S3Profile.AuthMode.ACCESS_KEY);
				}
				byId.put(profile.getId(), profile);
			}
			var profiles = new ArrayList<>(byId.values());
			profiles.sort(Comparator.comparing(S3Profile::displayName, String.CASE_INSENSITIVE_ORDER));
			return profiles;
		} catch (JacksonException e) {
			log.warn("Cannot read the S3 profile list [{}]: {}", file, e.getMessage());
			return List.of();
		}
	}

	/**
	 * Persist the given profiles, replacing the previous contents.
	 *
	 * @param profiles the profiles to save
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void save(List<S3Profile> profiles) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), profiles);
	}

	/**
	 * Insert or replace one profile (matched by id) and persist the list.
	 *
	 * @param profile the profile to save
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void upsert(S3Profile profile) throws IOException {
		var profiles = new ArrayList<>(load());
		profiles.removeIf(existing -> Objects.equals(existing.getId(), profile.getId()));
		profiles.add(profile);
		save(profiles);
	}

	/**
	 * Remove the profile with the given id (a no-op when absent) and persist.
	 *
	 * @param id the profile id to remove
	 * @throws IOException if the file cannot be written
	 */
	public synchronized void remove(String id) throws IOException {
		var profiles = new ArrayList<>(load());
		profiles.removeIf(existing -> Objects.equals(existing.getId(), id));
		save(profiles);
	}

	/**
	 * Find a profile by id.
	 *
	 * @param id the profile id
	 * @return the profile, or {@code null} when not found
	 */
	public synchronized S3Profile byId(String id) {
		if (id == null) {
			return null;
		}
		return load().stream().filter(profile -> Objects.equals(profile.getId(), id)).findFirst().orElse(null);
	}
}
