package renderer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/** Writes RGB PNG scanlines incrementally; memory use is proportional to image width, not total pixel count. */
final class StreamingPngWriter implements Closeable {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final DataOutputStream output;
    private final ChunkedIdatOutputStream idatChunks;
    private final DeflaterOutputStream compressed;
    private final byte[] row;
    private boolean closed;

    StreamingPngWriter(Path path, int width, int height) throws IOException {
        if (width < 1 || height < 1) throw new IllegalArgumentException("PNG dimensions must be positive.");
        this.output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)));
        output.write(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;  // 8 bits per channel
        header[9] = 2;  // truecolor RGB
        header[10] = 0;
        header[11] = 0;
        header[12] = 0;
        writeChunk(output, "IHDR", header);
        this.idatChunks = new ChunkedIdatOutputStream(output);
        this.compressed = new DeflaterOutputStream(idatChunks, new Deflater(6), 64 * 1024);
        this.row = new byte[Math.addExact(Math.multiplyExact(width, 3), 1)];
    }

    void writeRow(int[] rgbPixels) throws IOException {
        if (closed) throw new IOException("PNG writer is closed.");
        if (rgbPixels.length * 3L + 1L != row.length) throw new IllegalArgumentException("Row width does not match PNG width.");
        row[0] = 0; // PNG filter: None
        int offset = 1;
        for (int rgb : rgbPixels) {
            row[offset++] = (byte) (rgb >>> 16);
            row[offset++] = (byte) (rgb >>> 8);
            row[offset++] = (byte) rgb;
        }
        compressed.write(row);
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            compressed.finish();
            compressed.flush();
            idatChunks.finish();
            writeChunk(output, "IEND", new byte[0]);
            output.flush();
        } finally {
            compressed.close();
            output.close();
        }
    }

    private static void writeChunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] tag = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(tag);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(tag);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    private static void putInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static final class ChunkedIdatOutputStream extends OutputStream {
        private static final int CHUNK_SIZE = 64 * 1024;
        private final DataOutputStream target;
        private final byte[] buffer = new byte[CHUNK_SIZE];
        private int count;

        ChunkedIdatOutputStream(DataOutputStream target) { this.target = target; }

        @Override public void write(int value) throws IOException {
            buffer[count++] = (byte) value;
            if (count == buffer.length) flushChunk();
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            while (length > 0) {
                int copy = Math.min(length, buffer.length - count);
                System.arraycopy(bytes, offset, buffer, count, copy);
                count += copy;
                offset += copy;
                length -= copy;
                if (count == buffer.length) flushChunk();
            }
        }

        void finish() throws IOException { if (count > 0) flushChunk(); }

        private void flushChunk() throws IOException {
            byte[] data = count == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, count);
            writeChunk(target, "IDAT", data);
            count = 0;
        }

        @Override public void close() throws IOException { finish(); }
    }
}
