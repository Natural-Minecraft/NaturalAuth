package id.naturalsmp.naturalauth.velocity;

import com.velocitypowered.api.proxy.Player;

/**
 * Abstraction for sending Bedrock (Floodgate/Cumulus) forms.
 * This interface intentionally has NO Cumulus imports so that
 * VelocityListener can hold a reference to it without causing
 * Velocity's ASM event scanner to try loading Cumulus classes.
 */
public interface BedrockAuthProvider {
    void openAuthForm(Player player, boolean registered);
    void openRulesForm(Player player);
    void openEmailLinkForm(Player player);
    void reopenAuthFormDelayed(Player player, boolean registered);
    void reopenRulesFormDelayed(Player player);
    void reopenEmailLinkFormDelayed(Player player);
}
