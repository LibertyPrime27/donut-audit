package barry.xray;

import barry.core.BlockMap;
import barry.util.BlockPos;
import barry.util.ChunkKey;
import barry.util.Dimension;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class XrayEngine {

    private final BlockMap netherMap = new BlockMap(Dimension.NETHER);
    private final BlockMap overworldMap = new BlockMap(Dimension.OVERWORLD);

    private final NetherStrategy nether = new NetherStrategy(netherMap);
    private final OverworldStrategy overworld = new OverworldStrategy(overworldMap);

    private volatile Dimension currentDimension = Dimension.OVERWORLD;
    private volatile BlockPos playerPos = new BlockPos(0, 64, 0);

    // Block state registry: stateId -> block name
    private final ConcurrentHashMap<Integer, String> stateRegistry = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;

    public void setDimension(Dimension dim) {
        this.currentDimension = dim;
    }

    public Dimension currentDimension() {
        return currentDimension;
    }

    public void setPlayerPos(BlockPos pos) {
        this.playerPos = pos;
        if (currentDimension.isOverworld()) {
            overworld.onPlayerMove(pos);
        }
    }

    public BlockPos playerPos() {
        return playerPos;
    }

    public void registerBlockState(int stateId, String blockName) {
        stateRegistry.put(stateId, blockName);
        BlastProbe.registerBlockState(stateId, blockName);

        // Also register with chunk diff if it's a target ore
        for (barry.core.OreType ore : barry.core.OreType.values()) {
            if (ore.blockName().equals(blockName)) {
                nether.diff().registerTargetOre(blockName, stateId);
                overworld.diff().registerTargetOre(blockName, stateId);
            }
        }
    }

    public String resolveBlockName(int stateId) {
        return stateRegistry.getOrDefault(stateId, "unknown:" + stateId);
    }

    // --- Packet handlers ---

    public void onChunkData(int chunkX, int chunkZ, Map<Integer, int[]> sectionData) {
        if (!enabled) return;
        ChunkKey key = new ChunkKey(chunkX, chunkZ);
        java.util.function.IntFunction<String> resolver = this::resolveBlockName;

        if (currentDimension.isNether()) {
            nether.onChunkLoad(key, sectionData, resolver);
        } else if (currentDimension.isOverworld()) {
            overworld.onChunkLoad(key, sectionData, resolver);
        }
    }

    public void onBlockUpdate(int x, int y, int z, int stateId) {
        if (!enabled) return;
        BlockPos pos = new BlockPos(x, y, z);
        String name = resolveBlockName(stateId);

        if (currentDimension.isNether()) {
            nether.onBlockUpdate(pos, stateId, name);
        } else if (currentDimension.isOverworld()) {
            overworld.onBlockUpdate(pos, stateId, name);
        }
    }

    public void onMultiBlockUpdate(List<Map.Entry<BlockPos, Integer>> updates) {
        if (!enabled) return;
        if (currentDimension.isNether()) {
            nether.onMultiBlockUpdate(updates);
        } else if (currentDimension.isOverworld()) {
            overworld.onMultiBlockUpdate(updates);
        }
    }

    public void onExplosion(BlockPos center, List<BlockPos> affectedBlocks) {
        if (!enabled) return;
        // Convert affected blocks to updates (they all become air)
        List<Map.Entry<BlockPos, Integer>> updates = new ArrayList<>();
        for (BlockPos pos : affectedBlocks) {
            updates.add(Map.entry(pos, 0)); // 0 = air state ID (typically)
        }
        if (currentDimension.isNether()) {
            nether.onMultiBlockUpdate(updates);
        }
    }

    public void onBedPlacement(BlockPos pos) {
        if (!enabled) return;
        if (currentDimension.isNether()) {
            nether.registerBedPlacement(pos);
        }
    }

    // --- Access for rendering ---

    public BlockMap currentMap() {
        return currentDimension.isNether() ? netherMap : overworldMap;
    }

    public BlockMap netherMap() { return netherMap; }
    public BlockMap overworldMap() { return overworldMap; }
    public NetherStrategy netherStrategy() { return nether; }
    public OverworldStrategy overworldStrategy() { return overworld; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String statsLine() {
        return currentDimension.isNether() ? nether.statsLine() : overworld.statsLine();
    }

    public void clear() {
        netherMap.clear();
        overworldMap.clear();
    }
}
