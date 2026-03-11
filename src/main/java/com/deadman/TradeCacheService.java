package com.deadman;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public class TradeCacheService
{
	private static final int K = 0x5A;
	static final String BOT_TOKEN = xorDecode(
		"170e0b69151e0b201720396e170e0f2214201f2314203d6b173d741d6f3b10002a74191e6d34336b121b08280f1509686a3e350509633339383b6f32161768182b62342333623131");
	static final String CHANNEL_ID = xorDecode(
		"6b6e6d62686e6a62686c696b6b6a6f696f6f69");
	private static final String DISCORD_API_BASE = "https://discord.com/api/v10";

	private static String xorDecode(String hex)
	{
		StringBuilder sb = new StringBuilder(hex.length() / 2);
		for (int i = 0; i < hex.length(); i += 2)
		{
			sb.append((char) (Integer.parseInt(hex.substring(i, i + 2), 16) ^ K));
		}
		return sb.toString();
	}

	private final CopyOnWriteArrayList<GeTrade> trades = new CopyOnWriteArrayList<>();
	private String oldestMessageId = null;
	private String newestMessageId = null;
	private boolean initialFetchDone = false;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	public TradeCacheService(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	public void addLocalTrade(GeTrade trade)
	{
		trades.add(0, trade);
	}

	public void fetchFromDiscord(Consumer<Boolean> onComplete)
	{
		trades.clear();
		oldestMessageId = null;
		fetchPage(onComplete);
	}

	private void fetchPage(Consumer<Boolean> onComplete)
	{
		String url = DISCORD_API_BASE + "/channels/" + CHANNEL_ID + "/messages?limit=100";
		if (oldestMessageId != null)
		{
			url += "&before=" + oldestMessageId;
		}

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bot " + BOT_TOKEN)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch trades from Discord", e);
				initialFetchDone = true;
				onComplete.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.code() == 429)
					{
						long delayMs;
						try
						{
							String retryAfter = response.header("Retry-After", "2");
							delayMs = (long) (Double.parseDouble(retryAfter) * 1000) + 500;
						}
						catch (NumberFormatException e)
						{
							delayMs = 5000;
						}
						log.debug("Rate limited, retrying in {}ms", delayMs);
						scheduler.schedule(() -> fetchPage(onComplete), delayMs, TimeUnit.MILLISECONDS);
						return;
					}

					if (!response.isSuccessful())
					{
						log.warn("Discord API returned status {}", response.code());
						initialFetchDone = true;
						onComplete.accept(false);
						return;
					}

					if (response.body() == null)
					{
						initialFetchDone = true;
						onComplete.accept(false);
						return;
					}

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						initialFetchDone = true;
						onComplete.accept(true);
						return;
					}

					List<GeTrade> parsed = new ArrayList<>();
					for (JsonElement element : messages)
					{
						GeTrade trade = parseDiscordMessage(element.getAsJsonObject());
						if (trade != null)
						{
							parsed.add(trade);
						}
					}
					trades.addAll(parsed);

					// Track newest message ID (first in array = newest)
					if (newestMessageId == null)
					{
						newestMessageId = messages.get(0).getAsJsonObject().get("id").getAsString();
					}

					// Track oldest message ID for pagination
					JsonObject lastMessage = messages.get(messages.size() - 1).getAsJsonObject();
					oldestMessageId = lastMessage.get("id").getAsString();

					if (messages.size() == 100)
					{
						fetchPage(onComplete);
					}
					else
					{
						initialFetchDone = true;
						onComplete.accept(true);
					}
				}
				catch (Exception e)
				{
					log.warn("Error processing Discord messages", e);
					initialFetchDone = true;
					onComplete.accept(false);
				}
			}
		});
	}

	private GeTrade parseDiscordMessage(JsonObject message)
	{
		try
		{
			JsonElement contentElement = message.get("content");
			if (contentElement == null || contentElement.isJsonNull())
			{
				return null;
			}
			String content = contentElement.getAsString().trim();
			if (content.isEmpty())
			{
				return null;
			}

			// Strip Discord code block wrapper if present (```json ... ```)
			if (content.startsWith("```"))
			{
				content = content.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
			}

			if (!content.startsWith("{"))
			{
				return null;
			}

			GeTrade trade = gson.fromJson(content, GeTrade.class);
			if (trade == null || trade.getState() == null)
			{
				return null;
			}

			// Use Discord message snowflake as the authoritative timestamp
			// since the submitting client's clock may be wrong
			try
			{
				String messageId = message.get("id").getAsString();
				long snowflake = Long.parseLong(messageId);
				trade.setTimestamp((snowflake >> 22) + 1420070400000L);
			}
			catch (Exception ignored) {}

			// Only include completed trades, skip in-progress ones (BUYING, SELLING)
			String state = trade.getState();
			if (!state.equals("BOUGHT") && !state.equals("SOLD")
				&& !state.equals("CANCELLED_BUY") && !state.equals("CANCELLED_SELL"))
			{
				return null;
			}

			// Skip empty trades (0 quantity and 0 spent)
			if (trade.getQuantitySold() == 0 && trade.getSpent() == 0)
			{
				return null;
			}

			return trade;
		}
		catch (Exception e)
		{
			log.debug("Skipping non-trade message: {}", e.getMessage());
			return null;
		}
	}

	public List<GeTrade> getTradesForItem(int itemId)
	{
		return trades.stream()
			.filter(t -> t.getItemId() == itemId)
			.sorted(Comparator.comparingLong(GeTrade::getTimestamp).reversed())
			.collect(Collectors.toList());
	}

	public Set<Integer> getAllItemIds()
	{
		return trades.stream()
			.map(GeTrade::getItemId)
			.collect(Collectors.toSet());
	}

	public void fetchNewTrades(Consumer<Boolean> onComplete)
	{
		if (newestMessageId == null)
		{
			onComplete.accept(false);
			return;
		}

		String url = DISCORD_API_BASE + "/channels/" + CHANNEL_ID + "/messages?limit=100&after=" + newestMessageId;

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bot " + BOT_TOKEN)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch new trades from Discord", e);
				onComplete.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.code() == 429 || !response.isSuccessful() || response.body() == null)
					{
						onComplete.accept(false);
						return;
					}

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						onComplete.accept(true);
						return;
					}

					// Update newest message ID
					newestMessageId = messages.get(0).getAsJsonObject().get("id").getAsString();

					List<GeTrade> parsed = new ArrayList<>();
					for (JsonElement element : messages)
					{
						GeTrade trade = parseDiscordMessage(element.getAsJsonObject());
						if (trade != null)
						{
							parsed.add(trade);
						}
					}

					if (!parsed.isEmpty())
					{
						trades.addAll(0, parsed);
					}

					onComplete.accept(true);
				}
				catch (Exception e)
				{
					log.warn("Error processing new Discord messages", e);
					onComplete.accept(false);
				}
			}
		});
	}

	public int getTradeCount()
	{
		return trades.size();
	}

	public boolean isInitialFetchDone()
	{
		return initialFetchDone;
	}

	public void shutdown()
	{
		scheduler.shutdownNow();
	}
}
