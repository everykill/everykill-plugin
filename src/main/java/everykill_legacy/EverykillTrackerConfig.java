package everykill_legacy;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("everykilltracker")
public interface EverykillTrackerConfig extends Config
{
	@ConfigSection(
			name = "Output",
			description = "Where snapshots get written",
			position = 0
	)
	String outputSection = "output";

	@ConfigSection(
			name = "Tracking",
			description = "What gets tracked",
			position = 1
	)
	String trackingSection = "tracking";

	@ConfigSection(
			name = "Gist upload",
			description = "Optional: push snapshots to a GitHub gist",
			position = 2,
			closedByDefault = true
	)
	String gistSection = "gist";

	@ConfigItem(
			keyName = "autoWriteMinutes",
			name = "Auto-write every (min)",
			description = "How often to write a snapshot automatically. 0 disables auto-writing.",
			position = 1,
			section = outputSection
	)
	default int autoWriteMinutes()
	{
		return 5;
	}

	@ConfigItem(
			keyName = "writeOnLogout",
			name = "Write on logout",
			description = "Write a final snapshot when you log out",
			position = 2,
			section = outputSection
	)
	default boolean writeOnLogout()
	{
		return true;
	}

	@ConfigItem(
			keyName = "trackLocation",
			name = "Track location",
			description = "Include world coordinates, plane and region in snapshots",
			position = 1,
			section = trackingSection
	)
	default boolean trackLocation()
	{
		return true;
	}

	@ConfigItem(
			keyName = "trackLoot",
			name = "Track kills and loot",
			description = "Count kills per monster and log every drop received",
			position = 2,
			section = trackingSection
	)
	default boolean trackLoot()
	{
		return true;
	}

	@ConfigItem(
			keyName = "trackGear",
			name = "Track equipment and inventory",
			description = "Include what you're wearing and carrying in snapshots",
			position = 3,
			section = trackingSection
	)
	default boolean trackGear()
	{
		return true;
	}

	@ConfigItem(
			keyName = "sessionIdleMinutes",
			name = "Session idle timeout (min)",
			description = "Reset the XP-rate session after this long without XP. 0 never resets.",
			position = 4,
			section = trackingSection
	)
	default int sessionIdleMinutes()
	{
		return 15;
	}

	@ConfigItem(
			keyName = "gistEnabled",
			name = "Upload to gist",
			description = "Push each snapshot to a GitHub gist so it can be read remotely",
			position = 1,
			section = gistSection
	)
	default boolean gistEnabled()
	{
		return false;
	}

	@ConfigItem(
			keyName = "gistId",
			name = "Gist ID",
			description = "The ID of an existing gist to update (the hex string in its URL)",
			position = 2,
			section = gistSection
	)
	default String gistId()
	{
		return "";
	}

	@ConfigItem(
			keyName = "gistToken",
			name = "GitHub token",
			description = "A GitHub personal access token with gist scope. Stored locally by RuneLite.",
			secret = true,
			position = 3,
			section = gistSection
	)
	default String gistToken()
	{
		return "";
	}
}
