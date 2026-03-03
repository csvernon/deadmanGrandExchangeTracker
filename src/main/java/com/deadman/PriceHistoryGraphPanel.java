package com.deadman;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class PriceHistoryGraphPanel extends JPanel
{
	private static final int GRAPH_HEIGHT = 180;
	private static final int LEFT_MARGIN = 30;
	private static final int RIGHT_MARGIN = 10;
	private static final int TOP_MARGIN = 20;
	private static final int BOTTOM_MARGIN = 30;
	private static final int DOT_RADIUS = 3;
	private static final int Y_TICK_COUNT = 5;
	private static final int X_TICK_COUNT = 4;

	private static final Color BUY_COLOR = new Color(0, 200, 83);
	private static final Color SELL_COLOR = new Color(255, 82, 82);
	private static final Color GRID_COLOR = new Color(ColorScheme.MEDIUM_GRAY_COLOR.getRed(),
		ColorScheme.MEDIUM_GRAY_COLOR.getGreen(),
		ColorScheme.MEDIUM_GRAY_COLOR.getBlue(), 80);

	private final List<GeTrade> trades;

	public PriceHistoryGraphPanel(List<GeTrade> trades)
	{
		this.trades = trades != null ? trades : new ArrayList<>();
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		setPreferredSize(new Dimension(0, GRAPH_HEIGHT));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, GRAPH_HEIGHT));
		setMinimumSize(new Dimension(100, GRAPH_HEIGHT));
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			int w = getWidth();
			int h = getHeight();
			int plotLeft = LEFT_MARGIN;
			int plotRight = w - RIGHT_MARGIN;
			int plotTop = TOP_MARGIN;
			int plotBottom = h - BOTTOM_MARGIN;
			int plotW = plotRight - plotLeft;
			int plotH = plotBottom - plotTop;

			if (trades.size() < 2 || plotW < 20 || plotH < 20)
			{
				g2.setFont(FontManager.getRunescapeSmallFont());
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				String msg = "Not enough data to graph";
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
				return;
			}

			// Compute ranges
			long minTime = Long.MAX_VALUE, maxTime = Long.MIN_VALUE;
			int minPrice = Integer.MAX_VALUE, maxPrice = Integer.MIN_VALUE;
			for (GeTrade t : trades)
			{
				int price = GePriceLookupPanel.getActualPrice(t);
				long time = t.getTimestamp();
				if (time < minTime) minTime = time;
				if (time > maxTime) maxTime = time;
				if (price < minPrice) minPrice = price;
				if (price > maxPrice) maxPrice = price;
			}

			// Pad ranges for edge cases
			if (minTime == maxTime)
			{
				minTime -= 3600_000;
				maxTime += 3600_000;
			}
			if (minPrice == maxPrice)
			{
				minPrice = Math.max(0, minPrice - 100);
				maxPrice += 100;
			}
			// Add 5% padding to price range
			int pricePad = Math.max(1, (maxPrice - minPrice) / 20);
			minPrice = Math.max(0, minPrice - pricePad);
			maxPrice += pricePad;

			long timeRange = maxTime - minTime;
			int priceRange = maxPrice - minPrice;

			// Guard against zero ranges (shouldn't happen after padding, but be safe)
			if (timeRange <= 0 || priceRange <= 0)
			{
				return;
			}

			// Draw grid lines
			g2.setStroke(new BasicStroke(1));
			Font smallFont = FontManager.getRunescapeSmallFont().deriveFont(9f);
			g2.setFont(smallFont);
			FontMetrics fm = g2.getFontMetrics();

			// Y-axis grid + labels
			for (int i = 0; i <= Y_TICK_COUNT; i++)
			{
				int y = plotBottom - (int) ((long) i * plotH / Y_TICK_COUNT);
				g2.setColor(GRID_COLOR);
				g2.drawLine(plotLeft, y, plotRight, y);

				int priceAtTick = minPrice + (int) ((long) i * priceRange / Y_TICK_COUNT);
				String label = GePriceLookupPanel.formatGp(priceAtTick);
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				g2.drawString(label, plotLeft - fm.stringWidth(label) - 4, y + fm.getAscent() / 2);
			}

			// X-axis grid + labels
			SimpleDateFormat dateFmt = new SimpleDateFormat("MM/dd HH:mm");
			for (int i = 0; i <= X_TICK_COUNT; i++)
			{
				int x = plotLeft + (int) ((long) i * plotW / X_TICK_COUNT);
				g2.setColor(GRID_COLOR);
				g2.drawLine(x, plotTop, x, plotBottom);

				long timeAtTick = minTime + (long) i * timeRange / X_TICK_COUNT;
				String label = dateFmt.format(new Date(timeAtTick));
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				int labelW = fm.stringWidth(label);
				int labelX = x - labelW / 2;
				// Clamp to plot bounds
				labelX = Math.max(plotLeft, Math.min(labelX, plotRight - labelW));
				g2.drawString(label, labelX, plotBottom + fm.getHeight() + 2);
			}

			// Plot border
			g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
			g2.drawRect(plotLeft, plotTop, plotW, plotH);

			// Split into buy and sell series, sorted oldest first
			List<GeTrade> buys = new ArrayList<>();
			List<GeTrade> sells = new ArrayList<>();
			for (GeTrade t : trades)
			{
				if (t.isBuy())
				{
					buys.add(t);
				}
				else
				{
					sells.add(t);
				}
			}
			buys.sort(Comparator.comparingLong(GeTrade::getTimestamp));
			sells.sort(Comparator.comparingLong(GeTrade::getTimestamp));

			// Draw lines and dots for each series
			drawSeries(g2, buys, BUY_COLOR, plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
			drawSeries(g2, sells, SELL_COLOR, plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);

			// Legend (above plot area)
			g2.setFont(smallFont);
			int legendY = plotTop - 8;

			g2.setColor(BUY_COLOR);
			g2.fillOval(plotLeft, legendY - 6, 8, 8);
			g2.drawString("Buy", plotLeft + 12, legendY);

			g2.setColor(SELL_COLOR);
			g2.fillOval(plotLeft + 42, legendY - 6, 8, 8);
			g2.drawString("Sell", plotLeft + 54, legendY);
		}
		finally
		{
			g2.dispose();
		}
	}

	private void drawSeries(Graphics2D g2, List<GeTrade> series, Color color,
		int plotLeft, int plotTop, int plotW, int plotH,
		long minTime, long timeRange, int minPrice, int priceRange)
	{
		if (series.isEmpty())
		{
			return;
		}

		int prevX = -1, prevY = -1;
		g2.setStroke(new BasicStroke(1.5f));

		for (GeTrade t : series)
		{
			int price = GePriceLookupPanel.getActualPrice(t);
			long time = t.getTimestamp();

			int x = plotLeft + (int) ((time - minTime) * plotW / timeRange);
			int y = plotTop + plotH - (int) ((long) (price - minPrice) * plotH / priceRange);

			// Clamp to plot area
			x = Math.max(plotLeft, Math.min(x, plotLeft + plotW));
			y = Math.max(plotTop, Math.min(y, plotTop + plotH));

			// Draw connecting line
			if (prevX >= 0)
			{
				g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 100));
				g2.drawLine(prevX, prevY, x, y);
			}

			// Draw dot
			g2.setColor(color);
			g2.fillOval(x - DOT_RADIUS, y - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);

			prevX = x;
			prevY = y;
		}
	}
}
