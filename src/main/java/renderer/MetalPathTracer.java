package renderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native macOS Metal compute path tracer. It shares the scene importer, BVH, camera, lighting, and PNG writer with the CPU renderer. */
final class MetalPathTracer {
    private static final int MAX_BAND_BYTES = 16 * 1024 * 1024;
    private static volatile boolean loadAttempted;
    private static volatile boolean loaded;
    private static volatile String loadFailure = "Metal native bridge has not been loaded.";
    private static volatile String cachedDeviceName;
    private static Scene cachedScene;
    private static long cachedContext;

    private final Scene scene;
    private final double cloudCoverage;
    private final LightingSettings lighting;

    MetalPathTracer(Scene scene, double cloudCoverage, LightingSettings lighting) {
        this.scene = scene;
        this.cloudCoverage = Math.max(0.0, Math.min(1.0, cloudCoverage));
        this.lighting = lighting;
    }

    static boolean isAvailable() { return deviceName() != null; }

    static String deviceName() {
        if (!ensureLoaded()) return null;
        String current = cachedDeviceName;
        if (current != null) return current;
        try {
            current = nativeDeviceName();
            cachedDeviceName = current;
            if (current == null || current.isBlank()) {
                loadFailure = "Metal reported no default GPU device.";
                return null;
            }
            return current;
        } catch (LinkageError | RuntimeException error) {
            loadFailure = error.getMessage() == null ? error.toString() : error.getMessage();
            return null;
        }
    }

    static String unavailableReason() {
        deviceName();
        return loadFailure;
    }

