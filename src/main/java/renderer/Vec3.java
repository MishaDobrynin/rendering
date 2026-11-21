package renderer;

final class Vec3 {
    final double x;
    final double y;
    final double z;

    Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    Vec3 add(Vec3 v) { return new Vec3(x + v.x, y + v.y, z + v.z); }
    Vec3 sub(Vec3 v) { return new Vec3(x - v.x, y - v.y, z - v.z); }
    Vec3 mul(double s) { return new Vec3(x * s, y * s, z * s); }
    Vec3 mul(Vec3 v) { return new Vec3(x * v.x, y * v.y, z * v.z); }
    Vec3 div(double s) { return new Vec3(x / s, y / s, z / s); }
    double dot(Vec3 v) { return x * v.x + y * v.y + z * v.z; }
    Vec3 cross(Vec3 v) {
        return new Vec3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }
    double lengthSquared() { return dot(this); }
    double length() { return Math.sqrt(lengthSquared()); }
    Vec3 normalized() {
        double len = length();
        return len < 1e-12 ? new Vec3(0, 1, 0) : div(len);
    }
    Vec3 negate() { return new Vec3(-x, -y, -z); }
    double maxComponent() { return Math.max(x, Math.max(y, z)); }
    Vec3 clamp(double lo, double hi) {
        return new Vec3(Math.max(lo, Math.min(hi, x)), Math.max(lo, Math.min(hi, y)), Math.max(lo, Math.min(hi, z)));
    }
    static Vec3 lerp(Vec3 a, Vec3 b, double t) { return a.mul(1 - t).add(b.mul(t)); }
}
