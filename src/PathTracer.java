package renderer;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

final class PathTracer {
    private static final int MAX_BOUNCES = 7;
    private static final int LIGHT_SAMPLES = 2;
    private static final double WATER_IOR = 1.333;
    private static final Vec3 WATER_ABSORPTION = new Vec3(0.42, 0.13, 0.045);

    private final Bvh bvh;
    private final Light[] lights;

    PathTracer(Scene scene) {
        this.bvh = scene.bvh();
        lights = new Light[] {
                new Light(new Vec3(-4.8, 7.2, 4.5), new Vec3(1.0, 0.82, 0.65), 430.0, 1.65),
                new Light(new Vec3(4.5, 4.0, -3.5), new Vec3(0.54, 0.70, 1.0), 185.0, 1.15)
        };
    }

    BufferedImage render(CameraSnapshot camera, int width, int height, int samplesPerPixel,
                         RenderProgress progress, AtomicBoolean cancel) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        AtomicInteger doneRows = new AtomicInteger();
        IntStream.range(0, height).parallel().forEach(y -> {
            if (cancel.get()) return;
            for (int x = 0; x < width; x++) {
                if (cancel.get()) break;
                Vec3 sum = new Vec3(0, 0, 0);
                long seed = 0x9E3779B97F4A7C15L ^ ((long) y << 32) ^ (x * 0xD1B54A32D192ED03L);
                SplittableRandom random = new SplittableRandom(seed);
                for (int s = 0; s < samplesPerPixel; s++) {
                    double jx = random.nextDouble() - 0.5;
                    double jy = random.nextDouble() - 0.5;
                    sum = sum.add(trace(camera.ray(x + jx, y + jy, width, height), random));
                }
                pixels[y * width + x] = RasterRenderer.color(sum.div(samplesPerPixel));
            }
            int completed = doneRows.incrementAndGet();
            if ((completed & 1) == 0 || completed == height) progress.update(completed, height);
        });
        return image;
    }

    private Vec3 trace(Ray ray, SplittableRandom rng) {
        Vec3 radiance = new Vec3(0, 0, 0);
        Vec3 throughput = new Vec3(1, 1, 1);
        boolean insideWater = false;
        for (int bounce = 0; bounce < MAX_BOUNCES; bounce++) {
            Hit hit = new Hit();
            if (!bvh.intersect(ray, hit)) {
                Vec3 environment = sky(ray.direction);
                if (insideWater) environment = environment.mul(absorption(24.0));
                radiance = radiance.add(throughput.mul(environment));
                break;
            }
            if (insideWater) throughput = throughput.mul(absorption(hit.t));
            Material m = hit.material;
            radiance = radiance.add(throughput.mul(m.emission));
            Vec3 n = hit.normal;
            Vec3 p = hit.point;
            Vec3 view = ray.direction.negate();

            if (m.water) {
                // Dielectric water surface: Fresnel reflection/refraction, not a tinted diffuse plane.
                Vec3 waterDirect = directLighting(p, n, view, m, m.diffuse, rng);
                radiance = radiance.add(throughput.mul(waterDirect));

                double nFrom = insideWater ? WATER_IOR : 1.0;
                double nTo = insideWater ? 1.0 : WATER_IOR;
                double eta = nFrom / nTo;
                double cosI = Math.max(0.0, Math.min(1.0, -ray.direction.dot(n)));
                double sinT2 = eta * eta * Math.max(0.0, 1.0 - cosI * cosI);
                double r0 = (nFrom - nTo) / (nFrom + nTo);
                r0 *= r0;
                double fresnel = r0 + (1.0 - r0) * Math.pow(1.0 - cosI, 5.0);
                boolean totalInternalReflection = sinT2 >= 1.0;
                if (totalInternalReflection || rng.nextDouble() < fresnel) {
                    Vec3 reflected = reflect(ray.direction, n).normalized();
                    Vec3 rough = cosineHemisphere(reflected, rng);
                    Vec3 next = Vec3.lerp(reflected, rough, m.roughness * 0.24).normalized();
                    ray = new Ray(p.add(n.mul(0.0018)), next);
                } else {
                    double cosT = Math.sqrt(Math.max(0.0, 1.0 - sinT2));
                    Vec3 transmitted = ray.direction.mul(eta).add(n.mul(eta * cosI - cosT)).normalized();
                    insideWater = !insideWater;
                    ray = new Ray(p.sub(n.mul(0.0018)), transmitted);
                }
                continue;
            }

            Vec3 albedo = hit.hasUv ? m.diffuseAt(hit.texU, hit.texV) : m.diffuse;

            Vec3 direct = directLighting(p, n, view, m, albedo, rng);
            radiance = radiance.add(throughput.mul(direct));

            double spec = Math.max(0.0, Math.min(0.78, m.specular.maxComponent() * 0.68));
            double diffuseProbability = Math.max(0.22, 1.0 - spec);
            Vec3 nextDirection;
            if (rng.nextDouble() < spec) {
                Vec3 reflected = reflect(ray.direction, n).normalized();
                Vec3 rough = cosineHemisphere(reflected, rng);
                nextDirection = Vec3.lerp(reflected, rough, m.roughness * 0.72).normalized();
                if (nextDirection.dot(n) <= 0) nextDirection = reflected;
                Vec3 specColor = m.specular.clamp(0.03, 1.0);
                throughput = throughput.mul(specColor).div(Math.max(0.05, spec));
            } else {
                nextDirection = cosineHemisphere(n, rng);
                throughput = throughput.mul(albedo).div(diffuseProbability);
            }
            if (bounce >= 2) {
                double survive = Math.max(0.12, Math.min(0.88, throughput.maxComponent()));
                if (rng.nextDouble() > survive) break;
                throughput = throughput.div(survive);
            }
            ray = new Ray(p.add(n.mul(0.0015)), nextDirection);
        }
        return radiance;
    }

    private Vec3 directLighting(Vec3 point, Vec3 normal, Vec3 view, Material material, Vec3 albedo, SplittableRandom rng) {
        Vec3 sum = new Vec3(0, 0, 0);
        for (Light light : lights) {
            for (int i = 0; i < LIGHT_SAMPLES; i++) {
                Vec3 lightPoint = light.sample(rng);
                Vec3 delta = lightPoint.sub(point);
                double distance2 = delta.lengthSquared();
                double distance = Math.sqrt(distance2);
                Vec3 l = delta.div(distance);
                double ndotl = Math.max(0, normal.dot(l));
                if (ndotl <= 0) continue;
                Ray shadow = new Ray(point.add(normal.mul(0.0015)), l);
                Hit blocker = new Hit();
                blocker.t = distance - 0.003;
                if (bvh.intersect(shadow, blocker)) continue;
                double attenuation = light.power / Math.max(1.0, distance2);
                Vec3 diffuse = albedo.mul(1.0 / Math.PI);
                double glossPower = Math.max(3.0, 2.0 / (material.roughness * material.roughness));
                Vec3 halfway = l.add(view).normalized();
                double specTerm = Math.pow(Math.max(0, normal.dot(halfway)), glossPower);
                Vec3 specular = material.specular.mul(specTerm * 0.22);
                Vec3 brdf = diffuse.add(specular);
                sum = sum.add(brdf.mul(light.color).mul(ndotl * attenuation / LIGHT_SAMPLES));
            }
        }
        return sum;
    }

    private static Vec3 absorption(double distance) {
        return new Vec3(Math.exp(-WATER_ABSORPTION.x * distance),
                Math.exp(-WATER_ABSORPTION.y * distance),
                Math.exp(-WATER_ABSORPTION.z * distance));
    }

    private static Vec3 reflect(Vec3 incident, Vec3 normal) {
        return incident.sub(normal.mul(2.0 * incident.dot(normal)));
    }

    private static Vec3 cosineHemisphere(Vec3 normal, SplittableRandom rng) {
        double r1 = rng.nextDouble();
        double r2 = rng.nextDouble();
        double phi = 2.0 * Math.PI * r1;
        double r = Math.sqrt(r2);
        double x = Math.cos(phi) * r;
        double z = Math.sin(phi) * r;
        double y = Math.sqrt(Math.max(0, 1.0 - r2));
        Vec3 helper = Math.abs(normal.y) < 0.999 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 tangent = helper.cross(normal).normalized();
        Vec3 bitangent = normal.cross(tangent);
        return tangent.mul(x).add(normal.mul(y)).add(bitangent.mul(z)).normalized();
    }

    private static Vec3 sky(Vec3 direction) {
        double t = Math.max(0, Math.min(1, direction.y * 0.5 + 0.5));
        Vec3 horizon = new Vec3(0.16, 0.19, 0.22);
        Vec3 zenith = new Vec3(0.035, 0.06, 0.105);
        Vec3 sky = Vec3.lerp(horizon, zenith, t).mul(0.72);
        Vec3 sunDirection = new Vec3(-0.45, 0.82, 0.35).normalized();
        double sun = direction.dot(sunDirection);
        if (sun > 0.9992) sky = sky.add(new Vec3(7.0, 5.6, 4.2).mul(Math.pow((sun - 0.9992) / 0.0008, 2)));
        return sky;
    }

    private record Light(Vec3 center, Vec3 color, double power, double radius) {
        Vec3 sample(SplittableRandom rng) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double r = Math.sqrt(rng.nextDouble()) * radius;
            Vec3 offset = new Vec3(Math.cos(angle) * r, 0, Math.sin(angle) * r);
            return center.add(offset);
        }
    }
}
