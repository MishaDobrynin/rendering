package renderer;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

final class PathTracer {
    private static final int MAX_BOUNCES = 7;
    private static final int LIGHT_SAMPLES = 3;
    private static final double WATER_IOR = 1.333;
    private static final Vec3 WATER_ABSORPTION = new Vec3(0.42, 0.13, 0.045);

    private final Bvh bvh;
    private final boolean ocean;
    private final Light[] fillLights;
    private final double cloudCoverage;
    private final LightingSettings lighting;

    PathTracer(Scene scene, double cloudCoverage, LightingSettings lighting) {
        this.bvh = scene.bvh();
        this.ocean = scene.ocean();
        this.cloudCoverage = Math.max(0.0, Math.min(1.0, cloudCoverage));
        this.lighting = lighting;
        this.fillLights = new Light[] {
                new Light(new Vec3(-4.8, 7.2, 4.5), new Vec3(1.0, 0.88, 0.76), 120.0, 1.65),
                new Light(new Vec3(4.5, 4.0, -3.5), new Vec3(0.54, 0.70, 1.0), 58.0, 1.15)
        };
    }

    /**
     * Progressive-by-scanline render. The full-resolution frame is never stored in RAM:
     * a small band of rows is traced in parallel and immediately streamed to a PNG encoder.
     */
    RenderResult renderToPng(CameraSnapshot camera, int width, int height, int samplesPerPixel,
                             Path destination, RenderProgress progress, AtomicBoolean cancel) throws Exception {
        validateDimensions(width, height, samplesPerPixel);
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".partial");
        BufferedImage preview = makePreview(width, height);
        int previewWidth = preview.getWidth();
        int previewHeight = preview.getHeight();
        int bandSize = Math.max(1, Math.min(8, (4 * 1024 * 1024) / Math.max(1, width)));
        int rowsDone = 0;
        boolean completed = false;

        try (StreamingPngWriter writer = new StreamingPngWriter(temporary, width, height)) {
            for (int firstY = 0; firstY < height && !cancel.get(); firstY += bandSize) {
                int bandStart = firstY;
                int count = Math.min(bandSize, height - bandStart);
                int[][] band = new int[count][];
                IntStream.range(0, count).parallel().forEach(offset -> {
                    if (cancel.get()) return;
                    int y = bandStart + offset;
                    int[] row = new int[width];
                    for (int x = 0; x < width; x++) {
                        if (cancel.get()) break;
                        Vec3 sum = new Vec3(0, 0, 0);
                        long seed = 0x9E3779B97F4A7C15L ^ ((long) y << 32) ^ (x * 0xD1B54A32D192ED03L);
                        SplittableRandom random = new SplittableRandom(seed);
                        for (int sample = 0; sample < samplesPerPixel; sample++) {
                            double jx = random.nextDouble() - 0.5;
                            double jy = random.nextDouble() - 0.5;
                            sum = sum.add(trace(camera.ray(x + jx, y + jy, width, height), random));
                        }
                        row[x] = RasterRenderer.color(sum.div(samplesPerPixel));
                    }
                    band[offset] = row;
                });

                for (int offset = 0; offset < count; offset++) {
                    if (cancel.get()) break;
                    int[] row = band[offset];
                    if (row == null) break;
                    int y = firstY + offset;
                    writer.writeRow(row);
                    int py = (int) (((long) y * previewHeight) / height);
                    if (py < previewHeight && (y == 0 || py != (int) (((long) (y - 1) * previewHeight) / height))) {
                        int[] thumbRow = new int[previewWidth];
                        for (int px = 0; px < previewWidth; px++) {
                            int sx = Math.min(width - 1, (int) (((long) px * width) / previewWidth));
                            thumbRow[px] = row[sx];
                        }
                        preview.setRGB(0, py, previewWidth, 1, thumbRow, 0, previewWidth);
                    }
                    rowsDone++;
                }
                progress.update(rowsDone, height);
            }
            completed = rowsDone == height && !cancel.get();
        } catch (Throwable failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }

        if (completed) {
            try {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.deleteIfExists(temporary);
        }
        return new RenderResult(completed ? absolute : null, preview, !completed);
    }

