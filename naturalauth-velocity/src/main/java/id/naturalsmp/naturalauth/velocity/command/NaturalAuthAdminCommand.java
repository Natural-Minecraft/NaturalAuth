package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import net.kyori.adventure.text.Component;
import org.mindrot.jbcrypt.BCrypt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class NaturalAuthAdminCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public NaturalAuthAdminCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length < 2 || !args[0].equalsIgnoreCase("admin")) {
            sendHelp(invocation);
            return;
        }

        String sub = args[1].toLowerCase();
        switch (sub) {
            case "forcelogin":
                handleForceLogin(invocation, args);
                break;
            case "forceregister":
                handleForceRegister(invocation, args);
                break;
            case "changepassword":
                handleChangePassword(invocation, args);
                break;
            case "changeemail":
                handleChangeEmail(invocation, args);
                break;
            case "unregister":
                handleUnregister(invocation, args);
                break;
            case "kick":
                handleKick(invocation, args);
                break;
            case "getotp":
                handleGetOtp(invocation, args);
                break;
            case "resendotp":
                handleResendOtp(invocation, args);
                break;
            case "whois":
                handleWhois(invocation, args);
                break;
            case "setpremium":
                handleSetPremium(invocation, args);
                break;
            case "setcracked":
                handleSetCracked(invocation, args);
                break;
            default:
                sendHelp(invocation);
                break;
        }
    }

    private void sendHelp(Invocation invocation) {
        invocation.source().sendMessage(Component.text("§b§l⚡ NaturalAuth Admin Commands ⚡"));
        invocation.source().sendMessage(Component.text("§e/na admin forcelogin <player> §7- Paksa login player"));
        invocation.source().sendMessage(Component.text("§e/na admin forceregister <player> <pass> <pass> §7- Daftarkan player"));
        invocation.source().sendMessage(Component.text("§e/na admin changepassword <player> <newPass> §7- Ganti sandi player"));
        invocation.source().sendMessage(Component.text("§e/na admin changeemail <player> <newEmail> §7- Ganti email player"));
        invocation.source().sendMessage(Component.text("§e/na admin unregister <player> §7- Hapus akun player"));
        invocation.source().sendMessage(Component.text("§e/na admin kick <player/all/*/**> §7- Kick player"));
        invocation.source().sendMessage(Component.text("§e/na admin getotp <email> §7- Lihat OTP aktif email"));
        invocation.source().sendMessage(Component.text("§e/na admin resendotp <email> §7- Kirim ulang OTP email"));
        invocation.source().sendMessage(Component.text("§e/na admin whois <player> §7- Lihat profil detil (Chest GUI)"));
        invocation.source().sendMessage(Component.text("§e/na admin setpremium <player> §7- Set status premium"));
        invocation.source().sendMessage(Component.text("§e/na admin setcracked <player> §7- Set status cracked"));
    }

    private void handleForceLogin(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin forcelogin <player>"));
            return;
        }
        String targetName = args[2];
        Optional<Player> target = plugin.getServer().getPlayer(targetName);
        if (target.isEmpty()) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " tidak sedang online!"));
            return;
        }

        Player player = target.get();
        if (plugin.isAuthenticated(player.getUniqueId())) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " sudah dalam keadaan login!"));
            return;
        }

        if (plugin.getVelocityListener() != null) {
            plugin.getVelocityListener().finalizeAuth(player);
            invocation.source().sendMessage(Component.text("§aBerhasil memaksa login player " + player.getUsername()));
        } else {
            invocation.source().sendMessage(Component.text("§cInternal listener error."));
        }
    }

    private void handleForceRegister(Invocation invocation, String[] args) {
        if (args.length < 5) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin forceregister <player> <password> <password>"));
            return;
        }
        String targetName = args[2];
        String pass1 = args[3];
        String pass2 = args[4];

        if (!pass1.equals(pass2)) {
            invocation.source().sendMessage(Component.text("§cKonfirmasi password tidak cocok!"));
            return;
        }

        if (plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " sudah terdaftar di database!"));
            return;
        }

        UUID uuid = plugin.getServer().getPlayer(targetName).map(Player::getUniqueId).orElse(UUID.randomUUID());
        boolean success = plugin.register(uuid, targetName, pass1);
        if (success) {
            invocation.source().sendMessage(Component.text("§aBerhasil mendaftarkan player " + targetName + " dengan password baru."));
        } else {
            invocation.source().sendMessage(Component.text("§cGagal mendaftarkan player!"));
        }
    }

    private void handleChangePassword(Invocation invocation, String[] args) {
        if (args.length < 4) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin changepassword <player> <newPassword>"));
            return;
        }
        String targetName = args[2];
        String newPass = args[3];

        if (!plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " belum terdaftar!"));
            return;
        }

        int rounds = plugin.getConfig().getTable("settings").getLong("bcrypt-rounds", 10L).intValue();
        String hash = BCrypt.hashpw(newPass, BCrypt.gensalt(rounds));
        boolean success = plugin.getDatabaseManager().updatePasswordByUsername(targetName, hash);

        if (success) {
            invocation.source().sendMessage(Component.text("§aBerhasil mengganti password untuk player " + targetName));
            plugin.getServer().getPlayer(targetName).ifPresent(p -> {
                p.sendMessage(Component.text("§ePassword Anda telah diubah oleh Admin. Silakan gunakan password baru Anda."));
            });
        } else {
            invocation.source().sendMessage(Component.text("§cGagal mengganti password!"));
        }
    }

    private void handleChangeEmail(Invocation invocation, String[] args) {
        if (args.length < 4) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin changeemail <player> <newEmail>"));
            return;
        }
        String targetName = args[2];
        String newEmail = args[3];

        if (!plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " belum terdaftar!"));
            return;
        }

        boolean success = plugin.getDatabaseManager().updateEmailByUsername(targetName, newEmail);
        if (success) {
            invocation.source().sendMessage(Component.text("§aBerhasil mengubah email player " + targetName + " menjadi " + newEmail));
        } else {
            invocation.source().sendMessage(Component.text("§cGagal mengubah email!"));
        }
    }

    private void handleUnregister(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin unregister <player>"));
            return;
        }
        String targetName = args[2];

        if (!plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " belum terdaftar!"));
            return;
        }

        boolean success = plugin.getDatabaseManager().unregisterUser(targetName);
        if (success) {
            invocation.source().sendMessage(Component.text("§aBerhasil menghapus pendaftaran player " + targetName));
            plugin.getServer().getPlayer(targetName).ifPresent(p -> {
                plugin.getSessionManager().removeSession(p.getUniqueId());
                plugin.setAuthenticated(p.getUniqueId(), false);
                p.disconnect(Component.text("§cAkun Anda telah di-unregister oleh Admin!"));
            });
        } else {
            invocation.source().sendMessage(Component.text("§cGagal meng-unregister player!"));
        }
    }

    private void handleKick(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin kick <player/all/*/**>"));
            return;
        }
        String target = args[2];

        if (target.equalsIgnoreCase("all") || target.equals("*") || target.equals("**")) {
            int count = 0;
            for (Player p : plugin.getServer().getAllPlayers()) {
                p.disconnect(Component.text("§cDi-kick secara massal oleh Admin!"));
                count++;
            }
            invocation.source().sendMessage(Component.text("§aBerhasil meng-kick " + count + " player massal!"));
        } else {
            Optional<Player> targetPlayer = plugin.getServer().getPlayer(target);
            if (targetPlayer.isPresent()) {
                targetPlayer.get().disconnect(Component.text("§cAnda di-kick oleh Admin!"));
                invocation.source().sendMessage(Component.text("§aBerhasil meng-kick player " + target));
            } else {
                invocation.source().sendMessage(Component.text("§cPlayer " + target + " tidak ditemukan online!"));
            }
        }
    }

    private void handleGetOtp(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin getotp <email>"));
            return;
        }
        String email = args[2];
        String otp = plugin.getDatabaseManager().getOTP(email);
        if (otp != null) {
            invocation.source().sendMessage(Component.text("§aOTP Aktif untuk email §e" + email + " §aadalah: §b§l" + otp));
        } else {
            invocation.source().sendMessage(Component.text("§cTidak ada OTP aktif atau kedaluwarsa untuk email: " + email));
        }
    }

    private void handleResendOtp(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin resendotp <email>"));
            return;
        }
        String email = args[2];
        String otp = plugin.getDatabaseManager().getOTP(email);
        if (otp == null) {
            invocation.source().sendMessage(Component.text("§cTidak ada OTP aktif untuk email " + email + ". Buat baru dengan /email!"));
            return;
        }

        invocation.source().sendMessage(Component.text("§aMengirimkan ulang OTP ke email: " + email + "..."));
        plugin.sendOtpEmail("Player", email, otp).thenAccept(success -> {
            if (success) {
                invocation.source().sendMessage(Component.text("§aResend OTP sukses!"));
            } else {
                invocation.source().sendMessage(Component.text("§cResend OTP gagal!"));
            }
        });
    }

    private void handleWhois(Invocation invocation, String[] args) {
        if (!(invocation.source() instanceof Player adminPlayer)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player in-game!"));
            return;
        }

        if (args.length < 3) {
            adminPlayer.sendMessage(Component.text("§cGunakan: /na admin whois <player>"));
            return;
        }

        String targetUsername = args[2];
        
        // Signal Paper to open Whois Restricted Chest GUI
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_WHOIS_REQUEST);
            dos.writeUTF(adminPlayer.getUniqueId().toString());
            dos.writeUTF(targetUsername);

            adminPlayer.getCurrentServer().ifPresent(serverConnection -> {
                serverConnection.sendPluginMessage(NaturalAuthVelocity.BRIDGE_CHANNEL, baos.toByteArray());
            });
            adminPlayer.sendMessage(Component.text("§aMengajukan data Whois untuk " + targetUsername + " ke Paper..."));

        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct PACKET_WHOIS_REQUEST", e);
        }
    }

    private void handleSetPremium(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin setpremium <player>"));
            return;
        }
        String targetName = args[2];

        if (!plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " belum terdaftar!"));
            return;
        }

        Optional<Player> targetPlayer = plugin.getServer().getPlayer(targetName);
        UUID uuid = targetPlayer.map(Player::getUniqueId).orElse(null);

        if (uuid == null) {
            // Find uuid from database if player is offline
            java.util.Map<String, String> info = plugin.getDatabaseManager().getUserInfo(targetName);
            if (info != null) {
                uuid = UUID.fromString(info.get("uuid"));
            }
        }

        if (uuid == null) {
            invocation.source().sendMessage(Component.text("§cUUID player tidak ditemukan!"));
            return;
        }

        plugin.getDatabaseManager().setPremium(uuid, true);
        plugin.getDatabaseManager().updatePassword(uuid, ""); // Clear password hash

        invocation.source().sendMessage(Component.text("§aSukses menyetel status player " + targetName + " sebagai PREMIUM (Password terhapus)."));

        targetPlayer.ifPresent(p -> {
            p.disconnect(Component.text("§aStatus akun Anda diubah menjadi PREMIUM oleh Admin.\n§7Silakan gabung kembali menggunakan Minecraft Original secara otomatis!"));
        });
    }

    private void handleSetCracked(Invocation invocation, String[] args) {
        if (args.length < 3) {
            invocation.source().sendMessage(Component.text("§cGunakan: /na admin setcracked <player>"));
            return;
        }
        String targetName = args[2];

        if (!plugin.getDatabaseManager().isRegistered(targetName)) {
            invocation.source().sendMessage(Component.text("§cPlayer " + targetName + " belum terdaftar!"));
            return;
        }

        Optional<Player> targetPlayer = plugin.getServer().getPlayer(targetName);
        UUID uuid = targetPlayer.map(Player::getUniqueId).orElse(null);

        if (uuid == null) {
            java.util.Map<String, String> info = plugin.getDatabaseManager().getUserInfo(targetName);
            if (info != null) {
                uuid = UUID.fromString(info.get("uuid"));
            }
        }

        if (uuid == null) {
            invocation.source().sendMessage(Component.text("§cUUID player tidak ditemukan!"));
            return;
        }

        plugin.getDatabaseManager().setPremium(uuid, false);

        invocation.source().sendMessage(Component.text("§eSukses menyetel status player " + targetName + " sebagai CRACKED. Player dipaksa registrasi ulang."));

        // Delete from database completely so they have to re-register on next login!
        plugin.getDatabaseManager().unregisterUser(targetName);

        targetPlayer.ifPresent(p -> {
            p.disconnect(Component.text("§eStatus akun Anda diubah menjadi CRACKED oleh Admin.\n§7Silakan gabung kembali dan daftarkan password baru Anda!"));
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("naturalauth.admin");
    }
}
