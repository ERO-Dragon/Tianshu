package com.rheinmetal.tianshu.api.capability.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a UTF-8 prefix within a byte budget, dropping any partial trailing character. */
public final class ContentText {
    private ContentText() {
    }

    public static String read(InputStream input, int limit) throws IOException {
        byte[] prefix = input.readNBytes(limit);
        CharBuffer text = CharBuffer.allocate(prefix.length);
        var decoded = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(prefix), text, prefix.length < limit);
        if (decoded.isError()) {
            decoded.throwException();
        }
        return text.flip().toString();
    }

    public static Path inside(Path directory, Path target) throws IOException {
        Path real = target.toRealPath();
        if (!real.startsWith(directory.toRealPath())) {
            throw new IOException("content path outside its directory");
        }
        return real;
    }

    public static String read(Path file, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return read(input, limit);
        }
    }
}
