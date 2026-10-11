package com.deadman.world;

import com.deadman.RuneLiteStubs;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.CameraFocusableEntity;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import org.junit.Test;
import static org.junit.Assert.*;

public class EventRenderingTest
{
    @Test
    public void minimapProjectionRotatesAndClampsToTheEdge()
    {
        double[] north = EventMinimapOverlay.project(0, 10, 0, 4, 56);
        assertEquals(0, north[0], 0.001);
        assertEquals(-40, north[1], 0.001);
        double[] eastAtQuarterTurn = EventMinimapOverlay.project(10, 0, Math.PI / 2, 4, 56);
        assertEquals(0, eastAtQuarterTurn[0], 0.001);
        assertEquals(40, eastAtQuarterTurn[1], 0.001);
        double[] distant = EventMinimapOverlay.project(1000, 1000, 0, 4, 56);
        assertEquals(56, Math.hypot(distant[0], distant[1]), 0.001);
    }

    @Test
    public void iconsAndArrowsHaveVisiblePixelsAndDistinctTypes()
    {
        BufferedImage chest = MarkerArt.icon(EventNotice.Type.CHEST);
        BufferedImage breach = MarkerArt.icon(EventNotice.Type.BREACH);
        assertTrue(opaquePixels(chest) > 40);
        assertTrue(opaquePixels(breach) > 40);
        assertNotEquals(chest.getRGB(8, 10), breach.getRGB(8, 10));
        assertTrue(opaquePixels(MarkerArt.arrow(EventNotice.Type.CHEST, Math.PI)) > 30);
    }

    @Test
    public void mapRegistrationOwnsOnlyItsPointsAndRetainsNavigationTarget()
    {
        RecordingPoints points = new RecordingPoints();
        WorldMapPoint unrelated = new WorldMapPoint(new WorldPoint(20, 20, 0), MarkerArt.icon(EventNotice.Type.CHEST));
        points.add(unrelated);
        EventMarkersOverlay overlay = new EventMarkersOverlay(null, points);
        EventRecord record = new EventRecord(EventNotice.Type.CHEST, "Test", null, Instant.EPOCH,
            Instant.EPOCH.plusSeconds(600), new LocationEstimate(new WorldPoint(3001, 3001, 0), null, LocationEstimate.Precision.MAP_LABEL));
        overlay.show(Collections.singletonList(record));
        assertEquals(2, points.registered.size());
        WorldMapPoint marker = points.registered.get(1);
        assertEquals(new WorldPoint(3001, 3001, 0), marker.getTarget());
        assertTrue(marker.isJumpOnClick());
        assertTrue(marker.isSnapToEdge());
        assertTrue(marker.getTooltip().contains("Map landmark"));
        overlay.show(Collections.emptyList());
        assertEquals(Collections.singletonList(unrelated), points.registered);
    }

    @Test
    public void navigationDoesNotPointAcrossUnrelatedUndergroundMaps()
    {
        LocationEstimate underground = new LocationEstimate(new WorldPoint(3000, 9500, 0), null, LocationEstimate.Precision.CUSTOM);
        assertNull(underground.navigationFrom(new WorldPoint(3000, 3000, 0)));
        assertNull(underground.navigationFrom(new WorldPoint(4000, 9500, 0)));
        assertNull(underground.navigationFrom(new WorldPoint(3000, 9500, 1)));
        assertNull(underground.navigationFrom(new WorldPoint(3001, 9501, 0)));
    }

    @Test
    public void nearbyDisconnectedBasementsHaveNoNavigationArrow()
    {
        LocationEstimate manor = new LocationEstimate(new WorldPoint(3073, 9764, 0), null, LocationEstimate.Precision.MAP_LABEL);
        assertNull(manor.navigationFrom(new WorldPoint(3105, 9656, 0)));
    }

