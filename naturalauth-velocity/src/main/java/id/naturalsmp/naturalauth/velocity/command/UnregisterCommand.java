package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

public class UnregisterCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public UnregisterCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        if (!plugin.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("§cAnda harus login terlebih dahulu!"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirm")) {
            player.sendMessage(Component.text("§c§l┌────────────────────────────────────────┐"));
            player.sendMessage(Component.text("§c§l│              [!] PERINGATAN [!]              │"));
            player.sendMessage(Component.text("§c§l└────────────────────────────────────────┘"));
            player.sendMessage(Component.text("§7Tindakan ini akan MENGHAPUS akun Anda dari database server."));
            player.sendMessage(Component.text("§7Anda akan ter-kick dan harus melakukan registrasi ulang."));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§eKetik §a/unregister confirm §euntuk mengonfirmasi tindakan."));
            return;
        }

        boolean success = plugin.getDatabaseManager().unregisterUser(player.getUsername());
        if (success) {
            plugin.getSessionManager().removeSession(player.getUniqueId());
            plugin.setAuthenticated(player.getUniqueId(), false);
            player.disconnect(Component.text("§aAkun Anda telah sukses dihapus (Unregistered).\n§7Silakan masuk kembali jika ingin mendaftar ulang."));
        } else {
            player.sendMessage(Component.text("§cGagal menghapus akun. Silakan coba kembali!"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
