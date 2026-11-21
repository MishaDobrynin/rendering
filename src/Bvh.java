package renderer;

import java.util.List;

/** In-place median BVH. It avoids recursively copying large triangle sub-lists. */
final class Bvh {
    private final Triangle[] ordered;
    private final Node root;

    Bvh(List<Triangle> triangles) {
        if (triangles.isEmpty()) throw new IllegalArgumentException("The scene contains no triangles.");
        ordered = triangles.toArray(Triangle[]::new);
        root = build(0, ordered.length, 0);
    }

    boolean intersect(Ray ray, Hit hit) { return root.intersect(ray, hit, ordered); }

    private Node build(int start, int end, int depth) {
        Aabb box = ordered[start].bounds;
        for (int i = start + 1; i < end; i++) box = box.union(ordered[i].bounds);
        int count = end - start;
        if (count <= 8 || depth >= 48) return new Node(box, start, count, null, null);

        int axis = box.extent(0) >= box.extent(1) && box.extent(0) >= box.extent(2) ? 0
                : box.extent(1) >= box.extent(2) ? 1 : 2;
        quickSort(start, end - 1, axis);
        int mid = start + count / 2;
        return new Node(box, 0, 0, build(start, mid, depth + 1), build(mid, end, depth + 1));
    }

    private double key(Triangle t, int axis) { return Aabb.component(t.centroid, axis); }

    private void quickSort(int low, int high, int axis) {
        int i = low, j = high;
        double pivot = key(ordered[(low + high) >>> 1], axis);
        while (i <= j) {
            while (key(ordered[i], axis) < pivot) i++;
            while (key(ordered[j], axis) > pivot) j--;
            if (i <= j) {
                Triangle tmp = ordered[i]; ordered[i] = ordered[j]; ordered[j] = tmp;
                i++; j--;
            }
        }
        if (low < j) quickSort(low, j, axis);
        if (i < high) quickSort(i, high, axis);
    }

    private static final class Node {
        final Aabb bounds;
        final int start, count;
        final Node left, right;
        Node(Aabb bounds, int start, int count, Node left, Node right) {
            this.bounds = bounds; this.start = start; this.count = count; this.left = left; this.right = right;
        }
        boolean intersect(Ray ray, Hit hit, Triangle[] triangles) {
            if (!bounds.hit(ray, hit.t)) return false;
            boolean found = false;
            if (left == null) {
                int end = start + count;
                for (int i = start; i < end; i++) found |= triangles[i].intersect(ray, hit);
                return found;
            }
            found |= left.intersect(ray, hit, triangles);
            found |= right.intersect(ray, hit, triangles);
            return found;
        }
    }
}
