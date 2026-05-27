package id.naturalsmp.naturalauth.paper.gui;

import id.naturalsmp.naturalauth.common.AuthBridgeProtocol;
import id.naturalsmp.naturalauth.paper.NaturalAuthPaper;
import net.wesjd.anvilgui.AnvilGUI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

public class AnvilGuiRenderer {

    public static void openAnvilGUI(NaturalAuthPaper plugin, Player player, String type, String prompt) {
        ItemStack itemLeft = new ItemStack(Material.PAPER);
        ItemMeta meta = itemLeft.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(prompt);
            itemLeft.setItemMeta(meta);
        }

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title(type.equalsIgnoreCase("LOGIN") ? "Login - Tulis Password" : "Daftar - Tulis Password")
                .text(prompt)
                .itemLeft(itemLeft)
                .onClick((slot, stateSnapshot) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return Collections.emptyList();
                    }

                    String password = stateSnapshot.getText();
                    if (password == null || password.trim().isEmpty() || password.equalsIgnoreCase(prompt)) {
                        player.sendMessage("§cPassword tidak boleh kosong / harus diubah!");
                        return Collections.singletonList(AnvilGUI.ResponseAction.replaceInputText(prompt));
                    }

                    // Send password to Velocity via plugin messaging channel
                    submitPasswordToVelocity(plugin, player, password);

                    // Close Anvil GUI. If password is wrong, Velocity will send another packet to reopen it.
                    return Arrays.asList(AnvilGUI.ResponseAction.close());
                })
                .onClose(stateSnapshot -> {
                    // Reopen the GUI if player closes it without being authenticated
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && !plugin.isAuthenticated(player.getUniqueId())) {
                            String currentType = plugin.getListener().getActiveGuiType().getOrDefault(player.getUniqueId(), type);
                            String currentPrompt = plugin.getListener().getActivePrompt().getOrDefault(player.getUniqueId(), prompt);
                            openAnvilGUI(plugin, player, currentType, currentPrompt);
                        }
                    }, 20L); // 1 second delay
                })
                .open(player);
    }

    private static void submitPasswordToVelocity(NaturalAuthPaper plugin, Player player, String password) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_PASSWORD);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(password);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_PASSWORD to Velocity!");
            e.printStackTrace();
        }
    }
}
