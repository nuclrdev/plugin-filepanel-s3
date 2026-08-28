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
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Error;

/**
 * Confirmation and error prompts for deleting objects.
 *
 * <p>Deleting from a bucket deserves a moment's friction: there is no recycle bin, and on a bucket
 * without versioning the object is simply gone. The confirmation therefore lists exactly what will
 * be removed, by full {@code s3://} path, and a folder delete says how many objects that means
 * rather than just naming the folder.
 *
 * <p>Buttons answer to Tab and to the left and right arrows; Escape picks the safe choice — Cancel
 * when confirming, Skip when reporting a failure. Every method may be called from any thread and
 * blocks for the answer.
 */
public final class DeleteDialogs {

	private static final String TITLE = "Delete";

	private DeleteDialogs() {}

	/**
	 * Confirm deleting a set of objects.
	 *
	 * @param targets     what the user selected
	 * @param objectCount how many objects will actually be removed, counting folder contents
	 * @return {@code true} when the user confirmed
	 */
	public static boolean confirmDelete(List<NuclrResource> targets, long objectCount) {

		boolean single = objectCount == 1;
		var text = new StringBuilder(single
				? "Do you wish to delete this object?"
				: "Do you wish to delete these " + objectCount + " objects?");
		text.append("\n\n");
		for (NuclrResource target : targets) {
			text.append(fullPath(target)).append('\n');
		}
		text.append("\nDeleted objects cannot be recovered unless the bucket has versioning enabled.");

		Component message = buildText(text.toString().stripTrailing(), targets.size() > 1);
		return choose(TITLE, message, "OK", "Cancel");
	}

	/**
	 * Report that one object could not be deleted, and ask whether to carry on.
	 *
	 * @param name  the object that failed
	 * @param error why it failed
	 * @return {@code true} to skip it and continue, {@code false} to abort the whole operation
	 */
	public static boolean error(String name, S3Error error) {
		String detail = error == null ? "The object could not be deleted." : error.describe();
		Component message = buildText("Failed to delete:\n" + name + "\n\n" + detail, false);
		return choose(TITLE, message, "Skip", "Abort");
	}

	private static String fullPath(NuclrResource resource) {
		return resource.getFullPath() != null ? resource.getFullPath() : resource.getName();
	}

	private static Component buildText(String text, boolean scroll) {
		var area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(null);
		if (!scroll) {
			return area;
		}
		var scrollPane = new JScrollPane(area);
		scrollPane.setPreferredSize(new Dimension(520, 260));
		return scrollPane;
	}

	/**
	 * Show a modal two-button dialog. The safe button is the default, takes initial focus, and is
	 * what Escape and closing the window choose — so a reflexive Enter or Escape never deletes
	 * anything.
	 */
	private static boolean choose(String title, Component message, String proceedText, String safeText) {

		final boolean[] proceed = {false};

		Dialogs.onEdtAndWait(() -> {

			Window owner = Dialogs.activeWindow();
			var dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

			var proceedButton = new JButton(proceedText);
			var safeButton = new JButton(safeText);

			proceedButton.addActionListener(event -> {
				proceed[0] = true;
				dialog.dispose();
			});
			safeButton.addActionListener(event -> {
				proceed[0] = false;
				dialog.dispose();
			});

			Dialogs.installArrowTraversal(proceedButton, safeButton);

			dialog.getRootPane().registerKeyboardAction(event -> {
				proceed[0] = false;
				dialog.dispose();
			}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

			var content = new JPanel(new BorderLayout(0, 12));
			content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
			content.add(message, BorderLayout.CENTER);

			var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			buttons.add(proceedButton);
			buttons.add(safeButton);
			content.add(buttons, BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setDefaultButton(safeButton);
			dialog.pack();
			dialog.setLocationRelativeTo(owner);
			SwingUtilities.invokeLater(safeButton::requestFocusInWindow);
			dialog.setVisible(true);
		});

		return proceed[0];
	}
}
