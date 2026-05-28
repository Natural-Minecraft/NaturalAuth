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
            player.sendMessage(Component.text("§4§l╔══════════════════════════════════════╗"));
            player.sendMessage(Component.text("§4§l║      ⚠  PERINGATAN BERBAHAYA  ⚠      ║"));
            player.sendMessage(Component.text("§4§l╚══════════════════════════════════════╝"));
            player.sendMessage(Component.text("§c§lAksi ini §4§lTIDAK DAPAT DIBATALKAN§c§l!"));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§7• §fSemua data akun akan dihapus permanen dari database"));
            player.sendMessage(Component.text("§7• §fAnda akan ter-disconnect secara otomatis"));
            player.sendMessage(Component.text("§7• §fAnda harus registrasi ulang saat bergabung kembali"));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§eKetik §a§l/unregister confirm §euntuk melanjutkan."));
            player.sendMessage(Component.text("§7(Abaikan pesan ini jika ingin membatalkan)"));
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
