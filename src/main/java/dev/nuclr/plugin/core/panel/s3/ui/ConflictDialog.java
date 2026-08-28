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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * The "File already exists" warning shown when a copy or move would overwrite something already at
 * the destination.
 *
 * <p>Used in both directions — downloading an object onto a local file, and uploading a local file
 * onto an existing key — so it is deliberately transport-agnostic: callers pass already-formatted
 * description strings for the "New" and "Existing" rows rather than a path or a key. The choices
 * mirror the local file-system panel's: Overwrite, Skip, Rename, Append, Cancel, with a "Remember
 * choice" box that applies the same answer to the rest of the run.
 *
 * <p>Resolution marshals to the event dispatch thread and blocks, so the background transfer thread
 * can ask directly.
 */
public final class ConflictDialog {

	/** What to do about one name clash. */
	public enum Action {

		/** Replace what is already there. */
		OVERWRITE,

		/** Leave the existing item and move on. */
		SKIP,

		/** Write under a different name. */
		RENAME,

		/** Append to the existing item; only meaningful for a local destination. */
		APPEND,

		/** Stop the whole operation. */
		CANCEL
	}

	/**
	 * A chosen action and, for {@link Action#RENAME}, the name to use.
	 *
	 * @param action     what to do
	 * @param renameName the new name, or {@code null} to generate one
	 */
	public record Resolution(Action action, String renameName) {

		/**
		 * A resolution with no rename name.
		 *
		 * @param action what to do
		 * @return the resolution
		 */
		public static Resolution of(Action action) {
			return new Resolution(action, null);
		}
	}

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	/** The sticky answer once "Remember choice" was ticked; {@code null} until then. */
	private Resolution remembered;

	/** Create a resolver for one run, which is the scope a remembered choice applies to. */
	public ConflictDialog() {
	}

	/**
	 * Resolve one clash, or return the answer already remembered for this run.
	 *
	 * @param targetLabel       what is about to be written, shown under the title
	 * @param newDetail         the "New" row: the incoming item's size and timestamp
	 * @param existingDetail    the "Existing" row: what is already there
	 * @param defaultRenameName the suggestion pre-filled in the rename prompt
	 * @return what to do
	 */
	public Resolution resolve(String targetLabel, String newDetail, String existingDetail, String defaultRenameName) {
		if (remembered != null) {
			return remembered;
		}
		final Resolution[] result = new Resolution[1];
		Dialogs.onEdtAndWait(() -> result[0] = ask(targetLabel, newDetail, existingDetail, defaultRenameName));
		return result[0];
	}

