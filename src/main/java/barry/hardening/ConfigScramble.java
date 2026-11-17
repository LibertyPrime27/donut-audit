package barry.hardening;

import java.util.concurrent.ThreadLocalRandom;

public final class ConfigScramble {

    private ConfigScramble() {}

    // XOR-based config obfuscation for in-memory settings.
    // This is NOT cryptographic security — it's obfuscation against
    // casual heap inspection. The key is derived from runtime state
    // (PID, thread ID, attach timestamp) so it's different each session.

    private static long key = 0;

    public static void init() {
        // Derive a session-specific key from runtime entropy
        long pid = ProcessHandle.current().pid();
        long threadId = Thread.currentThread().threadId();
        long time = System.nanoTime();
        key = pid ^ (threadId << 16) ^ (time >>> 8);
        if (key == 0) key = 0xDEADBEEF_CAFEBABEL;
    }

    public static int scramble(int value) {
        return value ^ (int) (key & 0xFFFFFFFFL);
    }

    public static int unscramble(int scrambled) {
        return scrambled ^ (int) (key & 0xFFFFFFFFL);
    }

    public static long scramble(long value) {
        return value ^ key;
    }

    public static long unscramble(long scrambled) {
        return scrambled ^ key;
    }

    public static float scramble(float value) {
        int bits = Float.floatToRawIntBits(value);
        return Float.intBitsToFloat(bits ^ (int) (key & 0xFFFFFFFFL));
    }

    public static float unscramble(float scrambled) {
        int bits = Float.floatToRawIntBits(scrambled);
        return Float.intBitsToFloat(bits ^ (int) (key & 0xFFFFFFFFL));
    }

    public static byte[] scrambleBytes(byte[] data) {
        byte[] out = new byte[data.length];
        byte[] keyBytes = longToBytes(key);
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ keyBytes[i & 7]);
        }
        return out;
    }

    public static byte[] unscrambleBytes(byte[] data) {
        return scrambleBytes(data); // XOR is its own inverse
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return b;
    }

    public static boolean isInitialized() {
        return key != 0;
    }

    public static void wipe() {
        key = 0;
    }
}
