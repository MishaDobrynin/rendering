package renderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

final class RasterRenderer {
    private RasterRenderer() { }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height) {
        return render(scene, camera, width, height, 1);
    }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height, int stride) {
        return render(scene, camera, width, height, stride, 0.62);
    }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height, int stride, double cloudCoverage) {
        return render(scene, camera, width, height, stride, cloudCoverage, new LightingSettings(12.0, 180.0, 55.0));
    }

    static BufferedImage render(Scene scene, CameraSnapshot camera, int width, int height, int stride,
                                double cloudCoverage, LightingSettings lighting) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        double[] depth = new double[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                Ray skyRay = camera.ray(x + 0.5, y + 0.5, width, height);
                Vec3 background = lighting.sky(skyRay.direction);
                pixels[y * width + x] = color(CloudVolume.previewSky(skyRay, background, cloudCoverage, lighting));
            }
        }
        java.util.Arrays.fill(depth, Double.POSITIVE_INFINITY);

        Vec3 lightDir = lighting.sunDirection();
        Vec3 viewDir = camera.forward().negate();

        // Infinite sea preview: intersect each view ray with the mean water plane.
        // Final CPU/Metal renders solve the full procedural wave surface analytically.
        if (scene.ocean()) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    Ray ray = camera.ray(x + 0.5, y + 0.5, width, height);
                    if (Math.abs(ray.direction.y) < 1e-8) continue;
                    double t = (OceanWater.WATER_LEVEL - ray.origin.y) / ray.direction.y;
                    if (t <= 1e-5) continue;
                    Vec3 point = ray.at(t);
                    Vec3 normal = OceanWater.normalAt(point.x, point.z);
                    Vec3 view = ray.direction.negate();
                    double fresnel = Math.pow(1.0 - Math.max(0.0, normal.dot(view)), 5.0);
                    double sunGlint = Math.pow(Math.max(0.0, normal.dot(lightDir)), 96.0) * 1.25;
                    Vec3 deep = new Vec3(0.012, 0.065, 0.095);
                    Vec3 reflectedSky = lighting.sky(new Vec3(-ray.direction.x, Math.max(0.0, ray.direction.y), -ray.direction.z));
                    Vec3 waterColor = Vec3.lerp(deep, reflectedSky, 0.16 + 0.78 * fresnel)
                            .add(new Vec3(sunGlint, sunGlint * 0.94, sunGlint * 0.82));
                    int index = y * width + x;
                    depth[index] = t;
                    pixels[index] = color(waterColor);
                }
            }
        }

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
            double diffuse = 0.20 + 0.80 * Math.max(0, n.dot(lightDir));
            double rim = Math.pow(Math.max(0, 1.0 - Math.abs(n.dot(camera.forward()))), 3) * 0.10;
            Vec3 base = tri.material.diffuse;
            int flatRgb = color(base.mul(diffuse + rim).add(new Vec3(0.045, 0.05, 0.06)).add(tri.material.emission));

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

                    if (tri.material.diffuseTexture != null && tri.hasUv) {
                        double a0 = (w0 / p0.z()) / invZ;
                        double a1 = (w1 / p1.z()) / invZ;
                        double a2 = (w2 / p2.z()) / invZ;
                        double u = tri.ta.u() * a0 + tri.tb.u() * a1 + tri.tc.u() * a2;
                        double v = tri.ta.v() * a0 + tri.tb.v() * a1 + tri.tc.v() * a2;
                        Vec3 texel = tri.material.diffuseAt(u, v);
                        pixels[idx] = color(texel.mul(diffuse + rim).add(new Vec3(0.045, 0.05, 0.06)).add(tri.material.emission));
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
