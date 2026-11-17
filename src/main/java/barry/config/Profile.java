package barry.config;

public enum Profile {
    LEGIT("Legit — slow, cautious, hard to detect"),
    BALANCED("Balanced — default, good rate with safety"),
    YOLO("YOLO — maximum rate, minimal caution");

    private final String description;

    Profile(String description) { this.description = description; }

    public String description() { return description; }

    public Profile next() {
        Profile[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
