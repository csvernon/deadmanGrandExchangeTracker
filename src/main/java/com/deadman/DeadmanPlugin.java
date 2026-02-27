package com.deadman;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
	private static final String API_URL = "https://dmmhs2.onrender.com/api/ge";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private DeadmanConfig config;

	@Provides
	DeadmanConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeadmanConfig.class);
	}

	@Override
	protected void startUp()
	{
		log.info("Deadman plugin started");
	}

	@Override
	protected void shutDown()
	{
		log.info("Deadman plugin stopped");
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (client.getWorld() != TARGET_WORLD)
		{
			return;
		}

		GrandExchangeOffer offer = event.getOffer();
		GrandExchangeOfferState state = offer.getState();

		if (state == GrandExchangeOfferState.EMPTY)
		{
			return;
		}

		boolean buy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
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
	}

	private void submitTrade(GeTrade trade)
	{
		Request request = new Request.Builder()
			.url(API_URL)
			.post(RequestBody.create(JSON, gson.toJson(trade)))
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
}
