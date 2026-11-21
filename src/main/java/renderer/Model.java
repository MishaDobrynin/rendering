package renderer;

import java.nio.file.Path;
import java.util.List;

record Model(Path source, List<Triangle> triangles, List<String> warnings) {
    Model(Path source, List<Triangle> triangles) {
        this(source, triangles, List.of());
    }

    Model {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
