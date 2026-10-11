package com.deadman;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import okhttp3.*;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TradeCacheServiceTest
{
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private final Gson gson = new Gson();
	private static final long BASE_MESSAGE_ID = (System.currentTimeMillis() - 1420070400000L - 60000) << 22;

	private String page(int newest, int oldest)
	{
		JsonArray messages = new JsonArray();
		for (int id = newest; id >= oldest; id--)
		{
			JsonObject message = new JsonObject();
			message.addProperty("id", String.valueOf(BASE_MESSAGE_ID + (long) id * 4194304));
			message.addProperty("content", gson.toJson(GeTrade.builder().itemId(id).state("SOLD")
				.quantitySold(1).price(3000000000L).spent(3000000000L).build()));
			messages.add(message);
		}
		return gson.toJson(messages);
	}

	private Filepath cache() throws Exception
	{
		Filepath file = Filepath.Unchecked.getRooted(folder.getRoot().toPath()).join("cache.json");
		file.write("{\"newestMessageId\":\"" + BASE_MESSAGE_ID + "\","
			+ "\"oldestMessageId\":\"" + BASE_MESSAGE_ID + "\",\"backfillComplete\":true,\"trades\":[]}");
		return file;
	}

	private byte[] read(Filepath file) throws Exception
	{
		try (InputStream input = file.openInputStream())
		{
			return input.readAllBytes();
		}
	}

	private OkHttpClient client(AtomicInteger requests)
	{
		return new OkHttpClient.Builder().addInterceptor(chain ->
		{
			requests.incrementAndGet();
			boolean older = chain.request().url().queryParameter("before") != null;
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(ResponseBody.create(MediaType.parse("application/json"),
					older ? page(50, 1) : page(150, 51))).build();
		}).build();
	}

	@Test public void refreshPagesAll150MessagesAndKeepsLongPrices() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		TradeCacheService service = new TradeCacheService(client(requests), gson, cache(), 1000);
		try
		{
			CountDownLatch complete = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (service.getTradeCount() == 150) complete.countDown(); });
			assertTrue(complete.await(12, TimeUnit.SECONDS));
			assertEquals(2, requests.get());
			assertEquals(3000000000L, GePriceLookupPanel.getActualPrice(service.getTradesForItem(150).get(0)));
		}
		finally { service.shutdown(); }
	}

	@Test public void cacheWithoutCursorStillBackfillsHistory() throws Exception
	{
		Filepath file = cache();
		file.write("{\"trades\":[{\"itemId\":999,\"state\":\"SOLD\",\"timestamp\":" + System.currentTimeMillis() + "}]}");
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, file, 1000);
		try
		{
			CountDownLatch complete = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (service.isInitialFetchDone() && service.getTradeCount() == 151) complete.countDown(); });
			assertTrue(complete.await(12, TimeUnit.SECONDS));
		}
		finally { service.shutdown(); }
	}

	@Test public void followerDoesNotOverwriteCacheAndTakesOver() throws Exception
	{
		Filepath file = cache();
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = client(requests);
		TradeCacheService owner = new TradeCacheService(http, gson, file, 30);
		TradeCacheService follower = new TradeCacheService(http, gson, file, 30);
		try
		{
			CountDownLatch loaded = new CountDownLatch(1);
			owner.fetchFromDiscord(success -> { if (owner.getTradeCount() == 150) loaded.countDown(); });
			assertTrue(loaded.await(12, TimeUnit.SECONDS));
			byte[] before = read(file);
			CountDownLatch following = new CountDownLatch(1);
			follower.fetchFromDiscord(success -> following.countDown());
			assertTrue(following.await(5, TimeUnit.SECONDS));
			follower.addLocalTrade(GeTrade.builder().itemId(999).state("SOLD").build());
			CountDownLatch queued = new CountDownLatch(1);
			follower.fetchNewTrades(success -> queued.countDown());
			assertTrue(queued.await(5, TimeUnit.SECONDS));
			assertArrayEquals(before, read(file));
			int initial = requests.get();
			owner.shutdown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (requests.get() == initial && System.nanoTime() < deadline) Thread.sleep(10);
			assertTrue(requests.get() > initial);
		}
		finally { owner.shutdown(); follower.shutdown(); }
	}

	@Test public void shutdownDoesNotInterruptRunningWorkOrRunDelayedTasks() throws Exception
	{
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, cache(), 1000);
		Field field = TradeCacheService.class.getDeclaredField("scheduler");
		field.setAccessible(true);
		ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) field.get(service);
		CountDownLatch running = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicBoolean delayedRan = new AtomicBoolean();
		try
		{
			executor.execute(() ->
			{
				running.countDown();
				try { release.await(5, TimeUnit.SECONDS); }
				catch (InterruptedException e) { interrupted.set(true); }
			});
			assertTrue(running.await(5, TimeUnit.SECONDS));
			executor.schedule(() -> delayedRan.set(true), 1, TimeUnit.HOURS);
			service.shutdown();
			service.shutdown();
			assertFalse(executor.isTerminated());
			release.countDown();
			assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
			assertFalse(interrupted.get());
			assertFalse(delayedRan.get());
		}
		finally { release.countDown(); service.shutdown(); }
	}

	@Test public void rateLimitHonorsLongServerDelay() throws Exception
	{
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, cache(), 1000);
		try
		{
			Method method = TradeCacheService.class.getDeclaredMethod("getRateLimitDelayMs", Response.class);
			method.setAccessible(true);
			try (Response response = new Response.Builder().request(new Request.Builder().url("https://discord.com").build())
				.protocol(Protocol.HTTP_1_1).code(429).message("Limited")
				.body(ResponseBody.create(MediaType.parse("application/json"), "{\"retry_after\":120}")).build())
			{
				assertEquals(120500L, method.invoke(service, response));
			}
		}
		finally { service.shutdown(); }
	}

	@Test public void pagesArePacedEvenWhenServerRespondsImmediately() throws Exception
	{
		java.util.List<Long> starts = new java.util.concurrent.CopyOnWriteArrayList<>();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			starts.add(System.nanoTime());
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(ResponseBody.create(MediaType.parse("application/json"),
					starts.size() == 1 ? page(150, 51) : page(50, 1))).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, cache(), 1000);
		try
		{
			CountDownLatch done = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (service.getTradeCount() == 150) done.countDown(); });
			assertTrue(done.await(9, TimeUnit.SECONDS));
			assertEquals(2, starts.size());
			assertTrue("Successful pages must not burst", starts.get(1) - starts.get(0) >= TimeUnit.SECONDS.toNanos(5));
		}
		finally { service.shutdown(); }
	}

	@Test public void historyStopsAtThreeMonthBoundaryAndPrunesExistingCache() throws Exception
	{
		long cutoff = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMonths(3).toInstant().toEpochMilli();
		GeTrade recent = GeTrade.builder().itemId(1).state("SOLD").quantitySold(1).timestamp(cutoff + 86400000L).build();
		GeTrade expired = GeTrade.builder().itemId(2).state("SOLD").quantitySold(1).timestamp(cutoff - 86400000L).build();
		Filepath file = cache();
		JsonObject cached = new JsonObject();
		cached.add("trades", gson.toJsonTree(java.util.Arrays.asList(recent, expired)));
		file.write(gson.toJson(cached));
		JsonArray messages = new JsonArray();
		for (int i = 0; i < 100; i++)
		{
			JsonObject message = new JsonObject();
			message.addProperty("id", String.valueOf(((cutoff - 86400000L - i) - 1420070400000L) << 22));
			message.addProperty("content", gson.toJson(expired));
			messages.add(message);
		}
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			requests.incrementAndGet();
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), gson.toJson(messages))).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, file, 1000);
		try
		{
			CountDownLatch done = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (success && service.isInitialFetchDone() && requests.get() > 0) done.countDown(); });
			assertTrue(done.await(2, TimeUnit.SECONDS));
			assertEquals(1, requests.get());
			assertEquals(1, service.getTradeCount());
			assertTrue(service.getTradesForItem(2).isEmpty());
			JsonObject saved = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(1, saved.getAsJsonArray("trades").size());
			assertTrue(saved.get("backfillComplete").getAsBoolean());
		}
		finally { service.shutdown(); }
	}

	@Test public void itemLookupDoesNotScanUnrelatedTrades() throws Exception
	{
		AtomicInteger reads = new AtomicInteger();
		GeTrade unrelated = new GeTrade(1, 1, 1, 1, 1, "SOLD", 0, false, System.currentTimeMillis(), 345)
		{
			@Override public int getItemId() { reads.incrementAndGet(); return super.getItemId(); }
		};
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, cache(), 1000);
		try
		{
			service.addLocalTrade(unrelated);
			Field field = TradeCacheService.class.getDeclaredField("scheduler");
			field.setAccessible(true);
			CountDownLatch merged = new CountDownLatch(1);
			((ScheduledThreadPoolExecutor) field.get(service)).execute(merged::countDown);
			assertTrue(merged.await(2, TimeUnit.SECONDS));
			reads.set(0);
			assertTrue(service.getTradesForItem(999).isEmpty());
			assertTrue(service.getAllItemIds().contains(1));
			assertEquals("Reads must use the item index", 0, reads.get());
		}
		finally { service.shutdown(); }
	}

	@Test public void partialBackfillBatchesWritesAndNotificationsButShutdownFlushesProgress() throws Exception
	{
		Filepath file = cache();
		file.write("{\"newestMessageId\":\"" + (BASE_MESSAGE_ID + 151L * 4194304) + "\","
			+ "\"oldestMessageId\":\"" + (BASE_MESSAGE_ID + 151L * 4194304) + "\",\"backfillComplete\":false,\"trades\":[]}");
		byte[] original = read(file);
		CountDownLatch second = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger requests = new AtomicInteger();
		AtomicInteger notifications = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			if (requests.incrementAndGet() > 1)
			{
				second.countDown();
				try { release.await(10, TimeUnit.SECONDS); }
				catch (InterruptedException e) { throw new java.io.IOException(e); }
			}
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), page(150, 51))).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, file, 1000);
		try
		{
			service.fetchFromDiscord(success -> notifications.incrementAndGet());
			assertTrue(second.await(8, TimeUnit.SECONDS));
			assertEquals(100, service.getTradeCount());
			assertEquals("Only the initial cache load should notify before the batch deadline", 1, notifications.get());
			assertArrayEquals(original, read(file));
			service.shutdown();
			Field field = TradeCacheService.class.getDeclaredField("scheduler");
			field.setAccessible(true);
			assertTrue(((ScheduledThreadPoolExecutor) field.get(service)).awaitTermination(3, TimeUnit.SECONDS));
			JsonObject saved = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(100, saved.getAsJsonArray("trades").size());
			assertFalse(saved.get("backfillComplete").getAsBoolean());
		}
		finally { release.countDown(); service.shutdown(); }
	}

	@Test public void thirtySecondCheckpointSavesAndNotifiesDuringIncompleteSync() throws Exception
	{
		Filepath file = Filepath.Unchecked.getRooted(folder.getRoot().toPath()).join("cache.json");
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch notified = new CountDownLatch(1);
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			if (requests.incrementAndGet() > 1)
			{
				try { release.await(45, TimeUnit.SECONDS); }
				catch (InterruptedException e) { throw new java.io.IOException(e); }
			}
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), page(150, 51))).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, file, 1000);
		try
		{
			service.fetchFromDiscord(success -> { if (success) notified.countDown(); });
			assertTrue("Active sync must checkpoint without waiting for completion", notified.await(35, TimeUnit.SECONDS));
			assertEquals(100, service.getTradeCount());
			JsonObject saved = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(100, saved.getAsJsonArray("trades").size());
			assertFalse(saved.get("backfillComplete").getAsBoolean());
		}
		finally { release.countDown(); service.shutdown(); }
	}

	@Test public void indexedHistoryIsSortedDeduplicatedAndSnapshotsRemainStable() throws Exception
	{
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, cache(), 1000);
		try
		{
			GeTrade older = GeTrade.builder().itemId(1).state("SOLD").timestamp(System.currentTimeMillis() - 1000).build();
			GeTrade newer = GeTrade.builder().itemId(1).state("BOUGHT").timestamp(System.currentTimeMillis()).build();
			service.addLocalTrade(older);
			Field field = TradeCacheService.class.getDeclaredField("scheduler");
			field.setAccessible(true);
			ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) field.get(service);
			CountDownLatch first = new CountDownLatch(1);
			scheduler.execute(first::countDown);
			assertTrue(first.await(2, TimeUnit.SECONDS));
			java.util.List<GeTrade> snapshot = service.getTradesForItem(1);
			service.addLocalTrade(newer);
			service.addLocalTrade(older);
			CountDownLatch merged = new CountDownLatch(1);
			scheduler.execute(merged::countDown);
			assertTrue(merged.await(2, TimeUnit.SECONDS));
			assertEquals(2, service.getTradeCount());
			assertEquals(1, snapshot.size());
			assertEquals(newer, service.getTradesForItem(1).get(0));
			assertEquals(older, service.getTradesForItem(1).get(1));
			assertEquals(newer.getTimestamp(), service.getLatestTimestamp(1));
		}
		finally { service.shutdown(); }
	}

	@Test public void failedInitialSyncCanBeRetriedWithoutStartingDuplicateBackfills() throws Exception
	{
		Filepath file = Filepath.Unchecked.getRooted(folder.getRoot().toPath()).join("cache.json");
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			int number = requests.incrementAndGet();
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(number == 1 ? 503 : 200).message("Test")
				.body(ResponseBody.create(MediaType.parse("application/json"), number == 1 ? "{}" : page(50, 1))).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, file, 1000);
		try
		{
			CountDownLatch failed = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (!success) failed.countDown(); });
			assertTrue(failed.await(2, TimeUnit.SECONDS));
			CountDownLatch retried = new CountDownLatch(1);
			service.fetchFromDiscord(success -> retried.countDown());
			assertTrue(retried.await(9, TimeUnit.SECONDS));
			assertEquals(2, requests.get());
			assertEquals(50, service.getTradeCount());
		}
		finally { service.shutdown(); }
	}

	@Test public void loadingExpiredCacheCheckpointsEvenWhenRequestIsStalled() throws Exception
	{
		Filepath file = cache();
		JsonObject cached = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
		GeTrade expired = GeTrade.builder().itemId(1).state("SOLD").timestamp(1).build();
		cached.add("trades", gson.toJsonTree(java.util.Collections.singletonList(expired)));
		file.write(gson.toJson(cached));
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch checkpoint = new CountDownLatch(2);
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			try { release.await(45, TimeUnit.SECONDS); }
			catch (InterruptedException e) { throw new java.io.IOException(e); }
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), "[]")).build();
		}).build();
		TradeCacheService service = new TradeCacheService(http, gson, file, 1000);
		try
		{
			service.fetchFromDiscord(success -> { if (success) checkpoint.countDown(); });
			assertTrue("Pruned cache must checkpoint without a network response", checkpoint.await(35, TimeUnit.SECONDS));
			JsonObject saved = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(0, saved.getAsJsonArray("trades").size());
		}
		finally { release.countDown(); service.shutdown(); }
	}

	@Test public void partialNewSyncKeepsCompletedCursorAndReplaysWithoutDuplicates() throws Exception
	{
		Filepath file = cache();
		CountDownLatch second = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			if (requests.incrementAndGet() > 1)
			{
				second.countDown();
				try { release.await(12, TimeUnit.SECONDS); }
				catch (InterruptedException e) { throw new java.io.IOException(e); }
			}
			return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), page(150, 51))).build();
		}).build();
		TradeCacheService first = new TradeCacheService(http, gson, file, 1000);
		TradeCacheService restarted = null;
		try
		{
			first.fetchFromDiscord(success -> {});
			assertTrue(second.await(8, TimeUnit.SECONDS));
			first.shutdown();
			Field field = TradeCacheService.class.getDeclaredField("scheduler");
			field.setAccessible(true);
			assertTrue(((ScheduledThreadPoolExecutor) field.get(first)).awaitTermination(3, TimeUnit.SECONDS));
			JsonObject partial = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(String.valueOf(BASE_MESSAGE_ID), partial.get("newestMessageId").getAsString());
			assertEquals(100, partial.getAsJsonArray("trades").size());
			release.countDown();
			restarted = new TradeCacheService(client(new AtomicInteger()), gson, file, 1000);
			TradeCacheService active = restarted;
			CountDownLatch complete = new CountDownLatch(1);
			restarted.fetchFromDiscord(success -> { if (success && active.getTradeCount() == 150) complete.countDown(); });
			assertTrue(complete.await(9, TimeUnit.SECONDS));
			JsonObject saved = gson.fromJson(new String(read(file), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
			assertEquals(String.valueOf(BASE_MESSAGE_ID + 150L * 4194304), saved.get("newestMessageId").getAsString());
			assertEquals(150, saved.getAsJsonArray("trades").size());
		}
		finally { release.countDown(); first.shutdown(); if (restarted != null) restarted.shutdown(); }
	}
}
