package com.example.examplemod.client.render;

import com.example.examplemod.client.flight.ClientFlightState;
import com.example.examplemod.compat.immptl.ImmersivePortalsBackend;
import com.example.examplemod.flight.FlightMath;
import net.minecraft.client.Minecraft;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Oculus remains optional. Its public uniform interfaces are resolved only when its mixins run. */
public final class ShaderpackBridge {
    private static final Method PACK_ACTIVE = findActiveMethod();
    private static int uniformRegistrations;

    private ShaderpackBridge() {}

    private static Method findActiveMethod() {
        try { return Class.forName("net.irisshaders.iris.Iris").getMethod("isPackInUseQuick"); }
        catch (ReflectiveOperationException | LinkageError absent) { return null; }
    }

    public static boolean isActive() {
        if (PACK_ACTIVE == null) return false;
        try { return (boolean) PACK_ACTIVE.invoke(null); }
        catch (ReflectiveOperationException error) { return false; }
    }

    public static int uniformRegistrations() { return uniformRegistrations; }

    public static double altitude() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return 0;
        return FlightMath.altitude(client.gameRenderer.getMainCamera().getPosition().y,
                client.level.dimension().equals(ImmersivePortalsBackend.SPACE_DIMENSION),
                ClientFlightState.transitionAltitude());
    }

    public static void addUniforms(Object holder) {
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.gl.uniform.UniformHolder");
            Class<?> frequency = Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency");
            Object perFrame = frequency.getField("PER_FRAME").get(null);
            Method scalar = type.getMethod("uniform1f", frequency, String.class, DoubleSupplier.class);
            Method integer = type.getMethod("uniform1i", frequency, String.class, IntSupplier.class);
            Method vector = type.getMethod("uniform3f", frequency, String.class, Supplier.class);
            scalar.invoke(holder, perFrame, "rocketAltitude", (DoubleSupplier) ShaderpackBridge::altitude);
            scalar.invoke(holder, perFrame, "rocketTransitionAltitude", (DoubleSupplier) ClientFlightState::transitionAltitude);
            integer.invoke(holder, perFrame, "rocketWorldTime", (IntSupplier) () -> {
                var clock = AtmosphereRenderer.visualLevel();
                return clock == null ? 6000 : (int) Math.floorMod(clock.getDayTime(), 24000L);
            });
            integer.invoke(holder, perFrame, "rocketMoonPhase", (IntSupplier) () -> {
                var clock = AtmosphereRenderer.visualLevel();
                return clock == null ? 0 : clock.getMoonPhase();
            });
            vector.invoke(holder, perFrame, "rocketMapInfo", (Supplier<Vector3f>) OrbitalTerrainRenderer::mapInfo);
            uniformRegistrations++;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Oculus uniform API does not match the installed ROCKET profile", error);
        }
    }

    public static void addSamplers(Object holder) {
        try {
            Class<?> type = Class.forName("net.irisshaders.iris.gl.sampler.SamplerHolder");
            Method dynamic = type.getMethod("addDynamicSampler", IntSupplier.class, String[].class);
            dynamic.invoke(holder, (IntSupplier) OrbitalTerrainRenderer::terrainTexture, new String[]{"rocketTerrain"});
            String[] names = {"Top", "Bottom", "North", "South", "East", "West"};
            for (int face = 0; face < names.length; face++) {
                final int index = face;
                dynamic.invoke(holder, (IntSupplier) () -> OrbitalTerrainRenderer.earthTexture(index),
                        new String[]{"rocketEarth" + names[face]});
            }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Oculus sampler API does not match the installed ROCKET profile", error);
        }
    }
}
