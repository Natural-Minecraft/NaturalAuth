package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RegisterCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    private static final List<String> COMMON_PASSWORDS = Arrays.asList(
        "123456", "1234567", "12345678", "123456789", "password", "qwerty",
        "minecraft", "iloveyou", "letmein", "abc123", "admin", "000000",
        "111111", "123123", "dragon", "master", "monkey", "shadow"
    );

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
            player.sendMessage(Component.text("§cAkun Anda sudah terdaftar! Gunakan §e/login <password>§c untuk masuk."));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(Component.text("§cGunakan: §e/register <password> <konfirmasiPassword>"));
            return;
        }

        String password = args[0];
        String confirmPassword = args[1];

        // ── Password strength validation ───────────────────────────────────
        if (password.length() < 6) {
            player.sendMessage(Component.text("§cPassword minimal harus §e6 karakter§c! (sekarang: " + password.length() + " karakter)"));
            return;
        }
        if (password.equalsIgnoreCase(player.getUsername())) {
            player.sendMessage(Component.text("§cPassword tidak boleh sama dengan username Anda!"));
            return;
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            player.sendMessage(Component.text("§cPassword terlalu lemah! Hindari password umum seperti '123456' atau 'password'."));
            return;
        }
        if (!password.equals(confirmPassword)) {
            player.sendMessage(Component.text("§cKonfirmasi password tidak cocok! Pastikan keduanya sama."));
            return;
        }

        boolean success = plugin.register(player.getUniqueId(), player.getUsername(), password);
        if (success) {
            player.sendMessage(Component.text("§a§l✔ REGISTRASI BERHASIL!"));
            player.sendMessage(Component.text("§7Username: §f" + player.getUsername()));
            player.sendMessage(Component.text("§7Akun Anda telah dibuat dan siap digunakan."));

            // Trigger email linkage prompt after registration
            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                if (player.isActive()) {
                    player.sendMessage(Component.text("§e§l➤ §r§eLangkah berikutnya: Kaitkan email untuk keamanan akun"));
                    player.sendMessage(Component.text("§b/email <alamatEmail> §7— menerima OTP & reset password via email."));
                }
            }).delay(500, TimeUnit.MILLISECONDS).schedule();
        } else {
            player.sendMessage(Component.text("§cRegistrasi gagal. Silakan coba kembali atau hubungi admin!"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
