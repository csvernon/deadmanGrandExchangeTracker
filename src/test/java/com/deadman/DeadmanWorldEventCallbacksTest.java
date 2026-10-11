package com.deadman;

import com.deadman.world.EventMarkersOverlay;
import com.deadman.world.EventSession;
import com.deadman.world.LocationIndex;
import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeadmanWorldEventCallbacksTest
{
    @Test
    public void sceneLoadingHidesAndRestoresMarkersButHoppingClearsThem() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.chest("Lumbridge");
            assertEquals(1, fixture.points.registered.size());
            fixture.state(GameState.LOADING);
            assertTrue(fixture.points.registered.isEmpty());
            fixture.state(GameState.LOGGED_IN);
            assertEquals(1, fixture.points.registered.size());
            fixture.state(GameState.HOPPING);
            fixture.state(GameState.LOGGED_IN);
            assertTrue(fixture.points.registered.isEmpty());
            assertTrue(fixture.session.rows().isEmpty());
        }
    }

    @Test
    public void savedOverrideIsUsedOnStartupAndChangedThroughConfigCallback() throws Exception
    {
        try (Fixture fixture = new Fixture("Lumbridge | 1111,2222,0"))
        {
            fixture.chest("Lumbridge");
            assertEquals(new WorldPoint(1111,2222,0), fixture.points.registered.get(0).getTarget());
            fixture.override.set("Lumbridge | 3333,4444,0");
            fixture.configChanged();
            fixture.thread.flush();
            assertEquals(new WorldPoint(3333,4444,0), fixture.points.registered.get(0).getTarget());
            fixture.state(GameState.LOGIN_SCREEN);
            assertTrue(fixture.points.registered.isEmpty());
        }
    }

    @Test
    public void queuedWorkFromDisabledSessionCannotReplaceNewSessionMarkers() throws Exception
    {
        try (Fixture fixture = new Fixture())
        {
            fixture.chest("Lumbridge");
            EventSession old = fixture.session;
            fixture.configChanged();
            fixture.plugin.shutDown();
            fixture.installSession();
            fixture.chest("Varrock");
            fixture.thread.flush();
            assertTrue(old.rows().isEmpty());
            assertEquals(1, fixture.points.registered.size());
            assertTrue(fixture.points.registered.get(0).getName().contains("Varrock"));
            assertEquals("Varrock", fixture.session.rows().get(0).getPlace());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final DeadmanPlugin plugin = new DeadmanPlugin();
        final RecordingPoints points = new RecordingPoints();
        final QueuedThread thread = new QueuedThread();
        final AtomicReference<String> override = new AtomicReference<>("");
        final Map<String,Object> clientValues = new HashMap<>();
        final Client client;
        final BossBreachPanel panel;
        EventSession session;

        Fixture() throws Exception { this(""); }

        Fixture(String savedOverride) throws Exception
        {
            override.set(savedOverride);
            clientValues.put("getGameState",GameState.LOGGED_IN);
            clientValues.put("getWorld",345);
            client = RuneLiteStubs.api(Client.class,clientValues);
            AtomicReference<BossBreachPanel> created = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> created.set(new BossBreachPanel()));
            panel = created.get();
            field("client",client);
            field("clientThread",thread);
            field("bossBreachPanel",panel);
            // No GPU or GE infrastructure is needed to exercise owned marker cleanup.
            Constructor<?> constructor = OverlayManager.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            field("overlayManager",constructor.newInstance(new Object[constructor.getParameterCount()]));
            installSession();
        }

        void installSession() throws Exception
        {
            session = new EventSession(new DeadmanConfig(){
                @Override public String eventLocationOverrides(){return override.get();}
            },LocationIndex.bundled(new Gson()));
            session.session(GameState.LOGGED_IN,345);
            field("worldEventSession",session);
            field("eventMapOverlay",new EventMarkersOverlay(client,points));
            field("lastEventRevision",-1L);
        }

        void chest(String place) throws Exception
        {
            ChatMessage chat = new ChatMessage();
            chat.setType(ChatMessageType.BROADCAST);
            chat.setMessage("A Deadman's Chest has spawned at " + place + "!");
            callback("onChatMessage",ChatMessage.class,chat);
        }

        void state(GameState state) throws Exception
        {
            clientValues.put("getGameState",state);
            GameStateChanged event = new GameStateChanged();
            event.setGameState(state);
            callback("onGameStateChanged",GameStateChanged.class,event);
        }

        void configChanged() throws Exception
        {
            ConfigChanged change = new ConfigChanged();
            change.setGroup("deadman");
            change.setKey("eventLocationOverrides");
            callback("onConfigChanged",ConfigChanged.class,change);
        }

        void field(String name,Object value) throws Exception
        {
            Field field = DeadmanPlugin.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(plugin,value);
        }

        void callback(String name,Class<?> type,Object argument) throws Exception
        {
            Method method = DeadmanPlugin.class.getDeclaredMethod(name,type);
            method.setAccessible(true);
            method.invoke(plugin,argument);
        }

        @Override public void close() throws Exception
        {
            plugin.shutDown();
            thread.flush();
            SwingUtilities.invokeAndWait(panel::stopTimer);
        }
    }

    private static final class QueuedThread extends ClientThread
    {
        final List<Runnable> queue = new ArrayList<>();
        @Override public void invoke(Runnable runnable) { queue.add(runnable); }
        void flush()
        {
            List<Runnable> pending = new ArrayList<>(queue);
            queue.clear();
            pending.forEach(Runnable::run);
        }
    }

    private static final class RecordingPoints extends WorldMapPointManager
    {
        final List<WorldMapPoint> registered = new ArrayList<>();
        @Override public void add(WorldMapPoint point) { super.add(point); registered.add(point); }
        @Override public void remove(WorldMapPoint point) { super.remove(point); registered.remove(point); }
    }
}
