package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.Dimension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BlastProbe {

    // Bed explosion: power 5, fire = true, blockInteraction = DESTROY
    // TNT explosion: power 4, blockInteraction = DESTROY
    // Blast radius ≈ 5-7 blocks (irregular due to random ray-casting)
    private static final double BED_BLAST_RADIUS = 7.0;
    private static final double TNT_BLAST_RADIUS = 6.0;

    // Track pending probes: positions where we placed a bed/TNT
    // and are waiting for the explosion block updates
    private final ConcurrentHashMap<Long, PendingProbe> pending = new ConcurrentHashMap<>();

    // Statistics
    private int totalProbes = 0;
    private int confirmedReal = 0;
    private int confirmedFake = 0;

    public record PendingProbe(BlockPos center, double radius, long timestamp,
                                Map<Long, Integer> preBlastOrePositions) {}

    public void registerProbe(BlockPos center, boolean isBed, Map<Long, Integer> preBlastOrePositions) {
        double radius = isBed ? BED_BLAST_RADIUS : TNT_BLAST_RADIUS;
        pending.put(center.packed(), new PendingProbe(center, radius, System.currentTimeMillis(), preBlastOrePositions));
        totalProbes++;
    }

    public void processBlockUpdates(List<Map.Entry<BlockPos, Integer>> updates, BlockMap map, Dimension dim) {
        if (pending.isEmpty()) return;

        Set<Long> resolvedProbes = new HashSet<>();

        for (Map.Entry<BlockPos, Integer> update : updates) {
            BlockPos pos = update.getKey();
            int newStateId = update.getValue();

            // Check if this update is within any pending probe's radius
            for (Map.Entry<Long, PendingProbe> probe : pending.entrySet()) {
                PendingProbe pp = probe.getValue();
                if (pos.distanceTo(pp.center) > pp.radius) continue;

                long packedPos = pos.packed();

                // Was this position showing as ore before the blast?
                Integer preBlastStateId = pp.preBlastOrePositions.get(packedPos);
                if (preBlastStateId != null) {
                    String blockName = resolveBlockName(newStateId);
                    OreType ore = OreType.fromBlockName(blockName);

                    if (ore != null && ore.isBlastResistant()) {
                        // Position still shows as blast-resistant ore after explosion
                        // This is real ore (survived the blast)
                        map.put(pos, BlockConfidence.groundTruth(newStateId, blockName,
                            BlockConfidence.Source.BLAST_PROBE));
                        confirmedReal++;
                    } else {
                        // Position was showing as ore but is now something else
                        // The "ore" was fake (destroyed by blast or was never really there)
                        map.put(pos, BlockConfidence.groundTruth(newStateId, blockName,
                            BlockConfidence.Source.BLAST_PROBE));
                        confirmedFake++;
                    }
                } else {
                    // Position wasn't tracked as ore, but we still got ground truth
                    String blockName = resolveBlockName(newStateId);
                    map.put(pos, BlockConfidence.groundTruth(newStateId, blockName,
                        BlockConfidence.Source.BLAST_PROBE));
                }

                resolvedProbes.add(probe.getKey());
            }
        }

        // Clean up old probes (timeout after 5 seconds — explosion should be instant)
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().timestamp > 5000);
    }

    // Called by the bot when it's about to place a bed/TNT
    // Returns the positions within blast radius that currently show as ore
    public Map<Long, Integer> snapshotOrePositions(BlockPos center, boolean isBed, BlockMap map) {
        double radius = isBed ? BED_BLAST_RADIUS : TNT_BLAST_RADIUS;
        int r = (int) Math.ceil(radius);
        Map<Long, Integer> snapshot = new HashMap<>();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (pos.distanceTo(center) > radius) continue;

                    BlockConfidence conf = map.get(pos);
                    if (conf != null && conf.confidence() > 0) {
                        OreType ore = OreType.fromBlockName(conf.blockName());
                        if (ore != null) {
                            snapshot.put(pos.packed(), conf.blockStateId());
                        }
                    }
                }
            }
        }

        return snapshot;
    }

    public int totalProbes() { return totalProbes; }
    public int confirmedReal() { return confirmedReal; }
    public int confirmedFake() { return confirmedFake; }

    public void clear() {
        pending.clear();
        totalProbes = 0;
        confirmedReal = 0;
        confirmedFake = 0;
    }

    // Placeholder — actual resolution comes from the block state registry
    // which is loaded at runtime from the game's data
    static final ConcurrentHashMap<Integer, String> STATE_ID_CACHE = new ConcurrentHashMap<>();

    public static void registerBlockState(int stateId, String blockName) {
        STATE_ID_CACHE.put(stateId, blockName);
    }

    private static String resolveBlockName(int stateId) {
        return STATE_ID_CACHE.getOrDefault(stateId, "unknown:" + stateId);
    }
}
