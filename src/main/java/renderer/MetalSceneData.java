package renderer;

import java.awt.image.BufferedImage;
import java.util.IdentityHashMap;
import java.util.Map;

/** Packs the current scene and its CPU-built BVH into the stable buffer layout consumed by the Metal kernel. */
final class MetalSceneData {
    static final int TEXTURE_SIZE = 512;
    static final int MAX_TEXTURE_LAYERS = 64;
    static final int TRIANGLE_FLOATS = 48;

    final float[] triangles;
    final float[] nodeBounds;
    final int[] nodeMetadata;
    final byte[] textureArray;
    final int textureSize;
    final int textureLayers;
    final int triangleCount;
    final int nodeCount;

    private MetalSceneData(float[] triangles, float[] nodeBounds, int[] nodeMetadata,
                           byte[] textureArray, int textureSize, int textureLayers) {
        this.triangles = triangles;
        this.nodeBounds = nodeBounds;
        this.nodeMetadata = nodeMetadata;
        this.textureArray = textureArray;
        this.textureSize = textureSize;
        this.textureLayers = textureLayers;
        this.triangleCount = triangles.length / TRIANGLE_FLOATS;
        this.nodeCount = nodeMetadata.length / 4;
    }

    static MetalSceneData from(Scene scene) {
        Bvh.GpuData data = scene.bvh().gpuData();
        Triangle[] source = data.orderedTriangles();
        Map<Material, Integer> materialTextures = new IdentityHashMap<>();
        int nextLayer = 0;
        for (Triangle triangle : source) {
            Material material = triangle.material;
            if (material.diffuseTexture != null && !materialTextures.containsKey(material)) {
                if (nextLayer >= MAX_TEXTURE_LAYERS) {
                    throw new IllegalArgumentException("Metal supports up to " + MAX_TEXTURE_LAYERS
                            + " distinct diffuse texture materials per scene. Reduce the number of textured materials or use CPU rendering.");
                }
                materialTextures.put(material, nextLayer++);
            }
        }
        int layers = Math.max(1, nextLayer);
        byte[] atlas = buildTextureArray(materialTextures, layers);
        float[] packed = new float[Math.multiplyExact(source.length, TRIANGLE_FLOATS)];
        int cursor = 0;
        for (Triangle triangle : source) {
            Material m = triangle.material;
            cursor = put4(packed, cursor, triangle.a.x, triangle.a.y, triangle.a.z, 0);
            cursor = put4(packed, cursor, triangle.b.x, triangle.b.y, triangle.b.z, 0);
            cursor = put4(packed, cursor, triangle.c.x, triangle.c.y, triangle.c.z, 0);
            cursor = put4(packed, cursor, triangle.na.x, triangle.na.y, triangle.na.z, 0);
            cursor = put4(packed, cursor, triangle.nb.x, triangle.nb.y, triangle.nb.z, 0);
            cursor = put4(packed, cursor, triangle.nc.x, triangle.nc.y, triangle.nc.z, 0);
            cursor = put4(packed, cursor, triangle.ta.u(), triangle.ta.v(), triangle.tb.u(), triangle.tb.v());
            int textureIndex = materialTextures.getOrDefault(m, -1);
            cursor = put4(packed, cursor, triangle.tc.u(), triangle.tc.v(), textureIndex, m.roughness);
            cursor = put4(packed, cursor, m.diffuse.x, m.diffuse.y, m.diffuse.z, 0);
            cursor = put4(packed, cursor, m.specular.x, m.specular.y, m.specular.z, 0);
            cursor = put4(packed, cursor, m.emission.x, m.emission.y, m.emission.z, 0);
            cursor = put4(packed, cursor, 0, triangle.hasUv ? 1 : 0, 0, 0);
        }
        return new MetalSceneData(packed, data.nodeBounds(), data.nodeMetadata(), atlas, TEXTURE_SIZE, layers);
    }

    private static byte[] buildTextureArray(Map<Material, Integer> layers, int layerCount) {
        int bytesPerLayer = Math.multiplyExact(Math.multiplyExact(TEXTURE_SIZE, TEXTURE_SIZE), 4);
        byte[] result = new byte[Math.multiplyExact(bytesPerLayer, layerCount)];
        if (layers.isEmpty()) {
            for (int i = 0; i < result.length; i += 4) {
                result[i] = (byte) 255; result[i + 1] = (byte) 255;
                result[i + 2] = (byte) 255; result[i + 3] = (byte) 255;
            }
            return result;
        }
        for (Map.Entry<Material, Integer> entry : layers.entrySet()) {
            BufferedImage image = entry.getKey().diffuseTexture;
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            int offset = entry.getValue() * bytesPerLayer;
            for (int y = 0; y < TEXTURE_SIZE; y++) {
                double sourceY = ((y + 0.5) * sourceHeight / TEXTURE_SIZE) - 0.5;
                int y0 = Math.max(0, Math.min(sourceHeight - 1, (int) Math.floor(sourceY)));
                int y1 = Math.min(sourceHeight - 1, y0 + 1);
                double fy = Math.max(0.0, Math.min(1.0, sourceY - Math.floor(sourceY)));
                for (int x = 0; x < TEXTURE_SIZE; x++) {
                    double sourceX = ((x + 0.5) * sourceWidth / TEXTURE_SIZE) - 0.5;
                    int x0 = Math.max(0, Math.min(sourceWidth - 1, (int) Math.floor(sourceX)));
                    int x1 = Math.min(sourceWidth - 1, x0 + 1);
                    double fx = Math.max(0.0, Math.min(1.0, sourceX - Math.floor(sourceX)));
                    int c00 = image.getRGB(x0, y0);
                    int c10 = image.getRGB(x1, y0);
                    int c01 = image.getRGB(x0, y1);
                    int c11 = image.getRGB(x1, y1);
                    int i = offset + (y * TEXTURE_SIZE + x) * 4;
                    result[i] = (byte) bilerpChannel(c00 >>> 16 & 255, c10 >>> 16 & 255,
                            c01 >>> 16 & 255, c11 >>> 16 & 255, fx, fy);
                    result[i + 1] = (byte) bilerpChannel(c00 >>> 8 & 255, c10 >>> 8 & 255,
                            c01 >>> 8 & 255, c11 >>> 8 & 255, fx, fy);
                    result[i + 2] = (byte) bilerpChannel(c00 & 255, c10 & 255,
                            c01 & 255, c11 & 255, fx, fy);
                    result[i + 3] = (byte) bilerpChannel(c00 >>> 24 & 255, c10 >>> 24 & 255,
                            c01 >>> 24 & 255, c11 >>> 24 & 255, fx, fy);
                }
            }
        }
        return result;
    }

    private static int bilerpChannel(int c00, int c10, int c01, int c11, double fx, double fy) {
        double top = c00 + (c10 - c00) * fx;
        double bottom = c01 + (c11 - c01) * fx;
        return (int) Math.round(top + (bottom - top) * fy);
    }

    private static int put4(float[] target, int i, double a, double b, double c, double d) {
        target[i++] = (float) a; target[i++] = (float) b;
        target[i++] = (float) c; target[i++] = (float) d;
        return i;
    }
}
