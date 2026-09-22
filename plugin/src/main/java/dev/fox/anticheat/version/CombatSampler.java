/**
 * CombatSampler.java reads attacker eyes, target bounds, and eligibility context on the server thread.
 * It does not calculate reach, attack rates, or detector verdicts.
 */

package dev.fox.anticheat.version;

import dev.fox.anticheat.event.CombatContext;
import net.minecraft.server.v1_8_R3.AxisAlignedBB;
import net.minecraft.server.v1_8_R3.Entity;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;

public final class CombatSampler{

    public CombatContext sample(Player player, int targetId, String expectedUuid){
        if(!Bukkit.isPrimaryThread())
            throw new IllegalStateException("Combat sampling requires the server thread");

        String world = player.getWorld().getUID().toString();

        if(!player.isOnline() || player.isDead() || player.getGameMode() != GameMode.SURVIVAL)
            return CombatContext.unavailable(world, targetId, expectedUuid, "not_live_survival");

        InventoryType inventory = player.getOpenInventory().getTopInventory().getType();

        if(player.isInsideVehicle() || player.isSleeping() || player.isBlocking()
            || (inventory != InventoryType.CRAFTING && inventory != InventoryType.PLAYER)){
            return CombatContext.unavailable(world, targetId, expectedUuid, "unsupported_player_state");
        }

        EntityPlayer handle = ((CraftPlayer) player).getHandle();
        Entity target = handle.world.a(targetId);

        if(target == null || target == handle)
            return CombatContext.unavailable(world, targetId, expectedUuid, "missing_target");

        String uuid = target.getUniqueID().toString();

        // A reused entity ID must not inherit the previous entity's geometry history.
        if(!expectedUuid.isEmpty() && !expectedUuid.equals(uuid))
            return CombatContext.unavailable(world, targetId, expectedUuid, "target_id_reused");

        org.bukkit.entity.Entity bukkitTarget = target.getBukkitEntity();

        if(!(bukkitTarget instanceof LivingEntity) || bukkitTarget.isDead()
            || bukkitTarget.isInsideVehicle() || bukkitTarget.getWorld() != player.getWorld()){
            return CombatContext.unavailable(world, targetId, uuid, "unsupported_target");
        }

        Location eyes = player.getEyeLocation();
        AxisAlignedBB box = target.getBoundingBox();

        CombatContext context = new CombatContext(
            world,
            uuid,
            bukkitTarget.getType().name(),
            "",
            targetId,
            eyes.getX(),
            eyes.getY(),
            eyes.getZ(),
            box.a,
            box.b,
            box.c,
            box.d,
            box.e,
            box.f,
            handle.ping,
            true
        );
        context.yaw=eyes.getYaw(); context.pitch=eyes.getPitch(); context.rotationAvailable=true;
        context.attackerSprinting=player.isSprinting(); context.targetPlayer=bukkitTarget instanceof Player;
        return context;
    }
}
