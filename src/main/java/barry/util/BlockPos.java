package barry.util;

public record BlockPos(int x, int y, int z) {

    public long packed() {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static BlockPos fromPacked(long packed) {
        int x = (int) (packed >> 38);
        int z = (int) (packed << 26 >> 38);
        int y = (int) (packed << 52 >> 52);
        return new BlockPos(x, y, z);
    }

    public int chunkX() { return x >> 4; }
    public int chunkZ() { return z >> 4; }
    public int sectionY() { return y >> 4; }

    public long chunkKey() {
        return ((long) chunkX() << 32) | (chunkZ() & 0xFFFFFFFFL);
    }

    public double distanceTo(BlockPos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double distanceSqTo(BlockPos other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }
}
