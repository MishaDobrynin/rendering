package renderer;

import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

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

    /** A stable, flat representation of this BVH for GPU traversal. */
    GpuData gpuData() {
        List<Node> flat = new ArrayList<>();
        flatten(root, flat);
        Map<Node, Integer> indices = new IdentityHashMap<>();
        for (int i = 0; i < flat.size(); i++) indices.put(flat.get(i), i);
        float[] bounds = new float[flat.size() * 8];
        int[] metadata = new int[flat.size() * 4];
        for (int i = 0; i < flat.size(); i++) {
            Node n = flat.get(i);
            int b = i * 8;
            bounds[b] = (float) n.bounds.min.x;
            bounds[b + 1] = (float) n.bounds.min.y;
            bounds[b + 2] = (float) n.bounds.min.z;
            bounds[b + 3] = 0;
            bounds[b + 4] = (float) n.bounds.max.x;
            bounds[b + 5] = (float) n.bounds.max.y;
            bounds[b + 6] = (float) n.bounds.max.z;
            bounds[b + 7] = 0;
            int m = i * 4;
            metadata[m] = n.left == null ? -1 : indices.get(n.left);
            metadata[m + 1] = n.right == null ? -1 : indices.get(n.right);
            metadata[m + 2] = n.start;
            metadata[m + 3] = n.count;
        }
        return new GpuData(ordered, bounds, metadata);
    }

    private static void flatten(Node node, List<Node> flat) {
        flat.add(node);
        if (node.left != null) {
            flatten(node.left, flat);
            flatten(node.right, flat);
        }
    }

    record GpuData(Triangle[] orderedTriangles, float[] nodeBounds, int[] nodeMetadata) { }

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
