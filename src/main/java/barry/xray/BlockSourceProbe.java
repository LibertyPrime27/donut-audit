package barry.xray;

import barry.render.RenderHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// One-shot reflective explorer for the client's block-read path on this MC
// version. Dumps class names and candidate method/field signatures from
// Minecraft -> level -> chunk source -> a loaded chunk -> sections -> block
// states, so the real block source can be written against the actual API.
// Triggered on demand (bound to a key) because it is verbose and only needed
// while figuring out a new version.
public final class BlockSourceProbe {

    private static volatile boolean done;

    private BlockSourceProbe() {}

    public static void dumpOnce() {
        if (done) return;
        done = true;
        try {
            dump();
        } catch (Throwable t) {
            System.err.println("[barry] probe error: " + t);
        }
    }

    private static void dump() {
        System.err.println("[barry] ===== BLOCK SOURCE PROBE =====");
        Object mc = RenderHook.minecraftInstance();
        if (mc == null) { System.err.println("[barry] probe: no mc"); return; }

        // 1. level field on Minecraft (ClientLevel)
        Object level = findFieldValueByTypeName(mc, "ClientLevel");
        System.err.println("[barry] level = " + typeOf(level));
        if (level == null) {
            System.err.println("[barry] probe: fields on mc with 'Level'/'Client' in type:");
            for (Field f : mc.getClass().getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                if (tn.contains("Level") || tn.contains("Client") || tn.contains("World")) {
                    System.err.println("[barry]   " + f.getName() + " : " + f.getType().getName());
                }
            }
            return;
        }

        // 2. chunk source: level.getChunkSource() or a field
        System.err.println("[barry] --- level methods (chunk/getChunk/dimension) ---");
        for (Method m : level.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if ((n.contains("chunk") || n.contains("dimension") || n.equals("getblockstate"))
                    && m.getParameterCount() <= 3) {
                System.err.println("[barry]   " + sig(m));
            }
        }

        Object chunkSource = invokeNoArgReturningTypeContains(level, "ChunkSource");
        if (chunkSource == null) chunkSource = findFieldValueByTypeName(level, "ChunkCache");
        if (chunkSource == null) chunkSource = findFieldValueByTypeName(level, "ChunkSource");
        System.err.println("[barry] chunkSource = " + typeOf(chunkSource));

        // 3. getBlockState(BlockPos) directly on level — simplest read path
        System.err.println("[barry] --- level.getBlockState candidates ---");
        for (Method m : level.getClass().getMethods()) {
            if (m.getName().equals("getBlockState") && m.getParameterCount() == 1) {
                System.err.println("[barry]   " + sig(m) + "  param=" + m.getParameterTypes()[0].getName());
            }
        }

        if (chunkSource != null) {
            System.err.println("[barry] --- chunkSource methods (chunk/getChunk) ---");
            for (Method m : chunkSource.getClass().getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("chunk") && m.getParameterCount() <= 4) {
                    System.err.println("[barry]   " + sig(m));
                }
            }
            System.err.println("[barry] --- chunkSource fields (storage/array/map) ---");
            for (Field f : chunkSource.getClass().getDeclaredFields()) {
                String tn = f.getType().getSimpleName();
                System.err.println("[barry]   " + f.getName() + " : " + tn);
            }
        }

        // 4. BlockState -> Block -> registry name path, via a sample read at player feet.
        try {
            Object player = findFieldValueByTypeName(mc, "LocalPlayer");
            if (player != null) {
                double[] p = pos(player);
                if (p != null) {
                    dumpBlockStatePath(level, (int) p[0], (int) p[1] - 1, (int) p[2]);
                }
            }
        } catch (Throwable t) {
            System.err.println("[barry] probe: block-state path failed: " + t);
        }

        System.err.println("[barry] ===== END PROBE =====");
    }

    private static void dumpBlockStatePath(Object level, int x, int y, int z) throws Exception {
        // Build a BlockPos and call level.getBlockState(pos)
        Method getBlockState = null;
        for (Method m : level.getClass().getMethods()) {
            if (m.getName().equals("getBlockState") && m.getParameterCount() == 1) { getBlockState = m; break; }
        }
        if (getBlockState == null) { System.err.println("[barry] no getBlockState(pos)"); return; }

        Class<?> posClass = getBlockState.getParameterTypes()[0];
        Object pos = posClass.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
        Object state = getBlockState.invoke(level, pos);
        System.err.println("[barry] sample blockstate @ (" + x + "," + y + "," + z + ") = " + typeOf(state));

        if (state == null) return;
        System.err.println("[barry] --- BlockState methods (getBlock/isAir/is) ---");
        for (Method m : state.getClass().getMethods()) {
            String n = m.getName();
            if ((n.equals("getBlock") || n.equals("isAir") || n.startsWith("is")) && m.getParameterCount() == 0) {
                System.err.println("[barry]   " + sig(m));
            }
        }
        // getBlock() -> Block, then try to get registry name
        Object block = null;
        for (Method m : state.getClass().getMethods()) {
            if (m.getName().equals("getBlock") && m.getParameterCount() == 0) {
                block = m.invoke(state); break;
            }
        }
        System.err.println("[barry] block = " + typeOf(block));
        if (block != null) {
            System.err.println("[barry] block.toString() = " + block);
        }
    }

    // --- helpers ---

    private static Object findFieldValueByTypeName(Object obj, String typeContains) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeContains)) {
                try { f.setAccessible(true); Object v = f.get(obj); if (v != null) return v; }
                catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object invokeNoArgReturningTypeContains(Object obj, String retContains) {
        for (Method m : obj.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains(retContains)) {
                try { m.setAccessible(true); return m.invoke(obj); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static double[] pos(Object entity) {
        try {
            double x = (double) entity.getClass().getMethod("getX").invoke(entity);
            double y = (double) entity.getClass().getMethod("getY").invoke(entity);
            double z = (double) entity.getClass().getMethod("getZ").invoke(entity);
            return new double[]{x, y, z};
        } catch (Throwable ignored) { return null; }
    }

    private static String typeOf(Object o) { return o == null ? "null" : o.getClass().getName(); }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getReturnType().getSimpleName()).append(" ").append(m.getName()).append("(");
        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) { if (i > 0) sb.append(", "); sb.append(p[i].getSimpleName()); }
        return sb.append(")").toString();
    }
}
