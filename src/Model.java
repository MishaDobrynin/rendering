package renderer;

import java.nio.file.Path;
import java.util.List;

record Model(Path source, List<Triangle> triangles) { }
