package com.deadman;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class DeadmanPanel extends PluginPanel
{
	private final BossBreachPanel breachPanel;
	private final GePriceLookupPanel lookupPanel;

	public DeadmanPanel(BossBreachPanel breachPanel, GePriceLookupPanel lookupPanel)
	{
		super(false);
		this.breachPanel = breachPanel;
		this.lookupPanel = lookupPanel;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		// GE Price Lookup on top (takes remaining space)
		add(lookupPanel, BorderLayout.CENTER);

		// Separator + Breach Timer at the bottom
		JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
		separator.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);

		breachPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 0, 0, 0)
		));

		add(breachPanel, BorderLayout.SOUTH);
	}

	public void shutdown()
	{
		breachPanel.stopTimer();
		lookupPanel.stopAutoRefresh();
	}
}
