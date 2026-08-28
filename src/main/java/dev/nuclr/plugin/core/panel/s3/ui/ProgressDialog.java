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
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * The modal, cancellable progress dialog behind every long-running S3 operation: copy, move, delete,
 * and the transfers inside them.
 *
 * <p>The work runs on a background virtual thread and receives a {@link NuclrPluginCallback} wired
 * to this dialog. Because the dialog is modal, the event dispatch thread keeps pumping while the
 * caller blocks — which is what lets the work put its own prompts on screen (a file-exists warning,
 * a delete error) and have them stay responsive.
 *
 * <p>{@link #run} marshals itself onto the event dispatch thread, so it may be called from anywhere.
 */
@Slf4j
public final class ProgressDialog {

	private ProgressDialog() {}

	/**
	 * Run work under a progress dialog, blocking until it finishes or is cancelled.
	 *
	 * @param title the dialog title, such as {@code Copy} or {@code Delete}
	 * @param work  the work to run; it receives a callback wired to this dialog
	 */
	public static void run(String title, Consumer<NuclrPluginCallback> work) {
		Dialogs.onEdtAndWait(() -> show(title, work));
	}

	private static void show(String title, Consumer<NuclrPluginCallback> work) {

		Window owner = Dialogs.activeWindow();

		var itemLabel = new JLabel("Preparing…");
		var bar = new JProgressBar(0, 100);
		bar.setStringPainted(true);
		var cancelButton = new JButton("Cancel");

		var dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		var north = new JPanel(new BorderLayout(0, 6));
		north.add(itemLabel, BorderLayout.NORTH);
		north.add(bar, BorderLayout.CENTER);

		var south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		south.add(cancelButton);

		var content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
		content.add(north, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(440, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);

		var cancelled = new AtomicBoolean(false);
		var finished = new AtomicBoolean(false);

		cancelButton.addActionListener(event -> {
			cancelled.set(true);
			cancelButton.setEnabled(false);
			itemLabel.setText("Cancelling…");
		});

		var callback = new NuclrPluginCallback() {

			@Override
			public void onStart(String description) {
				SwingUtilities.invokeLater(() -> itemLabel.setText(description == null ? "" : description));
			}

			@Override
			public void onProgress(long current, long total) {
				SwingUtilities.invokeLater(() -> {
					if (total > 0) {
						bar.setIndeterminate(false);
						bar.setValue((int) Math.min(100, current * 100 / total));
					} else {
						bar.setIndeterminate(true);
					}
				});
			}

			@Override
			public void onComplete() {
				// The dialog closes when the work thread finishes; nothing extra to do here.
			}

			@Override
			public void onError(String description, Exception e) {
				log.warn("{} failed for [{}]: {}", title, description, e == null ? "?" : e.getMessage());
			}

			@Override
			public boolean isCancelled() {
				return cancelled.get();
			}
		};

		Thread.ofVirtual().name("s3-" + title.toLowerCase(java.util.Locale.ROOT)).start(() -> {
			try {
				work.accept(callback);
			} catch (RuntimeException e) {
				log.error("{} failed: {}", title, e.getMessage(), e);
			} finally {
				SwingUtilities.invokeLater(() -> {
					finished.set(true);
					dialog.dispose();
				});
			}
		});

		// Modal: blocks here, pumping the event dispatch thread, until the work disposes the dialog.
		dialog.setVisible(true);

		if (!finished.get()) {
			// Defensive: if the dialog closed some other way, make sure the work sees a cancel.
			cancelled.set(true);
		}
	}
}
