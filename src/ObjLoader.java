package renderer;

import javax.imageio.ImageIO;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Streaming Wavefront OBJ/MTL loader. Faces are triangulated and UV diffuse maps are retained. */
final class ObjLoader {
    private ObjLoader() { }

    static Model load(Path objPath) throws IOException {
        Bounds bounds = scanBounds(objPath);
        double largest = Math.max(bounds.max.x - bounds.min.x,
                Math.max(bounds.max.y - bounds.min.y, bounds.max.z - bounds.min.z));
        if (!Double.isFinite(largest) || largest < 1e-12) throw new IOException("The OBJ has invalid or zero-sized geometry.");
        double scale = 4.0 / largest;
        double centerX = (bounds.min.x + bounds.max.x) * 0.5;
        double centerZ = (bounds.min.z + bounds.max.z) * 0.5;

        List<Vec3> vertices = new ArrayList<>();
        List<Vec3> normals = new ArrayList<>();
        List<Vec2> texCoords = new ArrayList<>();
        List<Triangle> triangles = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        materials.put("default", new Material());
        Material current = materials.get("default");

        try (BufferedReader reader = Files.newBufferedReader(objPath)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "v" -> {
                        if (p.length >= 4) vertices.add(transform(new Vec3(parse(p[1]), parse(p[2]), parse(p[3])),
                                centerX, bounds.min.y, centerZ, scale));
                    }
                    case "vn" -> {
                        if (p.length >= 4) normals.add(new Vec3(parse(p[1]), parse(p[2]), parse(p[3])).normalized());
                    }
                    case "vt" -> {
                        if (p.length >= 3) texCoords.add(new Vec2(parse(p[1]), parse(p[2])));
                    }
                    case "mtllib" -> {
                        for (int i = 1; i < p.length; i++) {
                            Path mtl = objPath.getParent() == null ? Path.of(p[i]) : objPath.getParent().resolve(p[i]);
                            if (Files.exists(mtl)) materials.putAll(loadMtl(mtl));
                        }
                    }
                    case "usemtl" -> {
                        String name = line.substring("usemtl".length()).trim();
                        current = materials.computeIfAbsent(name.isEmpty() ? "default" : name, ignored -> new Material());
                    }
                    case "f" -> {
                        if (p.length < 4) continue;
                        FaceVertex first = parseFaceVertex(p[1], vertices.size(), texCoords.size(), normals.size());
                        FaceVertex previous = parseFaceVertex(p[2], vertices.size(), texCoords.size(), normals.size());
                        for (int i = 3; i < p.length; i++) {
                            FaceVertex next = parseFaceVertex(p[i], vertices.size(), texCoords.size(), normals.size());
                            triangles.add(makeTriangle(first, previous, next, vertices, texCoords, normals, current));
                            previous = next;
                        }
                    }
                    default -> { /* Groups, object labels and smoothing groups do not affect triangle geometry. */ }
                }
            }
        }
        if (triangles.isEmpty()) throw new IOException("No faces were found in this OBJ file.");
        return new Model(objPath, triangles);
    }

    private static Triangle makeTriangle(FaceVertex a, FaceVertex b, FaceVertex c,
                                         List<Vec3> vertices, List<Vec2> tex, List<Vec3> normals, Material m) {
        return new Triangle(vertices.get(a.vertex), vertices.get(b.vertex), vertices.get(c.vertex),
                a.normal < 0 ? null : normals.get(a.normal),
                b.normal < 0 ? null : normals.get(b.normal),
                c.normal < 0 ? null : normals.get(c.normal),
                a.tex < 0 ? null : tex.get(a.tex),
                b.tex < 0 ? null : tex.get(b.tex),
                c.tex < 0 ? null : tex.get(c.tex), m);
    }

    private static Bounds scanBounds(Path path) throws IOException {
        Vec3 min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 max = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        boolean found = false;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (!line.startsWith("v ")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 4) continue;
                Vec3 v = new Vec3(parse(p[1]), parse(p[2]), parse(p[3]));
                min = min3(min, v);
                max = max3(max, v);
                found = true;
            }
        }
        if (!found) throw new IOException("No vertex positions were found in this OBJ file.");
        return new Bounds(min, max);
    }

    private static Vec3 transform(Vec3 p, double cx, double minY, double cz, double scale) {
        return new Vec3((p.x - cx) * scale, (p.y - minY) * scale, (p.z - cz) * scale);
    }

    static void normalizeInPlace(List<Triangle> triangles) throws IOException {
        Vec3 min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 max = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (Triangle t : triangles) {
            min = min3(min, t.a); min = min3(min, t.b); min = min3(min, t.c);
            max = max3(max, t.a); max = max3(max, t.b); max = max3(max, t.c);
        }
        double largest = Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
        if (!Double.isFinite(largest) || largest < 1e-12) throw new IOException("The model has invalid or zero-sized geometry.");
        double scale = 4.0 / largest;
        double cx = (min.x + max.x) * 0.5, cz = (min.z + max.z) * 0.5;
        for (Triangle t : triangles) t.transform(cx, min.y, cz, scale);
    }

    private static Vec3 min3(Vec3 a, Vec3 b) {
        return new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }
    private static Vec3 max3(Vec3 a, Vec3 b) {
        return new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
    }

    private static FaceVertex parseFaceVertex(String token, int vertexCount, int texCount, int normalCount) throws IOException {
        String[] parts = token.split("/", -1);
        int vi = objIndex(parts[0], vertexCount);
        int ti = parts.length >= 2 && !parts[1].isEmpty() ? objIndex(parts[1], texCount) : -1;
        int ni = parts.length >= 3 && !parts[2].isEmpty() ? objIndex(parts[2], normalCount) : -1;
        return new FaceVertex(vi, ti, ni);
    }

    private static int objIndex(String value, int size) throws IOException {
        try {
            int index = Integer.parseInt(value);
            int resolved = index > 0 ? index - 1 : size + index;
            if (resolved < 0 || resolved >= size) throw new IOException("OBJ index is out of range: " + value);
            return resolved;
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid OBJ index: " + value, ex);
        }
    }

    private static Map<String, Material> loadMtl(Path path) throws IOException {
        Map<String, Material> result = new HashMap<>();
        Material current = null;
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "newmtl" -> {
                        String name = line.substring("newmtl".length()).trim();
                        current = new Material();
                        result.put(name, current);
                    }
                    case "Kd" -> { if (current != null && p.length >= 4) current.diffuse = rgb(p); }
                    case "Ks" -> { if (current != null && p.length >= 4) current.specular = rgb(p).clamp(0, 1); }
                    case "Ke" -> { if (current != null && p.length >= 4) current.emission = rgb(p); }
                    case "Ns" -> {
                        if (current != null && p.length >= 2) {
                            double ns = Math.max(0, parse(p[1]));
                            current.roughness = Math.max(0.06, Math.min(1.0, Math.sqrt(2.0 / (ns + 2.0))));
                        }
                    }
                    case "map_Kd" -> {
                        if (current != null && p.length >= 2) {
                            // Handles common map_Kd declarations. The final token is the image path;
                            // uncommon MTL map options are skipped deliberately.
                            String textureName = p[p.length - 1];
                            Path texturePath = path.getParent() == null ? Path.of(textureName) : path.getParent().resolve(textureName);
                            if (Files.exists(texturePath)) {
                                try { current.diffuseTexture = ImageIO.read(texturePath.toFile()); }
                                catch (IOException ignored) { /* Keep the material color if a map is corrupt. */ }
                            }
                        }
                    }
                    case "d", "Tr" -> { /* Opacity is not yet used by this opaque renderer. */ }
                    default -> { }
                }
            }
        }
        return result;
    }

    private static Vec3 rgb(String[] p) { return new Vec3(parse(p[1]), parse(p[2]), parse(p[3])).clamp(0, 1); }
    private static double parse(String value) { return Double.parseDouble(value); }
    private record FaceVertex(int vertex, int tex, int normal) { }
    private record Bounds(Vec3 min, Vec3 max) { }
}
