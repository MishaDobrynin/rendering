package renderer;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Assigns an image to a model, preferring the UV coordinates authored in the OBJ. */
final class TextureMapper {
    private TextureMapper() { }

    static ApplyResult apply(Model source, BufferedImage image) {
        if (image == null) throw new IllegalArgumentException("Texture image is empty or unsupported.");

        Vec3 min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 max = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (Triangle triangle : source.triangles()) {
            min = min(min, triangle.a); min = min(min, triangle.b); min = min(min, triangle.c);
            max = max(max, triangle.a); max = max(max, triangle.b); max = max(max, triangle.c);
        }
        double sx = Math.max(1e-8, max.x - min.x);
        double sy = Math.max(1e-8, max.y - min.y);
        double sz = Math.max(1e-8, max.z - min.z);

        Map<Material, Material> copies = new IdentityHashMap<>();
        List<Triangle> mapped = new ArrayList<>(source.triangles().size());
        int authoredUvTriangles = 0;
        int projectedTriangles = 0;
        for (Triangle triangle : source.triangles()) {
            Material material = copies.computeIfAbsent(triangle.material, old -> {
                Material copy = old.copy();
                copy.diffuse = new Vec3(1.0, 1.0, 1.0);
                copy.diffuseTexture = image;
                return copy;
            });

            Vec2 ta, tb, tc;
            if (triangle.hasUv) {
                // Do not regenerate an artist-authored UV map. A UV atlas is laid out
                // specifically for these coordinates, including its rotated islands.
                ta = triangle.ta;
                tb = triangle.tb;
                tc = triangle.tc;
                authoredUvTriangles++;
            } else {
                // Only faces without OBJ texture coordinates get the approximate fallback.
                Vec3 n = triangle.faceNormal;
                ta = project(triangle.a, n, min, sx, sy, sz);
                tb = project(triangle.b, n, min, sx, sy, sz);
                tc = project(triangle.c, n, min, sx, sy, sz);
                projectedTriangles++;
            }
            mapped.add(new Triangle(triangle.a, triangle.b, triangle.c, triangle.na, triangle.nb, triangle.nc,
                    ta, tb, tc, material));
        }
        Model result = new Model(source.source(), mapped, source.warnings());
        return new ApplyResult(result, authoredUvTriangles, projectedTriangles);
    }

    static int texturedMaterialCount(Model model) {
        Map<Material, Boolean> seen = new IdentityHashMap<>();
        for (Triangle triangle : model.triangles()) {
            if (triangle.material.diffuseTexture != null) seen.put(triangle.material, Boolean.TRUE);
        }
        return seen.size();
    }

    private static Vec2 project(Vec3 p, Vec3 normal, Vec3 min, double sx, double sy, double sz) {
        double ax = Math.abs(normal.x), ay = Math.abs(normal.y), az = Math.abs(normal.z);
        if (ax >= ay && ax >= az) return new Vec2((p.z - min.z) / sz, (p.y - min.y) / sy);
        if (ay >= ax && ay >= az) return new Vec2((p.x - min.x) / sx, (p.z - min.z) / sz);
        return new Vec2((p.x - min.x) / sx, (p.y - min.y) / sy);
    }

    private static Vec3 min(Vec3 a, Vec3 b) { return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)); }
    private static Vec3 max(Vec3 a, Vec3 b) { return new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z)); }

    record ApplyResult(Model model, int authoredUvTriangles, int projectedTriangles) {
        boolean usedAuthoredUvs() { return authoredUvTriangles > 0; }
        boolean fullyUvMapped() { return projectedTriangles == 0; }
    }
}
