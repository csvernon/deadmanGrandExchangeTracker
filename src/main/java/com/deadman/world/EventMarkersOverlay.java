package com.deadman.world;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;

public final class EventMarkersOverlay extends Overlay
{
    private final Client client;
    private final WorldMapPointManager manager;
    private final List<EventPoint> registered = new ArrayList<>();

    public EventMarkersOverlay(Client client, WorldMapPointManager manager)
    {
        this.client = client;
        this.manager = manager;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.MANUAL);
        drawAfterInterface(InterfaceID.WORLDMAP);
    }

    public void show(List<EventRecord> events)
    {
        clear();
        for (EventRecord event : events)
        {
            if (event.getLocation().getPoint() != null)
            {
                EventPoint point = new EventPoint(event);
                registered.add(point);
                manager.add(point);
            }
        }
    }

    public void clear()
    {
        for (EventPoint point : registered)
        {
            manager.remove(point);
        }
        registered.clear();
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            return null;
        }
        WorldMap map = client.getWorldMap();
        Point center = map == null ? null : map.getWorldMapPosition();
        if (center != null)
        {
            for (EventPoint point : registered)
            {
                if (point.isCurrentlyEdgeSnapped())
                {
                    point.setImage(MarkerArt.arrow(point.type,
                        Math.atan2(center.getY() - point.destination.getY(), point.destination.getX() - center.getX())));
                }
            }
        }
        return null;
    }

    private static final class EventPoint extends WorldMapPoint
    {
        private final EventNotice.Type type;
        private final WorldPoint destination;

        private EventPoint(EventRecord event)
        {
            super(event.getLocation().getPoint(), MarkerArt.icon(event.getType()));
            type = event.getType();
            destination = event.getLocation().getPoint();
            setTarget(destination);
            setJumpOnClick(true);
            setSnapToEdge(true);
            setName((type == EventNotice.Type.CHEST ? "Deadman's Chest: " : "Breach: ") + event.getPlace());
            setTooltip(getName() + " - " + event.getLocation().note());
        }

        @Override public void onEdgeSnap() { setImage(MarkerArt.arrow(type, 0)); }
        @Override public void onEdgeUnsnap() { setImage(MarkerArt.icon(type)); }
    }
}
