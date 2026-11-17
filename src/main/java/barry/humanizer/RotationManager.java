package barry.humanizer;

import barry.config.Settings;
import java.util.concurrent.ThreadLocalRandom;

public final class RotationManager {

    // GCD-quantized mouse deltas to avoid Baritone detection.
    //
    // Real mouse input produces deltas that are integer multiples of:
    //   (sensitivity * 0.6 + 0.2)^3 * 8 * 0.15
    // where sensitivity is the in-game mouse sensitivity slider (0.0-1.0).
    //
    // Baritone's tell: exact 45° multiples, duplicated identical look packets,
    // and float deltas that don't align to the mouse grid.
    //
    // GrimAC checks: AimModulo360, AimDuplicateLook, Baritone pattern detection.
    // Vulcan: Baritone check (looks for systematic rotation patterns).

    private double mouseSensitivity = 0.5; // default MC sensitivity
    private double gcdUnit; // the quantization unit

    // Anti-duplicate: track recent rotations to avoid repeats
    private float lastYaw = 0;
    private float lastPitch = 0;
    private int duplicateCount = 0;
    private static final int MAX_DUPLICATES = 2;

    // Off-axis bias: prevent clean 0/45/90 degree angles
    private float yawBias = 0;
    private float pitchBias = 0;

    public RotationManager() {
        recalculateGcd();
    }

    public void setSensitivity(double sens) {
        this.mouseSensitivity = Math.max(0.0, Math.min(1.0, sens));
        recalculateGcd();
    }

    private void recalculateGcd() {
        double f = mouseSensitivity * 0.6 + 0.2;
        gcdUnit = f * f * f * 8.0 * 0.15;
    }

    public float[] computeRotation(float targetYaw, float targetPitch) {
        Settings settings = Settings.get();
        boolean gcdMode = settings.gcdMode();
        float jitter = settings.jitter();

        float yaw = targetYaw;
        float pitch = targetPitch;

        // Add off-axis bias to prevent clean angles
        yaw += yawBias;
        pitch += pitchBias;

        // Clamp pitch
        pitch = Math.max(-90, Math.min(90, pitch));

        if (gcdMode) {
            // Quantize to GCD grid
            yaw = quantizeToGcd(yaw);
            pitch = quantizeToGcd(pitch);
        }

        // Add jitter (small random deviation within one GCD unit)
        if (jitter > 0) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            yaw += (float) (rng.nextGaussian() * gcdUnit * jitter);
            pitch += (float) (rng.nextGaussian() * gcdUnit * jitter * 0.5);
            pitch = Math.max(-90, Math.min(90, pitch));
        }

        // Anti-duplicate: if this rotation is identical to the last one,
        // add a tiny offset. GrimAC's AimDuplicateLook catches exact repeats.
        if (Math.abs(yaw - lastYaw) < 0.001f && Math.abs(pitch - lastPitch) < 0.001f) {
            duplicateCount++;
            if (duplicateCount > MAX_DUPLICATES) {
                yaw += (float) (gcdUnit * (ThreadLocalRandom.current().nextInt(1, 4)));
                duplicateCount = 0;
            }
        } else {
            duplicateCount = 0;
        }

        lastYaw = yaw;
        lastPitch = pitch;

        return new float[]{yaw, pitch};
    }

    public float[] smoothRotation(float currentYaw, float currentPitch,
                                    float targetYaw, float targetPitch,
                                    int steps) {
        // Multi-step smooth rotation to avoid instant snaps
        // Each step produces a GCD-quantized intermediate angle
        if (steps <= 1) return computeRotation(targetYaw, targetPitch);

        float deltaYaw = angleDiff(targetYaw, currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        // Ease-out curve: faster at start, slower at end
        float t = 1.0f / steps;
        float eased = 1.0f - (1.0f - t) * (1.0f - t);

        float stepYaw = currentYaw + deltaYaw * eased;
        float stepPitch = currentPitch + deltaPitch * eased;

        return computeRotation(stepYaw, stepPitch);
    }

    public void updateBias() {
        // Periodically shift the bias to prevent long runs on clean angles
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        yawBias = (float) (rng.nextGaussian() * 2.0);
        pitchBias = (float) (rng.nextGaussian() * 1.0);
    }

    public float[] computeLookAt(double playerX, double playerY, double playerZ,
                                   double targetX, double targetY, double targetZ) {
        double dx = targetX - playerX;
        double dy = targetY - (playerY + 1.62); // eye height
        double dz = targetZ - playerZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, dist));

        return computeRotation(yaw, pitch);
    }

    private float quantizeToGcd(float value) {
        return (float) (Math.round(value / gcdUnit) * gcdUnit);
    }

    private float angleDiff(float target, float current) {
        float diff = target - current;
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }

    public double gcdUnit() { return gcdUnit; }
}
