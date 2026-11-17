package barry.bot;

import barry.attach.Remap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

public final class Inventory {

    // Items we keep
    private static final Set<String> KEEP_ITEMS = Set.of(
        "netherite_pickaxe", "diamond_pickaxe", "netherite_sword",
        "ancient_debris", "netherite_scrap", "netherite_ingot",
        "diamond", "emerald",
        "ender_pearl",
        "golden_apple", "enchanted_golden_apple",
        "totem_of_undying",
        "white_bed", "red_bed", "orange_bed", "light_blue_bed", "yellow_bed",
        "lime_bed", "pink_bed", "gray_bed", "light_gray_bed", "cyan_bed",
        "purple_bed", "blue_bed", "brown_bed", "green_bed", "black_bed", "magenta_bed",
        "cooked_porkchop", "golden_carrot", "cooked_beef",
        "fire_resistance_potion"
    );

    // Items we always drop
    private static final Set<String> JUNK_ITEMS = Set.of(
        "netherrack", "blackstone", "basalt", "magma_block",
        "nether_quartz_ore", "nether_gold_ore",
        "cobblestone", "cobbled_deepslate", "gravel", "dirt",
        "soul_sand", "soul_soil"
    );

    private int bedSlot = -1;
    private int pickSlot = 0;
    private int swordSlot = 1;
    private int totemSlot = -1; // offhand

    // Repair threshold: durability % below which to repair
    private int repairThreshold = 10;
    private int keepSlots = 9; // hotbar slots that aren't for junk

    public boolean hasBed() {
        // Check hotbar and inventory for beds via reflection
        return bedSlot >= 0;
    }

    public void switchToBedSlot() {
        if (bedSlot < 0) return;
        setSelectedSlot(bedSlot);
    }

    public void switchToPickSlot() {
        setSelectedSlot(pickSlot);
    }

    public boolean isInventoryFull() {
        // Count empty inventory slots
        return getEmptySlots() == 0;
    }

    public void compactInventory() {
        // Drop junk items to free space
        // This requires sending inventory click packets
    }

    public void dropJunk() {
        // Iterate inventory, drop items in JUNK_ITEMS set
    }

    public boolean needsRepair() {
        // Check pickaxe durability
        return false; // TODO
    }

    private int getEmptySlots() {
        // Count empty inventory slots via reflection
        return 27; // placeholder
    }

    private void setSelectedSlot(int slot) {
        // Send ServerboundSetCarriedItemPacket
    }

    // --- Scan inventory state from game ---

    public void scanInventory() {
        try {
            ClassLoader cl = barry.Bootstrap.findMinecraftClassLoader();
            if (cl == null) return;

            Object mc = getMinecraftInstance(cl);
            if (mc == null) return;

            Object player = getPlayer(mc, cl);
            if (player == null) return;

            // player.getInventory() -> Inventory
            Object inv = null;
            for (Method m : player.getClass().getMethods()) {
                if (m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().contains("Inventory")
                        && !m.getReturnType().getSimpleName().contains("Container")) {
                    m.setAccessible(true);
                    inv = m.invoke(player);
                    break;
                }
            }
            if (inv == null) return;

            // Scan hotbar (slots 0-8) for beds and tools
            bedSlot = -1;
            for (int i = 0; i < 9; i++) {
                String item = getItemName(inv, i);
                if (item != null && item.contains("bed")) {
                    bedSlot = i;
                }
                if (item != null && item.contains("pickaxe")) {
                    pickSlot = i;
                }
            }
        } catch (Throwable ignored) {}
    }

    private String getItemName(Object inventory, int slot) {
        try {
            // inventory.getItem(slot).getItem().toString()
            Method getItem = null;
            for (Method m : inventory.getClass().getMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class
                        && m.getReturnType().getSimpleName().contains("ItemStack")) {
                    getItem = m;
                    break;
                }
            }
            if (getItem == null) return null;

            Object stack = getItem.invoke(inventory, slot);
            if (stack == null) return null;

            // Check if empty
            Method isEmpty = stack.getClass().getMethod("isEmpty");
            if ((boolean) isEmpty.invoke(stack)) return null;

            // Get item type
            for (Method m : stack.getClass().getMethods()) {
                if (m.getName().equals("getItem") && m.getParameterCount() == 0) {
                    Object item = m.invoke(stack);
                    return item != null ? item.toString() : null;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object getMinecraftInstance(ClassLoader cl) throws Exception {
        String mcClassName = Remap.resolveClass("net.minecraft.client.Minecraft");
        Class<?> mcClass = cl.loadClass(mcClassName);
        for (Method m : mcClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m.invoke(null);
            }
        }
        return null;
    }

    private Object getPlayer(Object mc, ClassLoader cl) throws Exception {
        String playerClassName = Remap.resolveClass("net.minecraft.client.player.LocalPlayer");
        Class<?> playerClass = cl.loadClass(playerClassName);
        for (Field f : mc.getClass().getDeclaredFields()) {
            if (playerClass.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f.get(mc);
            }
        }
        return null;
    }

    // --- Settings ---

    public void setRepairThreshold(int pct) { repairThreshold = pct; }
    public void setKeepSlots(int slots) { keepSlots = slots; }
    public int bedSlot() { return bedSlot; }
}
