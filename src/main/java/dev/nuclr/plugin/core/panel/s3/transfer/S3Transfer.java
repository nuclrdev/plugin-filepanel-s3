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
package dev.nuclr.plugin.core.panel.s3.transfer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dev.nuclr.plugin.core.panel.s3.S3Clients;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;
import dev.nuclr.plugin.core.panel.s3.auth.S3ProfileStore;
import dev.nuclr.plugin.core.panel.s3.auth.SecretStore;

/**
 * This plugin's side of Commander's Configs - Export / Import, spoken over {@code act} with plain
 * maps (Commander's {@code TransferProtocol}; see {@code commander/docs/settings-transfer-plan.md},
 * section 4). Saved S3 profiles are the {@code connections} category.
 *
 * <p>The only secret exported is the remembered secret access key, kept in the keychain under the
 * profile id. A session token is never exported, read or declared: it expires within hours.
 * Profiles that use {@code ~/.aws} files or SSO carry only the AWS profile name; those files are
 * never read here.
 *
 * <p>An endpoint is stripped of {@code user:password@} and of signature or token query parameters
 * on export, and again on apply, so a crafted file cannot put one into {@code profiles.json}. S3
 * never sends either (requests are signed with the access key), so what is stripped is dropped,
 * not handed back as a recovered secret. Apply never writes the keychain.
 *
 * <p>Called off the EDT, on the instance Commander keeps for actions.
 */
public final class S3Transfer {

	public static final String Describe = "transfer.describe";
	public static final String Snapshot = "transfer.snapshot";
	public static final String ReadSecrets = "transfer.readSecrets";
	public static final String FindEmbeddedSecrets = "transfer.findEmbeddedSecrets";
	public static final String Apply = "transfer.apply";
	public static final String Remove = "transfer.remove";

	static final int ProtocolVersion = 1;
	static final String Type = "connections";
	static final int SectionVersion = 1;

	// Field names, as they appear in the export file.
	static final String Name = "name";
	static final String AuthMode = "authMode";
	static final String AccessKeyId = "accessKeyId";
	static final String RememberSecret = "rememberSecret";
	static final String AwsProfileName = "awsProfileName";
	static final String Region = "region";
	static final String Endpoint = "endpoint";
	static final String PathStyleAccess = "pathStyleAccess";
	static final String Bucket = "bucket";
	static final String Prefix = "prefix";
	static final String SecretAccessKey = "secretAccessKey";

	/** Query parameters of an endpoint that carry a signature, token or password. */
	private static final Set<String> SecretParameters = Set.of("x-amz-security-token", "x-amz-signature",
			"x-amz-credential", "signature", "sig", "token", "access_token", "accesstoken", "password", "passwd",
			"pwd", "secret");

	private final Supplier<S3ProfileStore> profiles;
	private final Supplier<SecretStore> secrets;

	/** The rows of the last snapshot by row id, so an export reads secrets for exactly those. */
	private Map<String, S3Profile> lastSnapshot = Map.of();

	public S3Transfer(Supplier<S3ProfileStore> profiles, Supplier<SecretStore> secrets) {
		this.profiles = profiles;
		this.secrets = secrets;
	}

	public static boolean handles(String action) {
		return switch (action) {
			case Describe, Snapshot, ReadSecrets, FindEmbeddedSecrets, Apply, Remove -> true;
			default -> false;
		};
	}

	/**
	 * Answer one transfer action into {@code data}. Throws on failure; Commander treats a throw as
	 * "the plugin failed" and, for apply, relies on the store being unchanged.
	 */
	public void handle(String action, Map<String, Object> data) {
		if (!(data.get("transfer.protocol") instanceof Integer version) || version != ProtocolVersion) {
			return; // a protocol this build does not speak: do not take part
		}
		if (!Describe.equals(action) && !Type.equals(data.get("type"))) {
			return;
		}
		switch (action) {
			case Describe -> data.put("transfer.providers", List.of(describe()));
			case Snapshot -> data.put("rows", snapshot());
			case ReadSecrets -> data.put("secrets", readSecrets(String.valueOf(data.get("rowId"))));
			case FindEmbeddedSecrets -> data.put("fieldNames", findEmbeddedSecrets(strings(data.get("fields"))));
			case Apply -> {
				Map<String, Object> recovered = apply(items(data.get("items")));
				if (!recovered.isEmpty()) {
					data.put("recoveredSecrets", recovered);
				}
			}
			case Remove -> remove(ids(data.get("ids")));
			default -> {
			}
		}
	}

