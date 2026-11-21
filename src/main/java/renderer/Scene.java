package renderer;

import java.util.ArrayList;
import java.util.List;

final class Scene {
    private final Model model;
    private final List<Triangle> triangles;
    private final Bvh bvh;
    private final boolean ocean;

    Scene(Model model, boolean ocean) { this(model, ocean, 1.0); }

    Scene(Model model, boolean ocean, double modelScale) {
        this.model = scaledModel(model, modelScale);
        this.ocean = ocean;
        List<Triangle> visibleGeometry = new ArrayList<>(this.model.triangles().size() + 2);
        List<Triangle> acceleratedGeometry = new ArrayList<>(this.model.triangles().size() + (ocean ? 2 : 2));
        visibleGeometry.addAll(this.model.triangles());
        acceleratedGeometry.addAll(this.model.triangles());

        if (ocean) {
            // The ocean itself is analytic and unbounded; only its distant seabed is
            // represented by geometry. RasterRenderer draws the sea per pixel.
            OceanWater.appendSeabedTo(acceleratedGeometry);
        } else {
            Material ground = new Material();
            ground.diffuse = new Vec3(0.105, 0.12, 0.135);
            ground.specular = new Vec3(0.035, 0.04, 0.045);
            ground.roughness = 0.94;
            double s = 80.0;
            Vec3 a = new Vec3(-s, -0.035, -s);
            Vec3 b = new Vec3(s, -0.035, -s);
            Vec3 c = new Vec3(s, -0.035, s);
            Vec3 d = new Vec3(-s, -0.035, s);
            Vec3 up = new Vec3(0, 1, 0);
            Triangle g0 = new Triangle(a, c, b, up, up, up, ground);
            Triangle g1 = new Triangle(a, d, c, up, up, up, ground);
            visibleGeometry.add(g0);
            visibleGeometry.add(g1);
            acceleratedGeometry.add(g0);
            acceleratedGeometry.add(g1);
        }

        this.triangles = visibleGeometry;
        this.bvh = new Bvh(acceleratedGeometry);
    }

    private static Model scaledModel(Model source, double scale) {
        if (Math.abs(scale - 1.0) < 1e-9) return source;
        List<Triangle> copy = new ArrayList<>(source.triangles().size());
        for (Triangle t : source.triangles()) {
            Vec3 a = t.a.mul(scale), b = t.b.mul(scale), c = t.c.mul(scale);
            if (t.hasUv) copy.add(new Triangle(a, b, c, t.na, t.nb, t.nc, t.ta, t.tb, t.tc, t.material));
            else copy.add(new Triangle(a, b, c, t.na, t.nb, t.nc, t.material));
        }
        return new Model(source.source(), copy, source.warnings());
    }

    Model model() { return model; }
    List<Triangle> triangles() { return triangles; }
    Bvh bvh() { return bvh; }
    boolean ocean() { return ocean; }
}