    private static synchronized boolean ensureLoaded() {
        if (loadAttempted) return loaded;
        loadAttempted = true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            loadFailure = "Metal is available only on macOS.";
            return false;
        }
        try {
            System.loadLibrary("warshipmetal");
            loaded = true;
            return true;
        } catch (UnsatisfiedLinkError first) {
            String explicit = System.getenv("WARSHIP_METAL_LIBRARY");
            if (explicit != null && !explicit.isBlank()) {
                try {
                    System.load(Path.of(explicit).toAbsolutePath().toString());
                    loaded = true;
                    return true;
                } catch (UnsatisfiedLinkError ignored) { /* try project-local locations below */ }
            }
            Path local = Path.of(System.getProperty("user.dir", "."), "build", "native", "libwarshipmetal.dylib");
            if (Files.isRegularFile(local)) {
                try {
                    System.load(local.toAbsolutePath().toString());
                    loaded = true;
                    return true;
                } catch (UnsatisfiedLinkError second) {
                    loadFailure = second.getMessage();
                    return false;
                }
            }
            loadFailure = "Metal bridge not found. Run ./build.sh on the Mac to compile native/metal_bridge.m. "
                    + (first.getMessage() == null ? "" : first.getMessage());
            return false;
        }
    }

    BufferedImage renderPreview(CameraSnapshot camera, int width, int height, int samplesPerPixel) throws Exception {
        validateDimensions(width, height, samplesPerPixel);
        long handle = contextFor(scene);
        int[] pixels = nativeRenderRows(handle, width, height, samplesPerPixel, 0, height, uniforms(camera));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    PathTracer.RenderResult renderToPng(CameraSnapshot camera, int width, int height, int samplesPerPixel,
                                        Path destination, RenderProgress progress, AtomicBoolean cancel) throws Exception {
        validateDimensions(width, height, samplesPerPixel);
        Path absolute = destination.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".partial");
        BufferedImage preview = makePreview(width, height);
        int previewWidth = preview.getWidth();
        int previewHeight = preview.getHeight();
        int bandSize = (int) Math.max(1L, Math.min(32L, MAX_BAND_BYTES / Math.max(4L, (long) width * 4L)));
        int rowsDone = 0;
        boolean completed = false;
        try {
            long handle = contextFor(scene);
            float[] cameraAndLight = uniforms(camera);
            try (StreamingPngWriter writer = new StreamingPngWriter(temporary, width, height)) {
                for (int firstY = 0; firstY < height && !cancel.get(); firstY += bandSize) {
                    int count = Math.min(bandSize, height - firstY);
                    int[] band = nativeRenderRows(handle, width, height, samplesPerPixel, firstY, count, cameraAndLight);
                    if (cancel.get()) break;
                    for (int offset = 0; offset < count; offset++) {
                        int y = firstY + offset;
                        int[] row = Arrays.copyOfRange(band, offset * width, (offset + 1) * width);
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
            }
        } catch (Throwable failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }

        if (completed) {
            try {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary, absolute, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            Files.deleteIfExists(temporary);
        }
        return new PathTracer.RenderResult(completed ? absolute : null, preview, !completed);
    }

    private static synchronized long contextFor(Scene requestedScene) throws IOException {
        if (cachedScene == requestedScene && cachedContext != 0) return cachedContext;
        if (cachedContext != 0) nativeRelease(cachedContext);
        cachedContext = 0;
        cachedScene = null;
        MetalSceneData data = MetalSceneData.from(requestedScene);
        long created = nativeCreate(data.triangles, data.nodeBounds, data.nodeMetadata, data.textureArray,
                data.textureSize, data.textureLayers, loadShaderSource());
        if (created == 0) throw new IllegalStateException("Metal could not create a rendering context.");
        cachedScene = requestedScene;
        cachedContext = created;
        return created;
    }

    private float[] uniforms(CameraSnapshot camera) {
        Vec3 p = camera.position();
        Vec3 f = camera.forward();
        Vec3 r = camera.right();
        Vec3 u = camera.up();
        Vec3 sun = lighting.sunDirection();
        Vec3 sunColor = lighting.sunColor();
        return new float[] {
                (float) p.x, (float) p.y, (float) p.z,
                (float) f.x, (float) f.y, (float) f.z,
                (float) r.x, (float) r.y, (float) r.z,
                (float) u.x, (float) u.y, (float) u.z,
                (float) Math.tan(camera.fov() * 0.5),
                (float) cloudCoverage,
                (float) lighting.sunIntensity(),
                (float) lighting.timeOfDay(),
                (float) sun.x, (float) sun.y, (float) sun.z,
                (float) sunColor.x, (float) sunColor.y, (float) sunColor.z,
                scene.ocean() ? 1.0f : 0.0f,
                (float) OceanWater.waveAmplitude(),
                (float) OceanWater.waveLength()
        };
    }

    private static String loadShaderSource() throws IOException {
        try (InputStream input = MetalPathTracer.class.getResourceAsStream("/renderer/PathTrace.metal")) {
            if (input == null) throw new IOException("Bundled Metal shader resource /renderer/PathTrace.metal is missing. Rebuild the project.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void validateDimensions(int width, int height, int spp) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Width and height must be positive integers.");
        if (width > (Integer.MAX_VALUE - 1) / 3) throw new IllegalArgumentException("The requested width exceeds PNG scanline addressing limits.");
        if (spp < 1 || spp > 4096) throw new IllegalArgumentException("Samples per pixel must be between 1 and 4096.");
        long rowBytes = (long) width * 7L + 1L;
        long rowBudget = Math.max(16L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4L);
        if (rowBytes > rowBudget) throw new IllegalArgumentException("The requested width needs more scanline memory than this JVM can safely reserve.");
    }

    private static BufferedImage makePreview(int width, int height) {
        double scale = Math.min(1.0, Math.min(1600.0 / width, 1200.0 / height));
        int w = Math.max(1, (int) Math.round(width * scale));
        int h = Math.max(1, (int) Math.round(height * scale));
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    }

    private static native String nativeDeviceName();
    private static native long nativeCreate(float[] triangles, float[] nodeBounds, int[] nodeMetadata,
                                             byte[] textureArray, int textureSize, int textureLayers,
                                             String shaderSource);
    private static native int[] nativeRenderRows(long handle, int width, int height, int samplesPerPixel,
                                                 int firstY, int rows, float[] cameraAndLight);
    private static native void nativeRelease(long handle);
}
