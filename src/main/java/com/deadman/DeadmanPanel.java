package com.deadman;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.net.URI;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

@Slf4j
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

		// Discord button
		JButton discordButton = new JButton("Join the Deadman Discord");
		discordButton.setAlignmentX(CENTER_ALIGNMENT);
		discordButton.addActionListener(e ->
		{
			try
			{
				Desktop.getDesktop().browse(new URI("https://discord.gg/wFbewDmCbp"));
			}
			catch (Exception ex)
			{
				log.debug("Failed to open Discord link", ex);
			}
		});

		// Bottom panel wrapping breach timer and discord link
		JPanel bottomPanel = new JPanel();
		bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
		bottomPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		bottomPanel.add(breachPanel);
		bottomPanel.add(discordButton);

		add(bottomPanel, BorderLayout.SOUTH);
	}

	public void shutdown()
	{
		breachPanel.stopTimer();
		lookupPanel.stopAutoRefresh();
	}
}
