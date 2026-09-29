package dev.fox.monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingWorker;

/** One CLI handles both headless operation and UI actions. Never interpolates a shell command. */
final class Framework {
    private final MonitorConfig config;
    private boolean busy;
    Framework(MonitorConfig config) {
        this.config = config;
    }
    void run(List<String> arguments, Consumer<String> success, Consumer<String> failure) {
        if (busy) {
            failure.accept("Another analysis operation is still running.");
            return;
        }
        busy = true;
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception {
                List<String> command = new ArrayList<>(Arrays.asList(config.python,
                    config.framework.resolve("analytics/lab.py").toString(), "--store",
                    config.registry.toString()));
                command.addAll(arguments);
                Process process = new ProcessBuilder(command)
                                      .directory(config.framework.toFile())
                                      .redirectErrorStream(true)
                                      .start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                Thread reader = new Thread(() -> {
                    try (InputStream stream = process.getInputStream()) {
                        byte[] bytes = new byte[4096];
                        int n;
                        while ((n = stream.read(bytes)) >= 0)
                            synchronized (output) {
                                if (output.size() + n <= 262144)
                                    output.write(bytes, 0, n);
                            }
                    } catch (IOException ignored) {
                    }
                }, "analysis-output");
                reader.setDaemon(true);
                reader.start();
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IOException(
                        "Analysis exceeded two minutes; no UI action was assumed successful.");
                }
                reader.join(2000);
                String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (process.exitValue() != 0)
                    throw new IOException(result);
                return result;
            }
            protected void done() {
                busy = false;
                try {
                    success.accept(get());
                } catch (Exception error) {
                    failure.accept(error.getCause() == null ? error.toString()
                                                            : error.getCause().getMessage());
                }
            }
        }.execute();
    }
}
