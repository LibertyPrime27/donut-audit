package barry.humanizer;

import java.util.concurrent.ThreadLocalRandom;

public final class WanderManager {

    // Camera wander: subtle random head movement while mining or idle,
    // mimicking a human who isn't staring at exactly one point.
    //
    // Real players:
    // - Look around while walking (small yaw oscillation)
    // - Drift slightly while mining (pitch/yaw creep)
    // - Snap to new targets with overshoot and correction
    // - Occasionally look up at the ceiling or behind them

    private float wanderYaw = 0;
    private float wanderPitch = 0;
    private float wanderVelocityYaw = 0;
    private float wanderVelocityPitch = 0;
    private long lastWanderTime = 0;

    // Overshoot state for target acquisition
    private boolean overshooting = false;
    private float overshootAmount = 0;
    private int overshootTicks = 0;

    public float[] tickWander(float currentYaw, float currentPitch) {
        long now = System.currentTimeMillis();
        if (lastWanderTime == 0) {
            lastWanderTime = now;
            return new float[]{0, 0};
        }

        float dt = (now - lastWanderTime) / 1000.0f;
        lastWanderTime = now;

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Brownian motion with mean reversion
        wanderVelocityYaw += (float) (rng.nextGaussian() * 2.0 - wanderYaw * 0.1) * dt;
        wanderVelocityPitch += (float) (rng.nextGaussian() * 1.0 - wanderPitch * 0.2) * dt;

        // Damping
        wanderVelocityYaw *= 0.95f;
        wanderVelocityPitch *= 0.95f;

        wanderYaw += wanderVelocityYaw * dt;
        wanderPitch += wanderVelocityPitch * dt;

        // Clamp drift
        wanderYaw = Math.max(-5, Math.min(5, wanderYaw));
        wanderPitch = Math.max(-3, Math.min(3, wanderPitch));

        return new float[]{wanderYaw, wanderPitch};
    }

    public float[] computeOvershoot(float deltaYaw, float deltaPitch) {
        // When snapping to a new target, overshoot slightly and correct
        // This mimics real mouse movement
        if (Math.abs(deltaYaw) > 10 || Math.abs(deltaPitch) > 5) {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            float overshoot = 1.0f + (float) (rng.nextGaussian() * 0.08 + 0.05);
            return new float[]{deltaYaw * overshoot, deltaPitch * overshoot};
        }
        return new float[]{deltaYaw, deltaPitch};
    }

    public boolean shouldLookAround() {
        // Occasionally look around (every 30-120 seconds)
        return ThreadLocalRandom.current().nextFloat() < 0.001f;
    }

    public float[] lookAroundTarget(float currentYaw, float currentPitch) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float yaw = currentYaw + (float) (rng.nextGaussian() * 45);
        float pitch = (float) (rng.nextGaussian() * 20);
        return new float[]{yaw, pitch};
    }

    public void reset() {
        wanderYaw = 0;
        wanderPitch = 0;
        wanderVelocityYaw = 0;
        wanderVelocityPitch = 0;
    }
}
