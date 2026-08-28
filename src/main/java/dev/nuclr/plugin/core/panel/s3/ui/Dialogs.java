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

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.font.TextAttribute;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.nuclr.plugin.core.panel.s3.S3Error;
import lombok.extern.slf4j.Slf4j;

/**
 * The small Swing conveniences every dialog in this plugin needs: running work on the event
 * dispatch thread and waiting for it, finding a sensible owner window, arrow-key traversal across a
 * button row, and the plain message boxes.
 *
 * <p>Plugins load in isolated classloaders, so this deliberately duplicates helpers that exist in
 * the commander and in sibling panels rather than reaching for them.
 */
@Slf4j
public final class Dialogs {

	private Dialogs() {}

	/**
	 * Run something on the event dispatch thread and block until it finishes.
	 *
	 * <p>Safe from any thread: already on the dispatch thread, it simply runs. This is what lets a
	 * background transfer put a modal question on screen and wait for the answer.
	 *
	 * @param runnable the work to run
	 */
	public static void onEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Could not run a dialog on the event dispatch thread: {}", e.getMessage(), e);
		}
	}

	/**
	 * The window a dialog should be positioned against: the active one, whatever it is.
	 *
	 * @return the active window, or {@code null} when there is none
	 */
	public static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}

	/**
	 * The commander's own frame, for windows that should outlive a transient dialog rather than be
	 * anchored to one that is about to close.
	 *
	 * @return the main frame, or the active window when no frame is showing
	 */
	public static Window mainWindow() {
		for (Frame frame : Frame.getFrames()) {
			if (frame.isShowing()) {
				return frame;
			}
		}
		return activeWindow();
	}

	/**
	 * Show a modal error box.
	 *
	 * @param title   the dialog title
	 * @param message the message
	 */
	public static void error(String title, String message) {
		SwingUtilities.invokeLater(() ->
				JOptionPane.showMessageDialog(activeWindow(), message, title, JOptionPane.ERROR_MESSAGE));
	}

	/**
	 * Show a modal error box describing an S3 failure, unless the failure was the user cancelling —
	 * which needs no announcement.
	 *
	 * @param title the dialog title
	 * @param error the failure
	 */
	public static void error(String title, S3Error error) {
		if (error == null || error instanceof S3Error.Cancelled) {
			return;
		}
		error(title, error.describe());
	}

	/**
	 * Show a modal information box.
	 *
	 * @param title   the dialog title
	 * @param message the message
	 */
	public static void info(String title, String message) {
		SwingUtilities.invokeLater(() ->
				JOptionPane.showMessageDialog(activeWindow(), message, title, JOptionPane.INFORMATION_MESSAGE));
	}

	/**
	 * Ask a yes/no question and block for the answer, safe to call from any thread.
	 *
	 * @param title    the dialog title
	 * @param message  the question
	 * @return {@code true} when the user confirmed
	 */
	public static boolean confirm(String title, String message) {
		final boolean[] confirmed = {false};
		onEdtAndWait(() -> confirmed[0] = JOptionPane.showConfirmDialog(activeWindow(), message, title,
				JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION);
		return confirmed[0];
	}

	/**
	 * Prompt for a line of text and block for it, safe to call from any thread.
	 *
	 * @param title        the dialog title
	 * @param message      the prompt
	 * @param initialValue the pre-filled value, or {@code null}
	 * @return what was typed, or {@code null} if cancelled
	 */
	public static String prompt(String title, String message, String initialValue) {
		final String[] answer = new String[1];
		onEdtAndWait(() -> answer[0] = (String) JOptionPane.showInputDialog(activeWindow(), message, title,
				JOptionPane.PLAIN_MESSAGE, null, null, initialValue));
		return answer[0];
	}

	/**
	 * Wire the left and right arrow keys to move focus across a row of buttons, wrapping at the
	 * ends, so these dialogs are fully navigable without reaching for Tab.
	 *
	 * @param buttons the buttons in visual order
	 */
	public static void installArrowTraversal(JButton... buttons) {
		for (int i = 0; i < buttons.length; i++) {
			JButton self = buttons[i];
			JButton left = buttons[(i - 1 + buttons.length) % buttons.length];
			JButton right = buttons[(i + 1) % buttons.length];

			self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "focusLeft");
			self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "focusRight");
			self.getActionMap().put("focusLeft", action(left::requestFocusInWindow));
			self.getActionMap().put("focusRight", action(right::requestFocusInWindow));
		}
	}

	/**
	 * A label-sized button that looks and behaves like a hyperlink: underlined, in the
	 * look-and-feel's link colour, with the address as its tooltip.
	 *
	 * <p>It stays a button rather than becoming a styled {@code JLabel} so it keeps everything a
	 * button already gives us — Tab focus, a focus ring, and activation with Space.
	 *
	 * @param text  the visible text
	 * @param url   the address to open when it is activated
	 * @return the link
	 */
	public static JButton link(String text, String url) {

		var button = new JButton(text);
		button.setToolTipText(url);
		button.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setFocusPainted(true);
		button.setOpaque(false);
		button.setMargin(new Insets(0, 0, 0, 0));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.setForeground(linkColor());

		Map<TextAttribute, Object> attributes = new HashMap<>();
		attributes.put(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
		button.setFont(button.getFont().deriveFont(attributes));

		button.addActionListener(event -> browse(url));
		return button;
	}

	/**
	 * Open an address in the user's browser.
	 *
	 * <p>Where the desktop cannot launch one — a bare Linux session, a locked-down environment —
	 * the address is copied to the clipboard instead, so a click never simply does nothing.
	 *
	 * @param url the address to open
	 */
	public static void browse(String url) {

		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				return;
			}
		} catch (Exception e) {
			log.warn("Could not open {} in a browser: {}", url, e.getMessage(), e);
		}

		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
			info("Open in a browser", "This machine has no browser we can launch."
					+ System.lineSeparator() + "The address has been copied to the clipboard:"
					+ System.lineSeparator() + System.lineSeparator() + url);
		} catch (Exception e) {
			log.warn("Could not copy {} to the clipboard: {}", url, e.getMessage(), e);
			info("Open in a browser", url);
		}
	}

	/** The look-and-feel's link colour, falling back to a blue that reads on either theme. */
	private static Color linkColor() {
		Color color = UIManager.getColor("Component.linkColor");
		if (color == null) {
			color = UIManager.getColor("Hyperlink.linkColor");
		}
		return color == null ? new Color(0x589DF6) : color;
	}

	/**
	 * Wrap a runnable as a Swing action.
	 *
	 * @param runnable what the action does
	 * @return the action
	 */
	public static AbstractAction action(Runnable runnable) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent event) {
				runnable.run();
			}
		};
	}
}
