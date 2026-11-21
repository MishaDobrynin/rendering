package renderer;

import java.util.List;

/** Explicit triangulated Gerstner-wave water geometry with analytic vertex normals. */
final class OceanWater {
    private static volatile int CELLS = 190;
    private static final double HALF_EXTENT = 15.0;
    private static final double WATER_LEVEL = 0.30;

    private static volatile double waveAmplitude = 1.0;
    private static volatile double waveLength = 1.0;
    private static final Wave[] WAVES = {
            new Wave(1.00, 0.40, 0.12, 3.8, 0.70, 0.40),
            new Wave(0.62, -0.78, 0.085, 2.1, 0.63, 2.10),
            new Wave(-0.35, 0.94, 0.050, 1.15, 0.48, 4.20),
            new Wave(0.15, 0.99, 0.025, 0.72, 0.42, 1.30)
    };

    private OceanWater() { }

    static void setWaveControls(double amplitude, double wavelength) {
        waveAmplitude = Math.max(0.0, Math.min(3.0, amplitude));
        waveLength = Math.max(0.25, Math.min(3.0, wavelength));
    }

    static void setGridResolution(int cells) { CELLS = Math.max(24, Math.min(190, cells)); }

    static void appendTo(List<Triangle> triangles) {
        Material water = new Material();
        water.water = true;
        water.diffuse = new Vec3(0.018, 0.075, 0.105);
        water.specular = new Vec3(0.98, 0.99, 1.0);
        water.roughness = 0.075;

        int side = CELLS + 1;
        Vec3[] positions = new Vec3[side * side];
        Vec3[] normals = new Vec3[side * side];
        double step = (HALF_EXTENT * 2.0) / CELLS;
        for (int z = 0; z <= CELLS; z++) {
            for (int x = 0; x <= CELLS; x++) {
                double px = -HALF_EXTENT + x * step;
                double pz = -HALF_EXTENT + z * step;
                WavePoint point = evaluate(px, pz);
                int index = z * side + x;
                positions[index] = new Vec3(point.x, WATER_LEVEL + point.y, point.z);
                normals[index] = point.normal;
            }
        }
        for (int z = 0; z < CELLS; z++) {
            for (int x = 0; x < CELLS; x++) {
                int i = z * side + x;
                int right = i + 1;
                int down = i + side;
                int diagonal = down + 1;
                Vec3 a = positions[i], b = positions[right], c = positions[diagonal], d = positions[down];
                Vec3 na = normals[i], nb = normals[right], nc = normals[diagonal], nd = normals[down];
                triangles.add(new Triangle(a, c, b, na, nc, nb, water));
                triangles.add(new Triangle(a, d, c, na, nd, nc, water));
            }
        }

        // A dark, rough seabed gives refracted rays an underwater subject instead of a flat color.
        Material seabed = new Material();
        seabed.diffuse = new Vec3(0.018, 0.035, 0.043);
        seabed.specular = new Vec3(0.025, 0.03, 0.035);
        seabed.roughness = 0.98;
        double s = HALF_EXTENT + 2.0;
        double y = -2.45;
        Vec3 a = new Vec3(-s, y, -s), b = new Vec3(s, y, -s);
        Vec3 c = new Vec3(s, y, s), d = new Vec3(-s, y, s);
        Vec3 up = new Vec3(0, 1, 0);
        triangles.add(new Triangle(a, c, b, up, up, up, seabed));
        triangles.add(new Triangle(a, d, c, up, up, up, seabed));
    }

    private static WavePoint evaluate(double x, double z) {
        double px = x, py = 0, pz = z;
        double dXdx = 1, dYdx = 0, dZdx = 0;
        double dXdz = 0, dYdz = 0, dZdz = 1;
        for (Wave source : WAVES) {
            Wave wave = new Wave(source.dx, source.dz, source.amplitude * waveAmplitude,
                    source.wavelength * waveLength, source.steepness, source.phase);
            double len = Math.hypot(wave.dx, wave.dz);
            double dx = wave.dx / len, dz = wave.dz / len;
            double k = 2.0 * Math.PI / wave.wavelength;
            double phase = k * (dx * x + dz * z) + wave.phase;
            double sin = Math.sin(phase), cos = Math.cos(phase);
            double qa = wave.steepness * wave.amplitude;
            px += qa * dx * cos;
            py += wave.amplitude * sin;
            pz += qa * dz * cos;

            dXdx -= qa * k * dx * dx * sin;
            dYdx += wave.amplitude * k * dx * cos;
            dZdx -= qa * k * dz * dx * sin;
            dXdz -= qa * k * dx * dz * sin;
            dYdz += wave.amplitude * k * dz * cos;
            dZdz -= qa * k * dz * dz * sin;
        }
        Vec3 tangentX = new Vec3(dXdx, dYdx, dZdx);
        Vec3 tangentZ = new Vec3(dXdz, dYdz, dZdz);
        Vec3 normal = tangentZ.cross(tangentX).normalized();
        if (normal.y < 0) normal = normal.negate();
        return new WavePoint(px, py, pz, normal);
    }

    private record Wave(double dx, double dz, double amplitude, double wavelength, double steepness, double phase) { }
    private record WavePoint(double x, double y, double z, Vec3 normal) { }
}
