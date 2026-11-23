package com.example.examplemod.flight;

public final class FlightMath {
    public static final double SPACE_ENTRY_HEIGHT = 64.0;

    private FlightMath() {}

    public static double altitude(double physicalY, boolean inSpace, double threshold) {
        return physicalY + (inSpace ? threshold - SPACE_ENTRY_HEIGHT : 0.0);
    }

    public static double mappedY(double physicalY, boolean toSpace, double threshold) {
        return physicalY + (toSpace ? SPACE_ENTRY_HEIGHT - threshold : threshold - SPACE_ENTRY_HEIGHT);
    }

    public static float smoothRange(double altitude, double start, double end) {
        if (!Double.isFinite(altitude) || !Double.isFinite(start) || !Double.isFinite(end) || end <= start) {
            throw new IllegalArgumentException("Invalid altitude range");
        }
        double t = Math.max(0, Math.min(1, (altitude - start) / (end - start)));
        return (float) (t * t * (3 - 2 * t));
    }

    public static float orbitBlend(double altitude, double threshold) {
        return smoothRange(altitude, threshold + 1024, threshold + 1872);
    }

    public static FlightPhase phase(double altitude, double verticalSpeed, boolean inSpace,
                                    boolean flying, double threshold) {
        if (inSpace) {
            if (verticalSpeed < -0.05 && altitude < threshold + 96) return FlightPhase.REENTRY;
            return altitude >= threshold + 1872 ? FlightPhase.SYNCHRONOUS_ORBIT : FlightPhase.NEAR_ORBIT;
        }
        if (!flying) return FlightPhase.GROUND;
        if (Math.abs(altitude - threshold) < 32) return FlightPhase.TRANSITION;
        if (verticalSpeed < -0.05) return FlightPhase.DESCENT;
        if (altitude >= threshold * 0.65) return FlightPhase.UPPER_ATMOSPHERE;
        if (altitude >= 128) return FlightPhase.LOWER_ATMOSPHERE;
        return FlightPhase.LIFTOFF;
    }
}
