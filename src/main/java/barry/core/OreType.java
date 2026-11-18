package barry.core;

import barry.util.Dimension;
import java.util.Set;

public enum OreType {
    ANCIENT_DEBRIS("ancient_debris", Set.of(Dimension.NETHER), 8, 22, true, 0xFFFF00FF),
    DIAMOND_ORE("diamond_ore", Set.of(Dimension.OVERWORLD), -64, 16, false, 0xFF55FFFF),
    DEEPSLATE_DIAMOND("deepslate_diamond_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFF44CCCC),
    EMERALD_ORE("emerald_ore", Set.of(Dimension.OVERWORLD), -16, 320, false, 0xFF00FF00),
    DEEPSLATE_EMERALD("deepslate_emerald_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFF00CC00),
    GOLD_ORE("gold_ore", Set.of(Dimension.OVERWORLD, Dimension.NETHER), -64, 32, false, 0xFFFFFF00),
    NETHER_GOLD("nether_gold_ore", Set.of(Dimension.NETHER), 10, 117, false, 0xFFFFDD00),
    AMETHYST_BUD("budding_amethyst", Set.of(Dimension.OVERWORLD), -64, 30, false, 0xFFCC66FF),
    LAPIS_ORE("lapis_ore", Set.of(Dimension.OVERWORLD), -64, 64, false, 0xFF3333FF),
    DEEPSLATE_LAPIS("deepslate_lapis_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFF2222CC),
    IRON_ORE("iron_ore", Set.of(Dimension.OVERWORLD), -64, 320, false, 0xFFCC9966),
    DEEPSLATE_IRON("deepslate_iron_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFFAA7744),
    COPPER_ORE("copper_ore", Set.of(Dimension.OVERWORLD), -16, 112, false, 0xFFE87040),
    DEEPSLATE_COPPER("deepslate_copper_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFFCC5520),
    COAL_ORE("coal_ore", Set.of(Dimension.OVERWORLD), 0, 320, false, 0xFF444444),
    REDSTONE_ORE("redstone_ore", Set.of(Dimension.OVERWORLD), -64, 16, false, 0xFFFF0000),
    DEEPSLATE_REDSTONE("deepslate_redstone_ore", Set.of(Dimension.OVERWORLD), -64, 0, false, 0xFFCC0000),
    NETHER_QUARTZ("nether_quartz_ore", Set.of(Dimension.NETHER), 10, 117, false, 0xFFEEDDCC),
    // Storage / loot (not ores, but tracked the same way).
    CHEST("chest", Set.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END), -64, 320, false, 0xFFFFAA00),
    TRAPPED_CHEST("trapped_chest", Set.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END), -64, 320, false, 0xFFFF7700),
    BARREL("barrel", Set.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END), -64, 320, false, 0xFFCC8844),
    ENDER_CHEST("ender_chest", Set.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END), -64, 320, false, 0xFFAA33FF),
    SPAWNER("spawner", Set.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END), -64, 320, false, 0xFF66CCFF);

    private final String blockName;
    private final Set<Dimension> dimensions;
    private final int minY;
    private final int maxY;
    private final boolean blastResistant;
    private final int color;
    // User-toggleable via the filter menu. Default OFF — the user enables what
    // they want to see. Scanning still records every tracked block; rendering/HUD
    // filter on this flag so toggling is instant.
    private volatile boolean enabled = false;

    OreType(String blockName, Set<Dimension> dims, int minY, int maxY, boolean blastResistant, int color) {
        this.blockName = blockName;
        this.dimensions = dims;
        this.minY = minY;
        this.maxY = maxY;
        this.blastResistant = blastResistant;
        this.color = color;
    }

    public String blockName() { return blockName; }
    public Set<Dimension> dimensions() { return dimensions; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public boolean isBlastResistant() { return blastResistant; }
    public int color() { return color; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean e) { enabled = e; }
    public void toggleEnabled() { enabled = !enabled; }

    public boolean isTarget(Dimension dim) {
        return dimensions.contains(dim);
    }

    public boolean inYRange(int y) {
        return y >= minY && y <= maxY;
    }

    public static int enabledCount() {
        int n = 0;
        for (OreType t : values()) if (t.enabled) n++;
        return n;
    }

    public static OreType fromBlockName(String name) {
        if (name == null) return null;
        // Exact match first so e.g. "ender_chest" doesn't match "chest".
        for (OreType t : values()) {
            if (t.blockName.equals(name)) return t;
        }
        for (OreType t : values()) {
            if (name.contains(t.blockName)) return t;
        }
        return null;
    }
}
