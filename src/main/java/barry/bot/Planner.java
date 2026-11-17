package barry.bot;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;

public final class Planner {

    public enum Action {
        MOVE_TO,          // navigate to a position
        PLACE_BED,        // place and ignite a bed (nether probe)
        MINE_BLOCK,       // break a specific block
        COLLECT_DEBRIS,   // pick up debris at a known position
        TUNNEL,           // mine a tunnel in a direction
        WAIT,             // pause (humanizer, rate governor)
        PORTAL,           // use/build a nether portal
        INVENTORY,        // manage inventory (deposit, repair, eat)
        IDLE              // nothing to do, wander or disconnect
    }

    public record Task(Action action, BlockPos target, Map<String, Object> params) {
        public static Task moveTo(BlockPos pos) {
            return new Task(Action.MOVE_TO, pos, Map.of());
        }
        public static Task placeBed(BlockPos pos) {
            return new Task(Action.PLACE_BED, pos, Map.of());
        }
        public static Task mineBlock(BlockPos pos) {
            return new Task(Action.MINE_BLOCK, pos, Map.of());
        }
        public static Task collectDebris(BlockPos pos) {
            return new Task(Action.COLLECT_DEBRIS, pos, Map.of());
        }
        public static Task tunnel(BlockPos start, int dx, int dz, int length) {
            return new Task(Action.TUNNEL, start, Map.of("dx", dx, "dz", dz, "length", length));
        }
        public static Task wait(int ticks) {
            return new Task(Action.WAIT, BlockPos.fromPacked(0), Map.of("ticks", ticks));
        }
        public static Task inventory() {
            return new Task(Action.INVENTORY, BlockPos.fromPacked(0), Map.of());
        }
    }

    private final BlockMap map;
    private final Dimension dimension;
    private final Deque<Task> taskQueue = new ArrayDeque<>();

    // Planning state
    private BlockPos currentPos;
    private int debrisMinY = 8;
    private int debrisMaxY = 22;
    private int tunnelWidth = 2;
    private int tunnelY = 15; // center of debris band
    private double maxDetour = 32.0; // max blocks to detour for known debris
    private boolean preferRoof = false;

    // Coverage tracking: which chunks have been probed
    private final Set<Long> probedChunks = new HashSet<>();

    // Border: nether world border at 57k
    private static final int NETHER_BORDER = 57000;

    public Planner(BlockMap map, Dimension dimension) {
        this.map = map;
        this.dimension = dimension;
    }

    public void setPlayerPos(BlockPos pos) {
        this.currentPos = pos;
    }

    public Task nextTask() {
        if (!taskQueue.isEmpty()) {
            return taskQueue.poll();
        }
        return plan();
    }

    public Task peekTask() {
        if (!taskQueue.isEmpty()) {
            return taskQueue.peek();
        }
        return null;
    }

    public boolean hasWork() {
        return !taskQueue.isEmpty();
    }

    private Task plan() {
        if (currentPos == null) return Task.wait(20);

        // Priority 1: collect confirmed debris nearby
        Task collect = planDebrisCollection();
        if (collect != null) return collect;

        // Priority 2: probe nearest unprobed chunk in the debris band
        Task probe = planNextProbe();
        if (probe != null) return probe;

        // Priority 3: tunnel toward unexplored territory
        return planTunnel();
    }

    private Task planDebrisCollection() {
        List<Map.Entry<BlockPos, BlockConfidence>> nearby =
            map.oresNear(currentPos, maxDetour, 0.8f);

        for (Map.Entry<BlockPos, BlockConfidence> e : nearby) {
            OreType ore = OreType.fromBlockName(e.getValue().blockName());
            if (ore == null) continue;

            // In nether, only collect debris; in overworld, diamonds
            if (dimension.isNether() && ore != OreType.ANCIENT_DEBRIS) continue;
            if (dimension.isOverworld() && ore != OreType.DIAMOND_ORE
                    && ore != OreType.DEEPSLATE_DIAMOND) continue;

            BlockPos target = e.getKey();
            // Queue: move to adjacent block, mine the ore
            taskQueue.add(Task.moveTo(adjacentAir(target)));
            taskQueue.add(Task.mineBlock(target));
            taskQueue.add(Task.collectDebris(target));
            return taskQueue.poll();
        }
        return null;
    }

