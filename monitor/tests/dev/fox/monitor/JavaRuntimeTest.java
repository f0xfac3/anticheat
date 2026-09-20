/**
 * JavaRuntimeTest.java checks the startup probe without running Minecraft.
 * The fixture's version strings are simulated; the JVM used to launch it is real.
 */
package dev.fox.monitor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class JavaRuntimeTest{
    private static int passed;
    private static Path java;
    private static String classes;

    private interface Task{
        void run() throws Exception;
    }

    private static void test(String name, Task task) throws Exception{
        task.run();
        ++passed;
        System.out.println("PASS " + name);
    }

    private static void require(boolean result, String message){
        if(!result)
            throw new AssertionError(message);
    }

    private static IOException reject(Task task) throws Exception{
        try{
            task.run();
        }catch(IOException expected){
            return expected;
        }

        throw new AssertionError("Expected rejection");
    }

    private static List<String> fixture(String... arguments){
        List<String> result = new ArrayList<>(Arrays.asList(
            java.toString(),
            "-cp",
            classes,
            "dev.fox.monitor.JavaProbeFixture"
        ));
        result.addAll(Arrays.asList(arguments));
        return result;
    }

    private static void awaitReady(Path ready, Thread worker) throws Exception{
        for(int i = 0; i < 150 && worker.isAlive() && !Files.exists(ready); ++i)
            Thread.sleep(20);

        require(Files.exists(ready), "fixture never started");
    }

    private static boolean released(Path path) throws Exception{
        for(int i = 0; i < 100; ++i){
            try(FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)){
                FileLock lock = channel.tryLock();

                if(lock != null){
                    lock.release();
                    return true;
                }
            }

            Thread.sleep(20);
        }

        return false;
    }

    public static void main(String[] arguments) throws Exception{
        java = Paths.get(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java"
        );
        classes = Paths.get(JavaRuntimeTest.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();

        test("original wait-before-read pattern blocks on a full pipe", ()->{
            Process old = new ProcessBuilder(fixture("flood"))
                .redirectErrorStream(true)
                .start();

            try{
                require(!old.waitFor(1, TimeUnit.SECONDS), "expected full-pipe blockage");
            }finally{
                old.destroyForcibly();
                old.waitFor(3, TimeUnit.SECONDS);
                old.getInputStream().close();
                old.getErrorStream().close();
                old.getOutputStream().close();
            }
        });

        test("128 KiB stdout/stderr drained while process runs", ()->{
            String text = JavaRuntime.probe(fixture("flood"), 5000);
            require(text.length() > 131072, "large output incomplete");
            JavaRuntime.validate(text, java);
        });

        test("probe closes stdin and child can reach EOF", ()->{
            JavaRuntime.validate(JavaRuntime.probe(fixture("stdin"), 5000), java);
        });

        test("output beyond capture limit rejected without timeout", ()->{
            IOException error = reject(()->JavaRuntime.probe(fixture("oversize"), 5000));
            require(error.getMessage().contains("exceeds 256 KiB"), error.toString());
        });

        test("nonzero exit includes exit code and captured stderr", ()->{
            IOException error = reject(()->JavaRuntime.probe(fixture("nonzero"), 5000));
            require(error.getMessage().contains("code 7"), "exit code omitted");
            require(error.getMessage().contains("deliberately failed"), "output omitted");
        });

        test("property parser accepts Java 8 x64 CRLF output", ()->{
            JavaRuntime.validate(
                "Property settings:\r\n    java.specification.version = 1.8\r\n    sun.arch.data.model = 64\r\n",
                java
            );
        });

        test("Java 21 is rejected for the legacy server", ()->{
            IOException error = reject(()->JavaRuntime.validate(
                "java.specification.version = 21\nsun.arch.data.model = 64\n",
                java
            ));
            require(error.getMessage().contains("Found Java 21"), "wrong runtime diagnosis");
        });

        test("Java 8 32-bit rejected", ()->{
            reject(()->JavaRuntime.validate(
                "java.specification.version = 1.8\nsun.arch.data.model = 32\n",
                java
            ));
        });

        test("substring version match does not accept 1.80 or 640", ()->{
            reject(()->JavaRuntime.validate(
                "java.specification.version = 1.80\nsun.arch.data.model = 640\n",
                java
            ));
        });

        test("missing properties rejected instead of skipping validation", ()->{
            reject(()->JavaRuntime.validate("no version information", java));
        });

        test("real installed JVM version command completes", ()->{
            String text = JavaRuntime.probe(Arrays.asList(
                java.toString(), "-XshowSettings:properties", "-version"
            ), 15000);
            require(text.contains("java.specification.version"), "no actual version");
            require(text.contains("sun.arch.data.model"), "no actual architecture");
            System.out.println("  Actual test JVM: " + System.getProperty("java.version"));
        });

        test("real timeout kills the child and releases its OS file lock", ()->{
            Path dir = Files.createTempDirectory("monitor-probe-timeout-");
            Path lock = dir.resolve("child.lock");
            Path ready = dir.resolve("ready");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(()->{
                try{
                    JavaRuntime.probe(fixture("wait", lock.toString(), ready.toString()), 2000);
                }catch(Throwable error){
                    failure.set(error);
                }
            });
            worker.start();
            awaitReady(ready, worker);
            worker.join(5000);
            require(!worker.isAlive(), "timeout failed to return");
            require(failure.get() instanceof IOException, "timeout did not throw IOException");
            require(failure.get().getMessage().contains("timed out"), failure.get().toString());
            require(released(lock), "timeout left child running");
        });

        test("interruption kills the probe instead of leaving a child", ()->{
            Path dir = Files.createTempDirectory("monitor-probe-interrupt-");
            Path lock = dir.resolve("child.lock");
            Path ready = dir.resolve("ready");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(()->{
                try{
                    JavaRuntime.probe(fixture("wait", lock.toString(), ready.toString()), 30000);
                }catch(Throwable error){
                    failure.set(error);
                }
            });
            worker.start();
            awaitReady(ready, worker);
            worker.interrupt();
            worker.join(5000);
            require(!worker.isAlive(), "interrupted probe did not finish");
            require(failure.get() instanceof InterruptedException, "interruption not propagated");
            require(released(lock), "interruption left child running");
        });

        test("missing Java executable gives a launch error", ()->{
            Path absent = Files.createTempDirectory("monitor-no-java-").resolve("java-missing");
            reject(()->JavaRuntime.check(absent));
        });

        // Optional genuine Java 8 runtime, not the simulated property fixture.
        if(arguments.length > 0){
            test("real Java 8 x64 runtime passes the production check", ()->{
                JavaRuntime.check(Paths.get(arguments[0]));
            });
        }

        System.out.println(passed + " Java startup tests passed");
    }
}
