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
        this.triangles = new ArrayList<>(model.triangles().size() + (ocean ? 72_202 : 2));
        this.triangles.addAll(this.model.triangles());
        if (ocean) {
            OceanWater.appendTo(triangles);
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
            triangles.add(new Triangle(a, c, b, up, up, up, ground));
            triangles.add(new Triangle(a, d, c, up, up, up, ground));
        }
        this.bvh = new Bvh(triangles);
    }

    private static Model scaledModel(Model source, double scale) {
        if (Math.abs(scale - 1.0) < 1e-9) return source;
        List<Triangle> copy = new ArrayList<>(source.triangles().size());
        for (Triangle t : source.triangles()) {
            Vec3 a = t.a.mul(scale), b = t.b.mul(scale), c = t.c.mul(scale);
            Vec3 normal = b.sub(a).cross(c.sub(a)).normalized();
            copy.add(new Triangle(a, b, c, normal, normal, normal, t.ta, t.tb, t.tc, t.material));
        }
        return new Model(source.source(), copy);
    }

    Model model() { return model; }
    List<Triangle> triangles() { return triangles; }
    Bvh bvh() { return bvh; }
    boolean ocean() { return ocean; }
}
