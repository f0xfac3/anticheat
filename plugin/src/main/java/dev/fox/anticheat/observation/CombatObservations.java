/**
 * CombatObservations.java collects attack requests, arm swings, and watched target snapshots.
 * Reach and Autoclicker detection remain separate C++ checks.
 */

package dev.fox.anticheat.observation;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.bridge.ObservationSink;
import dev.fox.anticheat.event.AttackEvent;
import dev.fox.anticheat.event.CombatContext;
import dev.fox.anticheat.packet.PacketHandlers;
import dev.fox.anticheat.packet.PacketInfo;
import dev.fox.anticheat.version.CombatPackets;
import dev.fox.anticheat.version.CombatSampler;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInUseEntity;

public final class CombatObservations implements ObservationModule{
    private static final int MAX_TARGETS = 8;
    private static final long TARGET_LIFETIME_NS = 3_000_000_000L;

    private static final class WatchedTarget{
        final String uuid;
        long lastAttack;

        WatchedTarget(String uuid, long lastAttack){
            this.uuid = uuid;
            this.lastAttack = lastAttack;
        }
    }

    private final Session session;
    private final ObservationSink sink;
    private final CombatPackets packets = new CombatPackets();
    private final CombatSampler sampler = new CombatSampler();
    private final Map<Integer, WatchedTarget> targets = new LinkedHashMap<>();

    public CombatObservations(Session session, ObservationSink sink){
        this.session = session;
        this.sink = sink;
    }

    @Override
    public void registerHandlers(PacketHandlers handlers){
        handlers.on(
            PacketPlayInUseEntity.class,
            packets::copyAttack,
            this::onAttack
        );
        handlers.on(
            PacketPlayInArmAnimation.class,
            (packet, info)->info,
            this::onSwing
        );
    }

    private void onSwing(PacketInfo packet){
        sink.begin(
            EventWriter.SWING,
            session,
            packet.observedNanos,
            packet.epochMillis
        ).swing(packet.sequence, packet.readBatch);
        sink.send();
    }

    private void onAttack(AttackEvent event){
        CombatContext context = sampler.sample(session.player, event.targetId, "");
        long sampled = sink.now();

        sink.begin(
            EventWriter.ATTACK,
            session,
            event.packet.observedNanos,
            event.packet.epochMillis
        ).attack(event, context, sampled);
        sink.send();

        if(!context.available)
            return;

        // Refresh insertion order so the least recently attacked target is evicted first.
        targets.remove(event.targetId);
        targets.put(event.targetId, new WatchedTarget(context.targetUuid, sampled));

        if(targets.size() > MAX_TARGETS){
            Iterator<Integer> oldest = targets.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    @Override
    public void onTick(){
        long now = sink.now();

        Iterator<Map.Entry<Integer, WatchedTarget>> iterator = targets.entrySet().iterator();

        while(iterator.hasNext()){
            Map.Entry<Integer, WatchedTarget> entry = iterator.next();
            WatchedTarget target = entry.getValue();

            if(now < target.lastAttack || now - target.lastAttack > TARGET_LIFETIME_NS){
                iterator.remove();
                continue;
            }

            CombatContext context = sampler.sample(session.player, entry.getKey(), target.uuid);
            long sampled = sink.now();

            sink.begin(
                EventWriter.COMBAT_CONTEXT,
                session,
                sampled,
                System.currentTimeMillis()
            ).combatContext(context, sampled);
            sink.send();

            if(!context.available)
                iterator.remove();
        }
    }

    @Override
    public void reset(String reason){
        targets.clear();
    }
}
