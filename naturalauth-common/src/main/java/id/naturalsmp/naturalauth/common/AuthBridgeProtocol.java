package id.naturalsmp.naturalauth.common;

public class AuthBridgeProtocol {
    public static final String CHANNEL_NAMESPACE = "naturalauth";
    public static final String CHANNEL_NAME = "bridge";
    public static final String FULL_CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_NAME;

    // Packet IDs (Velocity → Paper)
    public static final byte PACKET_OPEN_GUI      = 1; // Velocity → Paper: open login/register AnvilGUI (UUID, type, prompt)
    public static final byte PACKET_AUTH_STATUS   = 3; // Velocity → Paper: auth result (UUID, success: boolean, message)
    public static final byte PACKET_OPEN_RULES    = 5; // Velocity → Paper: show server rules chat prompt (UUID)
    public static final byte PACKET_OPEN_EMAIL_LINK = 9; // Velocity → Paper: open email linking GUI (UUID)

    // Packet IDs (Paper → Velocity)
    public static final byte PACKET_SUBMIT_PASSWORD  = 2; // Paper → Velocity: player submitted password (UUID, password)
    public static final byte PACKET_PLAYER_READY     = 4; // Paper → Velocity: player joined lobby and is ready (UUID)
    public static final byte PACKET_RULES_ACCEPTED   = 6; // Paper → Velocity: player accepted the server rules (UUID)
    public static final byte PACKET_RULES_DECLINED   = 7; // Paper → Velocity: player declined the server rules (UUID)
    public static final byte PACKET_SUBMIT_EMAIL     = 8; // Paper → Velocity: player submitted email (UUID, email)
}
