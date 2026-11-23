package rosetta.client;

public final class RocketValidationBridge {
    public static void init() {
        if (Boolean.getBoolean("rocket.validation.bridge")) {
            com.rosetta.remotedebugbridge.net.RemoteBridge.start(null);
        }
    }
}
