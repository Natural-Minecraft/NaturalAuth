package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;
import java.util.concurrent.TimeUnit;

public class RegisterCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public RegisterCommand(NaturalAuthVelocity plugin) {
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
        if (registered) {
            player.sendMessage(Component.text("§cAkun Anda sudah terdaftar! Silakan gunakan §e/login <password> §cuntuk masuk."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(Component.text("§cGunakan: /register <password> <konfirmasiPassword>"));
            return;
        }

        String password = args[0];
        String confirmPassword = args[1];

        if (password.length() < 4) {
            player.sendMessage(Component.text("§cPassword minimal harus 4 karakter!"));
            return;
        }

        if (!password.equals(confirmPassword)) {
            player.sendMessage(Component.text("§cKonfirmasi password tidak cocok!"));
            return;
        }

        boolean success = plugin.register(player.getUniqueId(), player.getUsername(), password);
        if (success) {
            player.sendMessage(Component.text("§a§lNaturalAuth §r§aRegistrasi berhasil!"));
            
            // Trigger email linkage prompt after registration
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (player.isActive()) {
                    player.sendMessage(Component.text("§e§lNaturalAuth §r§eSilakan kaitkan alamat email Anda menggunakan perintah:"));
                    player.sendMessage(Component.text("§b/email <alamatEmail> §euntuk keamanan tambahan (lupa sandi/OTP)."));
                }
            }).delay(500, TimeUnit.MILLISECONDS).schedule();
        } else {
            player.sendMessage(Component.text("§cRegistrasi gagal. Silakan coba kembali!"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
