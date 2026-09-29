package dev.fox.anticheat.report;

import java.io.*;
import java.nio.*;
import java.util.zip.GZIPInputStream;

/** Exact raw-capture replay used to compare Java live features with the Python trainer. */
public final class BehaviorReplayTest {
    public static void main(String[] args) throws Exception {
        BehaviorTelemetry telemetry = new BehaviorTelemetry(w -> System.out.println(w.json()));
        for (String path : args)
            try (InputStream input = new GZIPInputStream(new FileInputStream(path))) {
                DataInputStream data = new DataInputStream(input);
                while (true) {
                    int first = data.read();
                    if (first < 0)
                        break;
                    int size = first | (data.readUnsignedByte() << 8)
                        | (data.readUnsignedByte() << 16) | (data.readUnsignedByte() << 24);
                    if (size < 48 || size > 8192)
                        throw new IOException("Invalid frame length");
                    byte[] bytes = new byte[size];
                    data.readFully(bytes);
                    telemetry.accept(ByteBuffer.wrap(bytes));
                }
            }
    }
}
