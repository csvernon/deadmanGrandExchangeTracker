package com.deadman;

import com.deadman.world.EventNotice;
import com.deadman.world.EventRecord;
import com.deadman.world.MarkerArt;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public final class WorldEventsPanel extends JPanel
{
	private final JPanel rows = new JPanel();
	private final JScrollPane scroll;
	private final Map<JLabel, Instant> expiryLabels = new HashMap<>();
	private boolean stopped;

	public WorldEventsPanel()
	{
		setLayout(new BorderLayout(0, 6));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));
		JLabel heading = new JLabel("Active Locations");
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(Color.WHITE);
		add(heading, BorderLayout.NORTH);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		rows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll = new JScrollPane(rows, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
		setEvents(java.util.Collections.emptyList());
	}

	public void setEvents(List<EventRecord> events)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setEvents(events));
			return;
		}
		if (stopped)
		{
			return;
		}
		rows.removeAll();
		expiryLabels.clear();
		if (events.isEmpty())
		{
			JLabel empty = new JLabel("No active locations");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			rows.add(empty);
		}
		for (EventRecord event : events)
		{
			rows.add(row(event));
		}
		scroll.setPreferredSize(new Dimension(200, events.isEmpty() ? 22 : Math.min(180, events.size() * 96)));
		updateTimers();
		rows.revalidate();
		rows.repaint();
		revalidate();
	}

	private JPanel row(EventRecord event)
	{
		JPanel row = new JPanel(new BorderLayout(0, 3));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(4, 0, 8, 0));
		String title = event.getType() == EventNotice.Type.CHEST ? "Deadman's Chest" : "Breach";
		if (event.getCombat() != null)
		{
			title += event.getCombat() == EventNotice.Combat.MULTI ? " (Multi)" : " (Single)";
		}
		JLabel heading = new JLabel(title, new ImageIcon(MarkerArt.icon(event.getType())), JLabel.LEFT);
		heading.setForeground(MarkerArt.color(event.getType()));
		heading.setFont(FontManager.getRunescapeBoldFont());
		row.add(heading, BorderLayout.NORTH);
		JTextArea location = new JTextArea(event.getPlace());
		location.setFont(FontManager.getRunescapeSmallFont());
		location.setForeground(Color.WHITE);
		location.setBackground(ColorScheme.DARK_GRAY_COLOR);
		location.setEditable(false);
		location.setLineWrap(true);
		location.setWrapStyleWord(true);
		location.setColumns(20);
		location.setRows(2);
		location.setToolTipText(event.getPlace());
		row.add(location, BorderLayout.CENTER);
		JPanel details = new JPanel(new BorderLayout(4, 0));
		details.setBackground(ColorScheme.DARK_GRAY_COLOR);
		String note = event.getLocation().note();
		JLabel precisionLabel = new JLabel(note);
		precisionLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		precisionLabel.setFont(FontManager.getRunescapeSmallFont());
		precisionLabel.setToolTipText(note);
		details.add(precisionLabel, BorderLayout.CENTER);
		JLabel remaining = new JLabel();
		remaining.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		remaining.setFont(FontManager.getRunescapeSmallFont());
		remaining.setToolTipText("Estimated time remaining");
		expiryLabels.put(remaining, event.getExpires());
		details.add(remaining, BorderLayout.EAST);
		row.add(details, BorderLayout.SOUTH);
		return row;
	}

	public void updateTimers()
	{
		Instant now = Instant.now();
		expiryLabels.forEach((label, until) ->
		{
			long seconds = Math.max(0, Duration.between(now, until).getSeconds());
			label.setText(String.format("%02d:%02d", seconds / 60, seconds % 60));
		});
	}

	public void stop()
	{
		stopped = true;
		expiryLabels.clear();
	}
}
