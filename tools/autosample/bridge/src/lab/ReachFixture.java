package lab;

import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Stationary request-geometry experiment. Damage is suppressed, never labelled a successful hit. */
final class ReachFixture implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final Function<String,List<String>> command;
    private final Supplier<World> world;
    private String owner = "", attacker = "", target = "", fault = "";
    private UUID attackerId, targetId;
    private Location originalAttacker, originalTarget, attackerAnchor, targetAnchor;
    private long heartbeat;
    private boolean recording;
    private double distance;
    private int suppressedDamage;

    ReachFixture(JavaPlugin plugin, Function<String,List<String>> command, Supplier<World> world) {
        this.plugin = plugin; this.command = command; this.world = world;
    }

    boolean active() { return !owner.isEmpty(); }

    Map<String,Object> state() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("owner", owner); result.put("attacker", attacker); result.put("target", target);
        result.put("recording", recording); result.put("fault", fault);
        result.put("center_distance", distance); result.put("suppressed_damage", suppressedDamage);
        result.put("fixture", "stationary-reach-v1");
        result.put("damage_and_velocity_suppressed", true);
        return result;
    }

    private String field(Map<String,String> args, String key) {
        String value = args.get(key);
        if (value == null || !value.matches("[A-Za-z0-9_.+=,:-]{1,80}"))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private Player player(String name) {
        Player p = plugin.getServer().getPlayerExact(name);
        if (p == null || !p.isOnline()) throw new IllegalStateException("Join localhost first: " + name);
        return p;
    }

    private void checkPlayer(Player p) {
        if (p.isDead() || p.getGameMode() != GameMode.SURVIVAL || p.getAllowFlight() || p.isFlying()
                || p.isSneaking() || p.isSprinting() || !p.getActivePotionEffects().isEmpty()
                || p.getItemInHand().getType() != Material.AIR)
            throw new IllegalStateException(p.getName() + ": use survival, empty hand, no potions, no sneak/sprint/flight.");
    }

    private void participants() {
        if (plugin.getServer().getOnlinePlayers().size() != 2)
            throw new IllegalStateException("Only the attacker and target may be online.");
        Player a = player(attacker), t = player(target);
        if (!a.getUniqueId().equals(attackerId) || !t.getUniqueId().equals(targetId))
            throw new IllegalStateException("Participant identity changed.");
        checkPlayer(a); checkPlayer(t);
    }

    private boolean displaced(Player p, Location anchor) {
        return anchor == null || !p.getWorld().equals(anchor.getWorld())
                || p.getLocation().distanceSquared(anchor) > .015 * .015;
    }

    Map<String,Object> action(String op, String requested, Map<String,String> args) {
        if (op.equals("reach_acquire")) {
            if (active()) throw new IllegalStateException("A Reach controller already owns the fixture.");
            String a = field(args, "attacker"), t = field(args, "target");
            if (!a.matches("[A-Za-z0-9_]{1,16}") || !t.matches("[A-Za-z0-9_]{1,16}") || a.equalsIgnoreCase(t))
                throw new IllegalArgumentException("Use two distinct Minecraft accounts.");
            Player ap = player(a), tp = player(t);
            checkPlayer(ap); checkPlayer(tp);
            if (plugin.getServer().getOnlinePlayers().size() != 2)
                throw new IllegalStateException("Only the attacker and target may be online.");
            List<String> status = command.apply("acdata status");
            if (status.stream().noneMatch(s -> s.contains("active=0") && s.contains("error=null")))
                throw new IllegalStateException("Recorder must be healthy and idle: " + status);
            attacker = a; target = t; attackerId = ap.getUniqueId(); targetId = tp.getUniqueId();
            originalAttacker = ap.getLocation(); originalTarget = tp.getLocation();
            attackerAnchor = targetAnchor = null;
            owner = requested; fault = ""; heartbeat = System.nanoTime();
            return state();
        }
        if (!active() || !owner.equals(requested)) throw new IllegalStateException("Reach lease not owned: " + fault);
        heartbeat = System.nanoTime();
        if (op.equals("reach_heartbeat")) return state();
        if (op.equals("reach_release")) { close(); return state(); }
        if (op.equals("reach_stop")) { stop(); return state(); }
        participants();
        if (op.equals("reach_position")) {
            if (recording) throw new IllegalStateException("Stop recording before changing distance.");
            double d = Double.parseDouble(field(args, "distance"));
            if (!Double.isFinite(d) || d < 2 || d > 5) throw new IllegalArgumentException("Center distance must be 2..5.");
            World w = world.get(); w.setPVP(true);
            attackerAnchor = new Location(w, .5, 65, .5, -90, 0);
            targetAnchor = new Location(w, .5 + d, 65, .5, 90, 0);
            if (!player(attacker).teleport(attackerAnchor) || !player(target).teleport(targetAnchor))
                throw new IllegalStateException("Fixture teleport failed.");
            distance = d; suppressedDamage = 0;
            player(attacker).sendMessage(ChatColor.AQUA + "Reach lab: stand still and aim at the target. Center distance " + d);
            player(target).sendMessage(ChatColor.AQUA + "Reach lab target: stay still. Damage is disabled during this fixture.");
        } else if (op.equals("reach_start")) {
            if (recording) throw new IllegalStateException("Trial already active.");
            if (displaced(player(attacker), attackerAnchor) || displaced(player(target), targetAnchor))
                throw new IllegalStateException("Both players must be at the prepared positions.");
            String label = field(args, "label");
            if (!label.equals("legit") && !label.equals("cheat")) throw new IllegalArgumentException("Invalid label.");
            String module = args.containsKey("module") ? field(args, "module")
                : (label.equals("legit") ? "none" : "reach");
            if (label.equals("legit") != module.equals("none"))
                throw new IllegalArgumentException("Legit uses module=none; cheat requires a named behavior.");
            List<String> reply = command.apply("acdata start " + attacker + " " + label + " "
                + module + " " + field(args,"client")
                + " " + field(args,"setting") + " " + field(args,"scenario"));
            String message = String.join(" ", reply);
            if (!message.startsWith("Recording trial-")) throw new IllegalStateException(message);
            recording = true;
            Map<String,Object> result = state(); result.put("trial", message.split(" ")[1]);
            result.put("attacker_uuid", attackerId.toString()); result.put("target_uuid", targetId.toString());
            player(attacker).sendMessage(ChatColor.GREEN + "Recording " + module
                + ": attack the target as declared; do not walk, jump or sneak.");
            return result;
        } else throw new IllegalArgumentException("Unknown Reach action.");
        return state();
    }

    void observe(long now) {
        if (!active()) return;
        try {
            if (now - heartbeat > 6_000_000_000L) throw new IllegalStateException("Controller heartbeat lost.");
            participants();
            if (recording && (displaced(player(attacker), attackerAnchor) || displaced(player(target), targetAnchor)))
                throw new IllegalStateException("A player moved; trial excluded. Keep both players stationary.");
        } catch (RuntimeException error) {
            fault = error.getMessage(); plugin.getLogger().warning("Reach fixture stopped: " + fault); close();
        }
    }

    private boolean member(UUID id) { return active() && (id.equals(attackerId) || id.equals(targetId)); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void damage(EntityDamageEvent event) {
        if (member(event.getEntity().getUniqueId())) { event.setCancelled(true); suppressedDamage++; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void velocity(PlayerVelocityEvent event) {
        if (member(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void food(FoodLevelChangeEvent event) {
        if (member(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    private void stop() {
        if (!recording) return;
        if (plugin.getServer().getPlayerExact(attacker) != null) command.apply("acdata stop " + attacker);
        recording = false;
    }

    @Override public void close() {
        if (!active()) return;
        try { stop(); }
        finally {
            owner = "";
            Player a = plugin.getServer().getPlayerExact(attacker), t = plugin.getServer().getPlayerExact(target);
            if (a != null && originalAttacker != null) a.teleport(originalAttacker);
            if (t != null && originalTarget != null) t.teleport(originalTarget);
            attackerAnchor = targetAnchor = null;
        }
    }
}
