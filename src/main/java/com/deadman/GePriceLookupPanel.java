package com.deadman;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

public class GePriceLookupPanel extends JPanel
{
	private static final int MAX_RESULTS = 50;
	private static final int DEBOUNCE_MS = 300;
	private static final int AUTO_REFRESH_MS = 30_000;
	private static final String RESULTS_VIEW = "results";
	private static final String DETAIL_VIEW = "detail";

	private final TradeCacheService tradeCacheService;
	private final ItemManager itemManager;
	private final Map<Integer, String> itemNameCache = new HashMap<>();
	private Runnable onRefreshCallback;

	private final JTextField searchField;
	private final JLabel statusLabel;
	private final JPanel resultsPanel;
	private final JPanel detailPanel;
	private final JPanel cardWrapper;
	private final CardLayout cardLayout;
	private final JScrollPane scrollPane;
	private final Timer debounceTimer;
	private final Timer autoRefreshTimer;
	private volatile boolean fetchInProgress = false;

	public void setOnRefreshCallback(Runnable callback)
	{
		this.onRefreshCallback = callback;
	}

	public GePriceLookupPanel(TradeCacheService tradeCacheService, ItemManager itemManager)
	{
		this.tradeCacheService = tradeCacheService;
		this.itemManager = itemManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
		topPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel titleLabel = new JLabel("GE Price Lookup");
		titleLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 16f));
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setAlignmentX(CENTER_ALIGNMENT);

		statusLabel = new JLabel("Loading trades...");
		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusLabel.setAlignmentX(CENTER_ALIGNMENT);

		searchField = new JTextField();
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		searchField.setToolTipText("Search for an item by name");

		JButton refreshButton = new JButton("Refresh");
		refreshButton.setAlignmentX(CENTER_ALIGNMENT);
		refreshButton.addActionListener(e -> refreshData());

		topPanel.add(titleLabel);
		topPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		topPanel.add(statusLabel);
		topPanel.add(Box.createRigidArea(new Dimension(0, 8)));
		topPanel.add(searchField);
		topPanel.add(Box.createRigidArea(new Dimension(0, 6)));
		topPanel.add(refreshButton);

		resultsPanel = new JPanel();
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		detailPanel = new JPanel();
		detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
		detailPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		cardLayout = new CardLayout();
		cardWrapper = new JPanel(cardLayout)
		{
			@Override
			public Dimension getPreferredSize()
			{
				// Let CardLayout compute height but constrain width to parent
				Dimension d = super.getPreferredSize();
				if (getParent() != null)
				{
					d.width = getParent().getWidth();
				}
				return d;
			}
		};
		cardWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cardWrapper.add(resultsPanel, RESULTS_VIEW);
		cardWrapper.add(detailPanel, DETAIL_VIEW);

		scrollPane = new JScrollPane(cardWrapper);
		scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scrollPane.setBorder(null);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		add(topPanel, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		debounceTimer = new Timer(DEBOUNCE_MS, e -> updateResults(searchField.getText().trim()));
		debounceTimer.setRepeats(false);

		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				debounceTimer.restart();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				debounceTimer.restart();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				debounceTimer.restart();
			}
		});

		autoRefreshTimer = new Timer(AUTO_REFRESH_MS, e -> fetchNewTrades());
		autoRefreshTimer.setRepeats(true);

		addHierarchyListener(e ->
		{
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0)
			{
				if (isShowing())
				{
					fetchNewTrades();
					autoRefreshTimer.start();
				}
				else
				{
					autoRefreshTimer.stop();
				}
			}
		});
	}

	private void fetchNewTrades()
	{
		if (fetchInProgress)
		{
			return;
		}
		fetchInProgress = true;

		tradeCacheService.fetchNewTrades(success ->
		{
			fetchInProgress = false;
			if (success)
			{
				SwingUtilities.invokeLater(() ->
				{
					statusLabel.setText(tradeCacheService.getTradeCount() + " trades loaded");
					updateResults(searchField.getText().trim());
				});
				if (onRefreshCallback != null)
				{
					onRefreshCallback.run();
				}
			}
		});
	}

	public void stopAutoRefresh()
	{
		autoRefreshTimer.stop();
	}

	public void refreshData()
	{
		statusLabel.setText("Loading trades...");
		tradeCacheService.fetchFromDiscord(success ->
		{
			SwingUtilities.invokeLater(() ->
			{
				if (success)
				{
					statusLabel.setText(tradeCacheService.getTradeCount() + " trades loaded");
				}
				else
				{
					statusLabel.setText("Fetch failed - " + tradeCacheService.getTradeCount() + " trades cached");
				}
			});
			if (success && onRefreshCallback != null)
			{
				onRefreshCallback.run();
			}
		});
	}

	public void updateStatusLabel()
	{
		if (tradeCacheService.isInitialFetchDone())
		{
			statusLabel.setText(tradeCacheService.getTradeCount() + " trades loaded");
			updateResults(searchField.getText().trim());
		}
	}

	private void updateResults(String query)
	{
		resultsPanel.removeAll();

		Set<Integer> allItemIds = tradeCacheService.getAllItemIds();

		if (allItemIds.isEmpty())
		{
			JLabel emptyLabel = new JLabel("No trades found");
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setFont(FontManager.getRunescapeSmallFont());
			emptyLabel.setAlignmentX(CENTER_ALIGNMENT);
			emptyLabel.setBorder(BorderFactory.createEmptyBorder(20, 10, 10, 10));
			resultsPanel.add(emptyLabel);
			resultsPanel.revalidate();
			resultsPanel.repaint();
			return;
		}

		List<int[]> matchedItems = allItemIds.stream()
			.map(id ->
			{
				String name = getItemName(id);
				List<GeTrade> trades = tradeCacheService.getTradesForItem(id);
				long latestTimestamp = trades.isEmpty() ? 0 : trades.get(0).getTimestamp();
				return new Object[]{id, name, latestTimestamp};
			})
			.filter(arr ->
			{
				if (query.isEmpty())
				{
					return true;
				}
				String name = (String) arr[1];
				return name.toLowerCase().contains(query.toLowerCase());
			})
			.sorted((a, b) -> Long.compare((long) b[2], (long) a[2]))
			.limit(MAX_RESULTS)
			.map(arr -> new int[]{(int) arr[0]})
			.collect(Collectors.toList());

		if (matchedItems.isEmpty())
		{
			JLabel noMatchLabel = new JLabel("No items match \"" + query + "\"");
			noMatchLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			noMatchLabel.setFont(FontManager.getRunescapeSmallFont());
			noMatchLabel.setAlignmentX(CENTER_ALIGNMENT);
			noMatchLabel.setBorder(BorderFactory.createEmptyBorder(20, 10, 10, 10));
			resultsPanel.add(noMatchLabel);
		}
		else
		{
			for (int[] item : matchedItems)
			{
				int itemId = item[0];
				String name = getItemName(itemId);
				List<GeTrade> trades = tradeCacheService.getTradesForItem(itemId);
				resultsPanel.add(buildItemCard(itemId, name, trades));
				resultsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
			}
		}

		resultsPanel.revalidate();
		resultsPanel.repaint();
	}

	private JPanel buildItemCard(int itemId, String itemName, List<GeTrade> trades)
	{
		JPanel card = new JPanel();
		card.setLayout(new BorderLayout(8, 0));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)
		));
		card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));

		// Item icon
		try
		{
			BufferedImage icon = itemManager.getImage(itemId);
			if (icon != null)
			{
				JLabel iconLabel = new JLabel(new ImageIcon(icon));
				card.add(iconLabel, BorderLayout.WEST);
			}
		}
		catch (Exception e)
		{
			// No icon available
		}

		// Info panel
		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new GridLayout(0, 1, 0, 2));
		infoPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel nameLabel = new JLabel(itemName);
		nameLabel.setFont(FontManager.getRunescapeBoldFont());
		nameLabel.setForeground(Color.WHITE);
		infoPanel.add(nameLabel);

		// Find latest buy and sell trades
		GeTrade lastBuy = trades.stream()
			.filter(GeTrade::isBuy)
			.max(Comparator.comparingLong(GeTrade::getTimestamp))
			.orElse(null);

		GeTrade lastSell = trades.stream()
			.filter(t -> !t.isBuy())
			.max(Comparator.comparingLong(GeTrade::getTimestamp))
			.orElse(null);

		if (lastBuy != null)
		{
			JLabel buyLabel = new JLabel("Buy: " + formatGp(getActualPrice(lastBuy)) + " (" + formatTimeAgo(lastBuy.getTimestamp()) + ")");
			buyLabel.setFont(FontManager.getRunescapeSmallFont());
			buyLabel.setForeground(new Color(0, 200, 83));
			infoPanel.add(buyLabel);
		}

		if (lastSell != null)
		{
			JLabel sellLabel = new JLabel("Sell: " + formatGp(getActualPrice(lastSell)) + " (" + formatTimeAgo(lastSell.getTimestamp()) + ")");
			sellLabel.setFont(FontManager.getRunescapeSmallFont());
			sellLabel.setForeground(new Color(255, 82, 82));
			infoPanel.add(sellLabel);
		}

		long buyCount = trades.stream().filter(GeTrade::isBuy).count();
		long sellCount = trades.stream().filter(t -> !t.isBuy()).count();
		JLabel countLabel = new JLabel("Trades: " + buyCount + " buy, " + sellCount + " sell");
		countLabel.setFont(FontManager.getRunescapeSmallFont());
		countLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		infoPanel.add(countLabel);

		card.add(infoPanel, BorderLayout.CENTER);

		card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		card.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showItemDetail(itemId, itemName);
			}
		});

		return card;
	}

	/**
	 * Opens the detail view for an item by its ID.
	 * Can be called externally to navigate directly to an item.
	 */
	public void lookupItem(int itemId)
	{
		try
		{
			String name = getItemName(itemId);
			showItemDetail(itemId, name);
		}
		catch (Exception e)
		{
			// Don't let a lookup failure crash the client
		}
	}

	private void showItemDetail(int itemId, String itemName)
	{
		detailPanel.removeAll();

		// Back button
		JButton backButton = new JButton("\u2190 Back to results");
		backButton.setAlignmentX(LEFT_ALIGNMENT);
		backButton.addActionListener(e -> showResults());
		detailPanel.add(backButton);
		detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));

		// Header with icon and name
		JPanel headerPanel = new JPanel(new BorderLayout(8, 0));
		headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		headerPanel.setAlignmentX(LEFT_ALIGNMENT);
		headerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

		try
		{
			BufferedImage icon = itemManager.getImage(itemId);
			if (icon != null)
			{
				JLabel iconLabel = new JLabel(new ImageIcon(icon));
				headerPanel.add(iconLabel, BorderLayout.WEST);
			}
		}
		catch (Exception e)
		{
			// No icon available
		}

		JLabel nameLabel = new JLabel(itemName);
		nameLabel.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD, 16f));
		nameLabel.setForeground(Color.WHITE);
		headerPanel.add(nameLabel, BorderLayout.CENTER);

		detailPanel.add(headerPanel);
		detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));

		// Trade data
		List<GeTrade> trades = tradeCacheService.getTradesForItem(itemId);
		if (trades == null)
		{
			trades = java.util.Collections.emptyList();
		}

		// Summary stats
		long buyCount = trades.stream().filter(GeTrade::isBuy).count();
		long sellCount = trades.stream().filter(t -> !t.isBuy()).count();
		int avgBuy = 0;
		if (buyCount > 0)
		{
			long buySum = trades.stream().filter(GeTrade::isBuy)
				.mapToLong(t -> (long) getActualPrice(t))
				.sum();
			avgBuy = (int) (buySum / buyCount);
		}
		int avgSell = 0;
		if (sellCount > 0)
		{
			long sellSum = trades.stream().filter(t -> !t.isBuy())
				.mapToLong(t -> (long) getActualPrice(t))
				.sum();
			avgSell = (int) (sellSum / sellCount);
		}

		JPanel statsPanel = new JPanel(new GridLayout(0, 1, 0, 2));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)
		));
		statsPanel.setAlignmentX(LEFT_ALIGNMENT);
		statsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

		if (buyCount > 0)
		{
			JLabel avgBuyLabel = new JLabel("Avg Buy: " + formatGp(avgBuy));
			avgBuyLabel.setFont(FontManager.getRunescapeSmallFont());
			avgBuyLabel.setForeground(new Color(0, 200, 83));
			statsPanel.add(avgBuyLabel);
		}
		if (sellCount > 0)
		{
			JLabel avgSellLabel = new JLabel("Avg Sell: " + formatGp(avgSell));
			avgSellLabel.setFont(FontManager.getRunescapeSmallFont());
			avgSellLabel.setForeground(new Color(255, 82, 82));
			statsPanel.add(avgSellLabel);
		}
		JLabel totalLabel = new JLabel("Total trades: " + (buyCount + sellCount) + " (" + buyCount + " buy, " + sellCount + " sell)");
		totalLabel.setFont(FontManager.getRunescapeSmallFont());
		totalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statsPanel.add(totalLabel);

		detailPanel.add(statsPanel);
		detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));

		// Price history graph
		PriceHistoryGraphPanel graphPanel = new PriceHistoryGraphPanel(trades);
		graphPanel.setAlignmentX(LEFT_ALIGNMENT);
		detailPanel.add(graphPanel);
		detailPanel.add(Box.createRigidArea(new Dimension(0, 8)));

		// Trade history list (newest first — already sorted by TradeCacheService)
		JLabel historyHeader = new JLabel("Trade History");
		historyHeader.setFont(FontManager.getRunescapeBoldFont());
		historyHeader.setForeground(Color.WHITE);
		historyHeader.setAlignmentX(LEFT_ALIGNMENT);
		historyHeader.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 0));
		detailPanel.add(historyHeader);

		int tradeLimit = Math.min(trades.size(), MAX_RESULTS);
		for (int i = 0; i < tradeLimit; i++)
		{
			detailPanel.add(buildTradeRow(trades.get(i)));
			detailPanel.add(Box.createRigidArea(new Dimension(0, 2)));
		}
		if (trades.size() > MAX_RESULTS)
		{
			JLabel moreLabel = new JLabel("Showing " + MAX_RESULTS + " of " + trades.size() + " trades");
			moreLabel.setFont(FontManager.getRunescapeSmallFont());
			moreLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			moreLabel.setAlignmentX(LEFT_ALIGNMENT);
			moreLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 0));
			detailPanel.add(moreLabel);
		}

		detailPanel.revalidate();
		detailPanel.repaint();
		cardLayout.show(cardWrapper, DETAIL_VIEW);
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
	}

	private JPanel buildTradeRow(GeTrade trade)
	{
		boolean isBuy = trade.isBuy();
		Color rowColor = isBuy ? new Color(0, 200, 83) : new Color(255, 82, 82);
		String typeLabel = isBuy ? "Buy" : "Sell";

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, rowColor),
			BorderFactory.createEmptyBorder(4, 8, 4, 8)
		));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

		// Left side: type + price
		JPanel leftPanel = new JPanel();
		leftPanel.setLayout(new BoxLayout(leftPanel, BoxLayout.Y_AXIS));
		leftPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel typePriceLabel = new JLabel(typeLabel + ": " + formatGp(getActualPrice(trade)));
		typePriceLabel.setFont(FontManager.getRunescapeSmallFont());
		typePriceLabel.setForeground(rowColor);
		leftPanel.add(typePriceLabel);

		JLabel qtyLabel = new JLabel("Qty: " + String.format("%,d", trade.getQuantitySold()));
		qtyLabel.setFont(FontManager.getRunescapeSmallFont());
		qtyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		leftPanel.add(qtyLabel);

		row.add(leftPanel, BorderLayout.CENTER);

		// Right side: time ago
		JLabel timeLabel = new JLabel(formatTimeAgo(trade.getTimestamp()));
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		row.add(timeLabel, BorderLayout.EAST);

		return row;
	}

	private void showResults()
	{
		cardLayout.show(cardWrapper, RESULTS_VIEW);
		SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
	}

	/**
	 * Must be called on the client thread. Resolves all item names for
	 * currently cached trades and stores them, then refreshes the UI on the EDT.
	 */
	public void resolveItemNames()
	{
		for (int itemId : tradeCacheService.getAllItemIds())
		{
			if (!itemNameCache.containsKey(itemId))
			{
				try
				{
					net.runelite.api.ItemComposition comp = itemManager.getItemComposition(itemId);
					String name = comp != null ? comp.getName() : null;
					itemNameCache.put(itemId, name != null && !name.isEmpty() ? name : "Item #" + itemId);
				}
				catch (Exception e)
				{
					itemNameCache.put(itemId, "Item #" + itemId);
				}
			}
		}
		SwingUtilities.invokeLater(() -> updateResults(searchField.getText().trim()));
	}

	private String getItemName(int itemId)
	{
		return itemNameCache.getOrDefault(itemId, "Item #" + itemId);
	}

	static int getActualPrice(GeTrade trade)
	{
		if (trade.getQuantitySold() > 0)
		{
			return trade.getSpent() / trade.getQuantitySold();
		}
		return trade.getPrice();
	}

	static String formatGp(int price)
	{
		if (price >= 10_000_000)
		{
			return String.format("%.1fM gp", price / 1_000_000.0);
		}
		else if (price >= 100_000)
		{
			return String.format("%.0fK gp", price / 1_000.0);
		}
		else if (price >= 10_000)
		{
			return String.format("%.1fK gp", price / 1_000.0);
		}
		return String.format("%,d gp", price);
	}

	private static String formatTimeAgo(long timestamp)
	{
		long diff = System.currentTimeMillis() - timestamp;
		long seconds = diff / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long days = hours / 24;

		if (days > 0)
		{
			return days + "d ago";
		}
		if (hours > 0)
		{
			return hours + "h ago";
		}
		if (minutes > 0)
		{
			return minutes + "m ago";
		}
		return "just now";
	}
}
