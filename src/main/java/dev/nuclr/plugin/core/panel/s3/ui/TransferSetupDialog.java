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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * The setup dialog shown when a copy or move starts (F5 and F6): where the items are going, and what
 * to do about names already in use there.
 *
 * <p>Two shapes, because the two directions differ in one respect. Copying <em>out</em> of S3 lands
 * in a local folder the user may want to change, so the destination is editable. Copying <em>into</em>
 * S3 lands in the bucket prefix the other panel already has open, so the destination is shown but
 * fixed — retyping an {@code s3://} URL here would just be a way to make a typo.
 *
 * <p>Blocks for the answer and may be called from any thread.
 */
@Slf4j
public final class TransferSetupDialog {

	/** Transfers run at once unless the user picks otherwise. */
	public static final int DEFAULT_CONCURRENCY = 4;

	private TransferSetupDialog() {}

	/**
	 * The choices for a transfer out of S3 to a local folder.
	 *
	 * @param destination the folder to write into
	 * @param existing    what to do about existing files, or {@code null} to ask each time
	 * @param concurrency how many objects to fetch at once
	 */
	public record Download(Path destination, ConflictDialog.Action existing, int concurrency) {}

	/**
	 * The choices for a transfer into S3.
	 *
	 * @param existing    what to do about existing keys, or {@code null} to ask each time
	 * @param concurrency how many files to send at once
	 */
	public record Upload(ConflictDialog.Action existing, int concurrency) {}

	/**
	 * Ask where to put items being copied out of S3.
	 *
	 * @param title         the dialog title, {@code Copy} or {@code Move}
	 * @param header        what is being transferred, such as {@code report.pdf} or {@code 3 items}
	 * @param defaultTarget the folder to pre-fill, normally the other panel's
	 * @return the choices, or {@code null} if cancelled
	 */
	public static Download showDownload(String title, String header, Path defaultTarget) {
		final Download[] result = new Download[1];
		Dialogs.onEdtAndWait(() -> result[0] = buildDownload(title, header, defaultTarget));
		return result[0];
	}

	/**
	 * Ask how to handle existing keys for items being copied into S3.
	 *
	 * @param title            the dialog title, {@code Copy} or {@code Move}
	 * @param header           what is being transferred
	 * @param destinationLabel the {@code s3://bucket/prefix} being written into
	 * @return the choices, or {@code null} if cancelled
	 */
	public static Upload showUpload(String title, String header, String destinationLabel) {
		final Upload[] result = new Upload[1];
		Dialogs.onEdtAndWait(() -> result[0] = buildUpload(title, header, destinationLabel));
		return result[0];
	}

	private static Download buildDownload(String title, String header, Path defaultTarget) {

		Window owner = Dialogs.activeWindow();
		var dialog = newDialog(owner, title);

		var destinationField = new JTextField(defaultTarget != null ? defaultTarget.toString() : "", 40);
		var destinationPanel = labelled(title + ' ' + header + " to:", destinationField);
		var existing = existingCombo();
		var existingRow = existingRow(existing);
		var concurrency = concurrencyCombo();
		var concurrencyRow = concurrencyRow(concurrency, "Fetch at once:");

		var confirmButton = new JButton(title);
		var cancelButton = new JButton("Cancel");
		final Download[] chosen = new Download[1];

		confirmButton.addActionListener(event -> {
			Path destination = parseDestination(destinationField, defaultTarget);
			if (destination == null) {
				return; // empty or unusable; leave the dialog open
			}
			chosen[0] = new Download(destination, existingAction(existing.getSelectedIndex()),
					concurrencyValue(concurrency));
			dialog.dispose();
		});
		cancelButton.addActionListener(event -> dialog.dispose());

		layout(dialog, owner, confirmButton, cancelButton, destinationPanel, existingRow, concurrencyRow);
		SwingUtilities.invokeLater(destinationField::requestFocusInWindow);
		dialog.setVisible(true);

		return chosen[0];
	}

	private static Upload buildUpload(String title, String header, String destinationLabel) {

		Window owner = Dialogs.activeWindow();
		var dialog = newDialog(owner, title);

		var destinationField = new JTextField(destinationLabel, 40);
		destinationField.setEditable(false);
		var destinationPanel = labelled(title + ' ' + header + " to:", destinationField);
		var existing = existingCombo();
		var existingRow = existingRow(existing);
		var concurrency = concurrencyCombo();
		var concurrencyRow = concurrencyRow(concurrency, "Send at once:");

		var confirmButton = new JButton(title);
		var cancelButton = new JButton("Cancel");
		final Upload[] chosen = new Upload[1];

		confirmButton.addActionListener(event -> {
			chosen[0] = new Upload(existingAction(existing.getSelectedIndex()), concurrencyValue(concurrency));
			dialog.dispose();
		});
		cancelButton.addActionListener(event -> dialog.dispose());

		layout(dialog, owner, confirmButton, cancelButton, destinationPanel, existingRow, concurrencyRow);
		SwingUtilities.invokeLater(confirmButton::requestFocusInWindow);
		dialog.setVisible(true);

		return chosen[0];
	}

	// -------------------------------------------------------------------------
	// Shared pieces
	// -------------------------------------------------------------------------

	private static JDialog newDialog(Window owner, String title) {
		var dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		return dialog;
	}

	private static JComboBox<String> existingCombo() {
		return new JComboBox<>(new String[] {"Ask", "Overwrite", "Skip", "Rename", "Append"});
	}

	private static JPanel existingRow(JComboBox<String> existing) {
		var row = new JPanel(new BorderLayout(8, 0));
		row.add(new JLabel("Already existing files:"), BorderLayout.WEST);
		row.add(existing, BorderLayout.CENTER);
		return row;
	}

	/**
	 * How many transfers to run at once.
	 *
	 * <p>Four by default: enough to cover the round-trip latency that dominates a transfer of many
	 * small objects, and low enough that a modest connection is not oversubscribed into timeouts.
	 * Set it to one to get the old strictly-sequential behaviour back.
	 */
	private static JComboBox<String> concurrencyCombo() {
		var combo = new JComboBox<>(new String[] {"1", "2", "4", "8", "16"});
		combo.setSelectedItem(String.valueOf(DEFAULT_CONCURRENCY));
		return combo;
	}

	private static int concurrencyValue(JComboBox<String> combo) {
		try {
			return Math.max(1, Integer.parseInt(String.valueOf(combo.getSelectedItem())));
		} catch (NumberFormatException e) {
			return DEFAULT_CONCURRENCY;
		}
	}

	private static JPanel concurrencyRow(JComboBox<String> concurrency, String label) {
		var row = new JPanel(new BorderLayout(8, 0));
		row.add(new JLabel(label), BorderLayout.WEST);
		row.add(concurrency, BorderLayout.CENTER);
		return row;
	}

	private static JPanel labelled(String label, Component field) {
		var panel = new JPanel(new BorderLayout(0, 4));
		panel.add(new JLabel(label), BorderLayout.NORTH);
		panel.add(field, BorderLayout.CENTER);
		return panel;
	}

	private static void layout(JDialog dialog, Window owner, JButton confirmButton, JButton cancelButton,
			JPanel destinationPanel, JPanel existingRow, JPanel concurrencyRow) {

		dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(confirmButton);
		buttons.add(cancelButton);

		var body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		destinationPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		existingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(destinationPanel);
		body.add(Box.createVerticalStrut(10));
		body.add(existingRow);
		concurrencyRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(Box.createVerticalStrut(10));
		body.add(concurrencyRow);

		var content = new JPanel(new BorderLayout(0, 10));
		content.add(body, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(confirmButton);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
	}

	private static Path parseDestination(JTextField field, Path baseDirectory) {
		String text = field.getText() == null ? "" : field.getText().trim();
		if (text.isEmpty()) {
			return null;
		}
		try {
			Path destination = Path.of(text);
			// A relative path means "relative to the destination panel's folder", not to whatever
			// directory the JVM happens to have been started in.
			if (!destination.isAbsolute() && baseDirectory != null) {
				destination = baseDirectory.resolve(destination).normalize();
			}
			return destination;
		} catch (InvalidPathException e) {
			log.debug("Unusable transfer destination [{}]: {}", text, e.getMessage());
			return null;
		}
	}

	/** Index 0 is "Ask", which means prompt per clash; the rest pre-answer every clash. */
	private static ConflictDialog.Action existingAction(int index) {
		return switch (index) {
			case 1 -> ConflictDialog.Action.OVERWRITE;
			case 2 -> ConflictDialog.Action.SKIP;
			case 3 -> ConflictDialog.Action.RENAME;
			case 4 -> ConflictDialog.Action.APPEND;
			default -> null;
		};
	}
}
