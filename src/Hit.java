package renderer;

final class Hit {
    double t = Double.POSITIVE_INFINITY;
    double baryU;
    double baryV;
    double texU;
    double texV;
    boolean hasUv;
    Vec3 point;
    Vec3 normal;
    Material material;
}
