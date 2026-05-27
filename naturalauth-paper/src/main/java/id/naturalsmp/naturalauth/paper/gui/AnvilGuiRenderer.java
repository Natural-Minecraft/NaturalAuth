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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AnvilGuiRenderer {

    private static final Map<UUID, String> tempPasswords = new ConcurrentHashMap<>();

    public static void clearTempPassword(UUID uuid) {
        tempPasswords.remove(uuid);
    }

    public static void openAnvilGUI(NaturalAuthPaper plugin, Player player, String type, String prompt) {
        if (type.equalsIgnoreCase("REGISTER")) {
            openRegisterStep1(plugin, player, prompt);
        } else {
            openLoginGUI(plugin, player, prompt);
        }
    }

    private static void openLoginGUI(NaturalAuthPaper plugin, Player player, String prompt) {
        ItemStack itemLeft = new ItemStack(Material.PAPER);
        ItemMeta meta = itemLeft.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(prompt);
            itemLeft.setItemMeta(meta);
        }

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title("Login - Tulis Password")
                .text(prompt)
                .itemLeft(itemLeft)
                .onClick((slot, stateSnapshot) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return Collections.emptyList();
                    }

                    String password = stateSnapshot.getText();
                    if (password == null || password.trim().isEmpty() || password.equalsIgnoreCase(prompt)) {
                        player.sendMessage("§cPassword tidak boleh kosong!");
                        return Collections.singletonList(AnvilGUI.ResponseAction.replaceInputText(prompt));
                    }

                    submitPasswordToVelocity(plugin, player, password);
                    return Arrays.asList(AnvilGUI.ResponseAction.close());
                })
                .onClose(stateSnapshot -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && !plugin.isAuthenticated(player.getUniqueId()) && !plugin.isPendingRules(player.getUniqueId())) {
                            String currentPrompt = plugin.getListener().getActivePrompt().getOrDefault(player.getUniqueId(), prompt);
                            openLoginGUI(plugin, player, currentPrompt);
                        }
                    }, 20L);
                })
                .open(player);
    }

    private static void openRegisterStep1(NaturalAuthPaper plugin, Player player, String prompt) {
        ItemStack itemLeft = new ItemStack(Material.PAPER);
        ItemMeta meta = itemLeft.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(prompt);
            itemLeft.setItemMeta(meta);
        }

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title("Daftar - Tulis Password")
                .text(prompt)
                .itemLeft(itemLeft)
                .onClick((slot, stateSnapshot) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return Collections.emptyList();
                    }

                    String password = stateSnapshot.getText();
                    if (password == null || password.trim().isEmpty() || password.equalsIgnoreCase(prompt) || password.length() < 4) {
                        player.sendMessage("§cPassword minimal 4 karakter!");
                        return Collections.singletonList(AnvilGUI.ResponseAction.replaceInputText(prompt));
                    }

                    tempPasswords.put(player.getUniqueId(), password);
                    
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline()) {
                            openRegisterStep2(plugin, player, "Ulangi Password:");
                        }
                    }, 2L);

                    return Arrays.asList(AnvilGUI.ResponseAction.close());
                })
                .onClose(stateSnapshot -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && !plugin.isAuthenticated(player.getUniqueId()) && !plugin.isPendingRules(player.getUniqueId()) && !tempPasswords.containsKey(player.getUniqueId())) {
                            String currentPrompt = plugin.getListener().getActivePrompt().getOrDefault(player.getUniqueId(), prompt);
                            openRegisterStep1(plugin, player, currentPrompt);
                        }
                    }, 20L);
                })
                .open(player);
    }

    private static void openRegisterStep2(NaturalAuthPaper plugin, Player player, String prompt) {
        ItemStack itemLeft = new ItemStack(Material.PAPER);
        ItemMeta meta = itemLeft.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(prompt);
            itemLeft.setItemMeta(meta);
        }

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title("Daftar - Konfirmasi Password")
                .text(prompt)
                .itemLeft(itemLeft)
                .onClick((slot, stateSnapshot) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return Collections.emptyList();
                    }

                    String confirm = stateSnapshot.getText();
                    String firstPassword = tempPasswords.get(player.getUniqueId());

                    if (firstPassword == null) {
                        return Arrays.asList(AnvilGUI.ResponseAction.close());
                    }

                    if (!firstPassword.equals(confirm)) {
                        player.sendMessage("§cKonfirmasi password tidak cocok! Silakan ulangi.");
                        return Collections.singletonList(AnvilGUI.ResponseAction.replaceInputText(prompt));
                    }

                    tempPasswords.remove(player.getUniqueId());
                    submitPasswordToVelocity(plugin, player, firstPassword);

                    return Arrays.asList(AnvilGUI.ResponseAction.close());
                })
                .onClose(stateSnapshot -> {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (player.isOnline() && !plugin.isAuthenticated(player.getUniqueId()) && !plugin.isPendingRules(player.getUniqueId()) && tempPasswords.containsKey(player.getUniqueId())) {
                            openRegisterStep2(plugin, player, prompt);
                        }
                    }, 20L);
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

    public static void openEmailLinkGUI(NaturalAuthPaper plugin, Player player) {
        ItemStack itemLeft = new ItemStack(Material.PAPER);
        ItemMeta meta = itemLeft.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("Kaitkan Email:");
            itemLeft.setItemMeta(meta);
        }

        new AnvilGUI.Builder()
                .plugin(plugin)
                .title("Kaitkan Email")
                .text("contoh@email.com")
                .itemLeft(itemLeft)
                .onClick((slot, stateSnapshot) -> {
                    if (slot != AnvilGUI.Slot.OUTPUT) {
                        return Collections.emptyList();
                    }

                    String email = stateSnapshot.getText();
                    if (email == null || email.trim().isEmpty() || email.equalsIgnoreCase("contoh@email.com") || !email.contains("@")) {
                        player.sendMessage("§cFormat email tidak valid!");
                        return Collections.singletonList(AnvilGUI.ResponseAction.replaceInputText("contoh@email.com"));
                    }

                    submitEmailToVelocity(plugin, player, email);
                    return Arrays.asList(AnvilGUI.ResponseAction.close());
                })
                .onClose(stateSnapshot -> {
                    // Closed means skipped, send empty email
                    submitEmailToVelocity(plugin, player, "");
                })
                .open(player);
    }

    private static void submitEmailToVelocity(NaturalAuthPaper plugin, Player player, String email) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            dos.writeByte(AuthBridgeProtocol.PACKET_SUBMIT_EMAIL);
            dos.writeUTF(player.getUniqueId().toString());
            dos.writeUTF(email);

            player.sendPluginMessage(plugin, AuthBridgeProtocol.FULL_CHANNEL, baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to send PACKET_SUBMIT_EMAIL to Velocity!");
            e.printStackTrace();
        }
    }
}
