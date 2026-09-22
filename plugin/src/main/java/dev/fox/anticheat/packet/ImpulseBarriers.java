package dev.fox.anticheat.packet;

import java.util.HashMap;
import java.util.Map;

/** Bounded ownership ledger. Reset can run on the server thread; copy/ack on Netty. */
public final class ImpulseBarriers {
    private static final class Entry {
        final long token, sent;
        Entry(long token, long sent) {
            this.token = token;
            this.sent = sent;
        }
    }
    private final Map<Short, Entry> pending = new HashMap<>();
    private int next = -30000;
    public synchronized short issue(long token, long now) {
        pending.entrySet().removeIf(e -> now < e.getValue().sent || now - e.getValue().sent > 2_000_000_000L);
        if (pending.size() >= 32)
            throw new IllegalStateException("Too many pending impulse barriers");
        for (int i = 0; i < 2768; i++) {
            short id = (short)next--;
            if (next < -32767)
                next = -30000;
            if (!pending.containsKey(id)) {
                pending.put(id, new Entry(token, now));
                return id;
            }
        }
        throw new IllegalStateException("No free impulse barrier ID");
    }
    public synchronized long acknowledge(int window, short id, long now) {
        if (window != 0)
            return 0;
        Entry entry = pending.remove(id);
        if (entry == null || now < entry.sent || now - entry.sent > 2_000_000_000L)
            return 0;
        return entry.token;
    }
    public synchronized boolean owns(int window, short id) {
        return window == 0 && pending.containsKey(id);
    }
    public synchronized void clear() {
        pending.clear();
    }
}
