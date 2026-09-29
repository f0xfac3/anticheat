package lab;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

/** Local experiment controls. All Bukkit access runs on the server thread. */
public final class AutoSampleLab extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private final String boot = UUID.randomUUID().toString();
    private HttpServer http;
    private ExecutorService executor;
    private volatile String snapshot = "{}";
    private String token, owner = "", playerName = "", fault = "";
    private long heartbeat, tick, previousTick;
    private double tickMs = 50;
    private boolean recording;
    private World arena;
    private ReachFixture reach;
    private static final String WORLD = "ac_auto_samples";

    @Override public void onEnable() {
        try {
            getDataFolder().mkdirs();
            Path key = new File(getDataFolder(), "token.txt").toPath();
            if (!Files.exists(key)) {
                byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
                StringBuilder value = new StringBuilder();
                for (byte b : bytes) value.append(String.format("%02x", b & 255));
                Files.write(key, value.toString().getBytes(StandardCharsets.UTF_8));
            }
            token = new String(Files.readAllBytes(key), StandardCharsets.UTF_8).trim();
            // Load before logins: otherwise saved arena coordinates are restored
            // into the default world after a server restart.
            ensureArena();
            reach = new ReachFixture(this, this::command, () -> arena);
            getServer().getPluginManager().registerEvents(reach, this);
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 8769), 8);
            executor = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r,"auto-sample-http"); t.setDaemon(true); return t; });
            http.setExecutor(executor); http.createContext("/", this::handle); http.start();
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getScheduler().runTaskTimer(this, this::observe, 1, 1);
            getLogger().info("Local sample observer ready on 127.0.0.1:8769; private arena is generated on first use.");
        } catch (Exception e) {
            getLogger().severe("Auto sample bridge unavailable: " + e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void handle(HttpExchange x) throws IOException {
        int code = 200; String result;
        try {
            if (!token.equals(x.getRequestHeaders().getFirst("X-Lab-Token"))) {
                code = 403; result = "{\"error\":\"authentication required\"}";
            } else if (x.getRequestMethod().equals("GET") && x.getRequestURI().getPath().equals("/state")) {
                result = snapshot;
            } else if (x.getRequestMethod().equals("POST") && x.getRequestURI().getPath().equals("/action")) {
                ByteArrayOutputStream body = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
                while ((n = x.getRequestBody().read(buffer)) != -1) {
                    body.write(buffer, 0, n); if (body.size() > 4096) throw new IllegalArgumentException("request too large");
                }
                final Map<String,String> args = new HashMap<>();
                for (String field : new String(body.toByteArray(), StandardCharsets.UTF_8).split("&")) {
                    String[] parts = field.split("=", 2);
                    if (parts.length == 2) args.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
                }
                Future<String> task = getServer().getScheduler().callSyncMethod(this, () -> action(args));
                try { result = task.get(15, TimeUnit.SECONDS); }
                catch (Exception e) { task.cancel(false); throw e; }
            } else { code = 404; result = "{\"error\":\"unknown endpoint\"}"; }
        } catch (Exception e) {
            code = 400; Throwable cause = e.getCause() == null ? e : e.getCause();
            result = gson.toJson(Collections.singletonMap("error", cause.toString()));
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = x.getResponseBody()) { out.write(bytes); }
    }

    private String field(Map<String,String> a, String name) {
        String s = a.get(name);
        if (s == null || !s.matches("[A-Za-z0-9_.+=,:-]{1,80}")) throw new IllegalArgumentException("invalid " + name);
        return s;
    }

    private List<String> command(String text) {
        List<String> output = new ArrayList<>(); ConsoleCommandSender console = getServer().getConsoleSender();
        ConsoleCommandSender receiver = (ConsoleCommandSender) Proxy.newProxyInstance(
            ConsoleCommandSender.class.getClassLoader(), new Class<?>[]{ConsoleCommandSender.class}, (p, method, args) -> {
                if (method.getName().equals("sendMessage")) {
                    if (args[0] instanceof String[]) Collections.addAll(output, (String[])args[0]);
                    else output.add(String.valueOf(args[0])); return null;
                }
                return method.invoke(console, args);
            });
        if (!getServer().dispatchCommand(receiver, text)) throw new IllegalStateException("command failed: " + text);
        return output;
    }

    private Player player() {
        Player p = getServer().getPlayerExact(playerName);
        if (p == null || !p.isOnline()) throw new IllegalStateException("selected player is offline");
        return p;
    }

    private void solePlayer() {
        if (getServer().getOnlinePlayers().size() != 1) throw new IllegalStateException("Exactly one client must be online; disconnect the other client.");
    }

    private String action(Map<String,String> a) {
        String op = field(a, "op"), requested = field(a, "owner");
        Map<String,Object> result = new LinkedHashMap<>();
        if (op.startsWith("reach_")) {
            if (!owner.isEmpty()) throw new IllegalStateException("Stop the solo controller first.");
            result.putAll(reach.action(op, requested, a));
            result.put("ok", true); result.put("boot", boot); return gson.toJson(result);
        }
        if (reach.active()) throw new IllegalStateException("Stop the Reach controller first.");
        if (op.equals("shutdown")) {
            if (!owner.isEmpty() || recording) throw new IllegalStateException("Stop the active controller before shutting down.");
            if (command("acdata status").stream().noneMatch(s -> s.contains("active=0")))
                throw new IllegalStateException("Cannot shut down while a manual recording is active.");
            getServer().getScheduler().runTaskLater(this, () -> getServer().shutdown(), 2);
        } else if (op.equals("acquire")) {
            if (!owner.isEmpty() && !owner.equals(requested)) throw new IllegalStateException("another controller owns the lease");
            solePlayer(); String name = field(a, "player");
            if (getServer().getPlayerExact(name) == null) throw new IllegalArgumentException("Player not online: " + name);
            List<String> status = command("acdata status");
            if (status.stream().noneMatch(s -> s.contains("active=0") && s.contains("error=null")))
                throw new IllegalStateException("Recorder must be healthy and idle: " + status);
            owner = requested; playerName = name; fault = ""; heartbeat = System.nanoTime();
        } else {
            if (owner.isEmpty() || !owner.equals(requested)) throw new IllegalStateException("lease not owned");
            heartbeat = System.nanoTime();
            if (op.equals("heartbeat")) { result.put("recording", recording); }
            else if (op.equals("prepare")) {
                if (recording) throw new IllegalStateException("cannot reset during a trial");
                solePlayer(); ensureArena(); Player p = player();
                if (p.isDead()) p.spigot().respawn();
                if (p.isDead()) throw new IllegalStateException("Respawn the selected client before collection.");
                p.closeInventory(); p.setGameMode(GameMode.SURVIVAL); p.setFlying(false); p.setAllowFlight(false);
                p.setWalkSpeed(0.2f); p.setFoodLevel(20); p.setSaturation(20); p.setHealth(p.getMaxHealth());
                p.setFireTicks(0); p.setFallDistance(0);
                for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
                if (!p.teleport(new Location(arena, 0.5, 65, 0.5, 0, 0)))
                    throw new IllegalStateException("Arena teleport failed.");
            } else if (op.equals("start")) {
                solePlayer(); if (recording) throw new IllegalStateException("trial already active");
                if (!player().getWorld().getName().equals(WORLD)) throw new IllegalStateException("prepare the arena first");
                String text = "acdata start " + playerName + " " + field(a,"label") + " " + field(a,"module") + " " + field(a,"client") + " " + field(a,"setting") + " " + field(a,"scenario");
                List<String> reply = command(text); String joined = String.join(" ", reply);
                if (!joined.startsWith("Recording trial-")) throw new IllegalStateException(joined);
                recording = true; result.put("trial", joined.split(" ")[1]); result.put("reply", reply);
            } else if (op.equals("stop")) { finish(); }
            else if (op.equals("release") || op.equals("disconnect")) {
                finish(); Player p = getServer().getPlayerExact(playerName);
                owner = ""; playerName = "";
                if (op.equals("disconnect") && p != null) p.kickPlayer("Lab controller disconnected this client. See controller output for results.");
            } else throw new IllegalArgumentException("unknown action");
        }
        if (owner.equals(requested)) heartbeat = System.nanoTime();
        result.put("ok", true); result.put("boot", boot); return gson.toJson(result);
    }

    private void finish() {
        if (recording) {
            if (getServer().getPlayerExact(playerName) != null) command("acdata stop " + playerName);
            recording = false;
        }
    }

    private void ensureArena() {
        if (arena != null) return;
        arena = getServer().getWorld(WORLD);
        if (arena == null) {
            WorldCreator c = new WorldCreator(WORLD); c.seed(829347); c.generateStructures(false);
            c.generator(new ChunkGenerator() {
                @Override public byte[][] generateBlockSections(World world, Random random, int x, int z, BiomeGrid biomes) {
                    byte[][] sections = new byte[16][];
                    sections[3] = new byte[4096]; sections[4] = new byte[4096];
                    for (int bx=0; bx<16; bx++) for (int bz=0; bz<16; bz++) {
                        sections[3][(15 << 8) | (bz << 4) | bx] = (byte)Material.BEDROCK.getId();
                        sections[4][(bz << 4) | bx] = (byte)Material.GRASS.getId();
                        biomes.setBiome(bx,bz,org.bukkit.block.Biome.PLAINS);
                    }
                    return sections;
                }
            });
            arena = c.createWorld();
        }
        arena.setDifficulty(Difficulty.PEACEFUL); arena.setTime(6000); arena.setStorm(false);
        arena.setGameRuleValue("doDaylightCycle","false"); arena.setGameRuleValue("doMobSpawning","false");
        arena.setGameRuleValue("doWeatherCycle","false"); arena.setGameRuleValue("keepInventory","true");
        arena.setSpawnLocation(0,65,0);
        for (int x=-6;x<=6;x++) for (int z=-6;z<=6;z++) arena.loadChunk(x,z,true);
    }

    @EventHandler public void food(FoodLevelChangeEvent e) {
        if (!owner.isEmpty() && e.getEntity().getName().equals(playerName) && e.getEntity().getWorld().getName().equals(WORLD)) e.setCancelled(true);
    }

    private void observe() {
        long now = System.nanoTime(); tick++;
        reach.observe(now);
        if (previousTick != 0) tickMs = .95*tickMs + .05*(now-previousTick)/1e6;
        previousTick = now;
        if (!owner.isEmpty() && (now-heartbeat > 6_000_000_000L || getServer().getOnlinePlayers().size()!=1 || getServer().getPlayerExact(playerName)==null)) {
            fault = "watchdog: heartbeat lost, player disconnected, or another player joined";
            finish(); owner=""; playerName="";
        }
        if (tick % 2 != 0) return;
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("schema",1); state.put("boot",boot); state.put("epoch_ms",System.currentTimeMillis());
        state.put("tick",tick); state.put("tick_ms",tickMs); state.put("owner",owner);
        state.put("recording",recording); state.put("fault",fault);
        state.put("reach", reach.state());
        List<Map<String,Object>> players = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) {
            Map<String,Object> v = new LinkedHashMap<>(); Location l = p.getLocation();
            v.put("name",p.getName()); v.put("uuid",p.getUniqueId().toString()); v.put("world",l.getWorld().getName());
            v.put("x",l.getX()); v.put("y",l.getY()); v.put("z",l.getZ()); v.put("yaw",l.getYaw()); v.put("pitch",l.getPitch());
            v.put("ground",p.isOnGround()); v.put("sprinting",p.isSprinting()); v.put("dead",p.isDead());
            v.put("food",p.getFoodLevel()); v.put("mode",p.getGameMode().name());
            v.put("flying",p.isFlying()); v.put("allow_flight",p.getAllowFlight()); players.add(v);
        }
        state.put("players",players); snapshot=gson.toJson(state);
    }

    @Override public void onDisable() {
        if (reach != null) reach.close();
        finish(); if (http != null) http.stop(0); if (executor != null) executor.shutdownNow();
    }
}
