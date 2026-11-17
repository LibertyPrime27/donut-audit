package barry.core;

public record BlockConfidence(int blockStateId, String blockName, float confidence, Source source) {

    public enum Source {
        CHUNK_LOAD,       // initial chunk data (may contain fakes)
        BLOCK_UPDATE,     // server block-update packet (ground truth)
        BLAST_PROBE,      // survived/didn't survive explosion (ground truth)
        MINING_REVEAL,    // revealed by breaking an adjacent block (ground truth)
        CAVE_EXPOSE,      // exposed to air/lava (ground truth in nether, needs hider check in OW)
        PLAYER_BREAK,     // another player broke a block nearby
        CHUNK_DIFF,       // survived multiple chunk resends (statistical)
        SEED_PREDICT,     // predicted from world seed (unverified)
        UNKNOWN           // no data
    }

    public boolean isGroundTruth() {
        return switch (source) {
            case BLOCK_UPDATE, BLAST_PROBE, MINING_REVEAL, CAVE_EXPOSE, PLAYER_BREAK -> true;
            default -> false;
        };
    }

    public static BlockConfidence unknown() {
        return new BlockConfidence(0, "unknown", 0f, Source.UNKNOWN);
    }

    public static BlockConfidence groundTruth(int stateId, String name, Source source) {
        return new BlockConfidence(stateId, name, 1.0f, source);
    }

    public static BlockConfidence statistical(int stateId, String name, float confidence) {
        return new BlockConfidence(stateId, name, confidence, Source.CHUNK_DIFF);
    }

    public static BlockConfidence predicted(int stateId, String name, float confidence) {
        return new BlockConfidence(stateId, name, confidence, Source.SEED_PREDICT);
    }
}
