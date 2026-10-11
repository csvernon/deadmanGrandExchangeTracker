package com.deadman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("deadman")
public interface DeadmanConfig extends Config
{
	@ConfigSection(name = "World event locations", description = "Breach and Deadman's Chest locations on world 345", position = 1)
	String worldEventsSection = "worldEvents";

	@ConfigItem(keyName = "chestMapMarkers", name = "Chest map markers",
		description = "Show announced Deadman's Chests on the world map and minimap", section = worldEventsSection, position = 0)
	default boolean chestMapMarkers()
	{
		return true;
	}

	@ConfigItem(keyName = "breachMapMarkers", name = "Breach map markers",
		description = "Show announced breaches on the world map and minimap", section = worldEventsSection, position = 1)
	default boolean breachMapMarkers()
	{
		return true;
	}

	@ConfigItem(keyName = "eventMinimapArrows", name = "Minimap arrows",
		description = "Show location icons and directional arrows on the minimap", section = worldEventsSection, position = 2)
	default boolean eventMinimapArrows()
	{
		return true;
	}

	@ConfigItem(keyName = "eventLocationOverrides", name = "Location overrides",
		description = "One per line: Place | x,y,plane | optional entrance x,y,plane. Overrides use exact coordinates.",
		section = worldEventsSection, position = 3)
	default String eventLocationOverrides()
	{
		return "";
	}

	@ConfigItem(
		keyName = "geAutoOpenPanel",
		name = "Auto lookup items",
		description = "Automatically look up the item being viewed in Grand Exchange if the panel is open"
	)
	default boolean geAutoOpenPanel()
	{
		return true;
	}
}
