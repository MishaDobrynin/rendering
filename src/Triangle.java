package renderer;

final class Triangle {
    Vec3 a, b, c;
    Vec3 na, nb, nc;
    Vec2 ta, tb, tc;
    final boolean hasUv;
    Vec3 e1, e2;
    Vec3 faceNormal;
    final Material material;
    Aabb bounds;
    Vec3 centroid;

    Triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 na, Vec3 nb, Vec3 nc, Material material) {
        this(a, b, c, na, nb, nc, Vec2.ZERO, Vec2.ZERO, Vec2.ZERO, material);
    }

    Triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 na, Vec3 nb, Vec3 nc,
             Vec2 ta, Vec2 tb, Vec2 tc, Material material) {
        this.a = a; this.b = b; this.c = c;
        this.na = na;
        this.nb = nb;
        this.nc = nc;
        this.hasUv = ta != null && tb != null && tc != null;
        this.ta = ta == null ? Vec2.ZERO : ta;
        this.tb = tb == null ? Vec2.ZERO : tb;
        this.tc = tc == null ? Vec2.ZERO : tc;
        this.material = material;
        updateDerived();
        if (this.na == null) this.na = faceNormal;
        else this.na = this.na.normalized();
        if (this.nb == null) this.nb = faceNormal;
        else this.nb = this.nb.normalized();
        if (this.nc == null) this.nc = faceNormal;
        else this.nc = this.nc.normalized();
    }

    void transform(double cx, double minY, double cz, double scale) {
        a = new Vec3((a.x - cx) * scale, (a.y - minY) * scale, (a.z - cz) * scale);
        b = new Vec3((b.x - cx) * scale, (b.y - minY) * scale, (b.z - cz) * scale);
        c = new Vec3((c.x - cx) * scale, (c.y - minY) * scale, (c.z - cz) * scale);
        updateDerived();
    }

    private void updateDerived() {
        e1 = b.sub(a);
        e2 = c.sub(a);
        faceNormal = e1.cross(e2).normalized();
        bounds = Aabb.forTriangle(a, b, c);
        centroid = a.add(b).add(c).div(3.0);
    }

    boolean intersect(Ray ray, Hit hit) {
        Vec3 p = ray.direction.cross(e2);
        double det = e1.dot(p);
        if (Math.abs(det) < 1e-10) return false;
        double invDet = 1.0 / det;
        Vec3 tvec = ray.origin.sub(a);
        double u = tvec.dot(p) * invDet;
        if (u < 0.0 || u > 1.0) return false;
        Vec3 q = tvec.cross(e1);
        double v = ray.direction.dot(q) * invDet;
        if (v < 0.0 || u + v > 1.0) return false;
        double t = e2.dot(q) * invDet;
        if (t < 1e-5 || t >= hit.t) return false;

        Vec3 n = na.mul(1.0 - u - v).add(nb.mul(u)).add(nc.mul(v)).normalized();
        if (n.dot(ray.direction) > 0) n = n.negate();
        hit.t = t;
        hit.point = ray.at(t);
        hit.normal = n;
        hit.material = material;
        hit.baryU = u;
        hit.baryV = v;
        hit.hasUv = hasUv;
        hit.texU = ta.u() * (1.0 - u - v) + tb.u() * u + tc.u() * v;
        hit.texV = ta.v() * (1.0 - u - v) + tb.v() * u + tc.v() * v;
        return true;
    }
}
