package id.naturalsmp.naturalauth.velocity.command;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.CompletableFuture;

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
            invocation.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        if (plugin.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a§lNaturalAuth §r§eAnda sudah login! Menyinkronkan status login ke server..."));
            if (plugin.getVelocityListener() != null) {
                plugin.getVelocityListener().finalizeAuth(player);
            }
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cGunakan: §e/register <password> <konfirmasiPassword>"));
            return;
        }

        String password = args[0];
        String confirmPassword = args[1];

        // ── Password strength validation ───────────────────────────────────
        if (password.length() < 6) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cPassword minimal harus §e6 karakter§c! (sekarang: " + password.length() + " karakter)"));
            return;
        }
        if (password.equalsIgnoreCase(player.getUsername())) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cPassword tidak boleh sama dengan username Anda!"));
            return;
        }
        if (COMMON_PASSWORDS.contains(password.toLowerCase())) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cPassword terlalu lemah! Hindari password umum seperti '123456' atau 'password'."));
            return;
        }
        if (!password.equals(confirmPassword)) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cKonfirmasi password tidak cocok! Pastikan keduanya sama."));
            return;
        }

        // Asynchronously check registration status and generate BCrypt hash on background thread
        CompletableFuture.supplyAsync(() -> plugin.getDatabaseManager().isRegistered(player.getUsername()))
            .thenAcceptAsync(registered -> {
                if (registered) {
                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cAkun Anda sudah terdaftar! Gunakan §e/login <password>§c untuk masuk."));
                    return;
                }

                // Heavy BCrypt hashing and registration
                CompletableFuture.supplyAsync(() -> plugin.register(player.getUniqueId(), player.getUsername(), password))
                    .thenAcceptAsync(success -> {
                        if (success) {
                            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a§l✔ REGISTRASI BERHASIL!"));
                            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7Username: §f" + player.getUsername()));
                            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§7Akun Anda telah dibuat dan siap digunakan."));

                            // Trigger email linkage prompt after registration
                            plugin.getServer().getScheduler().buildTask(plugin, () -> {
                                if (player.isActive()) {
                                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§e§l➤ §r§eLangkah berikutnya: Kaitkan email untuk keamanan akun"));
                                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§b/email <alamatEmail> §7— menerima OTP & reset password via email."));
                                }
                            }).delay(500, TimeUnit.MILLISECONDS).schedule();
                        } else {
                            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cRegistrasi gagal. Silakan coba kembali atau hubungi admin!"));
                        }
                    });
            });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}