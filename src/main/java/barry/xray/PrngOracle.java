package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.util.BlockPos;
import barry.util.ChunkKey;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cracks Paper's per-chunk anti-xray PRNG to identify fake blocks.
 *
 * Paper engine-mode-2 uses a 32-bit xorshift seeded per obfuscate() call.
 * The PRNG selects which preset block state to write at each fake position.
 * Given observed chunk data and the preset block state list, we can brute-force
 * the seed (2^32-1 states) and verify by replaying the sequence.
 *
 * Once cracked, every fake-block position in the chunk is known, and
 * subtracting them from the ore set yields only real blocks.
 */
public final class PrngOracle {

    private static final int SECTION_SIZE = 4096;

    private PrngOracle() {}

    /**
     * Paper's 32-bit xorshift: shifts 13, 17, 5.
     * Matches ChunkPacketBlockControllerAntiXray.java in Paper source.
     */
    static int xorshift(int state) {
        state ^= state << 13;
        state ^= state >>> 17;
        state ^= state << 5;
        return state;
    }

    /**
     * From a PRNG state, derive the index into the preset block state array.
     * Paper uses: random.getAsInt() which returns the absolute value modded
     * by the preset length. The xorshift returns the raw state; Paper wraps
     * it as an unsigned-ish value via Math.abs or bitmask.
     */
    static int presetIndex(int state, int presetLen) {
        return (state & 0x7FFFFFFF) % presetLen;
    }

    /**
     * Attempt to crack the PRNG seed for a single chunk section.
     *
     * @param sectionStates  the 4096 block state IDs from the chunk packet
     * @param solidMask      bitset: bit i set if position i is solid (non-exposed)
     *                       and would have been obfuscated by Paper
     * @param presetStateIds the ordered list of preset block state IDs that Paper
     *                       cycles through (from server config: hidden-blocks mapped
     *                       to their default state IDs)
     * @return cracked seed, or empty if no seed matched
     */
    public static OptionalInt crackSeed(
            int[] sectionStates,
            BitSet solidMask,
            int[] presetStateIds) {

        if (presetStateIds.length == 0) return OptionalInt.empty();

        int[] observedSeq = buildObservedSequence(sectionStates, solidMask, presetStateIds);
        if (observedSeq.length < 3) return OptionalInt.empty();

        Set<Integer> presetSet = new HashSet<>();
        for (int id : presetStateIds) presetSet.add(id);

        int pLen = presetStateIds.length;

        int cores = Runtime.getRuntime().availableProcessors();
        long total = 0xFFFFFFFFL;
        long chunkSize = (total + cores - 1) / cores;
        AtomicReference<Integer> found = new AtomicReference<>(null);

        ExecutorService pool = Executors.newFixedThreadPool(cores);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < cores; t++) {
            long start = (long) t * chunkSize + 1;
            long end = Math.min(start + chunkSize - 1, total);
            futures.add(pool.submit(() -> {
                for (long s = start; s <= end; s++) {
                    if (found.get() != null) return;
                    int state = (int) s;
                    boolean match = true;
                    for (int expected : observedSeq) {
                        state = xorshift(state);
                        int idx = presetIndex(state, pLen);
                        if (idx != expected) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        found.compareAndSet(null, (int) s);
                        return;
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }
        pool.shutdown();

        Integer result = found.get();
        return result != null ? OptionalInt.of(result) : OptionalInt.empty();
    }

    /**
     * Given a cracked seed, identify all fake block positions in the section.
     *
     * @return set of section-local indices (0..4095) that are fakes
     */
    public static BitSet identifyFakes(
            int crackedSeed,
            int[] sectionStates,
            BitSet solidMask,
            int[] presetStateIds) {

        BitSet fakes = new BitSet(SECTION_SIZE);
        int pLen = presetStateIds.length;
        int state = crackedSeed;

        for (int i = 0; i < SECTION_SIZE; i++) {
            if (!solidMask.get(i)) continue;

            state = xorshift(state);
            int idx = presetIndex(state, pLen);
            int fakeStateId = presetStateIds[idx];

            if (sectionStates[i] == fakeStateId) {
                fakes.set(i);
            }
        }

        return fakes;
    }

    /**
     * Full pipeline: crack the seed, then apply results to the BlockMap.
     * Marks fake-ore positions as unknown and surviving ore as high-confidence.
     *
     * @return true if the seed was cracked and results applied
     */
    public static boolean analyzeSection(
            ChunkKey chunk,
            int sectionY,
            int[] sectionStates,
            BitSet solidMask,
            int[] presetStateIds,
            Set<Integer> oreStateIds,
            BlockMap map) {

        OptionalInt seed = crackSeed(sectionStates, solidMask, presetStateIds);
        if (seed.isEmpty()) return false;

        BitSet fakes = identifyFakes(seed.getAsInt(), sectionStates, solidMask, presetStateIds);

        int bx = chunk.x() << 4;
        int bz = chunk.z() << 4;
        int baseY = sectionY << 4;

        for (int i = 0; i < SECTION_SIZE; i++) {
            if (!oreStateIds.contains(sectionStates[i])) continue;

            int lx = i & 0xF;
            int lz = (i >> 4) & 0xF;
            int ly = (i >> 8) & 0xF;
            BlockPos pos = new BlockPos(bx + lx, baseY + ly, bz + lz);

            if (fakes.get(i)) {
                BlockConfidence existing = map.get(pos);
                if (existing != null && !existing.isGroundTruth()) {
                    map.put(pos, BlockConfidence.unknown());
                }
            } else {
                map.put(pos, BlockConfidence.statistical(
                        sectionStates[i],
                        "prng-verified",
                        0.999f));
            }
        }

        return true;
    }

    /**
     * Build the observed sequence of preset indices from chunk data.
     * Paper iterates positions in XZY order and calls the PRNG for each
     * solid non-exposed block, selecting presetStateIds[index].
     * We reconstruct the index sequence from the observed block states.
     */
    private static int[] buildObservedSequence(
            int[] sectionStates,
            BitSet solidMask,
            int[] presetStateIds) {

        Map<Integer, Integer> stateToIdx = new HashMap<>();
        for (int i = 0; i < presetStateIds.length; i++) {
            stateToIdx.putIfAbsent(presetStateIds[i], i);
        }

        List<Integer> seq = new ArrayList<>();
        for (int i = 0; i < SECTION_SIZE; i++) {
            if (!solidMask.get(i)) continue;

            Integer idx = stateToIdx.get(sectionStates[i]);
            if (idx != null) {
                seq.add(idx);
            } else {
                seq.add(-1);
            }
        }

        return seq.stream().mapToInt(Integer::intValue).toArray();
    }
}
