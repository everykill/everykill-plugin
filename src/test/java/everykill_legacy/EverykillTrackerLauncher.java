package everykill_legacy;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Run this class to launch RuneLite with the tracker plugin already loaded.
 *
 * In IntelliJ: right-click this file and choose "Run EverykillTrackerLauncher.main()".
 * The client that opens is a normal RuneLite client with one extra plugin.
 */
public class EverykillTrackerLauncher
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(EverykillTrackerPlugin.class);
		RuneLite.main(args);
	}
}
