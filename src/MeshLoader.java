package renderer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

final class MeshLoader {
    private MeshLoader() { }

    static Model load(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".obj")) return ObjLoader.load(path);
        throw new IOException("Unsupported model format. Supported: OBJ.");
    }
}
