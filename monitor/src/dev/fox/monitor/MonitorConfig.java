/**
 * MonitorConfig.java resolves the local lab server and its Java 8 runtime.
 * These settings belong to the desktop launcher, not the detection engine.
 */

package dev.fox.monitor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

final class MonitorConfig{
    final Path home;
    final Path server;
    final Path serverJar;
    final String javaHome;
    final String minimumHeap;
    final String maximumHeap;

    MonitorConfig(Path home) throws IOException{
        this.home = home.toAbsolutePath().normalize();
        Properties values = new Properties();
        Path file = home.resolve("monitor.properties");

        if(Files.isRegularFile(file)){
            try(Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)){
                values.load(reader);
            }
        }

        server = home.resolve(values.getProperty("server.directory", "../../server-1.8"))
            .toAbsolutePath().normalize();
        serverJar = server.resolve(values.getProperty("server.jar", "spigot-1.8.8.jar"));
        javaHome = values.getProperty("java.home", "auto").trim();
        minimumHeap = heap(values.getProperty("heap.minimum", "1G"));
        maximumHeap = heap(values.getProperty("heap.maximum", "2G"));
    }

    private String heap(String value){
        if(!value.matches("[1-9][0-9]{0,5}[mMgG]"))
            throw new IllegalArgumentException("Heap must look like 1G or 2048M");

        return value;
    }

    Path javaExecutable() throws IOException{
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String executable = windows ? "java.exe" : "java";

        if(!javaHome.equals("auto")){
            Path root = home.resolve(javaHome).toAbsolutePath().normalize();
            Path result = root.resolve("bin").resolve(executable);

            if(!Files.isRegularFile(result))
                throw new IOException("java.home must be a JDK/JRE root, not bin or java.exe: " + root);

            return result;
        }

        List<Path> roots = new ArrayList<>();

        if(System.getProperty("java.specification.version").equals("1.8"))
            roots.add(Paths.get(System.getProperty("java.home")));

        for(String key : new String[]{"JAVA8_HOME", "JAVA_HOME"}){
            String value = System.getenv(key);

            if(value != null && !value.isEmpty())
                roots.add(Paths.get(value));
        }

        String programFiles = System.getenv("ProgramFiles");

        if(programFiles != null){
            addDirectories(roots, Paths.get(programFiles, "Eclipse Adoptium"));
            addDirectories(roots, Paths.get(programFiles, "Java"));
        }

        if(!windows)
            addDirectories(roots, Paths.get("/usr/lib/jvm"));

        for(Path root : roots){
            Path release = root.resolve("release");
            Path binary = root.resolve("bin").resolve(executable);

            if(!Files.isRegularFile(release) || !Files.isRegularFile(binary))
                continue;

            String text = new String(Files.readAllBytes(release), StandardCharsets.UTF_8);

            if(text.contains("JAVA_VERSION=\"1.8.") &&
                (text.contains("amd64") || text.contains("x86_64"))){
                return binary;
            }
        }

        throw new IOException(
            "No Java 8 x64 runtime found. Set java.home in monitor/monitor.properties "
            + "to your Java 8 folder, using forward slashes."
        );
    }

    private void addDirectories(List<Path> roots, Path parent) throws IOException{
        if(!Files.isDirectory(parent))
            return;

        List<Path> children = new ArrayList<>();

        try(java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(parent)){
            for(Path path : stream){
                if(Files.isDirectory(path))
                    children.add(path);
            }
        }

        Collections.sort(children, Collections.reverseOrder());
        roots.addAll(children);
    }

    int serverPort() throws IOException{
        Properties values = new Properties();
        Path file = server.resolve("server.properties");

        if(Files.isRegularFile(file)){
            try(Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)){
                values.load(reader);
            }
        }

        return Integer.parseInt(values.getProperty("server-port", "25565"));
    }

    String fastBreakSetting(){
        return checkSetting("fastbreak");
    }

    String checkSetting(String check){
        Path file = server.resolve("plugins/FoxAntiCheat/engine.conf");

        try{
            for(String line : Files.readAllLines(file, StandardCharsets.UTF_8)){
                String compact = line.replace(" ", "").replace("\t", "");

                if(compact.equals(check + ".enabled=false"))
                    return "Configured off";

                if(compact.equals(check + ".enabled=true"))
                    return "Configured on";
            }

            return "Default on (v0.3)";
        }catch(IOException ignored){
            return "Config unknown";
        }
    }
}
