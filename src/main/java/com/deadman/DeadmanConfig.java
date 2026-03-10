package com.deadman;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("deadman")
public interface DeadmanConfig extends Config
{
	@ConfigItem(
		keyName = "dataUrl",
		name = "DEADMAN GE TRACKER URL",
		description = "URL where GE trade data is viewable"
	)
	default String dataUrl()
	{
		return "https://dmmhs2.onrender.com/ge";
	}

	@ConfigItem(
		keyName = "geAutoOpenPanel",
		name = "Auto-open panel on GE lookup",
		description = "Automatically switch to the plugin panel when looking up an item in the Grand Exchange"
	)
	default boolean geAutoOpenPanel()
	{
		return true;
	}
}
