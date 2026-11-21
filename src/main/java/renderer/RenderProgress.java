package renderer;

@FunctionalInterface
interface RenderProgress {
    void update(int completed, int total);
}
