package renderer;

import java.util.List;

/** Infinite procedural ocean surface shared by the CPU and Metal path tracers. */
final class OceanWater {
    static final double WATER_LEVEL = 0.30;
    private static final double SEABED_LEVEL = -2.45;
    private static final double SEABED_EXTENT = 100_000.0;

    private static volatile double waveAmplitude = 1.0;
    private static volatile double waveLength = 1.0;
    private static final Wave[] WAVES = {
            new Wave(1.00, 0.40, 0.12, 3.8, 0.40),
            new Wave(0.62, -0.78, 0.085, 2.1, 2.10),
            new Wave(-0.35, 0.94, 0.050, 1.15, 4.20),
            new Wave(0.15, 0.99, 0.025, 0.72, 1.30)
    };

    private static final Material WATER = createWaterMaterial();
    private static final Material SEABED = createSeabedMaterial();

    private OceanWater() { }

    static void setWaveControls(double amplitude, double wavelength) {
        waveAmplitude = Math.max(0.0, Math.min(3.0, amplitude));
        waveLength = Math.max(0.25, Math.min(3.0, wavelength));
    }

    static double waveAmplitude() { return waveAmplitude; }
    static double waveLength() { return waveLength; }

    static Material waterMaterial() { return WATER; }

    /** Finds the nearest intersection with the unbounded wave surface. */
    static boolean intersect(Ray ray, Hit hit) {
        double dy = ray.direction.y;
        if (Math.abs(dy) < 1e-7) return false;

        // The mean sea plane is a good initial estimate for Newton iteration.
        double t = (WATER_LEVEL - ray.origin.y) / dy;
        if (!Double.isFinite(t) || t <= 1e-5 || t >= hit.t) return false;

        Sample sample = null;
        for (int iteration = 0; iteration < 10; iteration++) {
            Vec3 point = ray.at(t);
            sample = evaluate(point.x, point.z);
            double error = point.y - sample.height;
            double derivative = dy - sample.dHeightDx * ray.direction.x - sample.dHeightDz * ray.direction.z;
            if (!Double.isFinite(derivative) || Math.abs(derivative) < 1e-7) return false;
            double delta = error / derivative;
            if (!Double.isFinite(delta)) return false;
            // Avoid a large Newton step jumping to a distant wave crest.
            double maxStep = Math.max(2.0, Math.abs(t) * 0.45);
            delta = Math.max(-maxStep, Math.min(maxStep, delta));
            t -= delta;
            if (t <= 1e-5 || t >= hit.t || !Double.isFinite(t)) return false;
            if (Math.abs(delta) < 1e-6) break;
        }

        Vec3 point = ray.at(t);
        sample = evaluate(point.x, point.z);
        if (Math.abs(point.y - sample.height) > 0.0025) return false;

        Vec3 normal = new Vec3(-sample.dHeightDx, 1.0, -sample.dHeightDz).normalized();
        if (normal.dot(ray.direction) > 0.0) normal = normal.negate();
        hit.t = t;
        hit.point = point;
        hit.normal = normal;
        hit.material = WATER;
        hit.hasUv = false;
        hit.texU = 0;
        hit.texV = 0;
        hit.baryU = 0;
        hit.baryV = 0;
        return true;
    }

    /** Cheap displaced-normal approximation used by the CPU raster preview. */
    static Vec3 normalAt(double x, double z) {
        Sample sample = evaluate(x, z);
        return new Vec3(-sample.dHeightDx, 1.0, -sample.dHeightDz).normalized();
    }

    /** Adds a very large underwater floor so refracted paths still have a scene to hit. */
    static void appendSeabedTo(List<Triangle> triangles) {
        double s = SEABED_EXTENT;
        Vec3 a = new Vec3(-s, SEABED_LEVEL, -s);
        Vec3 b = new Vec3(s, SEABED_LEVEL, -s);
        Vec3 c = new Vec3(s, SEABED_LEVEL, s);
        Vec3 d = new Vec3(-s, SEABED_LEVEL, s);
        Vec3 up = new Vec3(0, 1, 0);
        triangles.add(new Triangle(a, c, b, up, up, up, SEABED));
        triangles.add(new Triangle(a, d, c, up, up, up, SEABED));
    }

    private static Sample evaluate(double x, double z) {
        double height = WATER_LEVEL;
        double dx = 0.0;
        double dz = 0.0;
        double ampScale = waveAmplitude;
        double lengthScale = waveLength;
        for (Wave wave : WAVES) {
            double len = Math.hypot(wave.dx, wave.dz);
            double dirX = wave.dx / len;
            double dirZ = wave.dz / len;
            double amplitude = wave.amplitude * ampScale;
            double k = 2.0 * Math.PI / (wave.wavelength * lengthScale);
            double phase = k * (dirX * x + dirZ * z) + wave.phase;
            double cosine = Math.cos(phase);
            height += amplitude * Math.sin(phase);
            dx += amplitude * k * cosine * dirX;
            dz += amplitude * k * cosine * dirZ;
        }
        return new Sample(height, dx, dz);
    }

    private static Material createWaterMaterial() {
        Material water = new Material();
        water.water = true;
        water.diffuse = new Vec3(0.018, 0.075, 0.105);
        water.specular = new Vec3(0.98, 0.99, 1.0);
        water.roughness = 0.075;
        return water;
    }

    private static Material createSeabedMaterial() {
        Material seabed = new Material();
        seabed.diffuse = new Vec3(0.018, 0.035, 0.043);
        seabed.specular = new Vec3(0.025, 0.03, 0.035);
        seabed.roughness = 0.98;
        return seabed;
    }

    private record Wave(double dx, double dz, double amplitude, double wavelength, double phase) { }
    private record Sample(double height, double dHeightDx, double dHeightDz) { }
}
