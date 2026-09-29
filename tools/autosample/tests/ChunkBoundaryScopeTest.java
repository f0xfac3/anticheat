import dev.fox.anticheat.observation.ChunkBoundaryScope;
public class ChunkBoundaryScopeTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!ChunkBoundaryScope.relevant(new int[]{10,-10},new int[]{0,0},0,0,1,1));
        check(ChunkBoundaryScope.relevant(new int[]{2},new int[]{2},0,0,1,1));
        check(ChunkBoundaryScope.relevant(new int[]{-3},new int[]{0},-.1,0,.1,0));
        check(ChunkBoundaryScope.relevant(new int[]{8,1},new int[]{8,0},0,0,1,1));
        check(ChunkBoundaryScope.relevant(new int[]{5},new int[]{0},0,0,160,0));
        check(ChunkBoundaryScope.relevant(null,new int[]{0},0,0,1,1));
        check(ChunkBoundaryScope.relevant(new int[]{0},new int[]{},0,0,1,1));
        check(ChunkBoundaryScope.relevant(new int[]{10},new int[]{10},Double.NaN,0,1,1));
        System.out.println("8 chunk-boundary checks passed");
    }
}
