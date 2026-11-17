package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkDiff {

    // For each chunk, store multiple snapshots of block state IDs
    // from successive chunk sends. After N snapshots, blocks that
    // appear as ore in ALL snapshots are likely real. Blocks that
    // appear as ore in some but not all are likely fake (anti-xray).
    //
    // Paper anti-xray mode 2 re-randomizes fake ores per chunk send.
    // A block that tests as ore in N independent samples has probability
    // p^N of being fake, where p is the probability that a random block
    // is assigned as that ore type. For typical configs, p ≈ 0.03-0.08
    // per hidden-block entry, so 3 samples give p^3 ≈ 0.00003-0.0005.

    private static final int MAX_SNAPSHOTS = 8;

    // chunkKey -> list of section snapshots
    // Each snapshot: sectionY -> int[4096] of block state IDs
    private final ConcurrentHashMap<Long, List<Map<Integer, int[]>>> snapshots = new ConcurrentHashMap<>();

    // Set of block state IDs that are ore types we care about
    private final Set<Integer> targetStateIds = ConcurrentHashMap.newKeySet();
    // Block name -> state ID, for reverse lookup
    private final ConcurrentHashMap<String, Integer> nameToStateId = new ConcurrentHashMap<>();

    public void registerTargetOre(String blockName, int stateId) {
        targetStateIds.add(stateId);
        nameToStateId.put(blockName, stateId);
    }

    public void addSnapshot(ChunkKey chunk, Map<Integer, int[]> sectionData) {
        long key = chunk.packed();
        List<Map<Integer, int[]>> list = snapshots.computeIfAbsent(key, k ->
            Collections.synchronizedList(new ArrayList<>()));

        // Deep copy the section data
        Map<Integer, int[]> copy = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : sectionData.entrySet()) {
            copy.put(e.getKey(), e.getValue().clone());
        }

        synchronized (list) {
            if (list.size() >= MAX_SNAPSHOTS) {
                list.removeFirst();
            }
            list.add(copy);
        }
    }

    public int snapshotCount(ChunkKey chunk) {
        List<Map<Integer, int[]>> list = snapshots.get(chunk.packed());
        return list == null ? 0 : list.size();
    }

    public void analyze(ChunkKey chunk, BlockMap map, Dimension dim) {
        List<Map<Integer, int[]>> list = snapshots.get(chunk.packed());
        if (list == null || list.size() < 2) return;

        int bx = chunk.x() << 4;
        int bz = chunk.z() << 4;

        synchronized (list) {
            int n = list.size();

            // For each section present in ALL snapshots
            Set<Integer> commonSections = new HashSet<>(list.getFirst().keySet());
            for (int i = 1; i < n; i++) {
                commonSections.retainAll(list.get(i).keySet());
            }

            for (int sectionY : commonSections) {
                int baseY = sectionY << 4;

                for (int idx = 0; idx < 4096; idx++) {
                    int lx = idx & 0xF;
                    int lz = (idx >> 4) & 0xF;
                    int ly = (idx >> 8) & 0xF;
                    int worldY = baseY + ly;

                    // Count how many snapshots show this position as ore
                    int oreAppearances = 0;
                    int lastOreStateId = 0;
                    boolean consistent = true;
                    int firstStateId = list.getFirst().get(sectionY)[idx];

                    for (Map<Integer, int[]> snap : list) {
                        int stateId = snap.get(sectionY)[idx];
                        if (targetStateIds.contains(stateId)) {
                            oreAppearances++;
                            if (lastOreStateId != 0 && lastOreStateId != stateId) {
                                consistent = false;
                            }
                            lastOreStateId = stateId;
                        }
                        if (stateId != firstStateId) {
                            consistent = false;
                        }
                    }

                    BlockPos pos = new BlockPos(bx + lx, worldY, bz + lz);

                    if (oreAppearances == n && consistent) {
                        // Appeared as the same ore in every snapshot — likely real
                        float confidence = 1.0f - (float) Math.pow(0.07, n);
                        String name = reverseLookup(lastOreStateId);
                        map.put(pos, BlockConfidence.statistical(lastOreStateId, name, confidence));
                    } else if (oreAppearances > 0 && oreAppearances < n) {
                        // Appeared as ore in some snapshots but not all — confirmed fake
                        // Don't add it to the map as ore; if it was there, remove it
                        BlockConfidence existing = map.get(pos);
                        if (existing != null && !existing.isGroundTruth()) {
                            map.put(pos, BlockConfidence.unknown());
                        }
                    } else if (oreAppearances == n && !consistent) {
                        // Different ore types each time — the position keeps getting
                        // assigned as some ore, but different types. Likely fake.
                        BlockConfidence existing = map.get(pos);
                        if (existing != null && !existing.isGroundTruth()) {
                            map.put(pos, BlockConfidence.unknown());
                        }
                    }
                    // oreAppearances == 0: not ore in any snapshot, skip
                }
            }
        }
    }

    public DiffStats stats(ChunkKey chunk) {
        List<Map<Integer, int[]>> list = snapshots.get(chunk.packed());
        if (list == null || list.size() < 2) return new DiffStats(0, 0, 0, 0);

        int stableOre = 0, flickeringOre = 0, stableOther = 0, changed = 0;

        synchronized (list) {
            int n = list.size();
            Set<Integer> commonSections = new HashSet<>(list.getFirst().keySet());
            for (int i = 1; i < n; i++) commonSections.retainAll(list.get(i).keySet());

            for (int sectionY : commonSections) {
                for (int idx = 0; idx < 4096; idx++) {
                    int first = list.getFirst().get(sectionY)[idx];
                    boolean allSame = true;
                    boolean isOre = targetStateIds.contains(first);
                    int oreCount = isOre ? 1 : 0;

                    for (int i = 1; i < n; i++) {
                        int sid = list.get(i).get(sectionY)[idx];
                        if (sid != first) allSame = false;
                        if (targetStateIds.contains(sid)) oreCount++;
                    }

                    if (allSame && isOre) stableOre++;
                    else if (allSame) stableOther++;
                    else if (oreCount > 0) flickeringOre++;
                    else changed++;
                }
            }
        }

        return new DiffStats(stableOre, flickeringOre, stableOther, changed);
    }

    private String reverseLookup(int stateId) {
        for (Map.Entry<String, Integer> e : nameToStateId.entrySet()) {
            if (e.getValue() == stateId) return e.getKey();
        }
        return "unknown:" + stateId;
    }

    public void clearChunk(ChunkKey chunk) {
        snapshots.remove(chunk.packed());
    }

    public void clear() {
        snapshots.clear();
    }

    public record DiffStats(int stableOre, int flickeringOre, int stableOther, int changed) {
        public int totalExamined() { return stableOre + flickeringOre + stableOther + changed; }
        public double fakeOreRatio() {
            int totalOre = stableOre + flickeringOre;
            return totalOre == 0 ? 0 : (double) flickeringOre / totalOre;
        }
    }
}
