package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;

public final class NetherStrategy {

    // Nether has only Paper anti-xray mode 2 — no Donut hider terrain masking.
    // This simplifies the problem significantly:
    // 1. All block data in chunks is from Paper's obfuscation only
    // 2. Block updates are always ground truth
    // 3. Blast probing with beds is natural gameplay
    // 4. Cave-following works because lava counts as non-solid

    private final BlockMap map;
    private final ChunkDiff diff;
    private final BlastProbe blastProbe;
    private final MiningReveal miningReveal;
    private final CaveFollower caveFollower;

    // Debris band: Y 8-22
    private static final int DEBRIS_MIN_Y = 8;
    private static final int DEBRIS_MAX_Y = 22;

    public NetherStrategy(BlockMap map) {
        this.map = map;
        this.diff = new ChunkDiff();
        this.blastProbe = new BlastProbe();
        this.miningReveal = new MiningReveal();
        this.caveFollower = new CaveFollower();
    }

    public void onChunkLoad(ChunkKey chunk, Map<Integer, int[]> sectionData,
                             java.util.function.IntFunction<String> stateIdToName) {
        // Store snapshot for temporal differencing
        diff.addSnapshot(chunk, sectionData);

        // Load all blocks into the map as initial (low-confidence) data
        int bx = chunk.x() << 4;
        int bz = chunk.z() << 4;

        for (Map.Entry<Integer, int[]> section : sectionData.entrySet()) {
            int sectionY = section.getKey();
            int baseY = sectionY << 4;
            int[] states = section.getValue();

            for (int idx = 0; idx < states.length; idx++) {
                int stateId = states[idx];
                int lx = idx & 0xF;
                int lz = (idx >> 4) & 0xF;
                int ly = (idx >> 8) & 0xF;
                int y = baseY + ly;

                // Only track blocks in the debris band for efficiency
                if (y < DEBRIS_MIN_Y - 2 || y > DEBRIS_MAX_Y + 2) continue;

                String name = stateIdToName.apply(stateId);
                BlockPos pos = new BlockPos(bx + lx, y, bz + lz);

                OreType ore = OreType.fromBlockName(name);
                if (ore != null && ore.isTarget(Dimension.NETHER)) {
                    // Initial chunk data: could be real or fake
                    map.put(pos, new BlockConfidence(stateId, name, 0.15f,
                        BlockConfidence.Source.CHUNK_LOAD));
                } else {
                    map.put(pos, new BlockConfidence(stateId, name, 0.5f,
                        BlockConfidence.Source.CHUNK_LOAD));
                }
            }
        }

        // If we have enough snapshots, run diff analysis
        if (diff.snapshotCount(chunk) >= 2) {
            diff.analyze(chunk, map, Dimension.NETHER);
        }

        // Check for exposed ores
        caveFollower.scanChunk(map, chunk.x(), chunk.z(), DEBRIS_MIN_Y, DEBRIS_MAX_Y, stateIdToName);
    }

    public void onBlockUpdate(BlockPos pos, int newStateId, String blockName) {
        // Block updates from the server are ground truth
        map.put(pos, BlockConfidence.groundTruth(newStateId, blockName,
            BlockConfidence.Source.BLOCK_UPDATE));
    }

    public void onMultiBlockUpdate(List<Map.Entry<BlockPos, Integer>> updates) {
        // Process through blast probe first (checks if updates are from an explosion)
        blastProbe.processBlockUpdates(updates, map, Dimension.NETHER);
        // Then through mining reveal
        miningReveal.processBlockUpdates(updates, map);
    }

    public void onPlayerBreak(BlockPos pos, int stateId, String blockName) {
        // Another player broke a block — the resulting update is ground truth
        map.put(pos, BlockConfidence.groundTruth(stateId, blockName,
            BlockConfidence.Source.PLAYER_BREAK));
    }

    public void registerBedPlacement(BlockPos bedPos) {
        Map<Long, Integer> snapshot = blastProbe.snapshotOrePositions(bedPos, true, map);
        blastProbe.registerProbe(bedPos, true, snapshot);
    }

    public ChunkDiff diff() { return diff; }
    public BlastProbe blastProbe() { return blastProbe; }
    public MiningReveal miningReveal() { return miningReveal; }
    public CaveFollower caveFollower() { return caveFollower; }

    public String statsLine() {
        return String.format("Nether | ores:%d gt:%d diff-samples:%d exposed:%d probed:%d (real:%d fake:%d)",
            map.oreCount(), map.groundTruthCount(),
            0, // would need to aggregate diff counts
            caveFollower.exposedOreCount(),
            blastProbe.totalProbes(), blastProbe.confirmedReal(), blastProbe.confirmedFake());
    }
}
