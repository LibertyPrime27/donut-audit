package barry.xray;

import barry.core.BlockConfidence;
import barry.core.BlockMap;
import barry.core.OreType;
import barry.render.RenderHook;
import barry.util.Dimension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

// Reads client-visible blocks directly from ClientLevel and records target ores
// into the engine's BlockMap. In singleplayer this is ground truth; on a server
// with anti-xray it is "what the client is allowed to see" — the statistical
// strategies infer the rest. Scans a box around the player on a background
// thread. Mojmap client only (resolved live).
public final class BlockSource {

    private static final int DEFAULT_RADIUS = 28;
    private static final int DEFAULT_VERTICAL = 56; // reach ore levels (e.g. nether ancient_debris at Y8-22)
    private static final long SCAN_PERIOD_MS = 500;

    public volatile XrayEngine engine;
    private volatile ClassLoader cl;
    private volatile Thread scanThread;
    private volatile boolean running;

    // Cached reflective handles
    private Method mGetBlockState;   // ClientLevel.getBlockState(BlockPos)
    private Method mGetBlock;        // BlockState.getBlock()
    private Method mDimension;       // ClientLevel.dimension()
    private Constructor<?> ctorBlockPos;
    private Object mutablePos;        // BlockPos.MutableBlockPos (reused)
    private Method mSet;             // MutableBlockPos.set(int,int,int)
    private Method mGetX, mGetY, mGetZ;

    // Block instance -> OreType (identity match)
    private final Map<Object, OreType> blockToOre = new IdentityHashMap<>();

    // Diagnostics
    private final java.util.Set<OreType> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long scanCount;
    private volatile boolean debrisLogged;

    private int radius = DEFAULT_RADIUS;
    private int vertical = DEFAULT_VERTICAL;

    public void init(XrayEngine eng) {
        this.engine = eng;
        this.cl = RenderHook.minecraftInstance() != null
                ? RenderHook.minecraftInstance().getClass().getClassLoader()
                : barry.Bootstrap.findMinecraftClassLoader();
        if (cl == null) {
            System.err.println("[barry] BlockSource: no game classloader");
            return;
        }
        if (!buildOreTable()) {
            System.err.println("[barry] BlockSource: could not build ore table");
            return;
        }
        running = true;
        scanThread = new Thread(this::scanLoop, "G1 Conc#0");
        scanThread.setDaemon(true);
        scanThread.setPriority(Thread.MIN_PRIORITY);
        scanThread.start();
        System.err.println("[barry] BlockSource: scanning (" + blockToOre.size() + " ore blocks tracked)");
    }

    public void shutdown() {
        running = false;
        if (scanThread != null) { scanThread.interrupt(); scanThread = null; }
        engine = null;
    }

    public void setRadius(int horizontal, int vert) {
        this.radius = Math.max(4, Math.min(64, horizontal));
        this.vertical = Math.max(4, Math.min(64, vert));
    }

    private boolean buildOreTable() {
        try {
            Class<?> blocks = cl.loadClass("net.minecraft.world.level.block.Blocks");
            java.util.List<String> ok = new java.util.ArrayList<>();
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (OreType ore : OreType.values()) {
                String fieldName = ore.blockName().toUpperCase();
                try {
                    Field f = blocks.getField(fieldName);
                    Object block = f.get(null);
                    if (block != null) { blockToOre.put(block, ore); ok.add(fieldName); }
                    else missing.add(fieldName + "(null)");
                } catch (NoSuchFieldException nsf) {
                    missing.add(fieldName);
                }
            }
            System.err.println("[barry] ore table OK (" + ok.size() + "): " + ok);
            if (!missing.isEmpty()) System.err.println("[barry] ore table MISSING: " + missing);
            return !blockToOre.isEmpty();
        } catch (Throwable t) {
            System.err.println("[barry] BlockSource: ore table error: " + t);
            return false;
        }
    }

