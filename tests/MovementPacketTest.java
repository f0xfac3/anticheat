package dev.fox.anticheat.packet;

import dev.fox.anticheat.Session;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.logging.Logger;
import net.minecraft.server.v1_8_R3.PacketPlayInFlying;

/** Real Netty forwarding/order plus bounded ownership and actual 1.8 packet getters. */
public final class MovementPacketTest {
    static int passed;
    static void check(boolean ok, String why) {
        if (!ok)
            throw new AssertionError(why);
        passed++;
        System.out.println("PASS " + why);
    }
    public static void main(String[] args) {
        ImpulseBarriers ledger = new ImpulseBarriers();
        short id = ledger.issue(42, 100);
        check(ledger.owns(0, id) && !ledger.owns(1, id), "barrier belongs to exact inventory window");
        check(ledger.acknowledge(1, id, 101) == 0 && ledger.acknowledge(0, id, 101) == 42,
              "matching acknowledgement resolves original impulse token");
        check(ledger.acknowledge(0, id, 102) == 0, "duplicate ack ignored");
        id = ledger.issue(43, 100);
        check(ledger.acknowledge(0, id, 2_000_000_101L) == 0, "stale ack ignored");
        for (int i = 1; i <= 32; i++)
            ledger.issue(i, 100);
        boolean bounded = false;
        try {
            ledger.issue(33, 100);
        } catch (IllegalStateException expected) {
            bounded = true;
        }
        check(bounded, "outstanding barrier ledger bounded");
        ledger.clear();
        check(ledger.acknowledge(0, id, 101) == 0, "reset drops ownership");

        Session s = new Session(1, null);
        List<Runnable> queued = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        Object original = new Object(), barrier = new Object();
        s.outbound.on(Object.class,
                      (p, i) -> new OutboundHandlers.Capture(() -> observations.add("out"), barrier));
        s.outbound.seal();
        s.handlers.on(String.class, (p, i) -> p, p -> observations.add(p));
        s.handlers.seal();
        PacketObserver observer = new PacketObserver(Logger.getAnonymousLogger(),
                                                     () -> 100L, (session, r, g) -> r.run(), queued::add);
        EmbeddedChannel channel = new EmbeddedChannel(observer.connectionHandler(s));
        channel.writeOutbound(original);
        check(channel.readOutbound() == original && channel.readOutbound() == barrier &&
                  channel.readOutbound() == null,
              "original forwarded once before barrier");
        check(observations.isEmpty() && queued.size() == 1,
              "outgoing observation is deferred from network handler");
        channel.writeInbound("in");
        check("in".equals(channel.readInbound()) && channel.readInbound() == null,
              "incoming packet forwarded once");
        for (Runnable r : queued)
            r.run();
        check(observations.equals(Arrays.asList("out", "in")), "queued event order retained");
        check(s.pending.get() == 0, "queue counters released");
        channel.finish();

        PacketPlayInFlying[] flying = {
            new PacketPlayInFlying(), new PacketPlayInFlying.PacketPlayInPosition(),
            new PacketPlayInFlying.PacketPlayInLook(), new PacketPlayInFlying.PacketPlayInPositionLook()};
        for (int i = 0; i < 4; i++) {
            check(flying[i].g() == (i == 1 || i == 3) && flying[i].h() == (i == 2 || i == 3),
                  "1.8 Flying presence bits variant " + i);
        }
        System.out.println(passed + " movement adapter checks passed");
    }
}
