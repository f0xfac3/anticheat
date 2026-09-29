package dev.fox.anticheat.observation;

import dev.fox.anticheat.Session;
import dev.fox.anticheat.bridge.EventWriter;
import dev.fox.anticheat.bridge.ObservationSink;
import dev.fox.anticheat.event.ImpulseEvent;
import dev.fox.anticheat.event.MovementContext;
import dev.fox.anticheat.event.MovementEvent;
import dev.fox.anticheat.packet.ImpulseBarriers;
import dev.fox.anticheat.packet.OutboundHandlers;
import dev.fox.anticheat.packet.PacketHandlers;
import dev.fox.anticheat.packet.PacketInfo;
import dev.fox.anticheat.version.MovementSampler;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.v1_8_R3.*;
import org.bukkit.Location;

/** Collect movement, actual outbound impulses and their owned processing barriers. */
public final class MovementObservations implements ObservationModule {
    private final Session session;
    private final ObservationSink sink;
    private final MovementSampler sampler = new MovementSampler();
    private final ImpulseBarriers barriers = new ImpulseBarriers();
    private final AtomicLong token = new AtomicLong();
    private final int entityId;
    private final Field velocityId = field(PacketPlayOutEntityVelocity.class, "a", int.class);
    private final Field vx = field(PacketPlayOutEntityVelocity.class, "b", int.class);
    private final Field vy = field(PacketPlayOutEntityVelocity.class, "c", int.class);
    private final Field vz = field(PacketPlayOutEntityVelocity.class, "d", int.class);
    private final Field ex = field(PacketPlayOutExplosion.class, "f", float.class);
    private final Field ey = field(PacketPlayOutExplosion.class, "g", float.class);
    private final Field ez = field(PacketPlayOutExplosion.class, "h", float.class);
    private final Field transactionWindow = field(PacketPlayOutTransaction.class, "a", int.class);
    private final Field transactionId = field(PacketPlayOutTransaction.class, "b", short.class);
    private final Field chunkX = field(PacketPlayOutMapChunk.class, "a", int.class);
    private final Field chunkZ = field(PacketPlayOutMapChunk.class, "b", int.class);
    private final Field bulkX = field(PacketPlayOutMapChunkBulk.class, "a", int[].class);
    private final Field bulkZ = field(PacketPlayOutMapChunkBulk.class, "b", int[].class);
    private double x, y, z;
    private long graceUntil, lastTick;
    private String world = "";
    private final java.util.Map<String, Integer> boundaryCounts = new java.util.HashMap<>();

    private void logBoundary(String type) {
        int count = boundaryCounts.getOrDefault(type, 0) + 1;
        boundaryCounts.put(type, count);
        if (count == 1 || count % 200 == 0)
            org.bukkit.Bukkit.getLogger().info("[FoxAntiCheat] movement boundary session=" + session.id
                + " packet=" + type + " count=" + count);
    }

