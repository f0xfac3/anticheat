package dev.fox.anticheat.version;

import dev.fox.anticheat.event.MovementContext;
import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.GenericAttributes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Bounded plain-terrain measurements. It never labels a movement as cheating. */
public final class MovementSampler {
    private static boolean finite(double value) {
        return Double.isFinite(value) && Math.abs(value) < 30_000_000;
    }
    private static boolean plain(Material m) {
        return m == Material.AIR || m == Material.STONE || m == Material.GRASS || m == Material.DIRT ||
            m == Material.COBBLESTONE || m == Material.WOOD || m == Material.BEDROCK;
    }
    private static AxisAlignedBB body(double x, double y, double z) {
        // Epsilon avoids treating floor contact itself as an obstructed body.
        return new AxisAlignedBB(x - .3, y + 0.0001, z - .3, x + .3, y + 1.8, z + .3);
    }
    private static boolean support(EntityPlayer p, double x, double y, double z) {
        return !p.world.getCubes(p, new AxisAlignedBB(x - .3, y - .001, z - .3, x + .3, y + .0001, z + .3))
                    .isEmpty();
    }
    public MovementContext sample(Player player, double px, double py, double pz, double x, double y,
                                  double z) {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Movement sampling requires server thread");
        MovementContext c = new MovementContext();
        c.world = player.getWorld().getUID().toString();
        c.reason = "unsupported_player_state";
        if (!player.isOnline() || player.isDead() || player.getGameMode() != GameMode.SURVIVAL ||
            player.getAllowFlight() || player.isFlying() || player.isInsideVehicle() || player.isSleeping())
            return c;
        c.reason = "invalid_or_large_position_step";
        if (!finite(px) || !finite(py) || !finite(pz) || !finite(x) || !finite(y) || !finite(z) ||
            Math.abs(x - px) > 3 || Math.abs(y - py) > 3 || Math.abs(z - pz) > 3 || y < 1 || y > 253)
            return c;
        EntityPlayer p = ((CraftPlayer)player).getHandle();
        // The deliberately narrow terrain model excludes liquids, ladders, webs, ice,
        // slime, stairs/slabs, piston blocks and other special movement/collision cases.
        int minX = (int)Math.floor(Math.min(px, x) - .6), maxX = (int)Math.floor(Math.max(px, x) + .6);
        int minY = (int)Math.floor(Math.min(py, y) - 1.1), maxY = (int)Math.floor(Math.max(py, y) + 2.1);
        int minZ = (int)Math.floor(Math.min(pz, z) - .6), maxZ = (int)Math.floor(Math.max(pz, z) + .6);
        c.reason = "unloaded_or_non_plain_terrain";
        if ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 256)
            return c;
        for (int bx = minX; bx <= maxX; bx++)
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!player.getWorld().isChunkLoaded(bx >> 4, bz >> 4))
                    return c;
                for (int by = minY; by <= maxY; by++)
                    if (!plain(player.getWorld().getBlockAt(bx, by, bz).getType()))
                        return c;
            }
        AxisAlignedBB swept = body(px, py, pz).a(body(x, y, z));
        c.sourceSupported = support(p, px, py, pz);
        c.destinationSupported = support(p, x, y, z);
        c.clearPath =
            p.world.getCubes(p, swept).isEmpty() && p.world.getEntities(p, swept.grow(.2, .2, .2)).isEmpty();
        c.flatGround =
            c.sourceSupported && c.destinationSupported && Math.abs(y - py) < 0.0001 && c.clearPath;
        c.usingItem = p.bS();
        c.sprinting = player.isSprinting();
        c.movementSpeed = p.getAttributeInstance(GenericAttributes.MOVEMENT_SPEED).getValue();
        c.friction = 0.6;
        c.jumpVelocity = 0.42;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.JUMP))
                c.jumpVelocity += 0.1 * (effect.getAmplifier() + 1);
        }
        c.available = true;
        c.reason = "";
        return c;
    }
}
