package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.util.BlockPos;

import java.util.List;
import java.util.Map;

public final class MiningReveal {

    // Paper anti-xray sends real block data within update-radius (default 2)
    // of any block the client interacts with. Every block update that follows
    // a client-initiated break or placement is ground truth.

    private int revealedCount = 0;

    public void processBlockUpdates(List<Map.Entry<BlockPos, Integer>> updates, BlockMap map) {
        for (Map.Entry<BlockPos, Integer> update : updates) {
            BlockPos pos = update.getKey();
            int stateId = update.getValue();
            String blockName = BlastProbe.STATE_ID_CACHE.getOrDefault(stateId, "unknown:" + stateId);

            map.put(pos, BlockConfidence.groundTruth(stateId, blockName,
                BlockConfidence.Source.MINING_REVEAL));
            revealedCount++;
        }
    }

    public int revealedCount() { return revealedCount; }

    public void clear() { revealedCount = 0; }
}
