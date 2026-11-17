package barry.config;

import barry.util.Dimension;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Settings {

    private static final Settings INSTANCE = new Settings();
    public static Settings get() { return INSTANCE; }

    // === Render ===
    private float confidenceFloor = 0.5f;
    private double renderRadius = 64.0;
    private double fadeStart = 48.0;
    private float maxOpacity = 0.8f;
    private boolean showTracers = true;
    private int maxTracers = 5;
    private boolean showHeatmap = false;
    private boolean showChunkGrid = false;
    private boolean showConfidence = true;
    private final Map<String, Boolean> oreVisibility = new ConcurrentHashMap<>();

    // === Rate and Goals ===
    private int targetDebrisPerHour = 200;
    private boolean humanCap = true;
    private boolean uncapped = false;

    // === Targeting ===
    private int netherMinY = 8;
    private int netherMaxY = 22;
    private int overworldMinY = -64;
    private int overworldMaxY = 16;
    private int blobMin = 1;
    private int blobMax = 100;
    private boolean skipIfLava = false;
    private float densityFloor = 0.0f;
    private boolean ignoreVisited = true;
    private final Set<String> blockBlacklist = ConcurrentHashMap.newKeySet();

    // === Pathing ===
    private int tunnelWidth = 2;
    private boolean diagonalBias = false;
    private double maxDetour = 32.0;
    private boolean preferRoof = false;
    private int bedRateCap = 0;
    private int tntRateCap = 0;
    private int stuckTimeout = 30;

    // === Humanizer ===
    private boolean gcdMode = true;
    private float jitter = 0.3f;
    private int latencyMin = 50;
    private int latencyMax = 150;
    private float wasteRatio = 0.05f;
    private float outOfOrderRatio = 0.02f;
    private int idleFrequency = 300;
    private float cameraWander = 0.5f;
    private boolean eatOnLowHealth = true;
    private boolean fleeOnLowHealth = true;

    // === Watcher ===
    private float watcherThreshold = 0.7f;
    private float watcherHysteresis = 0.3f;
    private int dwellBeforeDisconnect = 30;
    private final Map<String, Float> signalWeights = new ConcurrentHashMap<>();

    // === Inventory ===
    private final Set<String> junkBlacklist = ConcurrentHashMap.newKeySet();
    private boolean autoDrop = true;
    private int keepSlots = 9;
    private int repairThreshold = 10;
    private int depositTarget = -1;
    private int totemSlot = 40; // offhand

    // === Safety ===
    private int stopOnDebrisCount = 0;
    private int stopOnMinutes = 0;
    private boolean stopOnFullInventory = true;
    private boolean stopOnStaffDwell = true;
    private int stopOnFlagCount = 3;
    private boolean stopOnDimensionChange = false;
    private int healthFloor = 4;

    // === Dimensions ===
    private Dimension activeDimension = Dimension.NETHER;

    private Settings() {
        // Default signal weights
        signalWeights.put("tablist_diff", 0.4f);
        signalWeights.put("player_info_unlisted", 0.6f);
        signalWeights.put("entity_uuid_mismatch", 0.5f);
        signalWeights.put("rank_prefix", 0.3f);
        signalWeights.put("scoreboard_delta", 0.2f);
    }

    // === Getters (selected) ===

    public float confidenceFloor() { return confidenceFloor; }
    public double renderRadius() { return renderRadius; }
    public float maxOpacity() { return maxOpacity; }
    public boolean showTracers() { return showTracers; }
    public int maxTracers() { return maxTracers; }
    public int targetDebrisPerHour() { return targetDebrisPerHour; }
    public boolean humanCap() { return humanCap; }
    public boolean uncapped() { return uncapped; }
    public int netherMinY() { return netherMinY; }
    public int netherMaxY() { return netherMaxY; }
    public int tunnelWidth() { return tunnelWidth; }
    public double maxDetour() { return maxDetour; }
    public boolean gcdMode() { return gcdMode; }
    public float jitter() { return jitter; }
    public int latencyMin() { return latencyMin; }
    public int latencyMax() { return latencyMax; }
    public float watcherThreshold() { return watcherThreshold; }
    public int dwellBeforeDisconnect() { return dwellBeforeDisconnect; }
    public int healthFloor() { return healthFloor; }
    public Dimension activeDimension() { return activeDimension; }

    // === Setters (selected, with clamping) ===

    public void setConfidenceFloor(float v) { confidenceFloor = clamp(v, 0, 1); }
    public void setRenderRadius(double v) { renderRadius = clamp(v, 8, 256); fadeStart = renderRadius * 0.75; }
    public void setMaxOpacity(float v) { maxOpacity = clamp(v, 0, 1); }
    public void setShowTracers(boolean v) { showTracers = v; }
    public void setMaxTracers(int v) { maxTracers = clamp(v, 0, 20); }
    public void setTargetDebrisPerHour(int v) { targetDebrisPerHour = clamp(v, 20, 600); }
    public void setHumanCap(boolean v) { humanCap = v; }
    public void setUncapped(boolean v) { uncapped = v; }
    public void setNetherYRange(int min, int max) { netherMinY = min; netherMaxY = max; }
    public void setTunnelWidth(int v) { tunnelWidth = clamp(v, 1, 3); }
    public void setMaxDetour(double v) { maxDetour = clamp(v, 0, 128); }
    public void setGcdMode(boolean v) { gcdMode = v; }
    public void setJitter(float v) { jitter = clamp(v, 0, 1); }
    public void setLatencyBounds(int min, int max) { latencyMin = min; latencyMax = max; }
    public void setWatcherThreshold(float v) { watcherThreshold = clamp(v, 0, 1); }
    // panicKey moved to KeybindRegistry
    public void setHealthFloor(int v) { healthFloor = clamp(v, 0, 20); }
    public void setActiveDimension(Dimension d) { activeDimension = d; }
    public void setStopOnDebrisCount(int v) { stopOnDebrisCount = v; }
    public void setStopOnMinutes(int v) { stopOnMinutes = v; }

    // === Profile application ===

    public void applyProfile(Profile profile) {
        switch (profile) {
            case LEGIT -> {
                targetDebrisPerHour = 60;
                humanCap = true;
                gcdMode = true;
                jitter = 0.5f;
                wasteRatio = 0.10f;
                idleFrequency = 120;
                confidenceFloor = 0.8f;
                watcherThreshold = 0.5f;
            }
            case BALANCED -> {
                targetDebrisPerHour = 200;
                humanCap = true;
                gcdMode = true;
                jitter = 0.3f;
                wasteRatio = 0.05f;
                idleFrequency = 300;
                confidenceFloor = 0.5f;
                watcherThreshold = 0.7f;
            }
            case YOLO -> {
                targetDebrisPerHour = 500;
                humanCap = false;
                gcdMode = false;
                jitter = 0.1f;
                wasteRatio = 0.01f;
                idleFrequency = 600;
                confidenceFloor = 0.3f;
                watcherThreshold = 0.9f;
            }
        }
    }

    // === Serialization ===

    public String toBase64() {
        // Serialize all settings to a compact base64 string
        StringBuilder sb = new StringBuilder();
        sb.append("v1|");
        sb.append(targetDebrisPerHour).append("|");
        sb.append(humanCap ? 1 : 0).append("|");
        sb.append(confidenceFloor).append("|");
        sb.append(renderRadius).append("|");
        sb.append(gcdMode ? 1 : 0).append("|");
        sb.append(jitter).append("|");
        sb.append(watcherThreshold).append("|");
        sb.append(healthFloor);
        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }

    public void fromBase64(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded));
            String[] parts = decoded.split("\\|");
            if (!parts[0].equals("v1")) return;
            targetDebrisPerHour = Integer.parseInt(parts[1]);
            humanCap = parts[2].equals("1");
            confidenceFloor = Float.parseFloat(parts[3]);
            renderRadius = Double.parseDouble(parts[4]);
            gcdMode = parts[5].equals("1");
            jitter = Float.parseFloat(parts[6]);
            watcherThreshold = Float.parseFloat(parts[7]);
            healthFloor = Integer.parseInt(parts[8]);
        } catch (Exception ignored) {}
    }

    // === Utility ===

    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
