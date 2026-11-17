package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;

public final class OverworldStrategy {

    // Overworld has BOTH Paper anti-xray mode 2 AND the Donut hider's
    // terrain masking (deepslate fill below Y 0, 10-block Chebyshev
    // reveal cube, 48-block cave flood fill).
    //
    // Two distinct problems:
    // 1. Below Y 0 (hider floor): terrain is masked with deepslate.
    //    Real blocks are revealed only within 10 blocks of the player
    //    or via cave flood fill. Everything else is deepslate.
    // 2. Above Y 0 through Y 12 (barrier band): same masking rules.
    // 3. Above Y 12: standard Paper anti-xray only.
    //
    // Strategy:
    // - Below the hider floor, use the 10-block reveal cube: as the
    //   player moves, the hider sends real blocks near them. These
    //   are ground truth.
    // - Cave flood fill reveals connected caves. Walk through caves
    //   to unmask geometry.
    // - Mining reveals (update-radius) provide ground truth per block
    //   broken.
    // - Chunk differencing still works for the anti-xray layer, but
    //   the hider's terrain mask is deterministic per position, so
    //   it doesn't flicker — only the anti-xray fakes flicker.

    private final BlockMap map;
    private final ChunkDiff diff;
    private final MiningReveal miningReveal;
    private final CaveFollower caveFollower;

    // Donut hider config (defaults from Donut-Recreation source)
    private int hiderFloorY = 0;       // hide-below-y
    private int hiderBarrierUpperY = 12; // barrier-upper-y
    private int revealRadius = 10;     // Chebyshev reveal distance

    // Target ores
    private static final Set<OreType> TARGETS = Set.of(
        OreType.DIAMOND_ORE, OreType.DEEPSLATE_DIAMOND,
        OreType.EMERALD_ORE, OreType.DEEPSLATE_EMERALD,
        OreType.AMETHYST_BUD
    );

    public OverworldStrategy(BlockMap map) {
        this.map = map;
        this.diff = new ChunkDiff();
        this.miningReveal = new MiningReveal();
        this.caveFollower = new CaveFollower();
    }

    public void onChunkLoad(ChunkKey chunk, Map<Integer, int[]> sectionData,
                             java.util.function.IntFunction<String> stateIdToName) {
        diff.addSnapshot(chunk, sectionData);

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

                String name = stateIdToName.apply(stateId);
                BlockPos pos = new BlockPos(bx + lx, y, bz + lz);

                // Determine confidence based on the position relative to the hider
                float confidence;
                BlockConfidence.Source source = BlockConfidence.Source.CHUNK_LOAD;

                if (y < hiderFloorY) {
                    // Below hider floor: if this is deepslate, it might be mask
                    if (name.equals("deepslate")) {
                        confidence = 0.1f; // very low — probably mask
                    } else {
                        // Non-deepslate below floor could be revealed (near player)
                        // or a real block that wasn't masked (edge case)
                        confidence = 0.4f;
                    }
                } else if (y < hiderBarrierUpperY) {
                    // Barrier band: similar to below floor
                    confidence = name.equals("deepslate") ? 0.1f : 0.4f;
                } else {
                    // Above hider: only anti-xray
                    OreType ore = OreType.fromBlockName(name);
                    confidence = (ore != null) ? 0.15f : 0.5f;
                }

                map.put(pos, new BlockConfidence(stateId, name, confidence, source));
            }
        }

        if (diff.snapshotCount(chunk) >= 2) {
            diff.analyze(chunk, map, Dimension.OVERWORLD);
        }

        caveFollower.scanChunk(map, chunk.x(), chunk.z(), -64, 320, stateIdToName);
    }

    public void onBlockUpdate(BlockPos pos, int newStateId, String blockName) {
        // Ground truth — the hider's reveal or anti-xray's update-radius
        map.put(pos, BlockConfidence.groundTruth(newStateId, blockName,
            BlockConfidence.Source.BLOCK_UPDATE));
    }

    public void onMultiBlockUpdate(List<Map.Entry<BlockPos, Integer>> updates) {
        miningReveal.processBlockUpdates(updates, map);
    }

    public void onPlayerMove(BlockPos playerPos) {
        // When the player moves, the hider reveals blocks within revealRadius.
        // We can't directly detect which blocks were revealed vs. already known,
        // but we can mark blocks near the player as higher confidence if they
        // aren't deepslate (since the hider would have shown them real data).
        // This is handled implicitly by the next chunk resend carrying real data
        // near the player.
    }

    public void setHiderConfig(int floorY, int barrierUpperY, int reveal) {
        this.hiderFloorY = floorY;
        this.hiderBarrierUpperY = barrierUpperY;
        this.revealRadius = reveal;
    }

    public ChunkDiff diff() { return diff; }
    public MiningReveal miningReveal() { return miningReveal; }
    public CaveFollower caveFollower() { return caveFollower; }

    public String statsLine() {
        return String.format("OW | ores:%d gt:%d exposed:%d revealed:%d",
            map.oreCount(), map.groundTruthCount(),
            caveFollower.exposedOreCount(), miningReveal.revealedCount());
    }
}
