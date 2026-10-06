package com.deadman;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.io.Writer;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
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
	private final Filepath CACHE_FILE;
	private final Filepath LOCK_FILE;
	private static final long MIN_RATE_LIMIT_DELAY_MS = 5500;
	private static final long MAX_RATE_LIMIT_DELAY_MS = 60000;
	private final long cacheReloadIntervalMs;

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
	private volatile boolean initialFetchDone = false;
	private boolean backfillComplete = false;
	private boolean backfillInProgress = false;
	private boolean syncOwner = false;
	private long lastCacheModified = 0;
	private long lastCacheSize = 0;
	private long lastNewTradeFetchAttempt = 0;
	private int consecutiveRateLimits = 0;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
	private FileChannel lockChannel;
	private FileLock cacheLock;
	private volatile boolean stopped;
	private final java.util.Set<Call> activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public TradeCacheService(OkHttpClient httpClient, Gson gson, Filepath directory)
	{
		this(httpClient, gson, directory.join("trades-cache.json"), TimeUnit.SECONDS.toMillis(15));
	}

	TradeCacheService(OkHttpClient httpClient, Gson gson, Filepath cacheFile, long reloadIntervalMs)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.CACHE_FILE = cacheFile;
		this.LOCK_FILE = cacheFile.getParent().join("trades-cache.lock");
		this.cacheReloadIntervalMs = reloadIntervalMs;
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
	}

	public void addLocalTrade(GeTrade trade)
	{
		dispatch(() ->
		{
			trades.add(0, trade);
			if (syncOwner)
			{
				saveCache();
			}
		});
	}

	private void dispatch(Runnable task)
	{
		if (!stopped)
		{
			try
			{
				scheduler.execute(() -> { if (!stopped) task.run(); });
			}
			catch (java.util.concurrent.RejectedExecutionException ignored) {}
		}
	}

	public void fetchFromDiscord(Consumer<Boolean> onComplete)
	{
		dispatch(() -> initialize(onComplete));
	}

	private void initialize(Consumer<Boolean> onComplete)
	{
		syncOwner = syncOwner || acquireSyncLock();
		if (loadCache())
		{
			log.info("Loaded {} Deadman trades from local cache", trades.size());
			initialFetchDone = true;
			onComplete.accept(true);
			if (!syncOwner)
			{
				log.info("Another Deadman client is syncing Discord history; watching local cache");
				startCacheWatcher(onComplete);
				return;
			}

			if (newestMessageId == null || (!backfillComplete && oldestMessageId != null))
			{
				fetchPage(onComplete);
				return;
			}

			fetchNewTrades(onComplete);
			return;
		}

		initialFetchDone = false;
		if (syncOwner)
		{
			fetchPage(onComplete);
		}
		else
		{
			log.info("Another Deadman client is syncing Discord history; waiting for local cache");
			startCacheWatcher(onComplete);
			onComplete.accept(false);
		}
	}

	private boolean loadCache()
	{
		if (!CACHE_FILE.isFile())
		{
			return false;
		}

		try (Reader reader = CACHE_FILE.openBufferedReader())
		{
			CacheFile cache = gson.fromJson(reader, CacheFile.class);
			if (cache == null || cache.trades == null)
			{
				return false;
			}

			trades.clear();
			trades.addAll(cache.trades);
			newestMessageId = cache.newestMessageId;
			oldestMessageId = cache.oldestMessageId;
			backfillComplete = cache.backfillComplete;
			rememberCacheMetadata();
			return newestMessageId != null || !trades.isEmpty();
		}
		catch (Exception e)
		{
			log.warn("Failed to load Deadman trade cache", e);
			return false;
		}
	}

	private void saveCache()
	{
		if (!syncOwner || stopped)
		{
			return;
		}
		try
		{
			Filepath parent = CACHE_FILE.getParent();
			parent.createDirectories();

			CacheFile cache = new CacheFile();
			cache.newestMessageId = newestMessageId;
			cache.oldestMessageId = oldestMessageId;
			cache.backfillComplete = backfillComplete;
			cache.trades = new ArrayList<>(trades);

			Filepath temp = parent.createTempFile("trades-cache-", ".json");
			try
			{
				try (Writer writer = temp.openBufferedWriter())
				{
					gson.toJson(cache, writer);
				}
				try
				{
					temp.moveTo(CACHE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (AtomicMoveNotSupportedException e)
				{
					temp.moveTo(CACHE_FILE, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			finally
			{
				temp.deleteIfExists();
			}
			rememberCacheMetadata();
		}
		catch (Exception e)
		{
			log.warn("Failed to save Deadman trade cache", e);
		}
	}

	private boolean acquireSyncLock()
	{
		try
		{
			CACHE_FILE.getParent().createDirectories();
			lockChannel = LOCK_FILE.openFileChannel(StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			cacheLock = lockChannel.tryLock();
			if (cacheLock == null)
			{
				closeLock();
			}
			return cacheLock != null;
		}
		catch (Exception e)
		{
			log.debug("Unable to acquire Deadman cache sync lock", e);
			closeLock();
			return false;
		}
	}

	private void startCacheWatcher(Consumer<Boolean> onComplete)
	{
		scheduler.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (stopped) return;
				if (!syncOwner && acquireSyncLock())
				{
					syncOwner = true;
					loadCache();
					initialFetchDone = true;
					if (newestMessageId == null || !backfillComplete)
						fetchPage(onComplete);
					else
						fetchNewTrades(onComplete);
					return;
				}
				if (syncOwner) return;
				if (!CACHE_FILE.isFile())
				{
					return;
				}

				long modified = CACHE_FILE.getLastModifiedTime().toMillis();
				long size = CACHE_FILE.size();
				if (modified == lastCacheModified && size == lastCacheSize)
				{
					return;
				}

				if (loadCache())
				{
					initialFetchDone = true;
					log.info("Reloaded {} Deadman trades from local cache", trades.size());
					onComplete.accept(true);
				}
			}
			catch (Exception e)
			{
				log.warn("Failed to reload Deadman trade cache", e);
			}
		}, cacheReloadIntervalMs, cacheReloadIntervalMs, TimeUnit.MILLISECONDS);
	}

	private void rememberCacheMetadata() throws IOException
	{
		if (CACHE_FILE.isFile())
		{
			lastCacheModified = CACHE_FILE.getLastModifiedTime().toMillis();
			lastCacheSize = CACHE_FILE.size();
		}
	}

	private void mergeTrades(List<GeTrade> parsed, boolean newestFirst)
	{
		Map<String, GeTrade> merged = new LinkedHashMap<>();
		if (newestFirst)
		{
			for (GeTrade trade : parsed)
			{
				merged.put(tradeKey(trade), trade);
			}
		}
		for (GeTrade trade : trades)
		{
			merged.putIfAbsent(tradeKey(trade), trade);
		}
		if (!newestFirst)
		{
			for (GeTrade trade : parsed)
			{
				merged.putIfAbsent(tradeKey(trade), trade);
			}
		}
		trades.clear();
		trades.addAll(merged.values());
	}

	private static String tradeKey(GeTrade trade)
	{
		return trade.getItemId() + ":" + trade.getQuantitySold() + ":" + trade.getTotalQuantity() + ":" + trade.getPrice() + ":" +
			trade.getSpent() + ":" + trade.getState() + ":" + trade.getSlot() + ":" + trade.isBuy() + ":" + trade.getTimestamp() + ":" + trade.getWorld();
	}

	private void fetchPage(Consumer<Boolean> onComplete)
	{
		backfillInProgress = true;
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

		enqueue(request, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch trades from Discord", e);
				initialFetchDone = true;
				backfillInProgress = false;
				onComplete.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.code() == 429)
					{
						long delayMs = getRateLimitDelayMs(response);
						log.warn("Rate limited while fetching Deadman trades, retrying in {}ms", delayMs);
						scheduler.schedule(() -> fetchPage(onComplete), delayMs, TimeUnit.MILLISECONDS);
						return;
					}
					consecutiveRateLimits = 0;

					if (!response.isSuccessful())
					{
						log.warn("Discord API returned status {}", response.code());
						initialFetchDone = true;
						backfillInProgress = false;
						onComplete.accept(false);
						return;
					}

					if (response.body() == null)
					{
						initialFetchDone = true;
						backfillInProgress = false;
						onComplete.accept(false);
						return;
					}

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						backfillComplete = true;
						saveCache();
						initialFetchDone = true;
						backfillInProgress = false;
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
					mergeTrades(parsed, false);

					// Track newest message ID (first in array = newest)
					if (newestMessageId == null)
					{
						newestMessageId = messages.get(0).getAsJsonObject().get("id").getAsString();
					}

					// Track oldest message ID for pagination
					JsonObject lastMessage = messages.get(messages.size() - 1).getAsJsonObject();
					oldestMessageId = lastMessage.get("id").getAsString();
					saveCache();
					log.info("Fetched {} Discord messages, cached {} Deadman trades so far", messages.size(), trades.size());
					onComplete.accept(true);

					if (messages.size() == 100)
					{
						fetchPage(onComplete);
					}
					else
					{
						backfillComplete = true;
						saveCache();
						initialFetchDone = true;
						backfillInProgress = false;
						onComplete.accept(true);
					}
				}
				catch (Exception e)
				{
					log.warn("Error processing Discord messages", e);
					initialFetchDone = true;
					backfillInProgress = false;
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
		dispatch(() -> startNewTrades(onComplete));
	}

	private void startNewTrades(Consumer<Boolean> onComplete)
	{
		if (!syncOwner)
		{
			onComplete.accept(false);
			return;
		}

		if (backfillInProgress || !initialFetchDone)
		{
			onComplete.accept(false);
			return;
		}

		long now = System.currentTimeMillis();
		if (now - lastNewTradeFetchAttempt < TimeUnit.MINUTES.toMillis(1))
		{
			onComplete.accept(false);
			return;
		}
		lastNewTradeFetchAttempt = now;

		if (newestMessageId == null)
		{
			onComplete.accept(false);
			return;
		}

		backfillInProgress = true;
		fetchNewPage(onComplete, newestMessageId, null, null);
	}

	private void fetchNewPage(Consumer<Boolean> onComplete, String previousId, String beforeId, String latestId)
	{
		String url = DISCORD_API_BASE + "/channels/" + CHANNEL_ID + "/messages?limit=100";
		if (beforeId != null)
		{
			url += "&before=" + beforeId;
		}

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bot " + BOT_TOKEN)
			.get()
			.build();

		enqueue(request, new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Failed to fetch new trades from Discord", e);
				backfillInProgress = false;
				onComplete.accept(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.code() == 429)
					{
						long delay = getRateLimitDelayMs(response);
						scheduler.schedule(() -> fetchNewPage(onComplete, previousId, beforeId, latestId), delay, TimeUnit.MILLISECONDS);
						return;
					}
					if (!response.isSuccessful() || response.body() == null)
					{
						backfillInProgress = false;
						onComplete.accept(false);
						return;
					}
					consecutiveRateLimits = 0;

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						if (latestId != null) newestMessageId = latestId;
						backfillInProgress = false;
						saveCache();
						onComplete.accept(true);
						return;
					}

					// Update newest message ID
					String latest = latestId == null ? messages.get(0).getAsJsonObject().get("id").getAsString() : latestId;
					boolean reachedPrevious = false;

					List<GeTrade> parsed = new ArrayList<>();
					for (JsonElement element : messages)
					{
						String id = element.getAsJsonObject().get("id").getAsString();
						if (Long.parseLong(id) <= Long.parseLong(previousId))
						{
							reachedPrevious = true;
							break;
						}
						GeTrade trade = parseDiscordMessage(element.getAsJsonObject());
						if (trade != null)
						{
							parsed.add(trade);
						}
					}

					mergeTrades(parsed, true);
					if (!reachedPrevious && messages.size() == 100)
					{
						String before = messages.get(messages.size() - 1).getAsJsonObject().get("id").getAsString();
						fetchNewPage(onComplete, previousId, before, latest);
						return;
					}
					newestMessageId = latest;
					backfillInProgress = false;
					saveCache();

					onComplete.accept(true);
				}
				catch (Exception e)
				{
					log.warn("Error processing new Discord messages", e);
					backfillInProgress = false;
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

	public synchronized void shutdown()
	{
		if (stopped) return;
		stopped = true;
		for (Call call : activeCalls) call.cancel();
		// Release ownership only after any running cache operation finishes.
		scheduler.execute(this::closeLock);
		scheduler.shutdown();
	}

	private void enqueue(Request request, Callback callback)
	{
		if (stopped) return;
		Call call = httpClient.newCall(request);
		activeCalls.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call failed, IOException error)
			{
				activeCalls.remove(failed);
				dispatch(() -> callback.onFailure(failed, error));
			}

			@Override
			public void onResponse(Call completed, Response response)
			{
				activeCalls.remove(completed);
				try
				{
					scheduler.execute(() ->
					{
						if (stopped) { response.close(); return; }
						try { callback.onResponse(completed, response); }
						catch (IOException e) { response.close(); log.warn("Discord response failed", e); }
					});
				}
				catch (java.util.concurrent.RejectedExecutionException e) { response.close(); }
			}
		});
	}

	private void closeLock()
	{
		try
		{
			if (cacheLock != null)
			{
				cacheLock.release();
			}
		}
		catch (Exception ignored) {}
		try
		{
			if (lockChannel != null)
			{
				lockChannel.close();
			}
		}
		catch (Exception ignored) {}
		cacheLock = null;
		lockChannel = null;
	}

	private long getRateLimitDelayMs(Response response)
	{
		long discordDelayMs = 5000;
		try
		{
			String retryAfter = response.header("Retry-After", "5");
			if (response.body() != null)
			{
				JsonObject rateLimit = gson.fromJson(response.body().string(), JsonObject.class);
				if (rateLimit != null && rateLimit.has("retry_after"))
				{
					retryAfter = rateLimit.get("retry_after").getAsString();
				}
			}
			discordDelayMs = (long) (Double.parseDouble(retryAfter) * 1000) + 500;
		}
		catch (Exception ignored) {}

		consecutiveRateLimits++;
		long localBackoffMs = MIN_RATE_LIMIT_DELAY_MS * (1L << Math.min(consecutiveRateLimits - 1, 3));
		return Math.max(discordDelayMs, Math.min(MAX_RATE_LIMIT_DELAY_MS, localBackoffMs));
	}

	private static class CacheFile
	{
		private String newestMessageId;
		private String oldestMessageId;
		private boolean backfillComplete;
		private List<GeTrade> trades;
	}
}
