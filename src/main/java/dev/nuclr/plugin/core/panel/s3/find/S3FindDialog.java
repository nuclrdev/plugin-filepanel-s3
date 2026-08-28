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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;

/**
 * The Alt+F7 "Find files" dialog, scoped to the bucket folder currently open.
 *
 * <p>Offers a filename wildcard and the two switches that matter for a bucket: whether to descend
 * into sub-folders, and whether the match respects case. There is no content-search option, because
 * S3 listings carry names and metadata only — offering one would mean silently downloading the whole
 * prefix.
 */
public final class S3FindDialog {

	private static final String TITLE = "Find files";

	private S3FindDialog() {}

	/**
	 * Show the dialog and return the request to run.
	 *
	 * @param profileId the profile being searched
	 * @param bucket    the bucket being searched
	 * @param prefix    the prefix the search starts from
	 * @return the request, or {@code null} if cancelled
	 */
	public static S3FindRequest show(String profileId, String bucket, String prefix) {
		final S3FindRequest[] result = new S3FindRequest[1];
		Dialogs.onEdtAndWait(() -> result[0] = build(profileId, bucket, prefix));
		return result[0];
	}

	private static S3FindRequest build(String profileId, String bucket, String prefix) {

		Window owner = Dialogs.activeWindow();
		var dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		var patternField = new JTextField("*", 30);
		var patternPanel = new JPanel(new BorderLayout(0, 4));
		patternPanel.add(new JLabel("File name (wildcards * and ?):"), BorderLayout.NORTH);
		patternPanel.add(patternField, BorderLayout.CENTER);

		var locationField = new JTextField("s3://" + bucket + '/' + prefix, 30);
		locationField.setEditable(false);
		var locationPanel = new JPanel(new BorderLayout(0, 4));
		locationPanel.add(new JLabel("Search in:"), BorderLayout.NORTH);
		locationPanel.add(locationField, BorderLayout.CENTER);

		var recursive = new JCheckBox("Search sub-folders", true);
		var caseSensitive = new JCheckBox("Case sensitive", false);

		var searchButton = new JButton("Search");
		var cancelButton = new JButton("Cancel");
		final S3FindRequest[] chosen = new S3FindRequest[1];

		searchButton.addActionListener(event -> {
			String pattern = patternField.getText() == null ? "" : patternField.getText().trim();
			if (pattern.isEmpty()) {
				pattern = "*";
			}
			chosen[0] = new S3FindRequest(profileId, bucket, prefix, pattern,
					recursive.isSelected(), caseSensitive.isSelected());
			dialog.dispose();
		});
		cancelButton.addActionListener(event -> dialog.dispose());

		dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(searchButton);
		buttons.add(cancelButton);

		var body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		for (JComponent part : new JComponent[] {patternPanel, locationPanel, recursive, caseSensitive}) {
			part.setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(part);
			body.add(Box.createVerticalStrut(8));
		}

		var content = new JPanel(new BorderLayout(0, 10));
		content.add(body, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(searchButton);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(470, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(patternField::requestFocusInWindow);
		dialog.setVisible(true);

		return chosen[0];
	}
}
