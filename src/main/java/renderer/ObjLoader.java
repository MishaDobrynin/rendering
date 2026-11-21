package renderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
        if (objPath == null || !objPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".obj")) {
            throw new IOException("Only Wavefront OBJ model files can be imported.");
        }
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
        List<String> warnings = new ArrayList<>();
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
                        String referenceText = line.substring("mtllib".length()).trim();
                        for (String libraryName : materialLibraryReferences(objPath.getParent(), referenceText)) {
                            Path mtl = resolveAsset(objPath.getParent(), libraryName);
                            if (mtl == null) {
                                Path suggestion = findSimilarMaterialLibrary(objPath.getParent(), libraryName);
                                if (suggestion != null) {
                                    warnings.add("OBJ references material library '" + libraryName
                                            + "', but the matching file appears to be '"
                                            + suggestion.getFileName() + "'. Loaded that file instead.");
                                    mtl = suggestion;
                                } else {
                                    warnings.add("Material library '" + libraryName
                                            + "' was not found beside the OBJ. Geometry was loaded with default materials. "
                                            + "Put the matching .mtl file beside the OBJ or correct its mtllib line.");
                                    continue;
                                }
                            }
                            try {
                                materials.putAll(loadMtl(mtl, objPath.getParent(), warnings));
                            } catch (IOException ex) {
                                warnings.add("Could not fully load material library '" + mtl.getFileName()
                                        + "': " + ex.getMessage() + ". Geometry was retained with fallback materials.");
                            }
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
        return new Model(objPath, triangles, warnings);
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

    private static Map<String, Material> loadMtl(Path path, Path objDirectory, List<String> warnings) throws IOException {
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
                            String textureName = parseMapFilename(line.substring("map_Kd".length()).trim());
                            Path texturePath = resolveAsset(path.getParent(), textureName);
                            if (texturePath == null) texturePath = resolveAsset(objDirectory, textureName);
                            if (texturePath == null) {
                                warnings.add("Diffuse texture '" + textureName + "' referenced by '" + path.getFileName()
                                        + "' was not found. The affected material will use its diffuse color instead.");
                                break;
                            }
                            BufferedImage texture = ImageIO.read(texturePath.toFile());
                            if (texture == null) {
                                warnings.add("Diffuse texture '" + texturePath.getFileName()
                                        + "' is not a readable image. The affected material will use its diffuse color instead.");
                            } else {
                                current.diffuseTexture = texture;
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

    /**
     * OBJ exporters are inconsistent about quoting material paths containing spaces.
     * First preserve the complete remainder when it resolves as one file; otherwise
     * interpret it as the standard whitespace-separated list of MTL libraries.
     */
    private static List<String> materialLibraryReferences(Path objDirectory, String text) throws IOException {
        if (text == null || text.isBlank()) return List.of();
        Path whole = resolveAsset(objDirectory, text);
        if (whole != null) return List.of(text);
        List<String> tokens = quotedTokens(text);
        if (tokens.size() == 1) return tokens;

        // Some exporters emit an unquoted filename with spaces. Try a close filename
        // match against the full line before treating the tokens as multiple libraries.
        Path wholeSuggestion = findSimilarMaterialLibrary(objDirectory, text);
        if (wholeSuggestion != null) return List.of(text);
        return tokens;
    }

    /** Find a unique, near-identical .mtl filename (for example, a one-character typo). */
    private static Path findSimilarMaterialLibrary(Path directory, String reference) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) return null;
        String wanted = normalizedFilename(reference);
        if (wanted.isEmpty()) return null;
        Path best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean ambiguous = false;
        try (var files = Files.list(directory)) {
            for (Path candidate : files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mtl"))
                    .toList()) {
                String actual = normalizedFilename(candidate.getFileName().toString());
                int distance = editDistance(wanted, actual);
                int allowed = wanted.length() < 8 ? 1 : 2;
                if (distance <= allowed) {
                    if (distance < bestDistance) {
                        best = candidate;
                        bestDistance = distance;
                        ambiguous = false;
                    } else if (distance == bestDistance) {
                        ambiguous = true;
                    }
                }
            }
        }
        return ambiguous ? null : best;
    }

    private static String normalizedFilename(String value) {
        String leaf = value == null ? "" : value.replace('\\', '/');
        int slash = leaf.lastIndexOf('/');
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        return leaf.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[b.length()];
    }

    private static List<String> quotedTokens(String text) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").matcher(text);
        while (matcher.find()) tokens.add(matcher.group(1) != null ? matcher.group(1)
                : matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        return tokens;
    }

    private static String parseMapFilename(String arguments) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"([^\\\"]*)\\\"|'([^']*)'|(\\S+)").matcher(arguments);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            tokens.add(token);
        }
        int i = 0;
        while (i < tokens.size() && tokens.get(i).startsWith("-")) {
            String option = tokens.get(i++).toLowerCase(java.util.Locale.ROOT);
            int consume = switch (option) {
                case "-mm" -> 2;
                case "-o", "-s", "-t" -> {
                    int n = 0;
                    while (n < 3 && i < tokens.size() && isNumber(tokens.get(i))) { i++; n++; }
                    yield 0;
                }
                case "-blendu", "-blendv", "-cc", "-clamp", "-texres", "-bm", "-imfchan", "-type" -> 1;
                default -> 0;
            };
            i = Math.min(tokens.size(), i + consume);
        }
        if (i >= tokens.size()) return "";
        // Quoted paths stay one token; unquoted spaces are preserved by joining the remainder.
        return String.join(" ", tokens.subList(i, tokens.size()));
    }

    private static boolean isNumber(String value) {
        try { Double.parseDouble(value); return true; } catch (NumberFormatException ignored) { return false; }
    }

    private static Path resolveAsset(Path directory, String name) throws IOException {
        if (name == null || name.isBlank()) return null;
        String cleaned = unquote(name).replace('\\', java.io.File.separatorChar);
        Path candidate = Path.of(cleaned);
        if (!candidate.isAbsolute() && directory != null) candidate = directory.resolve(candidate);
        if (Files.isRegularFile(candidate)) return candidate.normalize();
        Path parent = candidate.getParent();
        Path leaf = candidate.getFileName();
        if (parent != null && Files.isDirectory(parent) && leaf != null) {
            try (var files = Files.list(parent)) {
                Path match = files.filter(Files::isRegularFile)
                        .filter(f -> f.getFileName().toString().equalsIgnoreCase(leaf.toString()))
                        .findFirst().orElse(null);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String unquote(String value) {
        String v = value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
            return v.substring(1, v.length() - 1);
        return v;
    }

    private static Vec3 rgb(String[] p) { return new Vec3(parse(p[1]), parse(p[2]), parse(p[3])).clamp(0, 1); }
    private static double parse(String value) { return Double.parseDouble(value); }
    private record FaceVertex(int vertex, int tex, int normal) { }
    private record Bounds(Vec3 min, Vec3 max) { }
}
