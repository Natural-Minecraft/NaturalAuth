package id.naturalsmp.naturalauth.velocity.command;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;
import java.util.UUID;

public class EmailCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public EmailCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cGunakan: /email <alamatEmail>"));
            return;
        }

        String email = args[0].trim();
        if (!email.contains("@") || !email.contains(".")) {
            player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cFormat alamat email tidak valid!"));
            return;
        }

        UUID uuid = player.getUniqueId();
        String otpCode = String.format("%06d", new java.util.Random().nextInt(1000000));

        // Save target email temporarily and store generated OTP
        plugin.getDatabaseManager().setEmail(uuid, email);
        plugin.getDatabaseManager().saveOTP(uuid, email, otpCode);

        player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§a§lNaturalAuth §r§aMengirimkan kode verifikasi OTP ke email Anda..."));
        
        plugin.sendOtpEmail(player.getUsername(), email, otpCode).thenAccept(success -> {
            if (success) {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§aOTP berhasil dikirim! Silakan cek inbox atau folder spam email Anda."));
                
                // Open Anvil OTP input GUI
                if (plugin.getVelocityListener() != null) {
                    plugin.getVelocityListener().sendOpenOtpToPaper(player);
                } else {
                    player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§eMasukkan OTP menggunakan Anvil GUI atau hubungi admin."));
                }
            } else {
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize("§cGagal mengirimkan email OTP. Silakan coba beberapa saat lagi!"));
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}