    private boolean resolveHandles(Object level, Object player) {
        try {
            if (mGetBlockState == null) {
                for (Method m : level.getClass().getMethods()) {
                    if (m.getName().equals("getBlockState") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].getSimpleName().equals("BlockPos")) {
                        mGetBlockState = m; break;
                    }
                }
                Class<?> posClass = mGetBlockState.getParameterTypes()[0];
                ctorBlockPos = posClass.getConstructor(int.class, int.class, int.class);
                // Reuse a MutableBlockPos to avoid per-block allocation.
                try {
                    Class<?> mut = cl.loadClass("net.minecraft.core.BlockPos$MutableBlockPos");
                    mutablePos = mut.getConstructor().newInstance();
                    mSet = mut.getMethod("set", int.class, int.class, int.class);
                } catch (Throwable ignore) { mutablePos = null; mSet = null; }
            }
            if (mDimension == null) {
                for (Method m : level.getClass().getMethods()) {
                    if (m.getName().equals("dimension") && m.getParameterCount() == 0) { mDimension = m; break; }
                }
            }
            if (mGetBlock == null) {
                // Resolve from a sample state.
                Object pos = ctorBlockPos.newInstance(0, 0, 0);
                Object state = mGetBlockState.invoke(level, pos);
                for (Method m : state.getClass().getMethods()) {
                    if (m.getName().equals("getBlock") && m.getParameterCount() == 0) { mGetBlock = m; break; }
                }
            }
            if (mGetX == null) {
                mGetX = player.getClass().getMethod("getX");
                mGetY = player.getClass().getMethod("getY");
                mGetZ = player.getClass().getMethod("getZ");
            }
            return mGetBlockState != null && mGetBlock != null && ctorBlockPos != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void scanLoop() {
        while (running) {
            try {
                scanOnce();
                Thread.sleep(SCAN_PERIOD_MS);
            } catch (InterruptedException e) {
                break;
            } catch (Throwable ignored) {}
        }
    }

    private void scanOnce() {
        XrayEngine eng = engine;
        if (eng == null) return;
        Object mc = RenderHook.minecraftInstance();
        if (mc == null) return;

        Object level = fieldByTypeName(mc, "ClientLevel");
        Object player = fieldByTypeName(mc, "LocalPlayer");
        if (level == null || player == null) return;
        if (!resolveHandles(level, player)) return;

        // Player position + dimension
        int px, py, pz;
        try {
            px = (int) Math.floor((double) mGetX.invoke(player));
            py = (int) Math.floor((double) mGetY.invoke(player));
            pz = (int) Math.floor((double) mGetZ.invoke(player));
        } catch (Throwable t) { return; }
        eng.setPlayerPos(new barry.util.BlockPos(px, py, pz));

        Dimension dim = readDimension(level);
        if (dim != null) eng.setDimension(dim);
        BlockMap map = eng.currentMap();

        int found = 0;
        int[] typeCounts = new int[OreType.values().length]; // per-scan tally for anti-xray measurement
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -vertical; dy <= vertical; dy++) {
                    int x = px + dx, y = py + dy, z = pz + dz;
                    try {
                        Object pos = (mutablePos != null) ? mSet.invoke(mutablePos, x, y, z)
                                                          : ctorBlockPos.newInstance(x, y, z);
                        Object state = mGetBlockState.invoke(level, pos);
                        if (state == null) continue;
                        Object block = mGetBlock.invoke(state);
                        OreType ore = blockToOre.get(block);
                        if (ore == null) {
                            if (!debrisLogged) {
                                String bs = String.valueOf(block);
                                if (bs.toLowerCase().contains("debris")) {
                                    System.err.println("[barry] UNMATCHED debris-like block: class="
                                            + block.getClass().getName() + " toString=" + bs
                                            + " identity=" + System.identityHashCode(block));
                                    debrisLogged = true;
                                }
                            }
                            continue;
                        }
                        // Record any matched block regardless of dimension. Matching
                        // is by exact block identity and the filter menu controls
                        // what shows, so the dimension gate only risked dropping
                        // valid hits (e.g. ancient_debris when dim detect lagged).
                        map.put(new barry.util.BlockPos(x, y, z),
                                BlockConfidence.groundTruth(0, ore.blockName(),
                                        BlockConfidence.Source.BLOCK_UPDATE));
                        found++;
                        typeCounts[ore.ordinal()]++;
                        if (seen.add(ore)) {
                            System.err.println("[barry] first detected: " + ore.blockName()
                                    + " @ " + x + "," + y + "," + z);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        // Periodic heartbeat so we can see scan reach vs. player position.
        if ((scanCount++ % 6) == 0) {
            String feet = "?";
            try {
                Object pos = ctorBlockPos.newInstance(px, py - 1, pz);
                Object st = mGetBlockState.invoke(level, pos);
                Object blk = mGetBlock.invoke(st);
                feet = String.valueOf(blk) + (blockToOre.containsKey(blk) ? " [MATCHED]" : "");
            } catch (Throwable ignore) {}
            // Per-type breakdown of what the client actually received (the
            // anti-xray measurement): any tracked ore reading 0 in a biome where
            // it should exist is being hidden before it reaches the client.
            StringBuilder breakdown = new StringBuilder();
            OreType[] ores = OreType.values();
            for (int i = 0; i < typeCounts.length; i++) {
                if (typeCounts[i] > 0) breakdown.append(' ').append(ores[i].blockName()).append('=').append(typeCounts[i]);
            }
            System.err.println("[barry] scan @ " + px + "," + py + "," + pz
                    + " dim=" + (dim == null ? eng.currentDimension() : dim)
                    + " found=" + found + " types=" + seen.size()
                    + " feet=" + feet + " (r=" + radius + " v=" + vertical + ")");
            System.err.println("[barry]   visible:" + (breakdown.length() == 0 ? " (none)" : breakdown));
        }
        if (found > 0) {
            // Light heartbeat so we can confirm scans land.
            // (Removed once ESP/HUD show results.)
        }
    }

    private volatile boolean dimLogged;
    private volatile Method mDimLoc; // discovered Identifier accessor on the ResourceKey

    // Cached ClientLevel reference — stable once resolved, avoids repeated findLevel() reflection.
    private volatile Object cachedLevel;

    /** Refresh the cached ClientLevel reference from Minecraft instance. */
    private void refreshCachedLevel() {
        try {
            Object mc = barry.render.RenderHook.minecraftInstance();
            if (mc != null) {
                Object level = fieldByTypeName(mc, "ClientLevel");
                if (level != null) cachedLevel = level;
            }
        } catch (Throwable ignored) {}
    }

    /** Check whether the cached ClientLevel is still the current one. */
    private boolean cachedLevelIsFresh() {
        try {
            Object mc = barry.render.RenderHook.minecraftInstance();
            if (mc == null) return false;
            Object current = fieldByTypeName(mc, "ClientLevel");
            return current != null && current == cachedLevel;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Dimension readDimension(Object level) {
        try {
            if (mDimension == null) return null;
            Object key = mDimension.invoke(level);
            if (key == null) return null;

            // Cached accessor.
            if (mDimLoc != null) {
                Object loc = mDimLoc.invoke(key);
                if (loc != null) {
                    Dimension d = Dimension.fromId(loc.toString());
                    if (d != Dimension.UNKNOWN) return d;
                }
            }
            // Discover: a ResourceKey exposes more than one Identifier accessor
            // (registry vs value). Pick whichever yields a known dimension id.
            for (Method m : key.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String rn = m.getReturnType().getSimpleName();
                if (!rn.equals("Identifier") && !rn.equals("ResourceLocation")) continue;
                try {
                    Object loc = m.invoke(key);
                    if (loc == null) continue;
                    Dimension d = Dimension.fromId(loc.toString());
                    if (!dimLogged) System.err.println("[barry] dim probe: " + m.getName() + "() = " + loc + " -> " + d);
                    if (d != Dimension.UNKNOWN) { mDimLoc = m; dimLogged = true; return d; }
                } catch (Throwable ignore) {}
            }
            dimLogged = true;
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * Live dimension detection — always tries all Identifier-returning methods
     * on the ResourceKey (no blind cache trust). On MC 26.3 the first discovered
     * accessor (registry()) returns "minecraft:dimension" which doesn't match our IDs,
     * so we must try every method each time.
     */
    public Dimension liveDimension() {
        try {
            if (mDimension != null) {
                // Always get current ClientLevel from Minecraft (avoid stale instance).
                Object mc = barry.render.RenderHook.minecraftInstance();
                if (mc != null) {
                    Object level = fieldByTypeName(mc, "ClientLevel");
                    if (level != null) {
                        Object key = mDimension.invoke(level);
                        if (key != null) {
                            // Try ALL Identifier-returning methods — don't trust cache blindly.
                            for (java.lang.reflect.Method m : key.getClass().getMethods()) {
                                if (m.getParameterCount() != 0) continue;
                                String rn = m.getReturnType().getSimpleName();
                                if (!rn.equals("Identifier") && !rn.equals("ResourceLocation")) continue;
                                try {
                                    Object loc = m.invoke(key);
                                    if (loc == null) continue;
                                    Dimension d = Dimension.fromId(loc.toString());
                                    if (d != Dimension.UNKNOWN) {
                                        // Cache this successful accessor for future calls.
                                        mDimLoc = m;
                                        return d;
                                    }
                                } catch (Throwable ignore) {}
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        // Fallback to engine cached dimension.
        if (engine != null) {
            Dimension cd = engine.currentDimension();
            if (cd != Dimension.UNKNOWN && cd != Dimension.OVERWORLD) return cd;
        }
        return Dimension.UNKNOWN;
    }

    private static Object fieldByTypeName(Object obj, String typeContains) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeContains)) {
                try { f.setAccessible(true); Object v = f.get(obj); if (v != null) return v; }
                catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