	static Map<String, Object> describe() {
		var fields = new ArrayList<Map<String, Object>>();
		fields.add(field(Name, "PLAIN"));
		fields.add(field(AuthMode, "PLAIN"));
		fields.add(field(AccessKeyId, "PLAIN"));
		fields.add(field(RememberSecret, "PLAIN"));
		fields.add(field(AwsProfileName, "PLAIN"));
		fields.add(field(Region, "ROUTING"));
		fields.add(field(Endpoint, "ROUTING"));
		fields.add(field(PathStyleAccess, "ROUTING"));
		fields.add(field(Bucket, "PLAIN"));
		fields.add(field(Prefix, "PLAIN"));
		// Kept under the bare profile id (SecretStore); the session token is deliberately absent.
		fields.add(secret(SecretAccessKey, ""));
		return Map.of("type", Type, "version", SectionVersion, "title", "S3", "fields", fields);
	}

	List<Map<String, Object>> snapshot() {
		var byRow = new LinkedHashMap<String, S3Profile>();
		int index = 0;
		for (S3Profile profile : profiles.get().loadAsSaved()) {
			index++;
			// An entry saved without an id keeps a row of its own; it just has no stored secret.
			String rowId = profile.getId() != null && !profile.getId().isBlank() ? profile.getId() : "#" + index;
			// A duplicated id is one profile, as the panel shows it: the later entry wins.
			byRow.put(rowId, profile);
		}
		var rows = new ArrayList<Map<String, Object>>();
		for (var entry : byRow.entrySet()) {
			S3Profile profile = entry.getValue();
			var row = new HashMap<String, Object>();
			row.put("rowId", entry.getKey());
			row.put("label", label(profile, entry.getKey()));
			row.put("fields", fields(profile));
			row.put("hasSecrets", !secrets(profile).isEmpty());
			rows.add(row);
		}
		lastSnapshot = byRow;
		return rows;
	}

	Map<String, String> readSecrets(String rowId) {
		S3Profile profile = lastSnapshot.get(rowId);
		if (profile == null) {
			profile = profiles.get().byId(rowId);
		}
		return profile == null ? Map.of() : secrets(profile);
	}

	static List<String> findEmbeddedSecrets(Map<String, String> fields) {
		String endpoint = fields.get(Endpoint);
		return endpoint != null && !stripEndpoint(endpoint).equals(endpoint) ? List.of(Endpoint) : List.of();
	}

	/** Add every item under its given id in one write. Nothing is recovered: see the class note. */
	Map<String, Object> apply(List<Map<String, Object>> items) {
		var added = new ArrayList<S3Profile>();
		for (Map<String, Object> item : items) {
			if (!(item.get("newId") instanceof String id) || id.isBlank()) {
				throw new IllegalArgumentException("An imported S3 profile has no id");
			}
			Map<String, String> fields = strings(item.get("fields"));

			var profile = new S3Profile();
			profile.setId(id);
			profile.setName(text(fields.get(Name)));
			profile.setAuthMode(authMode(fields.get(AuthMode)));
			profile.setAccessKeyId(text(fields.get(AccessKeyId)));
			// Kept as exported. Reading the key does not depend on it (CredentialsResolver), so a
			// key Commander stores under the new id is used either way.
			profile.setRememberSecret(Boolean.parseBoolean(fields.get(RememberSecret)));
			profile.setAwsProfileName(text(fields.get(AwsProfileName)));
			profile.setRegion(text(fields.get(Region)));
			String endpoint = fields.get(Endpoint);
			profile.setEndpoint(endpoint == null ? "" : stripEndpoint(endpoint));
			profile.setPathStyleAccess(Boolean.parseBoolean(fields.get(PathStyleAccess)));
			profile.setBucket(text(fields.get(Bucket)));
			profile.setPrefix(text(fields.get(Prefix)));
			added.add(profile);
		}
		profiles.get().addAll(added);
		return Map.of();
	}

	void remove(List<String> ids) {
		profiles.get().removeAll(new HashSet<>(ids));
		ids.forEach(S3Clients::forget);
	}

	/** A profile's non-secret fields, with the endpoint stripped of anything secret. */
	static Map<String, Object> fields(S3Profile profile) {
		var fields = new LinkedHashMap<String, Object>();
		put(fields, Name, profile.getName());
		fields.put(AuthMode, (profile.getAuthMode() == null ? S3Profile.AuthMode.ACCESS_KEY : profile.getAuthMode())
				.name());
		put(fields, AccessKeyId, profile.getAccessKeyId());
		fields.put(RememberSecret, String.valueOf(profile.isRememberSecret()));
		put(fields, AwsProfileName, profile.getAwsProfileName());
		put(fields, Region, profile.getRegion());
		put(fields, Endpoint, profile.getEndpoint() == null ? null : stripEndpoint(profile.getEndpoint()));
		fields.put(PathStyleAccess, String.valueOf(profile.isPathStyleAccess()));
		put(fields, Bucket, profile.getBucket());
		put(fields, Prefix, profile.getPrefix());
		return fields;
	}

