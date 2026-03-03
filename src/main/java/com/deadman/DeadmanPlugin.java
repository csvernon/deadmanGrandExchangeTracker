package com.deadman;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "Deadman",
	description = "Logs Grand Exchange transactions on world 345 to an external server. View data at https://dmmhs2.onrender.com/ge",
	tags = {"grandexchange", "deadman", "logging"}
)
public class DeadmanPlugin extends Plugin
{
	private static final int TARGET_WORLD = 345;
	private static final String DISCORD_API_BASE = "https://discord.com/api/v10";
	private static final String BOT_TOKEN = TradeCacheService.BOT_TOKEN;
	private static final String CHANNEL_ID = TradeCacheService.CHANNEL_ID;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private DeadmanConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	private DeadmanPanel deadmanPanel;
	private TradeCacheService tradeCacheService;
	private GePriceLookupPanel gePriceLookupPanel;
	private NavigationButton navButton;

	@Provides
	DeadmanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeadmanConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("Deadman plugin started");

		tradeCacheService = new TradeCacheService(okHttpClient, gson);
		BossBreachPanel bossBreachPanel = new BossBreachPanel();
		gePriceLookupPanel = new GePriceLookupPanel(tradeCacheService, itemManager);
		gePriceLookupPanel.setOnRefreshCallback(() ->
			clientThread.invokeLater(() -> gePriceLookupPanel.resolveItemNames())
		);
		deadmanPanel = new DeadmanPanel(bossBreachPanel, gePriceLookupPanel);

		BufferedImage icon;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/net/runelite/client/plugins/hiscore/deadman.png");
		}
		catch (Exception e)
		{
			log.warn("Failed to load deadman icon, using fallback", e);
			icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		}

		navButton = NavigationButton.builder()
			.tooltip("Deadman Tools")
			.icon(icon)
			.panel(deadmanPanel)
			.priority(5)
			.build();

		clientToolbar.addNavigation(navButton);

		tradeCacheService.fetchFromDiscord(success ->
		{
			SwingUtilities.invokeLater(() -> gePriceLookupPanel.updateStatusLabel());
			if (success)
			{
				clientThread.invokeLater(() -> gePriceLookupPanel.resolveItemNames());
			}
		});
	}

	@Override
	protected void shutDown()
	{
		log.info("Deadman plugin stopped");

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		if (deadmanPanel != null)
		{
			deadmanPanel.shutdown();
		}

		if (tradeCacheService != null)
		{
			tradeCacheService.shutdown();
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		try
		{
			if (client.getWorld() != TARGET_WORLD)
			{
				return;
			}

			GrandExchangeOffer offer = event.getOffer();
			if (offer == null)
			{
				return;
			}

			GrandExchangeOfferState state = offer.getState();

			if (state != GrandExchangeOfferState.BOUGHT
				&& state != GrandExchangeOfferState.SOLD
				&& state != GrandExchangeOfferState.CANCELLED_BUY
				&& state != GrandExchangeOfferState.CANCELLED_SELL)
			{
				return;
			}

			if (offer.getQuantitySold() == 0 && offer.getSpent() == 0)
			{
				return;
			}

			boolean buy = state == GrandExchangeOfferState.BOUGHT
				|| state == GrandExchangeOfferState.CANCELLED_BUY;

			GeTrade trade = GeTrade.builder()
				.itemId(offer.getItemId())
				.quantitySold(offer.getQuantitySold())
				.totalQuantity(offer.getTotalQuantity())
				.price(offer.getPrice())
				.spent(offer.getSpent())
				.state(state.name())
				.slot(event.getSlot())
				.buy(buy)
				.timestamp(System.currentTimeMillis())
				.world(TARGET_WORLD)
				.build();

			submitTrade(trade);
			tradeCacheService.addLocalTrade(trade);
		}
		catch (Exception e)
		{
			log.warn("Error processing GE offer change", e);
		}
	}

	private void submitTrade(GeTrade trade)
	{
		try
		{
			Map<String, String> payload = new HashMap<>();
			payload.put("content", "```json\n" + gson.toJson(trade) + "\n```");

			Request request = new Request.Builder()
				.url(DISCORD_API_BASE + "/channels/" + CHANNEL_ID + "/messages")
				.header("Authorization", "Bot " + BOT_TOKEN)
				.post(RequestBody.create(JSON, gson.toJson(payload)))
				.build();

			okHttpClient.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					log.warn("Failed to submit GE trade", e);
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					try (response)
					{
						log.debug("GE trade submitted: itemId={} state={}", trade.getItemId(), trade.getState());
					}
				}
			});
		}
		catch (Exception e)
		{
			log.warn("Failed to build GE trade request", e);
		}
	}
}
