package id.naturalsmp.naturalauth.velocity;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ReconnectionQueueManager {
    private final NaturalAuthVelocity plugin;
    // Map of ServerName -> List of Player UUIDs
    private final Map<String, List<UUID>> queues = new ConcurrentHashMap<>();
    // Map of Player UUID -> Server Name they are waiting for
    private final Map<UUID, String> playerTargets = new ConcurrentHashMap<>();
    // Set of ServerNames currently processing their queue
    private final Set<String> processingServers = ConcurrentHashMap.newKeySet();

    public ReconnectionQueueManager(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
        // Schedule a task to update titles for queued players every 1.5 seconds
        plugin.getServer().getScheduler().buildTask(plugin, this::updateQueueTitles)
                .repeat(1500, TimeUnit.MILLISECONDS)
                .schedule();
    }

    public synchronized void addPlayer(Player player, String targetServer) {
        UUID uuid = player.getUniqueId();
        String target = targetServer.toLowerCase();
        List<UUID> q = queues.computeIfAbsent(target, k -> new ArrayList<>());
        if (!q.contains(uuid)) {
            q.add(uuid);
            playerTargets.put(uuid, target);
        }
        updateTitleForPlayer(player, target);
    }

    public synchronized void removePlayer(UUID uuid) {
        String target = playerTargets.remove(uuid);
        if (target != null) {
            List<UUID> q = queues.get(target);
            if (q != null) {
                q.remove(uuid);
            }
        }
    }

    public synchronized int getPosition(UUID uuid) {
        String target = playerTargets.get(uuid);
        if (target == null) return -1;
        List<UUID> q = queues.get(target);
        if (q == null) return -1;
        return q.indexOf(uuid) + 1; // 1-indexed
    }

    public synchronized int getTotal(String serverName) {
        List<UUID> q = queues.get(serverName.toLowerCase());
        return q != null ? q.size() : 0;
    }

    public String getTargetServer(UUID uuid) {
        return playerTargets.get(uuid);
    }

    public boolean hasQueues() {
        for (List<UUID> q : queues.values()) {
            if (!q.isEmpty()) return true;
        }
        return false;
    }

    public Set<String> getQueuedServers() {
        return queues.keySet();
    }

    private void updateQueueTitles() {
        for (Player player : plugin.getServer().getAllPlayers()) {
            UUID uuid = player.getUniqueId();
            String target = playerTargets.get(uuid);
            if (target != null) {
                updateTitleForPlayer(player, target);
            }
        }
    }

    private void updateTitleForPlayer(Player player, String target) {
        int pos = getPosition(player.getUniqueId());
        int total = getTotal(target);
        if (pos > 0 && total > 0) {
            Component title = LegacyComponentSerializer.legacyAmpersand().deserialize("&d&lReconnecting");
            Component subtitle = LegacyComponentSerializer.legacyAmpersand().deserialize("&eYour position is &a" + pos + " &e/ &a" + total);
            player.showTitle(Title.title(title, subtitle, Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(100))));
        }
    }

    public synchronized void startProcessingIfOnline(String serverName) {
        String sName = serverName.toLowerCase();
        if (processingServers.contains(sName)) return;
        List<UUID> q = queues.get(sName);
        if (q == null || q.isEmpty()) return;

        processingServers.add(sName);
        plugin.getLogger().info("Starting sequential redirection queue for server " + serverName + ". Size: " + q.size());

        // Process sequentially on a separate task
        processNext(sName, 0);
    }

    private void processNext(String serverName, int index) {
        plugin.getServer().getScheduler().buildTask(plugin, () -> {
            UUID nextUuid;
            synchronized (this) {
                List<UUID> q = queues.get(serverName);
                if (q == null || q.isEmpty()) {
                    processingServers.remove(serverName);
                    plugin.getLogger().info("Sequential redirection queue for server " + serverName + " is now empty.");
                    return;
                }

                // Check if server is still online. If it went offline again, stop processing.
                if (!plugin.isServerOnline(serverName)) {
                    processingServers.remove(serverName);
                    plugin.getLogger().warn("Server " + serverName + " went offline. Stopping queue processing.");
                    return;
                }

                nextUuid = q.remove(0);
                playerTargets.remove(nextUuid);
            }

            // Redirect the player
            plugin.getServer().getPlayer(nextUuid).ifPresent(player -> {
                plugin.getServer().getServer(serverName).ifPresent(targetServer -> {
                    plugin.getLogger().info("Redirecting queued player " + player.getUsername() + " to " + serverName);

                    // Tell Paper companion plugin that reconnect warp is active
                    if (plugin.getVelocityListener() != null) {
                        plugin.getVelocityListener().sendReconnectReadyToPaper(player);
                    }

                    plugin.unregisterLimboPlayer(player.getUniqueId());
                    player.createConnectionRequest(targetServer).fireAndForget();
                });
            });

            // Schedule next redirection
            processNext(serverName, index + 1);
        }).delay(index == 0 ? 5000 : getDelayForIndex(index), TimeUnit.MILLISECONDS).schedule();
    }

    private long getDelayForIndex(int index) {
        // index represents the index of the next player to redirect.
        // 1st player redirected at index 0 (delay 5s).
        // 2nd player redirected at index 1 (delay 3s).
        // 3rd player redirected at index 2 (delay 1s).
        // 4th+ player redirected at index >= 3 (delay 0.5s).
        if (index == 1) return 3000;
        if (index == 2) return 1000;
        return 500;
    }
}
