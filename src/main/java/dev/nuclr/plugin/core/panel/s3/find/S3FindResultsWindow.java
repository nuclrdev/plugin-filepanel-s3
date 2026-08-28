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
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.s3.S3Error;
import dev.nuclr.plugin.core.panel.s3.ui.Dialogs;

/**
 * The non-modal results window for a find.
 *
 * <p>Implements {@link S3FindService.Listener}, so hits appear as the search walks the bucket rather
 * than after it. Non-modal on purpose: a search over a large prefix can run for a while, and the
 * user should be able to keep working in the panels meanwhile — and stop the search the moment they
 * see what they were looking for.
 *
 * <p>Activating a hit takes the panel to it; the "Panel" button hands the whole result set to a
 * temporary panel, so a set of matches scattered across a bucket can be worked on as one listing.
 */
public final class S3FindResultsWindow extends JDialog implements S3FindService.Listener {

	private static final long serialVersionUID = 1L;

	private final transient DefaultListModel<NuclrResource> model = new DefaultListModel<>();

	private final JList<NuclrResource> list = new JList<>(model);

	private final JLabel status = new JLabel("Searching…");

	private final JButton stopButton = new JButton("Stop");

	private final JButton panelButton = new JButton("Panel");

	private final JButton closeButton = new JButton("Close");

	private final transient Consumer<NuclrResource> onActivate;

	private final transient Consumer<List<NuclrResource>> onSendToPanel;

	private transient S3FindService.SearchHandle handle;

	private volatile boolean finished;

	/**
	 * Build the window for one search.
	 *
	 * @param owner         the window to anchor against
	 * @param request       what is being searched for
	 * @param onActivate    called when a hit is activated, to navigate the panel to it
	 * @param onSendToPanel called to open the whole result set in a temporary panel
	 */
	public S3FindResultsWindow(Window owner, S3FindRequest request, Consumer<NuclrResource> onActivate,
			Consumer<List<NuclrResource>> onSendToPanel) {

		super(owner, "Find results — " + request.namePattern(), ModalityType.MODELESS);
		this.onActivate = onActivate;
		this.onSendToPanel = onSendToPanel;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, list.getFont().getSize()));
		list.setCellRenderer(pathRenderer());
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
					activateSelection();
				}
			}
		});
		list.getInputMap(JComponent.WHEN_FOCUSED)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.activate");
		list.getActionMap().put("find.activate", Dialogs.action(this::activateSelection));

		stopButton.addActionListener(event -> stopSearch());
		panelButton.setToolTipText("Open these results in a temporary panel");
		panelButton.setEnabled(false);
		panelButton.addActionListener(event -> sendResultsToPanel());
		closeButton.addActionListener(event -> dispose());

		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(stopButton);
		buttons.add(panelButton);
		buttons.add(closeButton);

		var content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(status, BorderLayout.NORTH);
		content.add(new JScrollPane(list), BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);

		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.results.close");
		getRootPane().getActionMap().put("find.results.close", Dialogs.action(this::dispose));

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent event) {
				stopSearch();
			}
		});

		setPreferredSize(new Dimension(900, 520));
		setMinimumSize(new Dimension(640, 360));
		pack();
		setLocationRelativeTo(owner);
	}

	/**
	 * Bind the running search so this window can stop it.
	 *
	 * @param handle the search handle
	 */
	public void bind(S3FindService.SearchHandle handle) {
		this.handle = handle;
	}

	@Override
	public void onMatch(NuclrResource resource) {
		model.addElement(resource);
		panelButton.setEnabled(onSendToPanel != null);
		refreshStatus(-1);
	}

	@Override
	public void onProgress(long scanned, long matched) {
		if (!finished) {
			refreshStatus(scanned);
		}
	}

	@Override
	public void onComplete(long scanned, long matched, boolean cancelled, S3Error error) {

		finished = true;
		stopButton.setEnabled(false);

		if (error != null) {
			status.setText("Search failed after " + scanned + " scanned — " + error.describe());
			return;
		}
		status.setText("Search " + (cancelled ? "stopped" : "complete") + " — "
				+ model.getSize() + " match(es), " + scanned + " scanned");
	}

	private void refreshStatus(long scanned) {
		var text = new StringBuilder().append(model.getSize()).append(" match(es)");
		if (scanned >= 0) {
			text.append(" — ").append(scanned).append(" scanned");
		}
		text.append(" — searching…");
		status.setText(text.toString());
	}

	private void stopSearch() {
		if (handle != null) {
			handle.cancel();
		}
	}

	private void activateSelection() {
		NuclrResource selected = list.getSelectedValue();
		if (selected != null && onActivate != null) {
			onActivate.accept(selected);
		}
	}

	/** Hand the whole result set to a temporary panel and close this window. */
	private void sendResultsToPanel() {
		if (onSendToPanel == null || model.isEmpty()) {
			return;
		}
		var snapshot = new ArrayList<NuclrResource>(model.getSize());
		for (int i = 0; i < model.getSize(); i++) {
			snapshot.add(model.getElementAt(i));
		}
		onSendToPanel.accept(snapshot);
		dispose();
	}

	/** Show each hit by its full {@code s3://} path, so matches from different folders are unambiguous. */
	private static DefaultListCellRenderer pathRenderer() {
		return new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index,
					boolean selected, boolean focused) {
				String text = value instanceof NuclrResource resource
						? (resource.getFullPath() != null ? resource.getFullPath() : resource.getName())
						: String.valueOf(value);
				return super.getListCellRendererComponent(list, text, index, selected, focused);
			}
		};
	}
}
