package renderer;

final class Aabb {
    final Vec3 min;
    final Vec3 max;

    Aabb(Vec3 min, Vec3 max) { this.min = min; this.max = max; }

    static Aabb forTriangle(Vec3 a, Vec3 b, Vec3 c) {
        double e = 1e-6;
        return new Aabb(
                new Vec3(Math.min(a.x, Math.min(b.x, c.x)) - e,
                         Math.min(a.y, Math.min(b.y, c.y)) - e,
                         Math.min(a.z, Math.min(b.z, c.z)) - e),
                new Vec3(Math.max(a.x, Math.max(b.x, c.x)) + e,
                         Math.max(a.y, Math.max(b.y, c.y)) + e,
                         Math.max(a.z, Math.max(b.z, c.z)) + e));
    }

    Aabb union(Aabb b) {
        return new Aabb(
                new Vec3(Math.min(min.x, b.min.x), Math.min(min.y, b.min.y), Math.min(min.z, b.min.z)),
                new Vec3(Math.max(max.x, b.max.x), Math.max(max.y, b.max.y), Math.max(max.z, b.max.z)));
    }

    double extent(int axis) {
        return axis == 0 ? max.x - min.x : axis == 1 ? max.y - min.y : max.z - min.z;
    }

    static double component(Vec3 v, int axis) {
        return axis == 0 ? v.x : axis == 1 ? v.y : v.z;
    }

    boolean hit(Ray ray, double maxT) {
        double tMin = 1e-5;
        double tMax = maxT;
        double inv;
        double t0, t1;
        if (Math.abs(ray.direction.x) < 1e-14) {
            if (ray.origin.x < min.x || ray.origin.x > max.x) return false;
        } else {
            inv = 1.0 / ray.direction.x;
            t0 = (min.x - ray.origin.x) * inv; t1 = (max.x - ray.origin.x) * inv;
            if (t0 > t1) { double q = t0; t0 = t1; t1 = q; }
            tMin = Math.max(tMin, t0); tMax = Math.min(tMax, t1);
            if (tMax < tMin) return false;
        }
        if (Math.abs(ray.direction.y) < 1e-14) {
            if (ray.origin.y < min.y || ray.origin.y > max.y) return false;
        } else {
            inv = 1.0 / ray.direction.y;
            t0 = (min.y - ray.origin.y) * inv; t1 = (max.y - ray.origin.y) * inv;
            if (t0 > t1) { double q = t0; t0 = t1; t1 = q; }
            tMin = Math.max(tMin, t0); tMax = Math.min(tMax, t1);
            if (tMax < tMin) return false;
        }
        if (Math.abs(ray.direction.z) < 1e-14) {
            if (ray.origin.z < min.z || ray.origin.z > max.z) return false;
        } else {
            inv = 1.0 / ray.direction.z;
            t0 = (min.z - ray.origin.z) * inv; t1 = (max.z - ray.origin.z) * inv;
            if (t0 > t1) { double q = t0; t0 = t1; t1 = q; }
            tMin = Math.max(tMin, t0); tMax = Math.min(tMax, t1);
            if (tMax < tMin) return false;
        }
        return true;
    }
}
