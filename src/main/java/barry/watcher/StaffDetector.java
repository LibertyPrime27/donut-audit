package barry.watcher;

import barry.config.Settings;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class StaffDetector {

    // Multi-signal staff proximity detector.
    //
    // Staff on DonutSMP can vanish (become invisible spectators). When vanished:
    // - They are NOT sent to the client as entities
    // - They produce no sound or particle events
    // - There is no "player joined" message
    //
    // But several signals leak:
    // 1. Tablist diffs: a vanished player might still appear in the tablist
    //    with listed=false (PlayerInfoUpdate packet)
    // 2. Entity spawns for UUIDs absent from tab: if the vanish plugin
    //    removes from tab but forgets to prevent entity packets
    // 3. Rank prefixes: staff ranks in scoreboard or tablist
    // 4. Scoreboard count deltas: player count changes without a join message
    // 5. Timing anomalies: TPS drops or chunk resends when staff teleport nearby

    // Known staff UUIDs (populated from tablist scan)
    private final Set<UUID> knownStaffUuids = ConcurrentHashMap.newKeySet();
    private final Set<String> staffPrefixes = ConcurrentHashMap.newKeySet();

    // Signal state
    private final Map<String, SignalState> signals = new ConcurrentHashMap<>();

    // Composite score: 0.0 (no staff) to 1.0 (definitely staff present)
    private volatile float compositeScore = 0;
    private volatile long lastUpdateTime = 0;

    // Hysteresis state
    private volatile boolean alerted = false;
    private volatile long alertTime = 0;
    private volatile long clearTime = 0;

    public StaffDetector() {
        // Default staff prefixes (DonutSMP-specific, adjust per server)
        staffPrefixes.addAll(Set.of(
            "[Owner]", "[Admin]", "[Sr.Mod]", "[Mod]", "[Helper]",
            "[Staff]", "[Dev]", "[Builder]"
        ));
    }

    public record SignalState(String name, float value, long timestamp) {}

    // --- Signal inputs ---

    public void onTablistUpdate(List<TablistEntry> entries) {
        float signal = 0;

        for (TablistEntry entry : entries) {
            // Check for staff prefix in display name
            if (entry.displayName != null) {
                for (String prefix : staffPrefixes) {
                    if (entry.displayName.contains(prefix)) {
                        knownStaffUuids.add(entry.uuid);
                        signal = Math.max(signal, 0.3f);
                    }
                }
            }

            // Check for listed=false (vanished player showing in tab)
            if (!entry.listed && knownStaffUuids.contains(entry.uuid)) {
                signal = Math.max(signal, 0.6f);
            }
        }

        updateSignal("tablist_diff", signal);
    }

    public void onPlayerInfoUpdate(UUID uuid, boolean listed) {
        if (!listed && knownStaffUuids.contains(uuid)) {
            updateSignal("player_info_unlisted", 0.7f);
        } else if (!listed) {
            // Unknown player with listed=false — could be staff
            updateSignal("player_info_unlisted", 0.4f);
        }
    }

    public void onEntitySpawn(UUID entityUuid, Set<UUID> currentTabUuids) {
        // Entity spawned but UUID not in tablist — could be vanished staff
        if (!currentTabUuids.contains(entityUuid)) {
            if (knownStaffUuids.contains(entityUuid)) {
                updateSignal("entity_uuid_mismatch", 0.8f);
            } else {
                updateSignal("entity_uuid_mismatch", 0.5f);
            }
        }
    }

    public void onScoreboardCountChange(int oldCount, int newCount) {
        // Player count increased without a corresponding join message
        if (newCount > oldCount) {
            updateSignal("scoreboard_delta", 0.3f);
        }
    }

    public void onRankPrefix(UUID uuid, String prefix) {
        for (String staffPrefix : staffPrefixes) {
            if (prefix.contains(staffPrefix)) {
                knownStaffUuids.add(uuid);
                updateSignal("rank_prefix", 0.5f);
                return;
            }
        }
    }

    // --- Composite scoring ---

    private void updateSignal(String name, float value) {
        long now = System.currentTimeMillis();
        signals.put(name, new SignalState(name, value, now));
        recalcComposite(now);
    }

    private void recalcComposite(long now) {
        Settings settings = Settings.get();
        float score = 0;
        float totalWeight = 0;

        for (Map.Entry<String, SignalState> entry : signals.entrySet()) {
            SignalState state = entry.getValue();

            // Signals decay over time (30-second half-life)
            long age = now - state.timestamp;
            float decay = (float) Math.exp(-age / 30000.0);
            float decayed = state.value * decay;

            float weight = 1.0f; // default weight
            // Could load per-signal weights from settings here

            score += decayed * weight;
            totalWeight += weight;
        }

        compositeScore = totalWeight > 0 ? Math.min(1.0f, score / totalWeight * 2) : 0;
        lastUpdateTime = now;

        // Hysteresis
        float threshold = settings.watcherThreshold();
        float hysteresis = threshold * 0.3f; // 30% hysteresis band

        if (!alerted && compositeScore > threshold) {
            alerted = true;
            alertTime = now;
        } else if (alerted && compositeScore < (threshold - hysteresis)) {
            // Only clear if score has been below threshold for 30 seconds
            if (clearTime == 0) {
                clearTime = now;
            } else if (now - clearTime > 30000) {
                alerted = false;
                clearTime = 0;
            }
        } else {
            clearTime = 0;
        }
    }

    // --- Query ---

    public float score() { return compositeScore; }
    public boolean isAlerted() { return alerted; }
    public long alertDuration() {
        return alerted ? System.currentTimeMillis() - alertTime : 0;
    }

    public void addStaffUuid(UUID uuid) { knownStaffUuids.add(uuid); }
    public void addStaffPrefix(String prefix) { staffPrefixes.add(prefix); }
    public Set<UUID> knownStaff() { return Collections.unmodifiableSet(knownStaffUuids); }

    public Map<String, SignalState> signalStates() {
        return Collections.unmodifiableMap(signals);
    }

    public void clear() {
        signals.clear();
        compositeScore = 0;
        alerted = false;
    }

    public record TablistEntry(UUID uuid, String name, String displayName, boolean listed) {}
}