	private Resolution ask(String targetLabel, String newDetail, String existingDetail, String defaultRenameName) {

		Window owner = Dialogs.activeWindow();
		var dialog = new JDialog(owner, "Warning", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		var title = new JLabel("File already exists", JLabel.CENTER);
		title.setFont(title.getFont().deriveFont(Font.BOLD));

		var header = new JPanel(new BorderLayout(0, 4));
		header.add(title, BorderLayout.NORTH);
		header.add(new JLabel(targetLabel), BorderLayout.CENTER);

		var info = new JPanel(new GridLayout(2, 1, 0, 2));
		info.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		info.add(detailRow("New", newDetail));
		info.add(detailRow("Existing", existingDetail));

		var remember = new JCheckBox("Remember choice");
		final Resolution[] picked = new Resolution[1];

		var overwrite = new JButton("Overwrite");
		var skip = new JButton("Skip");
		var rename = new JButton("Rename");
		var append = new JButton("Append");
		var cancel = new JButton("Cancel");

		overwrite.addActionListener(event -> {
			picked[0] = Resolution.of(Action.OVERWRITE);
			dialog.dispose();
		});
		skip.addActionListener(event -> {
			picked[0] = Resolution.of(Action.SKIP);
			dialog.dispose();
		});
		append.addActionListener(event -> {
			picked[0] = Resolution.of(Action.APPEND);
			dialog.dispose();
		});
		rename.addActionListener(event -> {
			// Remembering a rename stores the policy, not one name: each later clash is auto-named.
			if (remember.isSelected()) {
				picked[0] = Resolution.of(Action.RENAME);
				dialog.dispose();
				return;
			}
			String newName = promptRename(owner, defaultRenameName);
			if (newName == null) {
				return; // back to the warning
			}
			picked[0] = new Resolution(Action.RENAME, newName);
			dialog.dispose();
		});
		cancel.addActionListener(event -> {
			picked[0] = Resolution.of(Action.CANCEL);
			dialog.dispose();
		});

		dialog.getRootPane().registerKeyboardAction(event -> {
			picked[0] = Resolution.of(Action.CANCEL);
			dialog.dispose();
		}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		Dialogs.installArrowTraversal(overwrite, skip, rename, append, cancel);

		var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(overwrite);
		buttons.add(skip);
		buttons.add(rename);
		buttons.add(append);
		buttons.add(cancel);

		var rememberRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		rememberRow.add(remember);

		var center = new JPanel(new BorderLayout(0, 6));
		center.add(header, BorderLayout.NORTH);
		center.add(info, BorderLayout.CENTER);
		center.add(rememberRow, BorderLayout.SOUTH);

		var content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		content.add(center, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(overwrite);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(overwrite::requestFocusInWindow);
		dialog.setVisible(true);

		Resolution resolution = picked[0] != null ? picked[0] : Resolution.of(Action.CANCEL);
		if (remember.isSelected() && resolution.action() != Action.CANCEL) {
			remembered = resolution;
		}
		return resolution;
	}

	/** Ask for a replacement name; {@code null} means the rename was abandoned. */
	private String promptRename(Window owner, String defaultName) {

		var dialog = new JDialog(owner, "Rename", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		var field = new JTextField(defaultName == null ? "" : defaultName, 40);

		var top = new JPanel(new BorderLayout(0, 4));
		top.add(new JLabel("New name:"), BorderLayout.NORTH);
		top.add(field, BorderLayout.CENTER);

		final String[] result = new String[1];

		var ok = new JButton("OK");
		var cancel = new JButton("Cancel");
		ok.addActionListener(event -> {
			String text = field.getText() == null ? "" : field.getText().trim();
			if (text.isEmpty()) {
				return;
			}
			result[0] = text;
			dialog.dispose();
		});
		cancel.addActionListener(event -> dialog.dispose());

		dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(ok);
		buttons.add(cancel);

		var content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		content.add(top, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(480, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(field::requestFocusInWindow);
		dialog.setVisible(true);

		return result[0];
	}

	/**
	 * Generate a free name by numbering: {@code README.md} becomes {@code README (1).md}, counting
	 * up until {@code exists} says the name is available.
	 *
	 * @param name   the clashing name
	 * @param exists tells whether a candidate is taken
	 * @return a name that is free
	 */
	public static String autoRenameName(String name, Predicate<String> exists) {
		int dot = name.lastIndexOf('.');
		String base = dot <= 0 ? name : name.substring(0, dot);
		String extension = dot <= 0 ? "" : name.substring(dot);
		for (int i = 1; ; i++) {
			String candidate = base + " (" + i + ")" + extension;
			if (!exists.test(candidate)) {
				return candidate;
			}
		}
	}

	/**
	 * The size and timestamp of a local file, formatted for a detail row.
	 *
	 * @param path the local file
	 * @return the formatted detail, or {@code "?"} when it cannot be read
	 */
	public static String pathDetail(Path path) {
		try {
			BasicFileAttributes attributes =
					Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.size() + "   "
					+ STAMP.format(attributes.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
		} catch (IOException e) {
			return "?";
		}
	}

	private static JComponent detailRow(String label, String detail) {
		var row = new JPanel(new BorderLayout(12, 0));
		row.add(new JLabel(label), BorderLayout.WEST);
		row.add(new JLabel(detail == null ? "" : detail, JLabel.RIGHT), BorderLayout.EAST);
		return row;
	}
}
