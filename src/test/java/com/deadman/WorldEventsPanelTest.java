package com.deadman;

import com.deadman.world.EventSession;
import com.deadman.world.LocationIndex;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
import net.runelite.client.util.Filepath;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorldEventsPanelTest
{
	@Test
	public void rendersWrappedLocationsAtSidebarWidth() throws Exception
	{
		EventSession events = active();
		for (String text : new String[]{"A Deadman's Chest has spawned at north of the Warriors' Guild!",
			"A breach has spawned at The southern Asgarnian Kingdom! (Multi).",
			"A breach has spawned at an unknown long location with several landmarks! (Single)."})
		{
			events.message(ChatMessageType.BROADCAST, text, Instant.now());
		}
		SwingUtilities.invokeAndWait(() ->
		{
			BossBreachPanel panel = new BossBreachPanel();
			try
			{
				panel.setEvents(events.rows());
				panel.setSize(225, 445);
				layout(panel);
				assertWrappedAreas(panel);
				assertLocationHeadingIsVisible(panel);
				BufferedImage image = new BufferedImage(225, 445, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				panel.printAll(graphics);
				graphics.dispose();
				Filepath directory = Filepath.Unchecked.getRooted(Paths.get("build").toAbsolutePath()).joinSegment("reports");
				directory.createDirectories();
				Filepath output = directory.joinSegment("world-events-panel.png");
				try (OutputStream stream = output.openOutputStream())
				{
					ImageIO.write(image, "png", stream);
				}
			}
			catch (Exception ex)
			{
				throw new AssertionError(ex);
			}
			finally
			{
				panel.stopTimer();
			}
		});
	}

	private static void layout(Container container)
	{
		container.doLayout();
		for (Component component : container.getComponents())
		{
			if (component instanceof Container)
			{
				layout((Container) component);
			}
		}
	}

	private static void assertWrappedAreas(Container container)
	{
		for (Component component : container.getComponents())
		{
			if (component instanceof JTextArea)
			{
				JTextArea area = (JTextArea) component;
				assertTrue(area.getLineWrap());
				assertTrue("Location must fit sidebar", area.getWidth() > 0 && area.getWidth() <= 205);
				assertTrue("Location needs two visible lines", area.getHeight() >= area.getFontMetrics(area.getFont()).getHeight() * 2);
			}
			if (component instanceof Container)
			{
				assertWrappedAreas((Container) component);
			}
		}
	}

	private static void assertLocationHeadingIsVisible(Container container)
	{
		for (Component component : container.getComponents())
		{
			if (component instanceof JLabel && "Active Locations".equals(((JLabel) component).getText()))
			{
				assertEquals("Heading needs contrast on dark background", Color.WHITE, component.getForeground());
			}
			if (component instanceof Container)
			{
				assertLocationHeadingIsVisible((Container) component);
			}
		}
	}

	@Test
	public void displaysCombatLocationAndUnmappedWarning() throws Exception
	{
		EventSession events = active();
		events.message(ChatMessageType.BROADCAST, "A breach has spawned at mystery place 999! (Multi).", Instant.now());
		SwingUtilities.invokeAndWait(() ->
		{
			WorldEventsPanel panel = new WorldEventsPanel();
			panel.setEvents(events.rows());
			List<String> text = textOf(panel);
			assertTrue(text.contains("Breach (Multi)"));
			assertTrue(text.contains("mystery place 999"));
			assertTrue(text.contains("Unmapped"));
			panel.stop();
		});
	}

	@Test
	public void stoppedPanelIgnoresQueuedUpdates() throws Exception
	{
		EventSession events = active();
		events.message(ChatMessageType.BROADCAST, "A Deadman's Chest has spawned at Varrock!", Instant.now());
		SwingUtilities.invokeAndWait(() ->
		{
			WorldEventsPanel panel = new WorldEventsPanel();
			panel.stop();
			panel.setEvents(events.rows());
			assertFalse(textOf(panel).contains("Varrock"));
		});
	}

	private static List<String> textOf(Container container)
	{
		List<String> result = new ArrayList<>();
		for (Component component : container.getComponents())
		{
			if (component instanceof JLabel)
			{
				result.add(((JLabel) component).getText());
			}
			if (component instanceof JTextArea)
			{
				result.add(((JTextArea) component).getText());
			}
			if (component instanceof Container)
			{
				result.addAll(textOf((Container) component));
			}
		}
		return result;
	}

	private static EventSession active()
	{
		EventSession session = new EventSession(new DeadmanConfig(){}, LocationIndex.bundled(new Gson()));
		session.session(GameState.LOGGED_IN, 345);
		return session;
	}
}
