package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.Dimension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CaveFollower {

    // Anti-xray cannot hide ore exposed to air or non-solid blocks.
    // In the nether, lava is passable and counts as a non-solid neighbor,
    // so debris in lava tubes and cave walls is genuinely visible.
    //
    // This module tracks blocks that are exposed (have at least one
    // air/fluid neighbor) and are ore types. These are ground truth.

    private static final Set<String> NON_SOLID_BLOCKS = Set.of(
        "air", "cave_air", "void_air", "water", "lava", "light",
        "torch", "wall_torch", "soul_torch", "soul_wall_torch",
        "redstone_torch", "redstone_wall_torch",
        "fire", "soul_fire",
        "snow", "cobweb"
    );

    private int exposedOreCount = 0;
    private final ConcurrentHashMap<Long, Boolean> processed = new ConcurrentHashMap<>();

    public void scanChunk(BlockMap map, int chunkX, int chunkZ, int minY, int maxY,
                           java.util.function.IntFunction<String> stateIdToName) {
        int bx = chunkX << 4;
        int bz = chunkZ << 4;

        for (int y = minY; y <= maxY; y++) {
            for (int x = bx; x < bx + 16; x++) {
                for (int z = bz; z < bz + 16; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    long packed = pos.packed();
                    if (processed.containsKey(packed)) continue;

                    BlockConfidence conf = map.get(pos);
                    if (conf == null) continue;
                    if (conf.isGroundTruth()) continue; // already confirmed

                    OreType ore = OreType.fromBlockName(conf.blockName());
                    if (ore == null) continue;

                    // Check neighbors for non-solid
                    if (hasNonSolidNeighbor(map, pos, stateIdToName)) {
                        // This ore is exposed — anti-xray didn't hide it
                        map.put(pos, BlockConfidence.groundTruth(
                            conf.blockStateId(), conf.blockName(),
                            BlockConfidence.Source.CAVE_EXPOSE));
                        exposedOreCount++;
                        processed.put(packed, true);
                    }
                }
            }
        }
    }

    private boolean hasNonSolidNeighbor(BlockMap map, BlockPos pos,
                                         java.util.function.IntFunction<String> stateIdToName) {
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int[] off : offsets) {
            BlockPos neighbor = pos.offset(off[0], off[1], off[2]);
            BlockConfidence nc = map.get(neighbor);
            if (nc == null) continue;
            String name = nc.blockName();
            if (name.startsWith("unknown:")) {
                name = stateIdToName.apply(nc.blockStateId());
            }
            if (NON_SOLID_BLOCKS.contains(name) || name.contains("air")) {
                return true;
            }
        }
        return false;
    }

    public int exposedOreCount() { return exposedOreCount; }

    public void clear() {
        exposedOreCount = 0;
        processed.clear();
    }
}
