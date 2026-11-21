package renderer;

import java.awt.image.BufferedImage;

final class Material {
    Vec3 diffuse = new Vec3(0.55, 0.58, 0.60);
    Vec3 specular = new Vec3(0.12, 0.12, 0.12);
    Vec3 emission = new Vec3(0, 0, 0);
    double roughness = 0.72;
    BufferedImage diffuseTexture;
    boolean water;

    Vec3 diffuseAt(double u, double v) {
        if (diffuseTexture == null) return diffuse;
        int w = diffuseTexture.getWidth(), h = diffuseTexture.getHeight();
        double x = wrap(u) * (w - 1);
        double y = (1.0 - wrap(v)) * (h - 1);
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = (x0 + 1) % w, y1 = (y0 + 1) % h;
        double tx = x - x0, ty = y - y0;
        Vec3 c00 = texel(x0, y0), c10 = texel(x1, y0);
        Vec3 c01 = texel(x0, y1), c11 = texel(x1, y1);
        Vec3 top = Vec3.lerp(c00, c10, tx);
        Vec3 bottom = Vec3.lerp(c01, c11, tx);
        return diffuse.mul(Vec3.lerp(top, bottom, ty));
    }

    private Vec3 texel(int x, int y) {
        int rgb = diffuseTexture.getRGB(x, y);
        return new Vec3(toLinear((rgb >>> 16) & 255), toLinear((rgb >>> 8) & 255), toLinear(rgb & 255));
    }

    private static double toLinear(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double wrap(double v) {
        return v - Math.floor(v);
    }

    Material copy() {
        Material m = new Material();
        m.diffuse = diffuse;
        m.specular = specular;
        m.emission = emission;
        m.roughness = roughness;
        m.diffuseTexture = diffuseTexture;
        m.water = water;
        return m;
    }
}
