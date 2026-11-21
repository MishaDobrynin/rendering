package renderer;

/** Immutable outdoor-light configuration. Sun direction is user controlled; clock time shapes the atmospheric palette. */
final class LightingSettings {
    private final double timeOfDay;
    private final double sunAzimuthDegrees;
    private final double sunElevationDegrees;
    private final Vec3 sunDirection;
    private final Vec3 sunColor;
    private final double sunIntensity;

    LightingSettings(double timeOfDay, double sunAzimuthDegrees, double sunElevationDegrees) {
        this.timeOfDay = clamp(timeOfDay, 0.0, 23.999);
        this.sunAzimuthDegrees = wrapDegrees(sunAzimuthDegrees);
        this.sunElevationDegrees = clamp(sunElevationDegrees, -12.0, 90.0);
        double az = Math.toRadians(this.sunAzimuthDegrees);
        double el = Math.toRadians(this.sunElevationDegrees);
        this.sunDirection = new Vec3(Math.cos(el) * Math.sin(az), Math.sin(el), Math.cos(el) * Math.cos(az)).normalized();

        double daylight = smooth(5.0, 8.4, this.timeOfDay) * (1.0 - smooth(16.1, 20.2, this.timeOfDay));
        double sunrise = Math.exp(-sq((this.timeOfDay - 6.2) / 1.65));
        double sunset = Math.exp(-sq((this.timeOfDay - 18.2) / 1.7));
        double golden = clamp(Math.max(sunrise, sunset), 0.0, 1.0);
        Vec3 noonSun = new Vec3(1.0, 0.96, 0.87);
        Vec3 goldenSun = new Vec3(1.0, 0.49, 0.22);
        Vec3 nightSun = new Vec3(0.34, 0.43, 0.68);
        this.sunColor = Vec3.lerp(Vec3.lerp(nightSun, noonSun, daylight), goldenSun, golden * 0.82);
        double horizonFactor = smooth(-8.0, 3.0, this.sunElevationDegrees);
        this.sunIntensity = (0.08 + 3.0 * daylight) * horizonFactor;
    }

    Vec3 sunDirection() { return sunDirection; }
    Vec3 sunColor() { return sunColor; }
    double sunIntensity() { return sunIntensity; }
    double timeOfDay() { return timeOfDay; }
    double sunAzimuthDegrees() { return sunAzimuthDegrees; }
    double sunElevationDegrees() { return sunElevationDegrees; }

    Vec3 sky(Vec3 direction) {
        double y = clamp(direction.normalized().y, -0.08, 1.0);
        double vertical = smooth(-0.08, 0.72, y);
        double daylight = smooth(5.0, 8.4, timeOfDay) * (1.0 - smooth(16.1, 20.2, timeOfDay));
        double sunrise = Math.exp(-sq((timeOfDay - 6.2) / 1.55));
        double sunset = Math.exp(-sq((timeOfDay - 18.2) / 1.6));
        double twilight = clamp(Math.max(sunrise, sunset), 0.0, 1.0);

        Vec3 dayHorizon = new Vec3(0.58, 0.75, 0.94);
        Vec3 dayZenith = new Vec3(0.095, 0.29, 0.67);
        Vec3 duskHorizon = new Vec3(1.15, 0.34, 0.105);
        Vec3 duskZenith = new Vec3(0.17, 0.20, 0.39);
        Vec3 nightHorizon = new Vec3(0.012, 0.019, 0.040);
        Vec3 nightZenith = new Vec3(0.0025, 0.006, 0.022);

        Vec3 day = Vec3.lerp(dayHorizon, dayZenith, vertical);
        Vec3 dusk = Vec3.lerp(duskHorizon, duskZenith, vertical);
        Vec3 night = Vec3.lerp(nightHorizon, nightZenith, vertical);
        Vec3 result = Vec3.lerp(night, day, daylight);
        result = Vec3.lerp(result, dusk, twilight * 0.88);

        double sunDot = direction.normalized().dot(sunDirection);
        double halo = Math.pow(Math.max(0.0, sunDot), 24.0) * (0.16 + 0.55 * twilight);
        double disc = sunDot > 0.99945 ? Math.pow((sunDot - 0.99945) / 0.00055, 2.0) * (1.2 + 5.0 * daylight) : 0.0;
        result = result.add(sunColor.mul(halo + disc));
        return result;
    }

    private static double smooth(double a, double b, double x) {
        double t = clamp((x - a) / (b - a), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
    private static double sq(double x) { return x * x; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double wrapDegrees(double v) { double r = v % 360.0; return r < 0 ? r + 360.0 : r; }
}
