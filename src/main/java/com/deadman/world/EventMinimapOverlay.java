package com.deadman.world;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

public final class EventMinimapOverlay extends Overlay
{
    private static final int RADIUS = 56;
    private final Client client;
    private final Supplier<List<EventRecord>> events;

    public EventMinimapOverlay(Client client, Supplier<List<EventRecord>> events)
    {
        this.client = client;
        this.events = events;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        Player player = client.getLocalPlayer();
        WorldView world = client.getTopLevelWorldView();
        if (client.getGameState() != GameState.LOGGED_IN || player == null || world == null || world.isInstance())
        {
            return null;
        }
        LocalPoint local = player.getLocalLocation();
        if (local == null || local.getWorldView() != WorldView.TOPLEVEL)
        {
            return null;
        }
        Point center = Perspective.localToMinimap(client, local);
        WorldPoint current = player.getWorldLocation();
        double scale = client.getMinimapZoom();
        if (center == null || current == null || !Double.isFinite(scale) || scale <= 0)
        {
            return null;
        }
        double yaw = (client.getCameraYawTarget() & 0x3fff) * (Math.PI * 2 / 16384);
        Graphics2D g = (Graphics2D) graphics.create();
        try
        {
            for (EventRecord event : events.get())
            {
                WorldPoint target = event.getLocation().navigationFrom(current);
                if (target == null)
                {
                    continue;
                }
                int dx = target.getX() - current.getX();
                int dy = target.getY() - current.getY();
                double[] screen = project(dx, dy, yaw, scale, RADIUS);
                boolean distant = Math.hypot(dx, dy) * scale > RADIUS;
                BufferedImage icon = distant ? MarkerArt.arrow(event.getType(), Math.atan2(screen[1], screen[0])) : MarkerArt.icon(event.getType());
                int x = center.getX() + (int) Math.round(screen[0]) - icon.getWidth() / 2;
                int y = center.getY() + (int) Math.round(screen[1]) - icon.getHeight() / 2;
                g.drawImage(icon, x, y, null);
            }
        }
        finally { g.dispose(); }
        return null;
    }

    static double[] project(double east, double north, double yaw, double scale, double radius)
    {
        double distance = Math.hypot(east, north);
        double bearing = Math.atan2(-north, east) + yaw;
        double pixels = Math.min(radius, distance * scale);
        return new double[]{Math.cos(bearing) * pixels, Math.sin(bearing) * pixels};
    }
}
