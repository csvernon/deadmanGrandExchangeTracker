package com.deadman;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.runelite.client.callback.ClientThread;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GePriceLookupPanelTest
{
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void resolvesNamesInSmallBatchesAndStopsWhenDisabled() throws Exception
    {
        TradeCacheService service = new TradeCacheService(new OkHttpClient(), new Gson(),
            Filepath.Unchecked.getRooted(folder.getRoot().toPath()));
        AtomicReference<GePriceLookupPanel> panel = new AtomicReference<>();
        try
        {
            for (int id = 1; id <= 100; id++)
                service.addLocalTrade(GeTrade.builder().itemId(id).state("SOLD").timestamp(System.currentTimeMillis()).build());
            Field scheduler = TradeCacheService.class.getDeclaredField("scheduler");
            scheduler.setAccessible(true);
            CountDownLatch merged = new CountDownLatch(1);
            ((ScheduledThreadPoolExecutor) scheduler.get(service)).execute(merged::countDown);
            assertTrue(merged.await(2, TimeUnit.SECONDS));
            // No item definitions: exercise the real fallback-name path without a live game client.
            SwingUtilities.invokeAndWait(() -> panel.set(new GePriceLookupPanel(service, null)));
            QueuedThread thread = new QueuedThread();
            panel.get().requestItemNames(thread);
            panel.get().requestItemNames(thread);
            assertEquals("Repeated refreshes must share one client task", 1, thread.tasks.size());
            BooleanSupplier task = thread.tasks.get(0);
            assertFalse(task.getAsBoolean());
            Field names = GePriceLookupPanel.class.getDeclaredField("itemNameCache");
            names.setAccessible(true);
            Map<?,?> resolved = (Map<?,?>) names.get(panel.get());
            assertEquals("Only one bounded batch may run in a client tick", 20, resolved.size());
            service.addLocalTrade(GeTrade.builder().itemId(102).state("SOLD").timestamp(System.currentTimeMillis()).build());
            CountDownLatch incoming = new CountDownLatch(1);
            ((ScheduledThreadPoolExecutor) scheduler.get(service)).execute(incoming::countDown);
            assertTrue(incoming.await(2, TimeUnit.SECONDS));
            panel.get().requestItemNames(thread);
            assertEquals(1, thread.tasks.size());
            for (int pass = 0; pass < 3; pass++) assertFalse(task.getAsBoolean());
            assertFalse("A refresh arriving mid-resolution must be picked up", task.getAsBoolean());
            assertEquals(100, resolved.size());
            assertTrue(task.getAsBoolean());
            assertEquals(101, resolved.size());
            service.addLocalTrade(GeTrade.builder().itemId(101).state("SOLD").timestamp(System.currentTimeMillis()).build());
            CountDownLatch added = new CountDownLatch(1);
            ((ScheduledThreadPoolExecutor) scheduler.get(service)).execute(added::countDown);
            assertTrue(added.await(2, TimeUnit.SECONDS));
            panel.get().requestItemNames(thread);
            SwingUtilities.invokeAndWait(panel.get()::stopAutoRefresh);
            assertTrue(thread.tasks.get(1).getAsBoolean());
            assertEquals("Queued name work must not run after shutdown", 101, resolved.size());
        }
        finally
        {
            if (panel.get() != null) SwingUtilities.invokeAndWait(panel.get()::stopAutoRefresh);
            service.shutdown();
        }
    }

    private static final class QueuedThread extends ClientThread
    {
        final List<BooleanSupplier> tasks = new ArrayList<>();
        @Override public void invokeLater(BooleanSupplier task) { tasks.add(task); }
    }
}
