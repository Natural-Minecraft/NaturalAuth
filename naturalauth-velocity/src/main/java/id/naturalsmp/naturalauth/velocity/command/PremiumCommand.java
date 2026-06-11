package id.naturalsmp.naturalauth.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.velocity.NaturalAuthVelocity;
import net.kyori.adventure.text.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

public class PremiumCommand implements SimpleCommand {

    private final NaturalAuthVelocity plugin;

    public PremiumCommand(NaturalAuthVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("§cCommand ini hanya dapat digunakan oleh player!"));
            return;
        }

        String captcha = generateRandomCaptcha();
        sendOpenPremiumGui(player, captcha);
    }

    private String generateRandomCaptcha() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private void sendOpenPremiumGui(Player player, String captcha) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_OPEN_PREMIUM_GUI);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(captcha);

            player.getCurrentServer().ifPresent(serverConnection -> {
                serverConnection.sendPluginMessage(NaturalAuthVelocity.BRIDGE_CHANNEL, baos.toByteArray());
            });
        } catch (IOException e) {
            plugin.getLogger().error("Failed to construct/send PACKET_OPEN_PREMIUM_GUI", e);
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
