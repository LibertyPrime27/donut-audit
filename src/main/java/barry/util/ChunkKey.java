package barry.util;

public record ChunkKey(int x, int z) {

    public long packed() {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static ChunkKey fromPacked(long packed) {
        return new ChunkKey((int) (packed >> 32), (int) packed);
    }

    public static ChunkKey fromBlock(int blockX, int blockZ) {
        return new ChunkKey(blockX >> 4, blockZ >> 4);
    }
}
