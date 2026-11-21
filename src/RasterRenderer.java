package renderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

final class RasterRenderer {
    private RasterRenderer() { }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height) {
        return render(scene, camera, width, height, 1);
    }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height, int stride) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double[] depth = new double[width * height];
        for (int y = 0; y < height; y++) {
            double t = (double) y / Math.max(1, height - 1);
            int sky = color(new Vec3(0.055, 0.075, 0.10).mul(1 - t).add(new Vec3(0.20, 0.23, 0.25).mul(t)));
            java.util.Arrays.fill(pixels, y * width, (y + 1) * width, sky);
        }
        java.util.Arrays.fill(depth, Double.POSITIVE_INFINITY);

        Vec3 lightDir = new Vec3(-0.45, 0.82, 0.35).normalized();
        Vec3 viewDir = camera.forward().negate();
        for (Triangle tri : scene.triangles()) {
            Projected p0 = camera.project(tri.a, width, height);
            Projected p1 = camera.project(tri.b, width, height);
            Projected p2 = camera.project(tri.c, width, height);
            if (p0 == null || p1 == null || p2 == null) continue;
            double area = edge(p0.x(), p0.y(), p1.x(), p1.y(), p2.x(), p2.y());
            if (Math.abs(area) < 1e-8) continue;
            int minX = Math.max(0, (int) Math.floor(Math.min(p0.x(), Math.min(p1.x(), p2.x()))));
            int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(p0.x(), Math.max(p1.x(), p2.x()))));
            int minY = Math.max(0, (int) Math.floor(Math.min(p0.y(), Math.min(p1.y(), p2.y()))));
            int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(p0.y(), Math.max(p1.y(), p2.y()))));
            Vec3 n = tri.faceNormal;
            double diffuse = 0.24 + 0.76 * Math.max(0, n.dot(lightDir));
            double rim = Math.pow(Math.max(0, 1.0 - Math.abs(n.dot(camera.forward()))), 3) * 0.10;
            Vec3 base = tri.material.diffuse;
            int flatRgb = color(base.mul(diffuse + rim).add(new Vec3(0.035, 0.04, 0.045)).add(tri.material.emission));

            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    double px = x + 0.5, py = y + 0.5;
                    double w0 = edge(p1.x(), p1.y(), p2.x(), p2.y(), px, py) / area;
                    double w1 = edge(p2.x(), p2.y(), p0.x(), p0.y(), px, py) / area;
                    double w2 = 1.0 - w0 - w1;
                    if (w0 < -1e-8 || w1 < -1e-8 || w2 < -1e-8) continue;
                    double invZ = w0 / p0.z() + w1 / p1.z() + w2 / p2.z();
                    if (invZ <= 0) continue;
                    double z = 1.0 / invZ;
                    int idx = y * width + x;
                    if (z >= depth[idx]) continue;
                    depth[idx] = z;

                    if (tri.material.water) {
                        double a0 = (w0 / p0.z()) / invZ;
                        double a1 = (w1 / p1.z()) / invZ;
                        double a2 = (w2 / p2.z()) / invZ;
                        Vec3 normal = tri.na.mul(a0).add(tri.nb.mul(a1)).add(tri.nc.mul(a2)).normalized();
                        double fresnel = Math.pow(1.0 - Math.max(0, normal.dot(viewDir)), 5.0);
                        Vec3 deep = new Vec3(0.012, 0.065, 0.095);
                        Vec3 reflection = new Vec3(0.19, 0.27, 0.34);
                        double glint = Math.pow(Math.max(0, normal.dot(lightDir)), 110.0) * 1.8;
                        Vec3 waterColor = Vec3.lerp(deep, reflection, 0.16 + 0.78 * fresnel)
                                .add(new Vec3(glint, glint * 0.94, glint * 0.82));
                        pixels[idx] = color(waterColor);
                    } else if (tri.material.diffuseTexture != null && tri.hasUv) {
                        double a0 = (w0 / p0.z()) / invZ;
                        double a1 = (w1 / p1.z()) / invZ;
                        double a2 = (w2 / p2.z()) / invZ;
                        double u = tri.ta.u() * a0 + tri.tb.u() * a1 + tri.tc.u() * a2;
                        double v = tri.ta.v() * a0 + tri.tb.v() * a1 + tri.tc.v() * a2;
                        Vec3 texel = tri.material.diffuseAt(u, v);
                        pixels[idx] = color(texel.mul(diffuse + rim).add(new Vec3(0.035, 0.04, 0.045)).add(tri.material.emission));
                    } else {
                        pixels[idx] = flatRgb;
                    }
                }
            }
        }
        return image;
    }

    private static double edge(double ax, double ay, double bx, double by, double px, double py) {
        return (px - ax) * (by - ay) - (py - ay) * (bx - ax);
    }

    static int color(Vec3 linear) {
        double r = tone(linear.x), g = tone(linear.y), b = tone(linear.z);
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    private static double tone(double v) {
        v = Math.max(0, v);
        double mapped = v / (1.0 + v);
        return Math.pow(mapped, 1.0 / 2.2);
    }
}
