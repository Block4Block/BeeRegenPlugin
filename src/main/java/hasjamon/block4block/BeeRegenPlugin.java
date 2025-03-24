package hasjamon.block4block;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.block.Beehive;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.java.JavaPlugin;

public class BeeRegenPlugin extends JavaPlugin {

    // Original cooldown time in milliseconds (configured in config.yml, default is 60 seconds)
    private long cooldownMillis;
    // New configuration options
    private int maxBeesPerInterval;
    private long intervalMillis;  // interval in milliseconds
    private int maxBeesInHive;

    // Configurable check interval in ticks for the runTaskTimer
    private long checkIntervalTicks;

    // Map to keep track of each block's last spawn timestamp. Key: world:x:y:z
    private final Map<String, Long> blockCooldowns = new HashMap<>();
    // Map to track the number of bees spawned per beehive in the current interval
    private final Map<String, Integer> beesSpawnedCount = new HashMap<>();
    // Map to track when the current interval started for each beehive
    private final Map<String, Long> spawnIntervalReset = new HashMap<>();

    @Override
    public void onEnable() {
        // Save the default config if one is not present
        saveDefaultConfig();

        // Read configuration values:
        cooldownMillis = getConfig().getLong("cooldown", 60) * 1000;
        maxBeesPerInterval = getConfig().getInt("maxBeesPerInterval", 3);
        intervalMillis = getConfig().getLong("interval", 3600) * 1000;
        maxBeesInHive = getConfig().getInt("maxBeesInHive", 0);

        // Read the check interval in seconds from config and convert to ticks (20 ticks per second)
        long checkIntervalSeconds = getConfig().getLong("checkIntervalSeconds", 10);
        checkIntervalTicks = checkIntervalSeconds * 20;

        // Start periodic task to check all Bee Nests and Beehives using the configurable interval
        new BukkitRunnable() {
            @Override
            public void run() {
                checkBeehives();
            }
        }.runTaskTimer(this, 0, checkIntervalTicks);

        getLogger().info("BeeRegenPlugin enabled!");
    }

    private void checkBeehives() {
        // Iterate over all loaded chunks in the primary world
        for (var chunk : getServer().getWorlds().get(0).getLoadedChunks()) {
            for (var block : chunk.getTileEntities()) {
                if (block instanceof Beehive) {
                    Beehive beehive = (Beehive) block;
                    String key = block.getLocation().toString();
                    long currentTime = System.currentTimeMillis();

                    // Reset the spawn count if the interval has passed
                    if (!spawnIntervalReset.containsKey(key) || (currentTime - spawnIntervalReset.get(key)) >= intervalMillis) {
                        spawnIntervalReset.put(key, currentTime);
                        beesSpawnedCount.put(key, 0);
                    }

                    // Check if the beehive is below the configured bee threshold.
                    // For maxBeesInHive = 0, this will only spawn if there are no bees.
                    if (beehive.getEntityCount() <= maxBeesInHive) {
                        int count = beesSpawnedCount.getOrDefault(key, 0);
                        // If we haven't reached the maximum bees spawned per interval
                        if (count < maxBeesPerInterval) {
                            // Check if the cooldown has passed for this beehive
                            if (!blockCooldowns.containsKey(key) || (currentTime - blockCooldowns.get(key)) >= cooldownMillis) {
                                spawnBee(block.getLocation());
                                blockCooldowns.put(key, currentTime);
                                beesSpawnedCount.put(key, count + 1);
                            }
                        }
                    }
                }
            }
        }
    }

    private void spawnBee(Location location) {
        // Create a bee slightly above the hive/nest to avoid suffocation
        Location spawnLocation = location.add(0.5, 1, 0.5);
        Bee bee = (Bee) location.getWorld().spawnEntity(spawnLocation, EntityType.BEE);

        // Make the bee persistent so it won't disappear and remains close to the beehive
        bee.setPersistent(true);
        // Bees naturally return to their hive; no need to set a home location manually.
    }

    // Command to reload the config (e.g., /beespawn reload)
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("beespawn")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                // Reload all configuration values
                cooldownMillis = getConfig().getLong("cooldown", 60) * 1000;
                maxBeesPerInterval = getConfig().getInt("maxBeesPerInterval", 3);
                intervalMillis = getConfig().getLong("interval", 3600) * 1000;
                maxBeesInHive = getConfig().getInt("maxBeesInHive", 0);
                long checkIntervalSeconds = getConfig().getLong("checkIntervalSeconds", 10);
                checkIntervalTicks = checkIntervalSeconds * 20;

                sender.sendMessage("BeeRegenPlugin config reloaded!");
                return true;
            }
            sender.sendMessage("Usage: /beespawn reload");
            return true;
        }
        return false;
    }
}