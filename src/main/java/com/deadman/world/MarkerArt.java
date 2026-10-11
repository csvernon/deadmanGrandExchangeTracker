package com.deadman.world;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

public final class MarkerArt
{
    private static final BufferedImage[] ICONS = new BufferedImage[2];
    private static final BufferedImage[][] ARROWS = new BufferedImage[2][16];

    static
    {
        for (EventNotice.Type type : EventNotice.Type.values())
        {
            ICONS[type.ordinal()] = drawIcon(type);
            for (int direction = 0; direction < 16; direction++)
            {
                ARROWS[type.ordinal()][direction] = drawArrow(type, direction * Math.PI / 8);
            }
        }
    }

    private MarkerArt() {}

    public static Color color(EventNotice.Type type)
    {
        return type == EventNotice.Type.CHEST ? new Color(255, 209, 90) : new Color(255, 112, 125);
    }

    public static BufferedImage icon(EventNotice.Type type)
    {
        return ICONS[type.ordinal()];
    }

    public static BufferedImage arrow(EventNotice.Type type, double angle)
    {
        int direction = Math.floorMod((int) Math.round(angle * 8 / Math.PI), 16);
        return ARROWS[type.ordinal()][direction];
    }

    private static BufferedImage drawIcon(EventNotice.Type type)
    {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(20, 22, 24, 230));
            g.fillOval(0, 0, 19, 19);
            g.setColor(color(type));
            g.setStroke(new BasicStroke(2));
            if (type == EventNotice.Type.CHEST)
            {
                g.drawRect(4, 7, 11, 8);
                g.drawArc(4, 3, 11, 10, 0, 180);
                g.drawLine(4, 10, 15, 10);
                g.fillRect(9, 9, 2, 4);
            }
            else
            {
                Path2D rift = new Path2D.Double();
                rift.moveTo(11, 3);
                rift.lineTo(6, 10);
                rift.lineTo(10, 10);
                rift.lineTo(8, 17);
                rift.lineTo(14, 8);
                rift.lineTo(10, 8);
                rift.closePath();
                g.fill(rift);
                g.drawLine(4, 5, 5, 7);
                g.drawLine(15, 13, 16, 15);
            }
        }
        finally { g.dispose(); }
        return image;
    }

    private static BufferedImage drawArrow(EventNotice.Type type, double angle)
    {
        BufferedImage image = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try
        {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(12, 12);
            g.rotate(angle);
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(9, 0);
            arrow.lineTo(-6, -6);
            arrow.lineTo(-3, 0);
            arrow.lineTo(-6, 6);
            arrow.closePath();
            g.setColor(new Color(20, 22, 24));
            g.setStroke(new BasicStroke(3));
            g.draw(arrow);
            g.setColor(color(type));
            g.fill(arrow);
        }
        finally { g.dispose(); }
        return image;
    }
}
