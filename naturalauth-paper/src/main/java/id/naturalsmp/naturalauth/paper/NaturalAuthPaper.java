package id.naturalsmp.naturalauth.paper;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.listener.PaperListener;
import id.naturalsmp.naturalauth.paper.schematic.SchematicLoader;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;

public class NaturalAuthPaper extends JavaPlugin {

    private final Set<UUID> authenticatedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingRulesPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> limboPlayers = ConcurrentHashMap.newKeySet();
    private PaperListener listener;
    private Location spawnLocation;
    private Location schematicPasteLocation;
    private boolean enableSchematicLoading;
    private boolean lobbyMode;

    public boolean isLobbyMode() {
        return lobbyMode;
    }

    @Override
    public void onEnable() {
        saveDefaultConfigSafe();
        loadConfigValues();

        // Register plugin messaging channels
        getServer().getMessenger().registerOutgoingPluginChannel(this, AuthBridgeProtocol.FULL_CHANNEL);
        // Register BungeeCord channel so we can transfer players to other proxy servers
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        this.listener = new PaperListener(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, AuthBridgeProtocol.FULL_CHANNEL, listener);

        // Register events
        getServer().getPluginManager().registerEvents(listener, this);

        // Register commands
        if (getCommand("naturalauth") != null) {
            getCommand("naturalauth").setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("Hanya pemain yang dapat menjalankan perintah ini!");
                    return true;
                }
                
                UUID uuid = player.getUniqueId();
                if (args.length > 0) {
                    String sub = args[0].toLowerCase();
                    if (sub.equals("acceptrules")) {
                        if (isPendingRules(uuid)) {
                            listener.sendPacketRulesAccepted(player);
                            setPendingRules(uuid, false);
                            setAuthenticated(uuid, true);
                        } else {
                            player.sendMessage("§cAnda tidak sedang dalam antrean persetujuan peraturan!");
                        }
                        return true;
                    } else if (sub.equals("declinerules")) {
                        if (isPendingRules(uuid)) {
                            listener.sendPacketRulesDeclined(player);
                            setPendingRules(uuid, false);
                            player.kick(net.kyori.adventure.text.Component.text("§cAnda harus menyetujui peraturan untuk bermain!"));
                        } else {
                            player.sendMessage("§cAnda tidak sedang dalam antrean persetujuan peraturan!");
                        }
                        return true;
                    }
                }
                player.sendMessage("§cUsage: /naturalauth <acceptrules|declinerules>");
                return true;
            });
        }

        // Schematic loading is completely disabled to support the Virtual Void Lobby feature.
        // The lobby will remain purely empty (void) with only a single barrier block for player spawning.
        getLogger().info("Virtual Void Lobby mode active: physical platform loading has been disabled.");

        org.bukkit.Bukkit.getConsoleSender().sendMessage(
                org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "\n&a================================================================================\n" +
                    "&a _   _       _                             _        &e    _         _   _\n" +
                    "&a| \\ | | __ _| |_ _   _ _ __ __ _  | |   &e   / \\   _   _| |_| |__\n" +
                    "&a|  \\| |/ _` | __| | | | '__/ _` | | |   &e  / _ \\ | | | | __| '_ \\\n" +
                    "&a| |\\  | (_| | |_| |_| | | | (_| | | |   &e / ___ \\| |_| | |_| | | |\n" +
                    "&a|_| \\_|\\__,_|\\__|\\__,_|_|  \\__,_|_|_|   &e/_/   \\_\\\\__,_|\\__|_| |_|\n" +
                    "          &f>> &eNaturalAuth (Paper) v" + getDescription().getVersion() + " Enabled! <<\n" +
                    "&a================================================================================\n"
                )
        );
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getLogger().info("NaturalAuth Paper companion has been disabled.");
    }

    /**
     * Robust alternative to saveDefaultConfig().
     * Copies config.yml from the JAR classpath to the data folder if it does not exist.
     */
    private void saveDefaultConfigSafe() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            try (InputStream in = getResource("config.yml")) {
                if (in != null) {
                    try (OutputStream out = Files.newOutputStream(configFile.toPath())) {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) != -1) {
                            out.write(buf, 0, read);
                        }
                    }
                    getLogger().info("config.yml created from default.");
                } else {
                    getLogger().warning("Default config.yml not found in plugin JAR via getResource!");
                    // Fall back to Bukkit's method
                    saveDefaultConfig();
                }
            } catch (IOException e) {
                getLogger().severe("Failed to create config.yml: " + e.getMessage());
            }
        }
        // Reload from disk so getConfig() returns the correct values
        reloadConfig();
    }

    private void loadConfigValues() {
        lobbyMode = getConfig().getBoolean("lobby-mode", true);
        enableSchematicLoading = lobbyMode && getConfig().getBoolean("enable-schematic-loading", true);

        // Read spawn location
        String spawnWorldName = getConfig().getString("spawn-location.world", "world");
        double spawnX = getConfig().getDouble("spawn-location.x", 0.5);
        double spawnY = getConfig().getDouble("spawn-location.y", 102.0);
        double spawnZ = getConfig().getDouble("spawn-location.z", 0.5);
        float spawnYaw = (float) getConfig().getDouble("spawn-location.yaw", 0.0);
        float spawnPitch = (float) getConfig().getDouble("spawn-location.pitch", 0.0);

        World spawnWorld = Bukkit.getWorld(spawnWorldName);
        if (spawnWorld == null) {
            spawnWorld = Bukkit.getWorlds().get(0);
        }
        spawnLocation = new Location(spawnWorld, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);

        // Read schematic paste location
        String pasteWorldName = getConfig().getString("schematic-paste.world", "world");
        double pasteX = getConfig().getDouble("schematic-paste.x", 0.0);
        double pasteY = getConfig().getDouble("schematic-paste.y", 100.0);
        double pasteZ = getConfig().getDouble("schematic-paste.z", 0.0);

        World pasteWorld = Bukkit.getWorld(pasteWorldName);
        if (pasteWorld == null) {
            pasteWorld = Bukkit.getWorlds().get(0);
        }
        schematicPasteLocation = new Location(pasteWorld, pasteX, pasteY, pasteZ);
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticatedPlayers.contains(uuid);
    }

    public void setAuthenticated(UUID uuid, boolean auth) {
        if (auth) {
            authenticatedPlayers.add(uuid);
            pendingRulesPlayers.remove(uuid);
        } else {
            authenticatedPlayers.remove(uuid);
        }
    }

    public boolean isPendingRules(UUID uuid) {
        return pendingRulesPlayers.contains(uuid);
    }

    public void setPendingRules(UUID uuid, boolean pending) {
        if (pending) {
            pendingRulesPlayers.add(uuid);
            authenticatedPlayers.remove(uuid); // Safety check
        } else {
            pendingRulesPlayers.remove(uuid);
        }
    }

    public Set<UUID> getAuthenticatedPlayers() {
        return authenticatedPlayers;
    }

    public Set<UUID> getPendingRulesPlayers() {
        return pendingRulesPlayers;
    }

    public Set<UUID> getLimboPlayers() {
        return limboPlayers;
    }

    public boolean isInLimbo(UUID uuid) {
        return limboPlayers.contains(uuid);
    }

    public void setLimbo(UUID uuid, boolean limbo) {
        if (limbo) {
            limboPlayers.add(uuid);
        } else {
            limboPlayers.remove(uuid);
        }
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    public PaperListener getListener() {
        return listener;
    }
}
