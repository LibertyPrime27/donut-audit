package barry.humanizer;

import barry.config.Settings;
import java.util.concurrent.ThreadLocalRandom;

public final class TimingManager {

    // Human-like timing for all actions.
    //
    // Break cadence: must track vanilla tool speed exactly. No acceleration.
    // The server measures break progress per tick and flags if progress
    // exceeds the theoretical maximum for the tool+block+enchantment combo.
    //
    // Action timing: real humans have variable reaction times. We model
    // this as a gamma distribution with shape ~4 and rate ~40, producing
    // a mode around 100ms with a long tail.
    //
    // Packet ordering: occasionally send look packets slightly out of order
    // or skip a tick's packet entirely, as real clients do under load.

    // Latency simulation: add variable delay to actions
    private final long[] recentDelays = new long[32];
    private int delayIndex = 0;

    public long actionDelay() {
        Settings settings = Settings.get();
        int min = settings.latencyMin();
        int max = settings.latencyMax();

        // Gamma-distributed delay
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            sum += -Math.log(rng.nextDouble());
        }
        long delay = (long) (sum / 4.0 * (max - min) + min);
        delay = Math.max(min, Math.min(max * 3L, delay)); // clamp extreme outliers

        recentDelays[delayIndex++ & 31] = delay;
        return delay;
    }

    public long breakDelay(String blockName, String toolName, int efficiencyLevel, boolean hasteEffect) {
        // Vanilla break speed calculation
        // This MUST match the server's calculation exactly to avoid fast-break detection
        //
        // Base speed = tool_speed * efficiency_bonus * haste_bonus / block_hardness / 30
        // If in water without Aqua Affinity: /5
        // If not on ground: /5
        //
        // Break time = 1 / speed ticks

        double hardness = getHardness(blockName);
        if (hardness < 0) return 50; // instant break

        double speed = getToolSpeed(toolName, blockName);
        if (efficiencyLevel > 0) {
            speed += efficiencyLevel * efficiencyLevel + 1;
        }
        if (hasteEffect) {
            speed *= 1.2 * 1.2; // Haste II (most common for mining)
        }

        double damage = speed / hardness / 30.0;
        int ticks;
        if (damage >= 1.0) {
            ticks = 0; // instant
        } else {
            ticks = (int) Math.ceil(1.0 / damage);
        }

        // Convert ticks to ms (50ms per tick) and add human reaction time
        long baseMs = ticks * 50L;
        long humanDelay = (long) (ThreadLocalRandom.current().nextGaussian() * 20 + 30);
        return Math.max(50, baseMs + humanDelay);
    }

    public boolean shouldSkipPacket() {
        // Occasionally skip a tick's movement/look packet (1-3% of ticks)
        return ThreadLocalRandom.current().nextFloat() < Settings.get().jitter() * 0.1f;
    }

    public boolean shouldReorderPacket() {
        return ThreadLocalRandom.current().nextFloat() < Settings.get().jitter() * 0.05f;
    }

    public boolean shouldIdle() {
        // Randomly pause as if the player looked away from the screen
        int freq = 300; // seconds between idles on average
        // Poisson: probability of idle this tick = 1/freq/20
        return ThreadLocalRandom.current().nextFloat() < 1.0f / (freq * 20);
    }

    public long idleDuration() {
        // 2-15 seconds of idle
        return ThreadLocalRandom.current().nextLong(2000, 15000);
    }

    public boolean shouldWaste() {
        // Occasionally mine a non-target block (makes the pattern less systematic)
        return ThreadLocalRandom.current().nextFloat() < Settings.get().jitter() * 0.15f;
    }

    private double getHardness(String blockName) {
        return switch (blockName) {
            case "netherrack" -> 0.4;
            case "ancient_debris" -> 30.0;
            case "obsidian" -> 50.0;
            case "stone", "deepslate" -> 1.5;
            case "diamond_ore", "deepslate_diamond_ore" -> 3.0;
            case "iron_ore", "deepslate_iron_ore" -> 3.0;
            case "gold_ore" -> 3.0;
            case "nether_gold_ore" -> 3.0;
            case "basalt" -> 1.25;
            case "blackstone" -> 1.5;
            default -> 1.5;
        };
    }

    private double getToolSpeed(String toolName, String blockName) {
        if (toolName == null) return 1.0;
        if (toolName.contains("netherite_pickaxe")) return 9.0;
        if (toolName.contains("diamond_pickaxe")) return 8.0;
        if (toolName.contains("iron_pickaxe")) return 6.0;
        if (toolName.contains("stone_pickaxe")) return 4.0;
        if (toolName.contains("wooden_pickaxe")) return 2.0;
        return 1.0;
    }

    public long averageDelay() {
        long sum = 0;
        int count = 0;
        for (long d : recentDelays) {
            if (d > 0) { sum += d; count++; }
        }
        return count > 0 ? sum / count : 0;
    }
}