	/**
	 * The remembered secret access key, wherever {@link SecretStore} finds it (keychain, or a
	 * pre-1.0.9 file not yet moved). Only an access-key profile uses one, so only those export it.
	 */
	private Map<String, String> secrets(S3Profile profile) {
		String id = profile.getId();
		if (id == null || id.isBlank() || profile.getAuthMode() != S3Profile.AuthMode.ACCESS_KEY) {
			return Map.of();
		}
		String key = secrets.get().secretAccessKey(id);
		return key == null || key.isBlank() ? Map.of() : Map.of(SecretAccessKey, key);
	}

	/**
	 * The endpoint without {@code user:password@} and without query parameters that carry a
	 * signature, token or password. Everything else is left exactly as written.
	 */
	static String stripEndpoint(String endpoint) {
		int scheme = endpoint.indexOf("://");
		int start = scheme < 0 ? 0 : scheme + 3;
		int end = endpoint.length();
		for (int i = start; i < endpoint.length(); i++) {
			char c = endpoint.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				end = i;
				break;
			}
		}
		String authority = endpoint.substring(start, end);
		int at = authority.lastIndexOf('@');
		var result = new StringBuilder(endpoint.substring(0, start)).append(at < 0 ? authority : authority.substring(at + 1));

		String rest = endpoint.substring(end);
		int query = rest.indexOf('?');
		if (query < 0) {
			return result.append(rest).toString();
		}
		int fragment = rest.indexOf('#', query);
		String path = rest.substring(0, query);
		String parameters = fragment < 0 ? rest.substring(query + 1) : rest.substring(query + 1, fragment);
		String tail = fragment < 0 ? "" : rest.substring(fragment);

		var kept = new ArrayList<String>();
		for (String parameter : parameters.split("&", -1)) {
			int equals = parameter.indexOf('=');
			String name = equals < 0 ? parameter : parameter.substring(0, equals);
			if (!SecretParameters.contains(decode(name).toLowerCase(Locale.ROOT))) {
				kept.add(parameter);
			}
		}
		result.append(path);
		if (kept.size() == parameters.split("&", -1).length) {
			result.append('?').append(parameters);
		} else if (!kept.isEmpty()) {
			result.append('?').append(String.join("&", kept));
		}
		return result.append(tail).toString();
	}

	private static String decode(String text) {
		try {
			return URLDecoder.decode(text, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return text;
		}
	}

	private static S3Profile.AuthMode authMode(String value) {
		if (value == null || value.isBlank()) {
			return S3Profile.AuthMode.ACCESS_KEY;
		}
		try {
			return S3Profile.AuthMode.valueOf(value.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Unknown S3 authentication mode: " + value, e);
		}
	}

	private static String label(S3Profile profile, String rowId) {
		String label = profile.displayName();
		if (label == null || label.isBlank()) {
			return rowId;
		}
		label = profile.getName() != null && !profile.getName().isBlank() ? label : stripEndpoint(label);
		return label.length() > 200 ? label.substring(0, 200) : label;
	}

	private static Map<String, Object> field(String name, String kind) {
		return Map.of("name", name, "kind", kind);
	}

	private static Map<String, Object> secret(String name, String suffix) {
		return Map.of("name", name, "kind", "SECRET", "credentialSuffix", suffix);
	}

	private static void put(Map<String, Object> fields, String name, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(name, value);
		}
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}

	private static Map<String, String> strings(Object raw) {
		var map = new HashMap<String, String>();
		if (raw instanceof Map<?, ?> values) {
			values.forEach((key, value) -> {
				if (key instanceof String name && value instanceof String text) {
					map.put(name, text);
				}
			});
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Object raw) {
		var items = new ArrayList<Map<String, Object>>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					items.add((Map<String, Object>) map);
				}
			}
		}
		return items;
	}

	private static List<String> ids(Object raw) {
		var ids = new ArrayList<String>();
		if (raw instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					ids.add(text);
				}
			}
		}
		return ids;
	}

	/** Field names, for tests. */
	static Set<String> fieldNames() {
		var names = new HashSet<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			names.add((String) ((Map<?, ?>) field).get("name"));
		}
		return names;
	}
}
