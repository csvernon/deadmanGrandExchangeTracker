package com.deadman;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

public class BossBreachPanel extends PluginPanel
{
	private static final int BREACH_INTERVAL_HOURS = 4;
	private static final int FIRST_BREACH_HOUR = 2;
	private static final int LAST_BREACH_HOUR = 22;
	private static final int BREACH_DURATION_MINUTES = 15;

	private static final DateTimeFormatter LOCAL_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE h:mm a");

	private final JLabel statusLabel = new JLabel();
	private final JLabel countdownLabel = new JLabel();
	private final JLabel countdownDescription = new JLabel();
	private final JLabel breachTimeLabel = new JLabel();
	private final Timer timer;

	public BossBreachPanel()
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel contentPanel = new JPanel();
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("Boss Breach");
		titleLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 16f));
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setAlignmentX(CENTER_ALIGNMENT);

		statusLabel.setFont(FontManager.getRunescapeFont().deriveFont(14f));
		statusLabel.setAlignmentX(CENTER_ALIGNMENT);

		countdownLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 24f));
		countdownLabel.setForeground(Color.WHITE);
		countdownLabel.setAlignmentX(CENTER_ALIGNMENT);

		countdownDescription.setFont(FontManager.getRunescapeSmallFont());
		countdownDescription.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		countdownDescription.setAlignmentX(CENTER_ALIGNMENT);

		breachTimeLabel.setFont(FontManager.getRunescapeSmallFont());
		breachTimeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		breachTimeLabel.setAlignmentX(CENTER_ALIGNMENT);

		contentPanel.add(titleLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 12)));
		contentPanel.add(statusLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		contentPanel.add(countdownLabel);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 4)));
		contentPanel.add(countdownDescription);
		contentPanel.add(Box.createRigidArea(new Dimension(0, 4)));
		contentPanel.add(breachTimeLabel);

		add(contentPanel, BorderLayout.NORTH);

		timer = new Timer(1000, e -> update());
		update();
		timer.start();
	}

	public void stopTimer()
	{
		timer.stop();
	}

	private void update()
	{
		try
		{
			BreachState state = calculateBreachState(ZonedDateTime.now(ZoneOffset.UTC));
			String localTime = state.nextBreachStart
				.withZoneSameInstant(ZoneId.systemDefault())
				.format(LOCAL_TIME_FORMAT);

			if (state.active)
			{
				statusLabel.setText("ACTIVE");
				statusLabel.setForeground(Color.GREEN);
				countdownLabel.setText(formatDuration(state.timeRemaining));
				countdownDescription.setText("until breach ends");
				breachTimeLabel.setText("Started " + localTime);
			}
			else
			{
				statusLabel.setText("Inactive");
				statusLabel.setForeground(Color.RED);
				countdownLabel.setText(formatDuration(state.timeRemaining));
				countdownDescription.setText("until next breach");
				breachTimeLabel.setText(localTime);
			}
		}
		catch (Exception e)
		{
			countdownLabel.setText("--:--:--");
			breachTimeLabel.setText("");
		}
	}

	static BreachState calculateBreachState(ZonedDateTime now)
	{
		ZonedDateTime saturday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
			.withHour(0).withMinute(0).withSecond(0).withNano(0);

		List<ZonedDateTime> breachTimes = buildBreachTimes(saturday);

		// Check if currently inside a breach window
		for (ZonedDateTime breachStart : breachTimes)
		{
			ZonedDateTime breachEnd = breachStart.plusMinutes(BREACH_DURATION_MINUTES);
			if (!now.isBefore(breachStart) && now.isBefore(breachEnd))
			{
				return new BreachState(true, Duration.between(now, breachEnd), breachStart);
			}
		}

		// Find the next future breach
		for (ZonedDateTime breachStart : breachTimes)
		{
			if (now.isBefore(breachStart))
			{
				return new BreachState(false, Duration.between(now, breachStart), breachStart);
			}
		}

		// All breaches this weekend are past, advance to next Saturday
		ZonedDateTime nextSaturday = saturday.plusWeeks(1);
		List<ZonedDateTime> nextBreachTimes = buildBreachTimes(nextSaturday);
		ZonedDateTime nextBreach = nextBreachTimes.get(0);
		return new BreachState(false, Duration.between(now, nextBreach), nextBreach);
	}

	private static List<ZonedDateTime> buildBreachTimes(ZonedDateTime saturday)
	{
		List<ZonedDateTime> times = new ArrayList<>();
		for (int day = 0; day <= 1; day++)
		{
			for (int hour = FIRST_BREACH_HOUR; hour <= LAST_BREACH_HOUR; hour += BREACH_INTERVAL_HOURS)
			{
				times.add(saturday.plusDays(day).withHour(hour).withMinute(0).withSecond(0).withNano(0));
			}
		}
		return times;
	}

	static String formatDuration(Duration duration)
	{
		long totalSeconds = duration.getSeconds();
		long days = totalSeconds / 86400;
		long hours = (totalSeconds % 86400) / 3600;
		long minutes = (totalSeconds % 3600) / 60;
		long seconds = totalSeconds % 60;

		if (days > 0)
		{
			return String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds);
		}
		return String.format("%02d:%02d:%02d", hours, minutes, seconds);
	}

	static class BreachState
	{
		final boolean active;
		final Duration timeRemaining;
		final ZonedDateTime nextBreachStart;

		BreachState(boolean active, Duration timeRemaining, ZonedDateTime nextBreachStart)
		{
			this.active = active;
			this.timeRemaining = timeRemaining;
			this.nextBreachStart = nextBreachStart;
		}
	}
}
