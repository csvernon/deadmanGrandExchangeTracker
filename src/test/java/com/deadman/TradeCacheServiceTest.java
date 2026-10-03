package com.deadman;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class TradeCacheServiceTest
{
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private final Gson gson = new Gson();

	private String page(int newest, int oldest)
	{
		JsonArray messages = new JsonArray();
		for (int id = newest; id >= oldest; id--)
		{
			JsonObject message = new JsonObject();
			message.addProperty("id", String.valueOf(1000000000000000000L + (long) id * 4194304));
			message.addProperty("content", gson.toJson(GeTrade.builder().itemId(id).state("SOLD")
				.quantitySold(1).price(3000000000L).spent(3000000000L).build()));
			messages.add(message);
		}
		return gson.toJson(messages);
	}

	private File cache() throws Exception
	{
		File file = new File(folder.getRoot(), "cache.json");
		Files.write(file.toPath(), ("{\"newestMessageId\":\"1000000000000000000\","
			+ "\"oldestMessageId\":\"1000000000000000000\",\"backfillComplete\":true,\"trades\":[]}").getBytes(StandardCharsets.UTF_8));
		return file;
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
			assertTrue(complete.await(5, TimeUnit.SECONDS));
			assertEquals(2, requests.get());
			assertEquals(3000000000L, GePriceLookupPanel.getActualPrice(service.getTradesForItem(150).get(0)));
		}
		finally { service.shutdown(); }
	}

	@Test public void cacheWithoutCursorStillBackfillsHistory() throws Exception
	{
		File file = cache();
		Files.write(file.toPath(), "{\"trades\":[{\"itemId\":999,\"state\":\"SOLD\"}]}".getBytes(StandardCharsets.UTF_8));
		TradeCacheService service = new TradeCacheService(client(new AtomicInteger()), gson, file, 1000);
		try
		{
			CountDownLatch complete = new CountDownLatch(1);
			service.fetchFromDiscord(success -> { if (service.isInitialFetchDone() && service.getTradeCount() == 151) complete.countDown(); });
			assertTrue(complete.await(5, TimeUnit.SECONDS));
		}
		finally { service.shutdown(); }
	}

	@Test public void followerDoesNotOverwriteCacheAndTakesOver() throws Exception
	{
		File file = cache();
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = client(requests);
		TradeCacheService owner = new TradeCacheService(http, gson, file, 30);
		TradeCacheService follower = new TradeCacheService(http, gson, file, 30);
		try
		{
			CountDownLatch loaded = new CountDownLatch(1);
			owner.fetchFromDiscord(success -> { if (owner.getTradeCount() == 150) loaded.countDown(); });
			assertTrue(loaded.await(5, TimeUnit.SECONDS));
			byte[] before = Files.readAllBytes(file.toPath());
			CountDownLatch following = new CountDownLatch(1);
			follower.fetchFromDiscord(success -> following.countDown());
			assertTrue(following.await(5, TimeUnit.SECONDS));
			follower.addLocalTrade(GeTrade.builder().itemId(999).state("SOLD").build());
			CountDownLatch queued = new CountDownLatch(1);
			follower.fetchNewTrades(success -> queued.countDown());
			assertTrue(queued.await(5, TimeUnit.SECONDS));
			assertArrayEquals(before, Files.readAllBytes(file.toPath()));
			int initial = requests.get();
			owner.shutdown();
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (requests.get() == initial && System.nanoTime() < deadline) Thread.sleep(10);
			assertTrue(requests.get() > initial);
		}
		finally { owner.shutdown(); follower.shutdown(); }
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
}
