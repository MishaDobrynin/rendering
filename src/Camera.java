package renderer;

final class Camera {
    double yaw = 0.75;
    double pitch = 0.24;
    double distance = 6.8;
    Vec3 target = new Vec3(0, 1.25, 0);

    void orbit(double dx, double dy) {
        yaw += dx * 0.008;
        pitch = Math.max(-1.25, Math.min(1.25, pitch + dy * 0.008));
    }

    void zoom(double wheelRotation) {
        distance *= Math.pow(1.12, wheelRotation);
        distance = Math.max(2.8, Math.min(35.0, distance));
    }

    CameraSnapshot snapshot() {
        Vec3 offset = new Vec3(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)).mul(distance);
        Vec3 position = target.add(offset);
        Vec3 forward = target.sub(position).normalized();
        Vec3 right = forward.cross(new Vec3(0, 1, 0)).normalized();
        Vec3 up = right.cross(forward).normalized();
        return new CameraSnapshot(position, forward, right, up, Math.toRadians(43.0));
    }
}

record CameraSnapshot(Vec3 position, Vec3 forward, Vec3 right, Vec3 up, double fov) {
    Ray ray(double px, double py, int width, int height) {
        double aspect = (double) width / height;
        double scale = Math.tan(fov * 0.5);
        double sx = (2.0 * ((px + 0.5) / width) - 1.0) * aspect * scale;
        double sy = (1.0 - 2.0 * ((py + 0.5) / height)) * scale;
        Vec3 dir = forward.add(right.mul(sx)).add(up.mul(sy)).normalized();
        return new Ray(position, dir);
    }

    Projected project(Vec3 point, int width, int height) {
        Vec3 rel = point.sub(position);
        double z = rel.dot(forward);
        if (z <= 0.08) return null;
        double aspect = (double) width / height;
        double scale = 1.0 / Math.tan(fov * 0.5);
        double nx = rel.dot(right) * scale / (z * aspect);
        double ny = rel.dot(up) * scale / z;
        return new Projected((nx * 0.5 + 0.5) * width, (0.5 - ny * 0.5) * height, z);
    }
}

record Projected(double x, double y, double z) { }
