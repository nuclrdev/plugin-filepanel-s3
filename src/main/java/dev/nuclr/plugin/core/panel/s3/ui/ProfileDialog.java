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
package dev.nuclr.plugin.core.panel.s3.ui;

import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.ArrayList;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.panel.s3.auth.AwsCli;
import dev.nuclr.plugin.core.panel.s3.auth.AwsConfigFiles;
import dev.nuclr.plugin.core.panel.s3.auth.S3Profile;

/**
 * The create and edit dialog for one {@link S3Profile}.
 *
 * <p>Its job is to make the four ways of reaching S3 look like four choices rather than four
 * products. Picking an authentication mode enables only the fields that mode needs and greys out
 * the rest, so a user who signs in with SSO never sees a secret-key box and a user pasting keys
 * never wonders which AWS profile to name. The AWS-profile and SSO modes populate their dropdown by
 * reading {@code ~/.aws} directly, so the choices offered are the ones that actually exist on this
 * machine.
 *
 * <p>The secret access key can be typed here for convenience, but it is handed back to the caller
 * for in-memory caching and never becomes part of the saved profile — see {@link S3Profile}.
 */
public final class ProfileDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final int NAME_MAX_LENGTH = 60;

	/** The most-used regions, offered as suggestions in an editable field. */
	private static final String[] COMMON_REGIONS = {
			"us-east-1", "us-east-2", "us-west-1", "us-west-2",
			"eu-west-1", "eu-west-2", "eu-central-1", "eu-north-1",
			"ap-south-1", "ap-southeast-1", "ap-southeast-2", "ap-northeast-1",
			"sa-east-1", "ca-central-1", "auto"};

	/**
	 * Where each authentication mode is set up and documented.
	 *
	 * <p>Every mode needs something arranged outside this dialog — a key minted, a file written, a
	 * directory connected, a role attached — so each one carries the console page that arranges it
	 * and the page that explains it.
	 */
	private static final String IAM_SECURITY_CREDENTIALS_CONSOLE =
			"https://console.aws.amazon.com/iam/home#/security_credentials";
	private static final String ACCESS_KEYS_DOCS =
			"https://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_access-keys.html";
	private static final String AWS_CONFIG_FILES_DOCS =
			"https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-files.html";
	private static final String IDENTITY_CENTER_CONSOLE =
			"https://console.aws.amazon.com/singlesignon/home";
	private static final String SSO_DOCS =
			"https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sso.html";
	private static final String IAM_ROLES_CONSOLE =
			"https://console.aws.amazon.com/iam/home#/roles";
	private static final String ENVIRONMENT_DOCS =
			"https://docs.aws.amazon.com/sdkref/latest/guide/environment-variables.html";

	/**
	 * The edited profile plus any secret typed alongside it.
	 *
	 * @param profile         the profile to save
	 * @param secretAccessKey the secret entered, or {@code null} if none was
	 * @param sessionToken    the session token entered, or {@code null} if none was
	 */
	public record Result(S3Profile profile, String secretAccessKey, String sessionToken) {}

	private final JTextField nameField = new JTextField(26);

	private final JRadioButton accessKeyAuth = new JRadioButton("Access key and secret", true);
	private final JRadioButton awsProfileAuth = new JRadioButton("AWS profile (~/.aws)");
	private final JRadioButton ssoAuth = new JRadioButton("IAM Identity Center (SSO)");
	private final JRadioButton environmentAuth = new JRadioButton("Environment / instance role");

	private final JTextField accessKeyIdField = new JTextField(26);
	private final JPasswordField secretKeyField = new JPasswordField(26);
	private final JPasswordField sessionTokenField = new JPasswordField(26);
	private final JComboBox<String> awsProfileField = new JComboBox<>();

	private final JComboBox<String> regionField = new JComboBox<>(new DefaultComboBoxModel<>(COMMON_REGIONS));
	private final JTextField endpointField = new JTextField(26);
	private final JCheckBox pathStyleField = new JCheckBox("Address buckets in the path (needed by most S3-compatible services)");
	private final JTextField bucketField = new JTextField(26);
	private final JTextField prefixField = new JTextField(26);

	private final JLabel authNote = new JLabel(" ");

	private transient Result result;

	/**
	 * Build the dialog, seeded from an existing profile or from the defaults of a fresh one.
	 *
	 * @param owner    the owning window
	 * @param title    the dialog title
	 * @param profile  the profile to edit; a new {@link S3Profile} for "New profile"
	 * @param awsFiles the AWS configuration reader used to populate the profile dropdown
	 */
	public ProfileDialog(Window owner, String title, S3Profile profile, AwsConfigFiles awsFiles) {
		super(owner, title, ModalityType.APPLICATION_MODAL);

		nameField.setText(profile.getName());
		accessKeyIdField.setText(profile.getAccessKeyId());
		endpointField.setText(profile.getEndpoint());
		pathStyleField.setSelected(profile.isPathStyleAccess());
		bucketField.setText(profile.getBucket());
		prefixField.setText(profile.getPrefix());
		regionField.setEditable(true);
		regionField.setSelectedItem(profile.effectiveRegion());

		populateAwsProfiles(awsFiles, profile.getAwsProfileName());

		var group = new ButtonGroup();
		group.add(accessKeyAuth);
		group.add(awsProfileAuth);
		group.add(ssoAuth);
		group.add(environmentAuth);
		switch (profile.getAuthMode() == null ? S3Profile.AuthMode.ACCESS_KEY : profile.getAuthMode()) {
			case ACCESS_KEY -> accessKeyAuth.setSelected(true);
			case AWS_PROFILE -> awsProfileAuth.setSelected(true);
			case SSO -> ssoAuth.setSelected(true);
			case ENVIRONMENT -> environmentAuth.setSelected(true);
		}

		setLayout(new GridBagLayout());
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 6, 4, 6);
		constraints.anchor = GridBagConstraints.WEST;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		int row = 0;

		row = addRow(constraints, row, "Name (optional):", nameField);
		row = addSeparator(constraints, row, "Authentication");

		var authPanel = new JPanel(new GridBagLayout());
		var authConstraints = new GridBagConstraints();
		authConstraints.anchor = GridBagConstraints.WEST;
		addAuthRow(authPanel, authConstraints, 0, accessKeyAuth,
				Dialogs.link("Console", IAM_SECURITY_CREDENTIALS_CONSOLE),
				Dialogs.link("Docs", ACCESS_KEYS_DOCS));
		addAuthRow(authPanel, authConstraints, 1, awsProfileAuth,
				Dialogs.link("Docs", AWS_CONFIG_FILES_DOCS));
		addAuthRow(authPanel, authConstraints, 2, ssoAuth,
				Dialogs.link("Console", IDENTITY_CENTER_CONSOLE),
				Dialogs.link("Docs", SSO_DOCS));
		addAuthRow(authPanel, authConstraints, 3, environmentAuth,
				Dialogs.link("Console", IAM_ROLES_CONSOLE),
				Dialogs.link("Docs", ENVIRONMENT_DOCS));
		row = addWide(constraints, row, authPanel);

		authNote.setFont(authNote.getFont().deriveFont(authNote.getFont().getSize2D() - 1f));
		row = addWide(constraints, row, authNote);

		row = addRow(constraints, row, "Access key id:", accessKeyIdField);
		row = addRow(constraints, row, "Secret access key:", secretKeyField);
		row = addRow(constraints, row, "Session token (optional):", sessionTokenField);
		row = addRow(constraints, row, "AWS profile:", awsProfileField);

		row = addSeparator(constraints, row, "Endpoint");
		row = addRow(constraints, row, "Region:", regionField);
		row = addRow(constraints, row, "Endpoint URL (optional):", endpointField);
		row = addWide(constraints, row, pathStyleField);

		row = addSeparator(constraints, row, "Starting location (optional)");
		row = addRow(constraints, row, "Bucket:", bucketField);
		row = addRow(constraints, row, "Prefix:", prefixField);

		accessKeyAuth.addActionListener(event -> updateAuthEnablement());
		awsProfileAuth.addActionListener(event -> updateAuthEnablement());
		ssoAuth.addActionListener(event -> updateAuthEnablement());
		environmentAuth.addActionListener(event -> updateAuthEnablement());
		updateAuthEnablement();

		var okButton = new JButton("OK");
		var cancelButton = new JButton("Cancel");
		okButton.addActionListener(event -> submit(profile));
		cancelButton.addActionListener(event -> dispose());
		getRootPane().setDefaultButton(okButton);

		// Right-aligned, following the platform's own affirmative/dismiss ordering where the
		// look-and-feel declares one — the same key JOptionPane consults for Yes/No order.
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		if (UIManager.getBoolean("OptionPane.isYesLast")) {
			buttons.add(cancelButton);
			buttons.add(okButton);
		} else {
			buttons.add(okButton);
			buttons.add(cancelButton);
		}
		addWide(constraints, row, buttons);

		getRootPane().registerKeyboardAction(event -> dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
		getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		pack();
		setMinimumSize(getSize());
		setLocationRelativeTo(owner);
	}

	/**
	 * Show the dialog and return what was entered.
	 *
	 * @param owner    the owning window
	 * @param title    the dialog title
	 * @param profile  the profile to seed the form with
	 * @param awsFiles the AWS configuration reader for the profile dropdown
	 * @return the result, or {@code null} if cancelled
	 */
	public static Result show(Window owner, String title, S3Profile profile, AwsConfigFiles awsFiles) {
		final Result[] result = new Result[1];
		Dialogs.onEdtAndWait(() -> {
			var dialog = new ProfileDialog(owner, title, profile, awsFiles);
			dialog.setVisible(true);
			result[0] = dialog.result;
		});
		return result[0];
	}

	// -------------------------------------------------------------------------
	// Form behaviour
	// -------------------------------------------------------------------------

	/** Offer the profiles that really exist in {@code ~/.aws}, keeping the current one selected. */
	private void populateAwsProfiles(AwsConfigFiles awsFiles, String selected) {

		var names = new ArrayList<String>();
		if (awsFiles != null) {
			for (AwsConfigFiles.Profile profile : awsFiles.profiles()) {
				names.add(profile.name());
			}
		}
		if (selected != null && !selected.isBlank() && !names.contains(selected)) {
			// Keep a profile that has since been removed from the file, so editing something else
			// about this connection does not silently repoint it at a different account.
			names.add(0, selected);
		}
		if (names.isEmpty()) {
			names.add("default");
		}

		awsProfileField.setModel(new DefaultComboBoxModel<>(names.toArray(new String[0])));
		awsProfileField.setEditable(true);
		awsProfileField.setSelectedItem(selected == null || selected.isBlank() ? names.get(0) : selected);
	}

	/** Enable exactly the fields the selected mode uses, and explain what that mode will do. */
	private void updateAuthEnablement() {

		boolean useAccessKey = accessKeyAuth.isSelected();
		boolean useAwsProfile = awsProfileAuth.isSelected() || ssoAuth.isSelected();

		accessKeyIdField.setEnabled(useAccessKey);
		secretKeyField.setEnabled(useAccessKey);
		sessionTokenField.setEnabled(useAccessKey);
		awsProfileField.setEnabled(useAwsProfile);

		if (useAccessKey) {
			authNote.setText("The secret is kept in memory for this session only and is never saved to disk.");
		} else if (awsProfileAuth.isSelected()) {
			authNote.setText("Credentials are read from ~/.aws; profiles that assume a role use the AWS CLI.");
		} else if (ssoAuth.isSelected()) {
			authNote.setText(AwsCli.isAvailable()
					? "Signs in through your identity provider; the AWS CLI refreshes the session."
					: "Requires the AWS CLI, which was not found on the PATH.");
		} else {
			authNote.setText("Uses AWS_ACCESS_KEY_ID, a container task role, or the EC2 instance role.");
		}
	}

	private void submit(S3Profile original) {

		S3Profile.AuthMode mode = selectedMode();

		var profile = new S3Profile();
		profile.setId(original.getId());
		profile.setName(trimmed(nameField.getText(), NAME_MAX_LENGTH));
		profile.setAuthMode(mode);
		profile.setRegion(text(regionField.getSelectedItem()));
		profile.setEndpoint(endpointField.getText().trim());
		profile.setPathStyleAccess(pathStyleField.isSelected());
		profile.setBucket(bucketField.getText().trim());
		profile.setPrefix(prefixField.getText().trim());

		String secret = null;
		String sessionToken = null;

		if (mode == S3Profile.AuthMode.ACCESS_KEY) {
			String accessKeyId = accessKeyIdField.getText().trim();
			if (accessKeyId.isBlank()) {
				showValidationError("An access key id is required for access-key authentication.");
				return;
			}
			profile.setAccessKeyId(accessKeyId);
			char[] enteredSecret = secretKeyField.getPassword();
			if (enteredSecret.length > 0) {
				secret = new String(enteredSecret);
			}
			char[] enteredToken = sessionTokenField.getPassword();
			if (enteredToken.length > 0) {
				sessionToken = new String(enteredToken);
			}
		} else if (mode == S3Profile.AuthMode.AWS_PROFILE || mode == S3Profile.AuthMode.SSO) {
			String awsProfileName = text(awsProfileField.getSelectedItem());
			if (awsProfileName.isBlank()) {
				showValidationError("Choose an AWS profile, or type its name.");
				return;
			}
			profile.setAwsProfileName(awsProfileName);
			if (mode == S3Profile.AuthMode.SSO && !AwsCli.isAvailable()) {
				showValidationError("SSO needs the AWS CLI, which was not found on the PATH.\n"
						+ "Install it, or choose another authentication mode.");
				return;
			}
		}

		if (!profile.getEndpoint().isBlank()) {
			String endpoint = profile.getEndpoint();
			if (endpoint.contains(" ")) {
				showValidationError("The endpoint URL cannot contain spaces.");
				return;
			}
			// Almost every S3-compatible service needs path-style addressing; a custom endpoint with
			// host-style buckets is far more often a mistake than a deliberate choice.
			if (!pathStyleField.isSelected()) {
				int answer = JOptionPane.showConfirmDialog(this,
						"Most S3-compatible services require path-style bucket addressing.\n"
								+ "Turn it on for this profile?",
						"Endpoint", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
				if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
					return;
				}
				profile.setPathStyleAccess(answer == JOptionPane.YES_OPTION);
			}
		}

		this.result = new Result(profile, secret, sessionToken);
		dispose();
	}

	private S3Profile.AuthMode selectedMode() {
		if (awsProfileAuth.isSelected()) {
			return S3Profile.AuthMode.AWS_PROFILE;
		}
		if (ssoAuth.isSelected()) {
			return S3Profile.AuthMode.SSO;
		}
		if (environmentAuth.isSelected()) {
			return S3Profile.AuthMode.ENVIRONMENT;
		}
		return S3Profile.AuthMode.ACCESS_KEY;
	}

	// -------------------------------------------------------------------------
	// Layout helpers
	// -------------------------------------------------------------------------

	private int addRow(GridBagConstraints constraints, int row, String label, Component field) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = 1;
		constraints.weightx = 0;
		add(new JLabel(label), constraints);
		constraints.gridx = 1;
		constraints.gridwidth = 2;
		constraints.weightx = 1;
		add(field, constraints);
		return row + 1;
	}

	/**
	 * Lay out one authentication choice: the radio button, then the links that lead to where that
	 * mode is set up.
	 *
	 * <p>The links are deliberately not disabled along with the mode's fields — reading up on a mode
	 * is exactly what someone does before choosing it.
	 *
	 * @param panel       the authentication panel
	 * @param constraints the constraints being reused down the panel
	 * @param row         the row to place this choice on
	 * @param radio       the radio button for the mode
	 * @param links       the links to offer beside it
	 */
	private static void addAuthRow(JPanel panel, GridBagConstraints constraints, int row,
			JRadioButton radio, JButton... links) {

		constraints.gridy = row;
		constraints.gridx = 0;
		constraints.weightx = 0;
		panel.add(radio, constraints);

		var linkRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		linkRow.setOpaque(false);
		for (JButton link : links) {
			linkRow.add(link);
		}

		constraints.gridx = 1;
		constraints.weightx = 1;
		panel.add(linkRow, constraints);
	}

	private int addWide(GridBagConstraints constraints, int row, Component component) {
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = 3;
		constraints.weightx = 1;
		add(component, constraints);
		return row + 1;
	}

	private int addSeparator(GridBagConstraints constraints, int row, String caption) {
		var label = new JLabel(caption);
		label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
		label.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		return addWide(constraints, row, label);
	}

	private void showValidationError(String message) {
		JOptionPane.showMessageDialog(this, message, "Invalid profile", JOptionPane.ERROR_MESSAGE);
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}

	private static String trimmed(String value, int maxLength) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
	}
}
