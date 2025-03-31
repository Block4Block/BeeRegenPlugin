package hasjamon.block4block;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Beehive;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class BeeRegenPlugin extends JavaPlugin {

    // Beehive-related config options (all in ticks)
    private long cooldownTicks;
    private int maxBeesPerInterval;
    private long spawnIntervalTicks;
    private int maxBeesInHive;
    private long checkIntervalTicks;

    // Despawn time in ticks
    private long despawnTicks;
    private NamespacedKey despawnKey;

    // Global tick counter
    private long tickCounter = 0;

    // Maps for tracking hive and spawn information
    private final Map<String, Long> blockCooldowns = new HashMap<>();
    private final Map<String, Integer> beesSpawnedCount = new HashMap<>();
    private final Map<String, Long> spawnIntervalReset = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Load all configuration values in ticks
        checkIntervalTicks = config.getLong("checkIntervalTicks", 12000);
        cooldownTicks = config.getLong("cooldownTicks", 12000);
        maxBeesPerInterval = config.getInt("maxBeesPerInterval", 3);
        spawnIntervalTicks = config.getLong("spawnIntervalTicks", 72000);
        maxBeesInHive = config.getInt("maxBeesInHive", 0);
        despawnTicks = config.getLong("despawnTicks", 6000);

        // Key to store and retrieve despawn tick info from bees
        despawnKey = new NamespacedKey(this, "despawnTick");

        // Start a task to increment the global tick counter
        new BukkitRunnable() {
            @Override
            public void run() {
                tickCounter++;
            }
        }.runTaskTimer(this, 1L, 1L); // Start after 1 tick, run every tick

        // Start a task to check beehives for spawning bees
        new BukkitRunnable() {
            @Override
            public void run() {
                checkBeehives();
            }
        }.runTaskTimer(this, 0L, checkIntervalTicks);

        // Start a task to check for bee despawn conditions
        new BukkitRunnable() {
            @Override
            public void run() {
                checkForBeeDespawn();
            }
        }.runTaskTimer(this, 0L, checkIntervalTicks);

        getLogger().info("BeeRegenPlugin enabled! Using tick-based timing.");
    }

    private void checkBeehives() {
        for (var chunk : getServer().getWorlds().get(0).getLoadedChunks()) {
            for (var block : chunk.getTileEntities()) {
                if (block instanceof Beehive) {
                    Beehive beehive = (Beehive) block;
                    String key = block.getLocation().toString();

                    // Reset the spawn count if the interval has passed
                    if (!spawnIntervalReset.containsKey(key) || (tickCounter - spawnIntervalReset.get(key)) >= spawnIntervalTicks) {
                        spawnIntervalReset.put(key, tickCounter);
                        beesSpawnedCount.put(key, 0);
                    }

                    // Check if the beehive has fewer than the allowed number of bees
                    if (beehive.getEntityCount() <= maxBeesInHive) {
                        int count = beesSpawnedCount.getOrDefault(key, 0);
                        if (count < maxBeesPerInterval) {
                            // Check if the cooldown has passed for this beehive
                            if (!blockCooldowns.containsKey(key) || (tickCounter - blockCooldowns.get(key)) >= cooldownTicks) {
                                spawnBee(block.getLocation());
                                blockCooldowns.put(key, tickCounter);
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
        bee.setPersistent(true);

        // Tag the bee with the current tick count for future despawn checks
        bee.getPersistentDataContainer().set(despawnKey, PersistentDataType.LONG, tickCounter);
    }

    private void checkForBeeDespawn() {
        // Iterate through all worlds and check all bees
        for (var world : getServer().getWorlds()) {
            for (Bee bee : world.getEntitiesByClass(Bee.class)) {
                if (despawnTicks > 0) { // Only apply despawn logic if the value is > 0
                    // Get the bee's spawn tick from the PersistentDataContainer
                    if (bee.getPersistentDataContainer().has(despawnKey, PersistentDataType.LONG)) {
                        long spawnTick = bee.getPersistentDataContainer().get(despawnKey, PersistentDataType.LONG);
                        // Despawn if the lifespan has been exceeded
                        if ((tickCounter - spawnTick) >= despawnTicks) {
                            bee.remove();
                            getLogger().info("Despawned bee at " + bee.getLocation() + " after " + (tickCounter - spawnTick) + " ticks.");
                        }
                    } else {
                        // If the bee was not tagged properly, tag it now
                        bee.getPersistentDataContainer().set(despawnKey, PersistentDataType.LONG, tickCounter);
                    }
                }
            }
        }
    }
}
