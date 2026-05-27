package id.naturalsmp.naturalauth.common;

public class AuthBridgeProtocol {
    public static final String CHANNEL_NAMESPACE = "naturalauth";
    public static final String CHANNEL_NAME = "bridge";
    public static final String FULL_CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_NAME;

    // Packet IDs
    public static final byte PACKET_OPEN_GUI = 1;      // Velocity -> Paper (UUID, Type: LOGIN/REGISTER, message)
    public static final byte PACKET_SUBMIT_PASSWORD = 2; // Paper -> Velocity (UUID, password)
    public static final byte PACKET_AUTH_STATUS = 3;    // Velocity -> Paper (UUID, success: boolean, message)
    public static final byte PACKET_PLAYER_READY = 4;   // Paper -> Velocity (UUID) - sent when player joins lobby server and is ready
}