    private static void validateDimensions(int width, int height, int spp) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Width and height must be positive integers.");
        if (width > (Integer.MAX_VALUE - 1) / 3) throw new IllegalArgumentException("The requested width exceeds Java PNG scanline addressing limits.");
        if (spp < 1 || spp > 4096) throw new IllegalArgumentException("Samples per pixel must be between 1 and 4096.");
        long rowBytes = (long) width * 7L + 1L;
        long rowBudget = Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4L);
        if (rowBytes > rowBudget) throw new IllegalArgumentException("The requested width needs more scanline memory than this JVM can safely reserve. Increase the JVM heap or reduce width.");
    }

    private static BufferedImage makePreview(int width, int height) {
        double scale = Math.min(1.0, Math.min(1600.0 / width, 1200.0 / height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    private Vec3 trace(Ray ray, SplittableRandom rng) {
        Vec3 radiance = new Vec3(0, 0, 0);
        Vec3 throughput = new Vec3(1, 1, 1);
        boolean insideWater = false;
        for (int bounce = 0; bounce < MAX_BOUNCES; bounce++) {
            Hit hit = new Hit();
            boolean found = bvh.intersect(ray, hit);
            if (ocean) found |= OceanWater.intersect(ray, hit);
            if (!found) {
                Vec3 environment = CloudVolume.march(ray, lighting.sky(ray.direction), cloudCoverage, rng.nextLong(), lighting);
                if (insideWater) environment = environment.mul(absorption(24.0));
                radiance = radiance.add(throughput.mul(environment));
                break;
            }
            if (insideWater) throughput = throughput.mul(absorption(hit.t));
            Material material = hit.material;
            radiance = radiance.add(throughput.mul(material.emission));
            Vec3 normal = hit.normal;
            Vec3 point = hit.point;
            Vec3 view = ray.direction.negate();

            if (material.water) {
                Vec3 waterDirect = directLighting(point, normal, view, material, material.diffuse, rng);
                radiance = radiance.add(throughput.mul(waterDirect));
                double nFrom = insideWater ? WATER_IOR : 1.0;
                double nTo = insideWater ? 1.0 : WATER_IOR;
                double eta = nFrom / nTo;
                double cosI = Math.max(0.0, Math.min(1.0, -ray.direction.dot(normal)));
                double sinT2 = eta * eta * Math.max(0.0, 1.0 - cosI * cosI);
                double r0 = (nFrom - nTo) / (nFrom + nTo);
                r0 *= r0;
                double fresnel = r0 + (1.0 - r0) * Math.pow(1.0 - cosI, 5.0);
                if (sinT2 >= 1.0 || rng.nextDouble() < fresnel) {
                    Vec3 reflected = reflect(ray.direction, normal).normalized();
                    Vec3 rough = cosineHemisphere(reflected, rng);
                    Vec3 next = Vec3.lerp(reflected, rough, material.roughness * 0.24).normalized();
                    ray = new Ray(point.add(normal.mul(0.0018)), next);
                } else {
                    double cosT = Math.sqrt(Math.max(0.0, 1.0 - sinT2));
                    Vec3 transmitted = ray.direction.mul(eta).add(normal.mul(eta * cosI - cosT)).normalized();
                    insideWater = !insideWater;
                    ray = new Ray(point.sub(normal.mul(0.0018)), transmitted);
                }
                continue;
            }

            Vec3 albedo = hit.hasUv ? material.diffuseAt(hit.texU, hit.texV) : material.diffuse;
            Vec3 direct = directLighting(point, normal, view, material, albedo, rng);
            radiance = radiance.add(throughput.mul(direct));

            double specularProbability = Math.max(0.0, Math.min(0.78, material.specular.maxComponent() * 0.68));
            double diffuseProbability = Math.max(0.22, 1.0 - specularProbability);
            Vec3 nextDirection;
            if (rng.nextDouble() < specularProbability) {
                Vec3 reflected = reflect(ray.direction, normal).normalized();
                Vec3 rough = cosineHemisphere(reflected, rng);
                nextDirection = Vec3.lerp(reflected, rough, material.roughness * 0.72).normalized();
                if (nextDirection.dot(normal) <= 0) nextDirection = reflected;
                throughput = throughput.mul(material.specular.clamp(0.03, 1.0)).div(Math.max(0.05, specularProbability));
            } else {
                nextDirection = cosineHemisphere(normal, rng);
                throughput = throughput.mul(albedo).div(diffuseProbability);
            }
            if (bounce >= 2) {
                double survive = Math.max(0.12, Math.min(0.88, throughput.maxComponent()));
                if (rng.nextDouble() > survive) break;
                throughput = throughput.div(survive);
            }
            ray = new Ray(point.add(normal.mul(0.0015)), nextDirection);
        }
        return radiance;
    }

    private Vec3 directLighting(Vec3 point, Vec3 normal, Vec3 view, Material material, Vec3 albedo, SplittableRandom rng) {
        Vec3 sum = new Vec3(0, 0, 0);
        for (Light light : fillLights) {
            for (int i = 0; i < LIGHT_SAMPLES; i++) {
                Vec3 lightPoint = light.sample(rng);
                Vec3 delta = lightPoint.sub(point);
                double distance2 = delta.lengthSquared();
                double distance = Math.sqrt(distance2);
                Vec3 l = delta.div(distance);
                double ndotl = Math.max(0.0, normal.dot(l));
                if (ndotl <= 0) continue;
                Hit blocker = new Hit();
                blocker.t = distance - 0.003;
                if (bvh.intersect(new Ray(point.add(normal.mul(0.0015)), l), blocker)) continue;
                double attenuation = light.power / Math.max(1.0, distance2);
                sum = sum.add(brdf(material, albedo, normal, view, l).mul(light.color).mul(ndotl * attenuation / LIGHT_SAMPLES));
            }
        }

        Vec3 sun = lighting.sunDirection();
        double sunNdotL = Math.max(0.0, normal.dot(sun));
        if (sunNdotL > 0.0 && lighting.sunIntensity() > 0.001) {
            Hit sunBlocker = new Hit();
            Ray shadow = new Ray(point.add(normal.mul(0.0018)), sun);
            if (!bvh.intersect(shadow, sunBlocker)) {
                Vec3 sunBrdf = brdf(material, albedo, normal, view, sun);
                sum = sum.add(sunBrdf.mul(lighting.sunColor()).mul(sunNdotL * lighting.sunIntensity()));
            }
        }
        return sum;
    }

    private static Vec3 brdf(Material material, Vec3 albedo, Vec3 normal, Vec3 view, Vec3 light) {
        double glossPower = Math.max(3.0, 2.0 / (material.roughness * material.roughness));
        Vec3 halfway = light.add(view).normalized();
        double specTerm = Math.pow(Math.max(0.0, normal.dot(halfway)), glossPower);
        return albedo.mul(1.0 / Math.PI).add(material.specular.mul(specTerm * 0.22));
    }

    private static Vec3 absorption(double distance) {
        return new Vec3(Math.exp(-WATER_ABSORPTION.x * distance), Math.exp(-WATER_ABSORPTION.y * distance), Math.exp(-WATER_ABSORPTION.z * distance));
    }
    private static Vec3 reflect(Vec3 incident, Vec3 normal) { return incident.sub(normal.mul(2.0 * incident.dot(normal))); }
    private static Vec3 cosineHemisphere(Vec3 normal, SplittableRandom rng) {
        double r1 = rng.nextDouble(), r2 = rng.nextDouble();
        double phi = 2.0 * Math.PI * r1, r = Math.sqrt(r2);
        double x = Math.cos(phi) * r, z = Math.sin(phi) * r, y = Math.sqrt(Math.max(0.0, 1.0 - r2));
        Vec3 helper = Math.abs(normal.y) < 0.999 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 tangent = helper.cross(normal).normalized();
        Vec3 bitangent = normal.cross(tangent);
        return tangent.mul(x).add(normal.mul(y)).add(bitangent.mul(z)).normalized();
    }

    record RenderResult(Path savedPath, BufferedImage preview, boolean cancelled) { }
    private record Light(Vec3 center, Vec3 color, double power, double radius) {
        Vec3 sample(SplittableRandom rng) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double radiusSample = Math.sqrt(rng.nextDouble()) * radius;
            return center.add(new Vec3(Math.cos(angle) * radiusSample, 0, Math.sin(angle) * radiusSample));
        }
    }
}
