package barry.bot;

public final class Governor {

    // PI controller for debris/hour rate
    // Setpoint: target_debris_per_hour (default 200)
    // Process variable: 15-minute rolling average of debris entering inventory
    // Actuators (lowest priority first):
    //   sprint/jump cadence, pause budget, tunnel width, Y-band width,
    //   selection radius

    private int targetPerHour = 200;
    private boolean humanCap = true; // cap at 250/h
    private static final int HUMAN_CAP_VALUE = 250;
    private boolean uncapped = false;

    // PI parameters
    private double kp = 0.5;
    private double ki = 0.05;
    private double integralError = 0;
    private double lastError = 0;
    private static final double DEADBAND = 0.10; // ±10%
    private static final double ANTI_WINDUP = 60.0; // integral clamp

    // Rolling window (15 minutes)
    private static final int WINDOW_SIZE = 180; // 5-second buckets, 180 = 15 min
    private final int[] buckets = new int[WINDOW_SIZE];
    private int bucketIndex = 0;
    private long lastBucketTime = 0;

    // Debris counter
    private int totalDebris = 0;
    private long firstDebrisTime = 0;

    public void recordDebris() {
        totalDebris++;
        if (firstDebrisTime == 0) firstDebrisTime = System.currentTimeMillis();

        long now = System.currentTimeMillis();
        advanceBuckets(now);
        buckets[bucketIndex]++;
    }

    public long shouldPause(int currentDebris, long startTime) {
        if (uncapped) return 0;

        double currentRate = rollingRate();
        int effectiveTarget = humanCap ? Math.min(targetPerHour, HUMAN_CAP_VALUE) : targetPerHour;

        if (currentRate <= 0) return 0; // no data yet, don't throttle

        double error = currentRate - effectiveTarget;

        // Deadband: if within ±10% of target, decay integral toward zero
        // rather than hard-resetting (hard reset causes a bump on re-entry)
        if (Math.abs(error) < effectiveTarget * DEADBAND) {
            integralError *= 0.9;
            return 0;
        }

        // Accumulate integral with symmetric anti-windup clamping
        integralError += error;
        integralError = Math.max(-ANTI_WINDUP, Math.min(ANTI_WINDUP, integralError));

        if (error > 0) {
            // Rate too high — need to slow down
            double output = kp * error + ki * integralError;
            long pauseMs = (long) (output * 100);
            return Math.min(pauseMs, 10000); // cap at 10 second pause
        }

        // Rate too low — no pause, let the bot run freely
        return 0;
    }

    public double rollingRate() {
        advanceBuckets(System.currentTimeMillis());

        int total = 0;
        for (int b : buckets) total += b;

        // Convert 15-minute count to per-hour rate
        return total * 4.0;
    }

    public double sessionRate() {
        if (firstDebrisTime == 0) return 0;
        double hoursElapsed = (System.currentTimeMillis() - firstDebrisTime) / 3_600_000.0;
        if (hoursElapsed < 0.001) return 0;
        return totalDebris / hoursElapsed;
    }

    private void advanceBuckets(long now) {
        if (lastBucketTime == 0) {
            lastBucketTime = now;
            return;
        }

        long elapsed = now - lastBucketTime;
        int bucketsToAdvance = (int) (elapsed / 5000); // 5-second buckets

        for (int i = 0; i < Math.min(bucketsToAdvance, WINDOW_SIZE); i++) {
            bucketIndex = (bucketIndex + 1) % WINDOW_SIZE;
            buckets[bucketIndex] = 0;
        }

        if (bucketsToAdvance > 0) {
            lastBucketTime = now;
        }
    }

    // --- Settings ---

    public void setTargetPerHour(int target) {
        this.targetPerHour = Math.max(20, Math.min(600, target));
    }
    public void setHumanCap(boolean cap) { this.humanCap = cap; }
    public void setUncapped(boolean uc) { this.uncapped = uc; }
    public int targetPerHour() { return targetPerHour; }
    public boolean isHumanCapped() { return humanCap; }
    public int totalDebris() { return totalDebris; }
}
