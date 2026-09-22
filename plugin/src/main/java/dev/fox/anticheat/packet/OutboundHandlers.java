package dev.fox.anticheat.packet;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/** Outbound packet copying, with an optional ordered follow-up packet (e.g. a barrier). */
public final class OutboundHandlers {
    public static final class Capture {
        public final Runnable observation;
        public final Object afterWrite;
        public Capture(Runnable observation, Object afterWrite) {
            this.observation = observation;
            this.afterWrite = afterWrite;
        }
    }
    private final Map<Class<?>, BiFunction<Object, PacketInfo, Capture>> handlers = new HashMap<>();
    private boolean sealed;
    public <P> void on(Class<P> type, BiFunction<P, PacketInfo, Capture> copy) {
        if (sealed || handlers.containsKey(type))
            throw new IllegalStateException("Duplicate/sealed outbound handler");
        handlers.put(type, (packet, info) -> copy.apply(type.cast(packet), info));
    }
    public void seal() {
        sealed = true;
    }
    public Capture capture(Object packet, PacketInfo info) {
        if (!sealed)
            throw new IllegalStateException("Unsealed outbound handlers");
        BiFunction<Object, PacketInfo, Capture> handler = handlers.get(packet.getClass());
        return handler == null ? null : handler.apply(packet, info);
    }
}
