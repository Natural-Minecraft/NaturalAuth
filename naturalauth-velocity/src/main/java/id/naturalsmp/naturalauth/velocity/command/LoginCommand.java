package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

public class LoginCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public LoginCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        if (plugin.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("§cAnda sudah login!"));
            return;
        }

        boolean registered = plugin.getDatabaseManager().isRegistered(player.getUsername());
        if (!registered) {
            player.sendMessage(Component.text("§cAkun Anda belum terdaftar! Silakan gunakan §e/register <password> <password> §cuntuk mendaftar."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(Component.text("§cGunakan: /login <password>"));
            return;
        }

        String password = args[0];
        if (plugin.verifyPassword(player.getUsername(), password)) {
            player.sendMessage(Component.text("§aLogin berhasil!"));
            if (plugin.getVelocityListener() != null) {
                plugin.getVelocityListener().handlePasswordVerified(player);
            } else {
                plugin.setAuthenticated(player.getUniqueId(), true);
                player.sendMessage(Component.text("§aAutentikasi berhasil. Menghubungkan ke server..."));
            }
        } else {
            player.sendMessage(Component.text("§cPassword salah!"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
