package renderer;

/** Procedural single-layer volumetric cloud field. Final renders ray-march this density;
 * raster previews use a cheap directional 2-D approximation. */
final class CloudVolume {
    private static final double BASE_Y = 5.2;
    private static final double TOP_Y = 9.2;
    private static final int MARCH_STEPS = 16;

    private CloudVolume() { }

    static Vec3 march(Ray ray, Vec3 background, double coverage, long seed) {
        return march(ray, background, coverage, seed, new LightingSettings(12.0, 180.0, 55.0));
    }

    static Vec3 march(Ray ray, Vec3 background, double coverage, long seed, LightingSettings lighting) {
        Vec3 sunDirection = lighting.sunDirection();
        Vec3 sunLight = lighting.sunColor().mul(2.3 * Math.max(0.08, lighting.sunIntensity() / 3.0));
        if (coverage <= 0.001 || ray.direction.y <= 0.015) return background;
        double t0 = (BASE_Y - ray.origin.y) / ray.direction.y;
        double t1 = (TOP_Y - ray.origin.y) / ray.direction.y;
        if (t1 <= 0) return background;
        double start = Math.max(0.0, t0);
        double end = Math.max(start, t1);
        if (end <= start) return background;
        double step = (end - start) / MARCH_STEPS;
        double transmittance = 1.0;
        Vec3 accumulated = new Vec3(0, 0, 0);
        double jitter = unitHash(seed) * 0.8 + 0.1;
        for (int i = 0; i < MARCH_STEPS && transmittance > 0.025; i++) {
            double t = start + (i + jitter) * step;
            Vec3 p = ray.at(t);
            double density = density(p, coverage);
            if (density <= 0) continue;
            double sunFacing = 0.5 + 0.5 * Math.max(0, ray.direction.dot(sunDirection));
            double powder = 1.0 - Math.exp(-density * 1.6);
            Vec3 cloudLighting = Vec3.lerp(new Vec3(0.24, 0.29, 0.37), new Vec3(0.88, 0.91, 0.94), sunFacing)
                    .add(sunLight.mul(0.12 + 0.24 * sunFacing));
            double alpha = 1.0 - Math.exp(-density * step * 0.34);
            accumulated = accumulated.add(cloudLighting.mul(transmittance * alpha * (0.78 + 0.22 * powder)));
            transmittance *= (1.0 - alpha);
        }
        return accumulated.add(background.mul(transmittance));
    }

    /** Low-cost screen-space cloud approximation for interactive/raster previews. */
    static Vec3 previewSky(Ray ray, Vec3 background, double coverage) {
        return previewSky(ray, background, coverage, new LightingSettings(12.0, 180.0, 55.0));
    }

    static Vec3 previewSky(Ray ray, Vec3 background, double coverage, LightingSettings lighting) {
        Vec3 sunDirection = lighting.sunDirection();
        if (coverage <= 0.001 || ray.direction.y <= 0.02) return background;
        double t = (7.0 - ray.origin.y) / ray.direction.y;
        if (t <= 0) return background;
        Vec3 p = ray.at(t).mul(0.105);
        double broad = fbm2(p.x, p.z);
        double detail = noise2(p.x * 3.1 + 11.0, p.z * 3.1 - 7.0);
        double shape = broad * 0.78 + detail * 0.22;
        double threshold = 0.76 - coverage * 0.43;
        double cloud = smooth(threshold, threshold + 0.16, shape);
        if (cloud <= 0) return background;
        double light = 0.70 + 0.30 * Math.max(0, ray.direction.dot(sunDirection));
        Vec3 cloudColor = Vec3.lerp(new Vec3(0.12, 0.14, 0.21), lighting.sunColor().mul(0.9), Math.min(1.0, light));
        return Vec3.lerp(background, cloudColor, cloud * 0.88);
    }

    private static double density(Vec3 p, double coverage) {
        double x = p.x * 0.115 + 2.7;
        double y = p.y * 0.18 + 4.1;
        double z = p.z * 0.115 - 1.9;
        double broad = fbm3(x, y, z);
        double detail = noise3(x * 3.0 + 17.0, y * 3.0, z * 3.0 - 9.0);
        double shape = broad * 0.82 + detail * 0.18;
        double threshold = 0.78 - coverage * 0.47;
        double vertical = smooth(0.0, 0.18, (p.y - BASE_Y) / (TOP_Y - BASE_Y))
                * (1.0 - smooth(0.68, 1.0, (p.y - BASE_Y) / (TOP_Y - BASE_Y)) * 0.38);
        return smooth(threshold, threshold + 0.19, shape) * vertical * 1.35;
    }

    private static double fbm3(double x, double y, double z) {
        double sum = 0, amp = 0.60, norm = 0;
        for (int i = 0; i < 3; i++) {
            sum += noise3(x, y, z) * amp; norm += amp; amp *= 0.5;
            x *= 2.03; y *= 2.03; z *= 2.03;
        }
        return sum / norm;
    }

    private static double fbm2(double x, double z) {
        double sum = 0, amp = 0.58, norm = 0;
        for (int i = 0; i < 4; i++) {
            sum += noise2(x, z) * amp; norm += amp; amp *= 0.5;
            x *= 2.02; z *= 2.02;
        }
        return sum / norm;
    }

    private static double noise3(double x, double y, double z) {
        int ix = fastFloor(x), iy = fastFloor(y), iz = fastFloor(z);
        double fx = fade(x - ix), fy = fade(y - iy), fz = fade(z - iz);
        double a = lerp(lerp(hash(ix, iy, iz), hash(ix + 1, iy, iz), fx),
                lerp(hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz), fx), fy);
        double b = lerp(lerp(hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1), fx),
                lerp(hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1), fx), fy);
        return lerp(a, b, fz);
    }

    private static double noise2(double x, double z) {
        int ix = fastFloor(x), iz = fastFloor(z);
        double fx = fade(x - ix), fz = fade(z - iz);
        return lerp(lerp(hash(ix, 0, iz), hash(ix + 1, 0, iz), fx),
                lerp(hash(ix, 0, iz + 1), hash(ix + 1, 0, iz + 1), fx), fz);
    }

    private static double hash(int x, int y, int z) {
        long n = (long)x * 0x8DA6B343L ^ (long)y * 0xD8163841L ^ (long)z * 0xCB1AB31FL;
        n = (n ^ (n >>> 13)) * 0x85EBCA6BL;
        n = (n ^ (n >>> 16)) * 0xC2B2AE35L;
        n ^= n >>> 16;
        return (n & 0xFFFFFFL) / 16777215.0;
    }
    private static double unitHash(long n) {
        n ^= n >>> 33; n *= 0xff51afd7ed558ccdL; n ^= n >>> 33;
        return (n & 0xFFFFFFL) / 16777215.0;
    }
    private static int fastFloor(double v) { return (int)Math.floor(v); }
    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double smooth(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }
}