    private Task planNextProbe() {
        if (!dimension.isNether()) return null; // bed probing is nether-only

        // Find the nearest chunk that hasn't been probed
        ChunkKey current = ChunkKey.fromBlock(currentPos.x(), currentPos.z());

        // Spiral outward from current chunk
        for (int radius = 0; radius < 32; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    ChunkKey candidate = new ChunkKey(current.x() + dx, current.z() + dz);

                    // Check border
                    int worldX = candidate.x() << 4;
                    int worldZ = candidate.z() << 4;
                    if (Math.abs(worldX) > NETHER_BORDER || Math.abs(worldZ) > NETHER_BORDER) continue;

                    if (!probedChunks.contains(candidate.packed())) {
                        // Plan: move to chunk center at debris Y, place bed, ignite
                        BlockPos probePos = new BlockPos(
                            (candidate.x() << 4) + 8, tunnelY,
                            (candidate.z() << 4) + 8);

                        probedChunks.add(candidate.packed());
                        taskQueue.add(Task.moveTo(probePos));
                        taskQueue.add(Task.placeBed(probePos));
                        return taskQueue.poll();
                    }
                }
            }
        }
        return null;
    }

    private Task planTunnel() {
        // Default: tunnel in the current facing direction
        // The tunnel direction is chosen to maximize coverage of unprobed chunks
        int dx = 1, dz = 0;
        if (currentPos != null) {
            // Pick direction toward the nearest unprobed chunk cluster
            ChunkKey current = ChunkKey.fromBlock(currentPos.x(), currentPos.z());
            int bestDx = 1, bestDz = 0;
            int bestDist = Integer.MAX_VALUE;

            for (int[] dir : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                for (int dist = 1; dist < 16; dist++) {
                    ChunkKey check = new ChunkKey(current.x() + dir[0] * dist, current.z() + dir[1] * dist);
                    if (!probedChunks.contains(check.packed())) {
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestDx = dir[0];
                            bestDz = dir[1];
                        }
                        break;
                    }
                }
            }
            dx = bestDx;
            dz = bestDz;
        }

        return Task.tunnel(currentPos != null ? currentPos : new BlockPos(0, tunnelY, 0),
            dx, dz, 16);
    }

    private BlockPos adjacentAir(BlockPos pos) {
        // Find an adjacent position the player can stand in
        // Prefer below (mining from above) or same Y
        int[][] offsets = {{0,-1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0}};
        for (int[] off : offsets) {
            BlockPos adj = pos.offset(off[0], off[1], off[2]);
            BlockConfidence conf = map.get(adj);
            if (conf != null && (conf.blockName().contains("air") || conf.blockName().equals("lava"))) {
                return adj;
            }
        }
        // No known air — return position below
        return pos.offset(0, -1, 0);
    }

    // --- Settings ---

    public void setDebrisYRange(int min, int max) { debrisMinY = min; debrisMaxY = max; }
    public void setTunnelWidth(int w) { tunnelWidth = Math.max(1, Math.min(3, w)); }
    public void setTunnelY(int y) { tunnelY = y; }
    public void setMaxDetour(double d) { maxDetour = d; }
    public void setPreferRoof(boolean r) { preferRoof = r; }

    public int probedChunkCount() { return probedChunks.size(); }
    public int queueSize() { return taskQueue.size(); }

    public void clear() {
        taskQueue.clear();
        probedChunks.clear();
    }

    public void invalidatePlan() {
        taskQueue.clear();
    }
}
