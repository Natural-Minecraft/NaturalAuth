package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

public class LogoutCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public LogoutCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        // Check if player is authenticated
        if (!plugin.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("§cAnda belum login!"));
            return;
        }

        // Remove session and set as unauthenticated
        plugin.getSessionManager().removeSession(player.getUniqueId());
        plugin.setAuthenticated(player.getUniqueId(), false);

        // Fetch logout message from config (default back if not defined or section missing)
        String logoutMessage = "§aAnda telah berhasil logout.\n§7Silakan masuk kembali untuk login ulang.";
        if (plugin.getConfig() != null && plugin.getConfig().getTable("messages") != null) {
            String configMsg = plugin.getConfig().getTable("messages").getString("logout-success");
            if (configMsg != null) {
                logoutMessage = configMsg.replace("&", "§");
            }
        }

        // Disconnect player with the message
        player.disconnect(Component.text(logoutMessage));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