    private static Field field(Class<?> type, String name, Class<?> expected) {
        try {
            Field f = type.getDeclaredField(name);
            if (f.getType() != expected)
                throw new IllegalStateException("Unexpected packet field type");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Spigot packet layout", e);
        }
    }
    private static double value(Field f, Object p) {
        try {
            return ((Number)f.get(p)).doubleValue();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
    public MovementObservations(Session session, ObservationSink sink) {
        this.session = session;
        this.sink = sink;
        this.entityId = session.player.getEntityId();
        reset("join");
    }
    private <P extends PacketPlayInFlying> void flying(PacketHandlers h, Class<P> type) {
        h.on(type,
             (p, i)
                 -> new MovementEvent(i, p.a(), p.b(), p.c(), p.d(), p.e(), p.g(), p.h(), p.f()),
             this::movement);
    }
    private <P> void worldBoundary(Class<P> type) {
        session.outbound.on(type, (p, i) -> new OutboundHandlers.Capture(() -> {
            logBoundary(type.getSimpleName());
            // The current world snapshot cannot establish when the client saw new blocks.
            reset("client_world_update");
            sink.begin(EventWriter.CORRECTION, session, i.observedNanos, i.epochMillis);
            sink.send();
        }, null));
    }
    private static int[] coordinates(Field f, Object packet) {
        try {
            int[] values = (int[])f.get(packet);
            return values == null ? null : values.clone();
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private OutboundHandlers.Capture chunkBoundary(int[] xs, int[] zs, PacketInfo i) {
        // Copy coordinates on Netty; inspect player state only on the server thread.
        return new OutboundHandlers.Capture(() -> {
            Location current = session.player.getLocation();
            if (!ChunkBoundaryScope.relevant(xs, zs, x, z, current.getX(), current.getZ()))
                return;
            logBoundary("nearby_chunk_update");
            reset("client_world_update");
            sink.begin(EventWriter.CORRECTION, session, i.observedNanos, i.epochMillis);
            sink.send();
        }, null);
    }
    @Override
    public void registerHandlers(PacketHandlers h) {
        flying(h, PacketPlayInFlying.class);
        flying(h, PacketPlayInFlying.PacketPlayInPosition.class);
        flying(h, PacketPlayInFlying.PacketPlayInLook.class);
        flying(h, PacketPlayInFlying.PacketPlayInPositionLook.class);
        h.on(PacketPlayInTransaction.class,
             (p, i)
                 -> {
                 long owned = barriers.acknowledge(p.a(), p.b(), i.observedNanos);
                 return owned == 0 ? null : new Object[] {owned, i};
             },
             data -> {
                 PacketInfo i = (PacketInfo)data[1];
                 sink.begin(EventWriter.IMPULSE_ACK, session, i.observedNanos, i.epochMillis)
                     .impulseAck((Long)data[0]);
                 sink.send();
             });
        session.outbound.on(PacketPlayOutEntityVelocity.class, (p, i) -> {
            if ((int)value(velocityId, p) != entityId)
                return null;
            return impulse(i, value(vx, p) / 8000d, value(vy, p) / 8000d, value(vz, p) / 8000d, false);
        });
        session.outbound.on(PacketPlayOutExplosion.class,
                            (p, i) -> impulse(i, value(ex, p), value(ey, p), value(ez, p), true));
        session.outbound.on(PacketPlayOutPosition.class, (p, i) -> new OutboundHandlers.Capture(() -> {
            logBoundary("PacketPlayOutPosition");
            reset("server_position_correction");
            sink.begin(EventWriter.CORRECTION, session, i.observedNanos, i.epochMillis);
            sink.send();
        }, null));
        worldBoundary(PacketPlayOutBlockChange.class);
        worldBoundary(PacketPlayOutMultiBlockChange.class);
        session.outbound.on(PacketPlayOutMapChunk.class, (p, i) -> chunkBoundary(
            new int[]{(int)value(chunkX, p)}, new int[]{(int)value(chunkZ, p)}, i));
        session.outbound.on(PacketPlayOutMapChunkBulk.class,
            (p, i) -> chunkBoundary(coordinates(bulkX, p), coordinates(bulkZ, p), i));
        worldBoundary(PacketPlayOutRespawn.class);
        session.outbound.on(PacketPlayOutTransaction.class, (p, i) -> {
            if (!barriers.owns((int)value(transactionWindow, p), (short)value(transactionId, p)))
                return null;
            // Another plugin/server transaction used an owned ID. Invalidate evidence.
            barriers.clear();
            session.recordLoss();
            return null;
        });
    }
    private OutboundHandlers.Capture impulse(PacketInfo i, double dx, double dy, double dz,
                                             boolean additive) {
        long id = token.incrementAndGet();
        ImpulseEvent e = new ImpulseEvent(i, id, dx, dy, dz, additive);
        short barrier = barriers.issue(id, i.observedNanos);
        return new OutboundHandlers.Capture(() -> {
            sink.begin(EventWriter.IMPULSE, session, i.observedNanos, i.epochMillis).impulse(e);
            sink.send();
        }, new PacketPlayOutTransaction(0, barrier, false));
    }
    private void movement(MovementEvent e) {
        String currentWorld = session.player.getWorld().getUID().toString();
        if (!world.equals(currentWorld))
            reset("world_change");
        double nx = e.position ? e.x : x, ny = e.position ? e.y : y, nz = e.position ? e.z : z;
        MovementContext c = sampler.sample(session.player, x, y, z, nx, ny, nz);
        long sampled = sink.now();
        if (sampled < graceUntil) {
            c.available = false;
            c.reason = "lifecycle_or_server_stall_grace";
        }
        sink.begin(EventWriter.MOVEMENT, session, e.packet.observedNanos, e.packet.epochMillis)
            .movement(e, c, sampled);
        sink.send();
        if (e.position && Double.isFinite(nx) && Double.isFinite(ny) && Double.isFinite(nz)) {
            x = nx;
            y = ny;
            z = nz;
        }
    }
    @Override
    public void onTick() {
        long now = sink.now();
        if (lastTick != 0 && (now < lastTick || now - lastTick > 150_000_000L))
            graceUntil = now + 2_000_000_000L;
        lastTick = now;
    }
    @Override
    public void reset(String reason) {
        barriers.clear();
        Location p = session.player.getLocation();
        x = p.getX();
        y = p.getY();
        z = p.getZ();
        world = session.player.getWorld().getUID().toString();
        graceUntil = sink.now() + 2_000_000_000L;
        lastTick = 0;
    }
}
