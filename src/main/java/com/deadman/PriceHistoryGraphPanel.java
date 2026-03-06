package com.deadman;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
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
	private static final int GRAPH_HEIGHT = 220;
	private static final int LEFT_MARGIN = 25;
	private static final int RIGHT_MARGIN = 10;
	private static final int TOP_MARGIN = 24;
	private static final int BOTTOM_MARGIN = 28;
	private static final int DOT_RADIUS = 3;
	private static final int HOVER_RADIUS = 5;
	private static final int Y_TICK_COUNT = 5;
	private static final int X_TICK_COUNT = 4;

	private static final Color BUY_COLOR = new Color(39, 174, 96);
	private static final Color SELL_COLOR = new Color(192, 57, 43);
	private static final Color BUY_FILL = new Color(39, 174, 96, 38);
	private static final Color SELL_FILL = new Color(192, 57, 43, 38);
	private static final Color GRID_COLOR = new Color(61, 52, 38, 100);
	private static final Color LABEL_COLOR = new Color(138, 128, 112);

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
			drawGraph(g2);
		}
		catch (Exception e)
		{
			// Don't let painting exceptions crash the client
		}
		finally
		{
			g2.dispose();
		}
	}

	private void drawGraph(Graphics2D g2)
	{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

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
				g2.setColor(LABEL_COLOR);
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
			int pricePad = Math.max(1, (maxPrice - minPrice) / 20);
			minPrice = Math.max(0, minPrice - pricePad);
			maxPrice += pricePad;

			long timeRange = maxTime - minTime;
			int priceRange = maxPrice - minPrice;

			if (timeRange <= 0 || priceRange <= 0)
			{
				return;
			}

			// Grid lines
			g2.setStroke(new BasicStroke(1));
			Font labelFont = FontManager.getRunescapeSmallFont().deriveFont(10f);
			g2.setFont(labelFont);
			FontMetrics fm = g2.getFontMetrics();

			// Y-axis
			for (int i = 0; i <= Y_TICK_COUNT; i++)
			{
				int y = plotBottom - (int) ((long) i * plotH / Y_TICK_COUNT);
				g2.setColor(GRID_COLOR);
				g2.drawLine(plotLeft, y, plotRight, y);

				int priceAtTick = minPrice + (int) ((long) i * priceRange / Y_TICK_COUNT);
				String label = formatAxisPrice(priceAtTick);
				g2.setColor(LABEL_COLOR);
				g2.drawString(label, plotLeft - fm.stringWidth(label) - 6, y + fm.getAscent() / 2);
			}

			// X-axis
			SimpleDateFormat dateFmt = new SimpleDateFormat("M/d HH:mm");
			for (int i = 0; i <= X_TICK_COUNT; i++)
			{
				int x = plotLeft + (int) ((long) i * plotW / X_TICK_COUNT);
				g2.setColor(GRID_COLOR);
				g2.drawLine(x, plotTop, x, plotBottom);

				long timeAtTick = minTime + (long) i * timeRange / X_TICK_COUNT;
				String label = dateFmt.format(new Date(timeAtTick));
				g2.setColor(LABEL_COLOR);
				int labelW = fm.stringWidth(label);
				int labelX = x - labelW / 2;
				labelX = Math.max(plotLeft, Math.min(labelX, plotRight - labelW));
				g2.drawString(label, labelX, plotBottom + fm.getHeight() + 2);
			}

			// Split into buy/sell series
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

			// Draw filled areas first, then lines on top
			drawSeriesFill(g2, buys, BUY_COLOR, BUY_FILL, plotLeft, plotTop, plotW, plotH, plotBottom, minTime, timeRange, minPrice, priceRange);
			drawSeriesFill(g2, sells, SELL_COLOR, SELL_FILL, plotLeft, plotTop, plotW, plotH, plotBottom, minTime, timeRange, minPrice, priceRange);
			drawSeriesLine(g2, buys, BUY_COLOR, plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
			drawSeriesLine(g2, sells, SELL_COLOR, plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);

			// Legend
			g2.setFont(labelFont);
			int legendY = plotTop - 10;
			Color legendText = new Color(224, 214, 194);

			g2.setColor(BUY_COLOR);
			g2.fillRect(plotLeft, legendY - 8, 12, 10);
			g2.setColor(legendText);
			g2.drawString("Buy", plotLeft + 16, legendY);

			int sellLegendX = plotLeft + 50;
			g2.setColor(SELL_COLOR);
			g2.fillRect(sellLegendX, legendY - 8, 12, 10);
			g2.setColor(legendText);
			g2.drawString("Sell", sellLegendX + 16, legendY);
	}

	private int[] toPoint(GeTrade t, int plotLeft, int plotTop, int plotW, int plotH,
		long minTime, long timeRange, int minPrice, int priceRange)
	{
		int price = GePriceLookupPanel.getActualPrice(t);
		long time = t.getTimestamp();
		int x = plotLeft + (int) ((time - minTime) * plotW / timeRange);
		int y = plotTop + plotH - (int) ((long) (price - minPrice) * plotH / priceRange);
		x = Math.max(plotLeft, Math.min(x, plotLeft + plotW));
		y = Math.max(plotTop, Math.min(y, plotTop + plotH));
		return new int[]{x, y};
	}

	private void drawSeriesFill(Graphics2D g2, List<GeTrade> series, Color lineColor, Color fillColor,
		int plotLeft, int plotTop, int plotW, int plotH, int plotBottom,
		long minTime, long timeRange, int minPrice, int priceRange)
	{
		if (series.size() < 2)
		{
			return;
		}

		// Build filled polygon: line points + baseline
		Polygon poly = new Polygon();
		for (GeTrade t : series)
		{
			int[] pt = toPoint(t, plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
			poly.addPoint(pt[0], pt[1]);
		}
		// Close along the bottom
		int[] last = toPoint(series.get(series.size() - 1), plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
		int[] first = toPoint(series.get(0), plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
		poly.addPoint(last[0], plotBottom);
		poly.addPoint(first[0], plotBottom);

		// Gradient fill: stronger at top, fading to transparent at bottom
		g2.setPaint(new GradientPaint(0, plotTop, fillColor, 0, plotBottom, new Color(fillColor.getRed(), fillColor.getGreen(), fillColor.getBlue(), 0)));
		g2.fill(poly);
	}

	private void drawSeriesLine(Graphics2D g2, List<GeTrade> series, Color color,
		int plotLeft, int plotTop, int plotW, int plotH,
		long minTime, long timeRange, int minPrice, int priceRange)
	{
		if (series.isEmpty())
		{
			return;
		}

		// Compute all points
		int[] xs = new int[series.size()];
		int[] ys = new int[series.size()];
		for (int i = 0; i < series.size(); i++)
		{
			int[] pt = toPoint(series.get(i), plotLeft, plotTop, plotW, plotH, minTime, timeRange, minPrice, priceRange);
			xs[i] = pt[0];
			ys[i] = pt[1];
		}

		// Draw smooth curve using cubic Bezier approximation
		g2.setColor(color);
		g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		if (series.size() == 1)
		{
			g2.fillOval(xs[0] - DOT_RADIUS, ys[0] - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
			return;
		}

		float tension = 0.2f;
		Path2D path = new Path2D.Float();
		path.moveTo(xs[0], ys[0]);

		for (int i = 0; i < xs.length - 1; i++)
		{
			float cp1x, cp1y, cp2x, cp2y;

			if (i == 0)
			{
				cp1x = xs[i] + (xs[i + 1] - xs[i]) * tension;
				cp1y = ys[i] + (ys[i + 1] - ys[i]) * tension;
			}
			else
			{
				cp1x = xs[i] + (xs[i + 1] - xs[i - 1]) * tension;
				cp1y = ys[i] + (ys[i + 1] - ys[i - 1]) * tension;
			}

			if (i + 2 >= xs.length)
			{
				cp2x = xs[i + 1] - (xs[i + 1] - xs[i]) * tension;
				cp2y = ys[i + 1] - (ys[i + 1] - ys[i]) * tension;
			}
			else
			{
				cp2x = xs[i + 1] - (xs[i + 2] - xs[i]) * tension;
				cp2y = ys[i + 1] - (ys[i + 2] - ys[i]) * tension;
			}

			path.curveTo(cp1x, cp1y, cp2x, cp2y, xs[i + 1], ys[i + 1]);
		}

		g2.draw(path);

		// Draw dots
		for (int i = 0; i < xs.length; i++)
		{
			g2.setColor(color);
			g2.fillOval(xs[i] - DOT_RADIUS, ys[i] - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2);
		}
	}

	private static String formatAxisPrice(int price)
	{
		if (price >= 10_000_000)
		{
			return String.format("%.1fM", price / 1_000_000.0);
		}
		else if (price >= 100_000)
		{
			return String.format("%.0fK", price / 1_000.0);
		}
		else if (price >= 10_000)
		{
			return String.format("%.1fK", price / 1_000.0);
		}
		return String.format("%,d", price);
	}
}
