package dev.fox.anticheat.observation;

/** Distant view-distance streaming does not change the sampled collision region. */
public final class ChunkBoundaryScope {
    private ChunkBoundaryScope() {}
    public static boolean relevant(int[] xs, int[] zs, double previousX, double previousZ,
                                   double currentX, double currentZ) {
        if (xs == null || zs == null || xs.length != zs.length || xs.length == 0
                || !Double.isFinite(previousX) || !Double.isFinite(previousZ)
                || !Double.isFinite(currentX) || !Double.isFinite(currentZ)) return true;
        long px = (long)Math.floor(previousX / 16), pz = (long)Math.floor(previousZ / 16);
        long cx = (long)Math.floor(currentX / 16), cz = (long)Math.floor(currentZ / 16);
        // Cover the whole swept region plus two chunks of margin. The sampler
        // accepts at most a three-block step with 0.6-block horizontal padding.
        for (int n = 0; n < xs.length; n++)
            if (xs[n] >= Math.min(px, cx) - 2 && xs[n] <= Math.max(px, cx) + 2
                    && zs[n] >= Math.min(pz, cz) - 2 && zs[n] <= Math.max(pz, cz) + 2)
                return true;
        return false;
    }
}
