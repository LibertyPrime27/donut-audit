package barry.core;

import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class BlockMap {

    private final Dimension dimension;

    // chunk key -> section y -> position-within-section -> confidence
    // position-within-section: (x&15) | ((z&15) << 4) | ((y&15) << 8) = 12 bits = 4096 entries
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, BlockConfidence[]>> data = new ConcurrentHashMap<>();

    // Ore candidates: positions we believe contain real ore, indexed for rendering
    private final ConcurrentHashMap<Long, BlockConfidence> orePositions = new ConcurrentHashMap<>();

    // Stats
    private final AtomicLong groundTruthCount = new AtomicLong();
    private final AtomicLong statisticalCount = new AtomicLong();
    private final AtomicLong unknownCount = new AtomicLong();

    public BlockMap(Dimension dimension) {
        this.dimension = dimension;
    }

    public Dimension dimension() { return dimension; }

    public void put(BlockPos pos, BlockConfidence conf) {
        long chunkKey = pos.chunkKey();
        int sectionY = pos.sectionY();
        int index = sectionIndex(pos);

        ConcurrentHashMap<Integer, BlockConfidence[]> chunk =
            data.computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
        BlockConfidence[] section =
            chunk.computeIfAbsent(sectionY, k -> new BlockConfidence[4096]);

        BlockConfidence old = section[index];
        // Ground truth always wins. Higher confidence wins among same type.
        if (old != null && old.isGroundTruth() && !conf.isGroundTruth()) return;
        if (old != null && old.confidence() >= conf.confidence() && !conf.isGroundTruth()) return;

        section[index] = conf;
        long packed = pos.packed();

        // Track ore positions separately for rendering
        OreType ore = OreType.fromBlockName(conf.blockName());
        if (ore != null && ore.isTarget(dimension) && conf.confidence() > 0) {
            orePositions.put(packed, conf);
        } else if (old != null) {
            // If this position was an ore but now isn't, remove it
            OreType oldOre = OreType.fromBlockName(old.blockName());
            if (oldOre != null) {
                orePositions.remove(packed);
            }
        }

        // Stats
        if (conf.isGroundTruth()) groundTruthCount.incrementAndGet();
        else if (conf.source() != BlockConfidence.Source.UNKNOWN) statisticalCount.incrementAndGet();
        else unknownCount.incrementAndGet();
    }

    public BlockConfidence get(BlockPos pos) {
        long chunkKey = pos.chunkKey();
        int sectionY = pos.sectionY();
        int index = sectionIndex(pos);

        ConcurrentHashMap<Integer, BlockConfidence[]> chunk = data.get(chunkKey);
        if (chunk == null) return null;
        BlockConfidence[] section = chunk.get(sectionY);
        if (section == null) return null;
        return section[index];
    }

    public Map<Long, BlockConfidence> orePositions() {
        return Collections.unmodifiableMap(orePositions);
    }

    public List<Map.Entry<BlockPos, BlockConfidence>> oresNear(BlockPos center, double radius, float minConfidence) {
        double r2 = radius * radius;
        List<Map.Entry<BlockPos, BlockConfidence>> result = new ArrayList<>();
        for (Map.Entry<Long, BlockConfidence> e : orePositions.entrySet()) {
            if (e.getValue().confidence() < minConfidence) continue;
            BlockPos pos = BlockPos.fromPacked(e.getKey());
            if (pos.distanceSqTo(center) <= r2) {
                result.add(Map.entry(pos, e.getValue()));
            }
        }
        result.sort(Comparator.comparingDouble(a -> a.getKey().distanceSqTo(center)));
        return result;
    }

    public int oreCount() {
        return orePositions.size();
    }

    public int oreCount(float minConfidence) {
        int count = 0;
        for (BlockConfidence c : orePositions.values()) {
            if (c.confidence() >= minConfidence) count++;
        }
        return count;
    }

    public void clearChunk(ChunkKey chunk) {
        long key = chunk.packed();
        ConcurrentHashMap<Integer, BlockConfidence[]> sections = data.remove(key);
        if (sections == null) return;
        // Remove ore entries for this chunk
        int bx = chunk.x() << 4;
        int bz = chunk.z() << 4;
        for (Map.Entry<Integer, BlockConfidence[]> sec : sections.entrySet()) {
            int sy = sec.getKey() << 4;
            for (int i = 0; i < 4096; i++) {
                if (sec.getValue()[i] == null) continue;
                int lx = i & 0xF;
                int lz = (i >> 4) & 0xF;
                int ly = (i >> 8) & 0xF;
                BlockPos pos = new BlockPos(bx + lx, sy + ly, bz + lz);
                orePositions.remove(pos.packed());
            }
        }
    }

    public void clear() {
        data.clear();
        orePositions.clear();
        groundTruthCount.set(0);
        statisticalCount.set(0);
        unknownCount.set(0);
    }

    public long groundTruthCount() { return groundTruthCount.get(); }
    public long statisticalCount() { return statisticalCount.get(); }

    private static int sectionIndex(BlockPos pos) {
        return (pos.x() & 15) | ((pos.z() & 15) << 4) | ((pos.y() & 15) << 8);
    }
}
