package com.deadman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("deadman")
public interface DeadmanConfig extends Config
{
	@ConfigItem(
		keyName = "geAutoOpenPanel",
		name = "Auto lookup items when viewing in ge",
		description = "Automatically look up the item being viewed in Grand Exchange if the panel is open"
	)
	default boolean geAutoOpenPanel()
	{
		return true;
	}
}
