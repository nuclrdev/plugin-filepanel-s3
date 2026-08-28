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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * The modal prompt for a profile's secret access key.
 *
 * <p>Shown the first time a session opens an {@link S3Profile.AuthMode#ACCESS_KEY} profile, and
 * again after the endpoint rejects the credentials. What is typed goes into {@link SecretCache} and
 * nowhere else — never to the profile file, never to a log.
 *
 * <p>Marshals to the event dispatch thread and blocks for the answer, so it is safe to call from the
 * background thread that is trying to sign a request.
 */
@Slf4j
public final class SecretPrompt {

	private SecretPrompt() {}

	/**
	 * What the user entered.
	 *
	 * @param secretAccessKey the secret access key
	 * @param sessionToken    the session token for temporary credentials, or {@code null}
	 */
	public record Entry(String secretAccessKey, String sessionToken) {}

	/**
	 * Ask for the secret access key belonging to a profile.
	 *
	 * @param profile the profile being opened
	 * @param retry   {@code true} when a previous attempt was rejected, which changes the wording
	 * @return what was entered, or {@code null} if the user cancelled
	 */
	public static Entry ask(S3Profile profile, boolean retry) {
		final Entry[] result = new Entry[1];
		runOnEdtAndWait(() -> result[0] = build(profile, retry));
		return result[0];
	}

	private static Entry build(S3Profile profile, boolean retry) {

		var secretField = new JPasswordField(32);
		var tokenField = new JPasswordField(32);
		var temporary = new JCheckBox("These are temporary credentials (session token)");

		tokenField.setEnabled(false);
		temporary.addActionListener(event -> {
			tokenField.setEnabled(temporary.isSelected());
			if (temporary.isSelected()) {
				tokenField.requestFocusInWindow();
			}
		});

		var form = new JPanel(new GridBagLayout());
		var constraints = new GridBagConstraints();
		constraints.insets = new Insets(4, 0, 4, 6);
		constraints.anchor = GridBagConstraints.WEST;

		int row = 0;
		row = addRow(form, constraints, row, "Access key id:", new JLabel(
				profile.getAccessKeyId() == null || profile.getAccessKeyId().isBlank()
						? "(not set)"
						: profile.getAccessKeyId()));
		row = addRow(form, constraints, row, "Secret access key:", secretField);

		constraints.gridx = 0;
		constraints.gridy = row++;
		constraints.gridwidth = 2;
		form.add(temporary, constraints);
		constraints.gridwidth = 1;

		addRow(form, constraints, row, "Session token:", tokenField);

		var message = new JPanel(new BorderLayout(0, 10));
		message.add(new JLabel(retry
				? "<html>Those credentials were rejected. Enter the secret access key for <b>"
						+ escape(profile.displayName()) + "</b>:</html>"
				: "<html>Enter the secret access key for <b>" + escape(profile.displayName())
						+ "</b>:</html>"), BorderLayout.NORTH);
		message.add(form, BorderLayout.CENTER);
		message.add(new JLabel("<html><small>Held in memory for this session only; never written to disk.</small></html>"),
				BorderLayout.SOUTH);

		// showConfirmDialog offers no hook to focus a field inside a custom panel, and timing a
		// requestFocusInWindow() around the modal call races with the option pane's own focusing.
		// selectInitialValue is the method Swing itself calls to set initial focus, so overriding it
		// is the reliable way in.
		var optionPane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
			private static final long serialVersionUID = 1L;

			@Override
			public void selectInitialValue() {
				secretField.requestFocusInWindow();
			}
		};

		JDialog dialog = optionPane.createDialog(null, "S3 Credentials");
		dialog.setVisible(true);
		dialog.dispose();

		Object choice = optionPane.getValue();
		if (!(choice instanceof Integer value) || value != JOptionPane.OK_OPTION) {
			return null;
		}

		char[] secret = secretField.getPassword();
		if (secret.length == 0) {
			return null;
		}
		char[] token = temporary.isSelected() ? tokenField.getPassword() : new char[0];
		return new Entry(new String(secret), token.length == 0 ? null : new String(token));
	}

	private static int addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
		constraints.gridx = 0;
		constraints.gridy = row;
		form.add(new JLabel(label), constraints);
		constraints.gridx = 1;
		form.add(field, constraints);
		return row + 1;
	}

	/** Keep a profile name from being read as markup by the HTML label it lands in. */
	private static String escape(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to show the S3 credentials prompt: {}", e.getMessage(), e);
		}
	}
}
