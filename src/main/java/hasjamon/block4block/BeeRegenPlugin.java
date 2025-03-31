package hasjamon.block4block;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Beehive;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Bee;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class BeeRegenPlugin extends JavaPlugin {

    // Beehive spawning configuration (all in ticks)
    private long cooldownTicks;
    private int maxBeesPerInterval;
    private long spawnIntervalTicks;
    private int maxBeesInHive;
    private long checkIntervalTicks;

    // Bee despawn configuration (in ticks)
    private long despawnTicks;

    // Lost bee despawn configuration and thresholds (ticks and blocks)
    private boolean lostDespawnEnabled;
    private long lostDespawnTicks;
    private int maxDistanceFromHome;
    private boolean lostDespawnCheckNearbyHives;
    private int lostDespawnNearbyRange;

    // Persistent keys for bee tracking
    private NamespacedKey despawnKey;   // Stores the tick at which the bee was spawned
    private NamespacedKey homeKey;      // Stores the bee's "home" as a string "x,y,z"
    private NamespacedKey lostTickKey;  // Stores the tick at which the bee became "lost"

    // Global tick counter (incremented every tick)
    private long tickCounter = 0;

    // Maps for tracking beehive spawn information
    private final Map<String, Long> blockCooldowns = new HashMap<>();
    private final Map<String, Integer> beesSpawnedCount = new HashMap<>();
    private final Map<String, Long> spawnIntervalReset = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        // Load beehive spawning configuration (ticks)
        checkIntervalTicks = config.getLong("checkIntervalTicks", 12000);
        cooldownTicks = config.getLong("cooldownTicks", 12000);
        maxBeesPerInterval = config.getInt("maxBeesPerInterval", 3);
        spawnIntervalTicks = config.getLong("spawnIntervalTicks", 72000);
        maxBeesInHive = config.getInt("maxBeesInHive", 0);

        // Load despawn configuration (ticks)
        despawnTicks = config.getLong("despawnTicks", 6000);

        // Load lost bee despawn configuration
        lostDespawnEnabled = config.getBoolean("lostDespawnEnabled", true);
        lostDespawnTicks = config.getLong("lostDespawnTicks", 6000);
        maxDistanceFromHome = config.getInt("maxDistanceFromHome", 20);
        lostDespawnCheckNearbyHives = config.getBoolean("lostDespawnCheckNearbyHives", true);
        lostDespawnNearbyRange = config.getInt("lostDespawnNearbyRange", 5);

        // Initialize persistent keys
        despawnKey = new NamespacedKey(this, "despawnTick");
        homeKey = new NamespacedKey(this, "beeHome");
        lostTickKey = new NamespacedKey(this, "lostTick");

        // Increment the global tick counter every tick (must be synchronous)
        new BukkitRunnable() {
            @Override
            public void run() {
                tickCounter++;
            }
        }.runTaskTimer(this, 1L, 1L);

        // Check beehives for spawning bees across all worlds
        new BukkitRunnable() {
            @Override
            public void run() {
                checkBeehives();
            }
        }.runTaskTimer(this, 0L, checkIntervalTicks);

        // Check all bees for despawn conditions (both normal lifespan and lost conditions)
        new BukkitRunnable() {
            @Override
            public void run() {
                checkForBeeDespawn();
            }
        }.runTaskTimer(this, 0L, checkIntervalTicks);

        getLogger().info("BeeRegenPlugin enabled! (Tick-based timing with lost bee detection)");
    }

    private void checkBeehives() {
        for (var world : getServer().getWorlds()) {
            for (var chunk : world.getLoadedChunks()) {
                for (var block : chunk.getTileEntities()) {
                    if (block instanceof Beehive) {
                        Beehive beehive = (Beehive) block;
                        String key = block.getLocation().toString();

                        if (!spawnIntervalReset.containsKey(key) || (tickCounter - spawnIntervalReset.get(key)) >= spawnIntervalTicks) {
                            spawnIntervalReset.put(key, tickCounter);
                            beesSpawnedCount.put(key, 0);
                        }

                        if (beehive.getEntityCount() <= maxBeesInHive) {
                            int count = beesSpawnedCount.getOrDefault(key, 0);
                            if (count < maxBeesPerInterval) {
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
    }

    private void spawnBee(Location location) {
        Location spawnLocation = location.add(0.5, 1, 0.5);
        Bee bee = (Bee) spawnLocation.getWorld().spawnEntity(spawnLocation, EntityType.BEE);
        bee.setPersistent(true);

        // Tag the bee with its spawn tick and record its "home" as "x,y,z"
        bee.getPersistentDataContainer().set(despawnKey, PersistentDataType.LONG, tickCounter);
        String homeString = spawnLocation.getBlockX() + "," + spawnLocation.getBlockY() + "," + spawnLocation.getBlockZ();
        bee.getPersistentDataContainer().set(homeKey, PersistentDataType.STRING, homeString);
        bee.getPersistentDataContainer().remove(lostTickKey);
    }

    private void checkForBeeDespawn() {
        for (var world : getServer().getWorlds()) {
            for (Bee bee : world.getEntitiesByClass(Bee.class)) {
                // Normal lifespan-based despawn
                if (despawnTicks > 0 && bee.getPersistentDataContainer().has(despawnKey, PersistentDataType.LONG)) {
                    long spawnTick = bee.getPersistentDataContainer().get(despawnKey, PersistentDataType.LONG);
                    if ((tickCounter - spawnTick) >= despawnTicks) {
                        bee.remove();
                        getLogger().info("Despawned bee at " + bee.getLocation()
                                + " due to normal lifespan (" + (tickCounter - spawnTick) + " ticks).");
                        continue;
                    }
                }

                // Lost bee despawn check
                if (lostDespawnEnabled && bee.getPersistentDataContainer().has(homeKey, PersistentDataType.STRING)) {
                    String homeString = bee.getPersistentDataContainer().get(homeKey, PersistentDataType.STRING);
                    String[] parts = homeString.split(",");
                    if (parts.length == 3) {
                        try {
                            int homeX = Integer.parseInt(parts[0]);
                            int homeY = Integer.parseInt(parts[1]);
                            int homeZ = Integer.parseInt(parts[2]);
                            Location home = bee.getWorld().getBlockAt(homeX, homeY, homeZ).getLocation();
                            Location current = bee.getLocation();
                            double distanceSquared = current.distanceSquared(home);
                            int maxDistanceSquared = maxDistanceFromHome * maxDistanceFromHome;

                            // If the bee is beyond the maximum allowed distance:
                            if (distanceSquared > maxDistanceSquared) {
                                // If configured, check nearby blocks for a hive or nest.
                                if (lostDespawnCheckNearbyHives && isHiveNearby(current)) {
                                    // Hive detected nearby: clear lost timer and skip lost despawn.
                                    bee.getPersistentDataContainer().remove(lostTickKey);
                                    continue;
                                }

                                // No nearby hive found; proceed with lost despawn logic.
                                if (!bee.getPersistentDataContainer().has(lostTickKey, PersistentDataType.LONG)) {
                                    bee.getPersistentDataContainer().set(lostTickKey, PersistentDataType.LONG, tickCounter);
                                } else {
                                    long lostTick = bee.getPersistentDataContainer().get(lostTickKey, PersistentDataType.LONG);
                                    if ((tickCounter - lostTick) >= lostDespawnTicks) {
                                        bee.remove();
                                        getLogger().info("Despawned bee at " + bee.getLocation()
                                                + " due to being lost (" + Math.sqrt(distanceSquared) + " blocks from home).");
                                    }
                                }
                            } else {
                                // Bee is in range; clear lost timer if it exists.
                                bee.getPersistentDataContainer().remove(lostTickKey);
                            }
                        } catch (NumberFormatException ex) {
                            getLogger().warning("Invalid bee home coordinates: " + homeString);
                        }
                    }
                }
            }
        }
    }

    // Checks the blocks immediately surrounding the given location for a beehive or bee nest.
    private boolean isHiveNearby(Location current) {
        // We'll check within a cube of side length (2 * lostDespawnNearbyRange + 1)
        for (int x = -lostDespawnNearbyRange; x <= lostDespawnNearbyRange; x++) {
            for (int y = -lostDespawnNearbyRange; y <= lostDespawnNearbyRange; y++) {
                for (int z = -lostDespawnNearbyRange; z <= lostDespawnNearbyRange; z++) {
                    Location loc = current.clone().add(x, y, z);
                    Material type = loc.getBlock().getType();
                    if (type == Material.BEEHIVE || type == Material.BEE_NEST) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
