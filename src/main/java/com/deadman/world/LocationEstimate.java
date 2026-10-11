package com.deadman.world;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

@Value
public class LocationEstimate
{
    public enum Precision { MAP_LABEL, REGION, DIRECTION, CUSTOM, UNKNOWN }

    WorldPoint point;
    WorldPoint entrance;
    Precision precision;

    public WorldPoint navigationFrom(WorldPoint player)
    {
        if (point == null || player == null)
        {
            return null;
        }
        // Underground map coordinates are not adjacent to their surface entrances.
        boolean below = point.getY() >= 6400;
        boolean playerBelow = player.getY() >= 6400;
        if (below || playerBelow)
        {
            return below && !playerBelow && entrance != null && entrance.getY() < 6400
                && entrance.getPlane() == player.getPlane() ? entrance : null;
        }
        if (point.getPlane() != player.getPlane())
        {
            return null;
        }
        return point;
    }

    public String note()
    {
        switch (precision)
        {
            case MAP_LABEL: return "Map landmark";
            case REGION: return "Region estimate";
            case DIRECTION: return "Directional estimate";
            case CUSTOM: return "Custom location";
            default: return "Unmapped";
        }
    }
}
