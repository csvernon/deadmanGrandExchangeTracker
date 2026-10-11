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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
	private final Filepath defaultCacheFile;
	private Filepath CACHE_FILE;
	private Filepath LOCK_FILE;
	private static final long MIN_RATE_LIMIT_DELAY_MS = 5500;
	private static final long MAX_RATE_LIMIT_DELAY_MS = 60000;
	private final long cacheReloadIntervalMs;
	private static final long PAGE_INTERVAL_MS = 5000;
	private static final long UPDATE_INTERVAL_MS = 30000;
	private long requestNotBeforeNanos;
	private boolean initialized;
	private boolean cacheDirty;
	private ScheduledFuture<?> pendingUpdate;
	private ScheduledFuture<?> cacheWatcher;
	private Consumer<Boolean> pendingNotification;
	private TradeHistoryLimit historyLimit = TradeHistoryLimit.THREE_MONTHS;
	private long syncGeneration;
	private Consumer<Boolean> activeFetchNotification;

	private static String xorDecode(String hex)
	{
		StringBuilder sb = new StringBuilder(hex.length() / 2);
		for (int i = 0; i < hex.length(); i += 2)
		{
			sb.append((char) (Integer.parseInt(hex.substring(i, i + 2), 16) ^ K));
		}
		return sb.toString();
	}

	// Mutated only on the worker; readers see immutable per-item snapshots.
	private final Map<String, GeTrade> trades = new LinkedHashMap<>();
	private final Map<Integer, List<GeTrade>> itemTrades = new HashMap<>();
	private final Map<Integer, List<GeTrade>> itemSnapshots = new ConcurrentHashMap<>();
	private volatile int tradeCount;
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
		this.defaultCacheFile = cacheFile;
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
			mergeTrades(Collections.singletonList(trade));
			queueUpdate(null);
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

	public void setHistoryLimit(TradeHistoryLimit limit, Consumer<Boolean> onComplete)
	{
		dispatch(() ->
		{
			TradeHistoryLimit selected = limit == null ? TradeHistoryLimit.THREE_MONTHS : limit;
			if (historyLimit == selected)
			{
				onComplete.accept(initialFetchDone);
				return;
			}
			if (initialized)
			{
				flushUpdate(false);
				closeLock();
				if (cacheWatcher != null) cacheWatcher.cancel(false);
				syncOwner = false;
				syncGeneration++;
				backfillInProgress = false;
				Consumer<Boolean> superseded = activeFetchNotification;
				activeFetchNotification = null;
				if (superseded != null) superseded.accept(false);
			}
			historyLimit = selected;
			// Clients sharing a window share one sync owner; different windows cannot prune each other's cache.
			CACHE_FILE = selected == TradeHistoryLimit.THREE_MONTHS ? defaultCacheFile
				: defaultCacheFile.getParent().join("trades-cache-" + selected.name() + ".json");
			LOCK_FILE = defaultCacheFile.getParent().join("trades-cache-" + selected.name() + ".lock");
			if (selected == TradeHistoryLimit.THREE_MONTHS) LOCK_FILE = defaultCacheFile.getParent().join("trades-cache.lock");
			pruneExpiredTrades();
			oldestMessageId = null;
			newestMessageId = null;
			backfillComplete = false;
			cacheDirty = true;
			if (initialized)
			{
				syncOwner = acquireSyncLock();
				if (loadCache(onComplete)) initialFetchDone = true;
				flushUpdate(false);
			}
			onComplete.accept(true);
			if (initialized)
			{
				if (syncOwner)
				{
					if (!backfillComplete || newestMessageId == null) fetchPage(onComplete);
					else { lastNewTradeFetchAttempt = 0; startNewTrades(onComplete); }
				}
				else startCacheWatcher(onComplete);
			}
		});
	}

	private void initialize(Consumer<Boolean> onComplete)
	{
		if (initialized)
		{
			if (!syncOwner || backfillInProgress) onComplete.accept(initialFetchDone);
			else if (!backfillComplete) fetchPage(onComplete);
			else startNewTrades(onComplete);
			return;
		}
		initialized = true;
		scheduler.scheduleWithFixedDelay(() ->
		{
			if (!stopped && pruneExpiredTrades()) queueUpdate(onComplete);
		}, UPDATE_INTERVAL_MS, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
		syncOwner = syncOwner || acquireSyncLock();
		if (loadCache(onComplete))
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

			if (newestMessageId == null || !backfillComplete)
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

	private boolean loadCache(Consumer<Boolean> onComplete)
	{
		Filepath source = CACHE_FILE;
		if (!source.isFile() && historyLimit != TradeHistoryLimit.THREE_MONTHS) source = defaultCacheFile;
		if (!source.isFile())
		{
			return false;
		}

		try (Reader reader = source.openBufferedReader())
		{
			CacheFile cache = gson.fromJson(reader, CacheFile.class);
			if (cache == null || cache.trades == null)
			{
				return false;
			}

			trades.clear();
			itemTrades.clear();
			itemSnapshots.clear();
			mergeTrades(cache.trades);
			cacheDirty = source != CACHE_FILE || cache.trades.size() != trades.size();
			newestMessageId = cache.newestMessageId;
			oldestMessageId = cache.oldestMessageId;
			backfillComplete = cache.backfillComplete;
			TradeHistoryLimit cachedLimit = cache.historyLimit == null ? TradeHistoryLimit.THREE_MONTHS : cache.historyLimit;
			if (cachedLimit.ordinal() < historyLimit.ordinal())
			{
				oldestMessageId = null;
				backfillComplete = false;
			}
			cacheDirty |= cachedLimit != historyLimit;
			rememberCacheMetadata();
			if (cacheDirty && syncOwner) queueUpdate(onComplete);
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
		if (!syncOwner || !cacheDirty)
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
			cache.historyLimit = historyLimit;
			cache.trades = new ArrayList<>(trades.values());

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
			cacheDirty = false;
		}
		catch (Exception e)
		{
			log.warn("Failed to save Deadman trade cache", e);
		}
	}

	private void queueUpdate(Consumer<Boolean> onComplete)
	{
		cacheDirty = true;
		if (onComplete != null) pendingNotification = onComplete;
		if (pendingUpdate == null && !stopped)
		{
			pendingUpdate = scheduler.schedule(() -> flushUpdate(true), UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}
	}

	private void flushUpdate(boolean notify)
	{
		if (pendingUpdate != null) pendingUpdate.cancel(false);
		pendingUpdate = null;
		pruneExpiredTrades();
		saveCache();
		Consumer<Boolean> callback = pendingNotification;
		pendingNotification = null;
		if (notify && !stopped && callback != null) callback.accept(true);
	}

	private void finishFetch(Consumer<Boolean> onComplete, boolean success)
	{
		activeFetchNotification = null;
		initialFetchDone = true;
		backfillInProgress = false;
		flushUpdate(false);
		if (!stopped) onComplete.accept(success);
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
		long generation = syncGeneration;
		cacheWatcher = scheduler.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (stopped || generation != syncGeneration) return;
				if (!syncOwner && acquireSyncLock())
				{
					syncOwner = true;
					loadCache(onComplete);
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

				if (loadCache(onComplete))
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

	private long historyCutoff()
	{
		return historyLimit.cutoff(java.time.Instant.now());
	}

	private void mergeTrades(List<GeTrade> parsed)
	{
		long cutoff = historyCutoff();
		Set<Integer> changed = new HashSet<>();
		for (GeTrade trade : parsed)
		{
			if (trade != null && trade.getTimestamp() >= cutoff && trades.putIfAbsent(tradeKey(trade), trade) == null)
			{
				itemTrades.computeIfAbsent(trade.getItemId(), id -> new ArrayList<>()).add(trade);
				changed.add(trade.getItemId());
			}
		}
		publishItems(changed);
	}

	private boolean pruneExpiredTrades()
	{
		long cutoff = historyCutoff();
		Set<Integer> changed = new HashSet<>();
		Iterator<GeTrade> iterator = trades.values().iterator();
		while (iterator.hasNext())
		{
			GeTrade trade = iterator.next();
			if (trade.getTimestamp() < cutoff)
			{
				changed.add(trade.getItemId());
				iterator.remove();
			}
		}
		for (int item : changed) itemTrades.get(item).removeIf(trade -> trade.getTimestamp() < cutoff);
		publishItems(changed);
		if (!changed.isEmpty()) cacheDirty = true;
		return !changed.isEmpty();
	}

	private void publishItems(Set<Integer> changed)
	{
		for (int item : changed)
		{
			List<GeTrade> snapshot = new ArrayList<>(itemTrades.get(item));
			snapshot.sort(Comparator.comparingLong(GeTrade::getTimestamp).reversed());
			if (snapshot.isEmpty())
			{
				itemTrades.remove(item);
				itemSnapshots.remove(item);
			}
			else itemSnapshots.put(item, Collections.unmodifiableList(snapshot));
		}
		tradeCount = trades.size();
	}

	private static long messageTimestamp(JsonObject message)
	{
		return (Long.parseLong(message.get("id").getAsString()) >> 22) + 1420070400000L;
	}

	private static String tradeKey(GeTrade trade)
	{
		return trade.getItemId() + ":" + trade.getQuantitySold() + ":" + trade.getTotalQuantity() + ":" + trade.getPrice() + ":" +
			trade.getSpent() + ":" + trade.getState() + ":" + trade.getSlot() + ":" + trade.isBuy() + ":" + trade.getTimestamp() + ":" + trade.getWorld();
	}

	private void fetchPage(Consumer<Boolean> onComplete)
	{
		activeFetchNotification = onComplete;
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
				finishFetch(onComplete, false);
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
						retryRequest(() -> fetchPage(onComplete), delayMs);
						return;
					}
					consecutiveRateLimits = 0;

					if (!response.isSuccessful())
					{
						log.warn("Discord API returned status {}", response.code());
						finishFetch(onComplete, false);
						return;
					}

					if (response.body() == null)
					{
						finishFetch(onComplete, false);
						return;
					}

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						backfillComplete = true;
						cacheDirty = true;
						finishFetch(onComplete, true);
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
					mergeTrades(parsed);

					// Track newest message ID (first in array = newest)
					if (newestMessageId == null)
					{
						newestMessageId = messages.get(0).getAsJsonObject().get("id").getAsString();
					}

					// Track oldest message ID for pagination
					JsonObject lastMessage = messages.get(messages.size() - 1).getAsJsonObject();
					oldestMessageId = lastMessage.get("id").getAsString();
					queueUpdate(onComplete);
					log.debug("Fetched {} Discord messages, cached {} Deadman trades so far", messages.size(), trades.size());

					if (messages.size() == 100 && messageTimestamp(lastMessage) >= historyCutoff())
					{
						fetchPage(onComplete);
					}
					else
					{
						backfillComplete = true;
						finishFetch(onComplete, true);
					}
				}
				catch (Exception e)
				{
					log.warn("Error processing Discord messages", e);
					finishFetch(onComplete, false);
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
		return itemSnapshots.getOrDefault(itemId, Collections.emptyList());
	}

	public Set<Integer> getAllItemIds()
	{
		return new HashSet<>(itemSnapshots.keySet());
	}

	public long getLatestTimestamp(int itemId)
	{
		List<GeTrade> snapshot = getTradesForItem(itemId);
		return snapshot.isEmpty() ? 0 : snapshot.get(0).getTimestamp();
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
		if (!backfillComplete)
		{
			fetchPage(onComplete);
			return;
		}

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
		activeFetchNotification = onComplete;
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
				finishFetch(onComplete, false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (response.code() == 429)
					{
						long delay = getRateLimitDelayMs(response);
						retryRequest(() -> fetchNewPage(onComplete, previousId, beforeId, latestId), delay);
						return;
					}
					if (!response.isSuccessful() || response.body() == null)
					{
						finishFetch(onComplete, false);
						return;
					}
					consecutiveRateLimits = 0;

					String body = response.body().string();
					JsonArray messages = gson.fromJson(body, JsonArray.class);

					if (messages == null || messages.size() == 0)
					{
						if (latestId != null) newestMessageId = latestId;
						cacheDirty = true;
						finishFetch(onComplete, true);
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
						if (messageTimestamp(element.getAsJsonObject()) < historyCutoff())
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

					mergeTrades(parsed);
					queueUpdate(onComplete);
					if (!reachedPrevious && messages.size() == 100)
					{
						String before = messages.get(messages.size() - 1).getAsJsonObject().get("id").getAsString();
						fetchNewPage(onComplete, previousId, before, latest);
						return;
					}
					newestMessageId = latest;
					finishFetch(onComplete, true);
				}
				catch (Exception e)
				{
					log.warn("Error processing new Discord messages", e);
					finishFetch(onComplete, false);
				}
			}
		});
	}

	public int getTradeCount()
	{
		return tradeCount;
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
		scheduler.execute(() -> { flushUpdate(false); closeLock(); });
		scheduler.shutdown();
	}

	private void enqueue(Request request, Callback callback)
	{
		if (stopped) return;
		long generation = syncGeneration;
		long delay = Math.max(0, requestNotBeforeNanos - System.nanoTime());
		scheduler.schedule(() -> executeRequest(request, callback, generation), delay, TimeUnit.NANOSECONDS);
	}

	private void retryRequest(Runnable retry, long delayMs)
	{
		long generation = syncGeneration;
		scheduler.schedule(() ->
		{
			if (!stopped && generation == syncGeneration) retry.run();
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	private synchronized void executeRequest(Request request, Callback callback, long generation)
	{
		if (stopped || generation != syncGeneration) return;
		long delay = requestNotBeforeNanos - System.nanoTime();
		if (!activeCalls.isEmpty()) delay = Math.max(delay, TimeUnit.MILLISECONDS.toNanos(PAGE_INTERVAL_MS));
		if (delay > 0)
		{
			scheduler.schedule(() -> executeRequest(request, callback, generation), delay, TimeUnit.NANOSECONDS);
			return;
		}
		Call call = httpClient.newCall(request);
		activeCalls.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call failed, IOException error)
			{
				dispatch(() ->
				{
					requestNotBeforeNanos = Math.max(requestNotBeforeNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAGE_INTERVAL_MS));
					activeCalls.remove(failed);
					if (generation == syncGeneration) callback.onFailure(failed, error);
				});
			}

			@Override
			public void onResponse(Call completed, Response response)
			{
				try
				{
					scheduler.execute(() ->
					{
						if (stopped) { activeCalls.remove(completed); response.close(); return; }
						requestNotBeforeNanos = Math.max(requestNotBeforeNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAGE_INTERVAL_MS));
						if (generation != syncGeneration)
						{
							try (response)
							{
								if (response.code() == 429) getRateLimitDelayMs(response);
							}
							finally { activeCalls.remove(completed); }
							return;
						}
						try { callback.onResponse(completed, response); }
						catch (IOException e) { response.close(); log.warn("Discord response failed", e); }
						finally { activeCalls.remove(completed); }
					});
				}
				catch (java.util.concurrent.RejectedExecutionException e) { activeCalls.remove(completed); response.close(); }
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
		long delayMs = Math.max(discordDelayMs, Math.min(MAX_RATE_LIMIT_DELAY_MS, localBackoffMs));
		requestNotBeforeNanos = Math.max(requestNotBeforeNanos, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs));
		return delayMs;
	}

	private static class CacheFile
	{
		private String newestMessageId;
		private String oldestMessageId;
		private boolean backfillComplete;
		private TradeHistoryLimit historyLimit;
		private List<GeTrade> trades;
	}
}
