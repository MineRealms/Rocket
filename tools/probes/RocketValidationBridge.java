package rosetta.client;

public final class RocketValidationBridge {
    public static void init() {
        if (Boolean.getBoolean("rocket.validation.bridge")) {
            com.rosetta.remotedebugbridge.net.RemoteBridge.start(null);
        }
        if (Boolean.getBoolean("rocket.validation.hide")) {
            net.minecraft.client.Minecraft c = net.minecraft.client.Minecraft.getInstance();
            c.execute(() -> {
                c.options.pauseOnLostFocus = false;
                org.lwjgl.glfw.GLFW.glfwHideWindow(c.getWindow().getWindow());
            });
        }
    }
}
