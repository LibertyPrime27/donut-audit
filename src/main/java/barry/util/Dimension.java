package barry.util;

public enum Dimension {
    OVERWORLD("minecraft:overworld"),
    NETHER("minecraft:the_nether"),
    END("minecraft:the_end"),
    UNKNOWN("unknown");

    private final String id;

    Dimension(String id) { this.id = id; }

    public String id() { return id; }

    public static Dimension fromId(String id) {
        for (Dimension d : values()) {
            if (d.id.equals(id)) return d;
        }
        return UNKNOWN;
    }

    public boolean isNether() { return this == NETHER; }
    public boolean isOverworld() { return this == OVERWORLD; }
}