    @Test
    public void minimapRenderShowsNorthMarkerInActualCanvasPixels()
    {
        BufferedImage image = renderMinimap(WorldView.TOPLEVEL);
        assertTrue("North marker should be above the player", (image.getRGB(80, 40) >>> 24) > 0);
        assertEquals("No marker east of player", 0, image.getRGB(120,80));
    }

    @Test
    public void childWorldViewIsNotRenderedUsingMainWorldCoordinates()
    {
        assertEquals(0, opaquePixels(renderMinimap(42)));
    }

    @Test
    public void mapRenderTurnsEdgeMarkerTowardItsDestination()
    {
        RecordingPoints points = new RecordingPoints();
        WorldMap map = RuneLiteStubs.api(WorldMap.class, Map.of("getWorldMapPosition", new Point(3001,2800)));
        Client client = RuneLiteStubs.api(Client.class, Map.of("getGameState", GameState.LOGGED_IN, "getWorldMap", map));
        EventMarkersOverlay overlay = new EventMarkersOverlay(client, points);
        EventRecord record = record(new WorldPoint(3001,3001,0));
        overlay.show(Collections.singletonList(record));
        WorldMapPoint marker = points.registered.get(0);
        marker.setCurrentlyEdgeSnapped(true);
        marker.onEdgeSnap();
        BufferedImage canvas = new BufferedImage(160,160,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        try { overlay.render(graphics); }
        finally { graphics.dispose(); overlay.clear(); }
        assertTrue((marker.getImage().getRGB(12,4) >>> 24) > 0);
        assertEquals(0, marker.getImage().getRGB(20,12));
    }

    private static BufferedImage renderMinimap(int playerView)
    {
        WorldView top = RuneLiteStubs.api(WorldView.class, Map.of("getId", WorldView.TOPLEVEL, "isInstance", false));
        LocalPoint local = new LocalPoint(128,128,playerView);
        Player player = RuneLiteStubs.api(Player.class, Map.of("getLocalLocation", local,
            "getWorldLocation", new WorldPoint(3000,3000,0)));
        CameraFocusableEntity focus = RuneLiteStubs.api(CameraFocusableEntity.class,
            Map.of("getWorldView", top, "getCameraFocus", new LocalPoint(128,128,WorldView.TOPLEVEL)));
        Widget minimap = RuneLiteStubs.api(Widget.class, Map.of("isHidden", false, "getWidth",160,
            "getHeight",160,"getCanvasLocation",new Point(0,0)));
        Client client = RuneLiteStubs.api(Client.class, Map.of("getLocalPlayer", player, "getGameState",GameState.LOGGED_IN,
            "getTopLevelWorldView",top,"getCameraFocusEntity",focus,"isResized",false,"getWidget",minimap,
            "getMinimapZoom",4d,"getCameraYawTarget",0));
        EventMinimapOverlay overlay = new EventMinimapOverlay(client,
            () -> Collections.singletonList(record(new WorldPoint(3000,3010,0))));
        BufferedImage image = new BufferedImage(160,160,BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { overlay.render(graphics); }
        finally { graphics.dispose(); }
        return image;
    }

    private static EventRecord record(WorldPoint point)
    {
        return new EventRecord(EventNotice.Type.CHEST,"Test",null,Instant.EPOCH,Instant.EPOCH.plusSeconds(600),
            new LocationEstimate(point,null,LocationEstimate.Precision.MAP_LABEL));
    }

    private static int opaquePixels(BufferedImage image)
    {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++)
        {
            for (int x = 0; x < image.getWidth(); x++)
            {
                if ((image.getRGB(x, y) >>> 24) != 0) { count++; }
            }
        }
        return count;
    }

    private static final class RecordingPoints extends WorldMapPointManager
    {
        final List<WorldMapPoint> registered = new ArrayList<>();
        @Override public void add(WorldMapPoint point) { super.add(point); registered.add(point); }
        @Override public void remove(WorldMapPoint point) { super.remove(point); registered.remove(point); }
    }
}
