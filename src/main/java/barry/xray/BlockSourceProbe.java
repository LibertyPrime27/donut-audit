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

        // 5. ESP projection API: matrices, camera, window.
        try {
            dumpEspApi(mc);
        } catch (Throwable t) {
            System.err.println("[barry] probe: esp api failed: " + t);
        }

        // 6. World render API: LevelRenderer hook point + line-box helper.
        try {
            dumpWorldRenderApi(mc);
        } catch (Throwable t) {
            System.err.println("[barry] probe: world render api failed: " + t);
        }

        System.err.println("[barry] ===== END PROBE =====");
    }

    private static void dumpWorldRenderApi(Object mc) {
        ClassLoader cl = mc.getClass().getClassLoader();
        Object lr = findFieldValueByTypeName(mc, "LevelRenderer");
        System.err.println("[barry] levelRenderer = " + typeOf(lr));
        if (lr != null) {
            System.err.println("[barry] --- LevelRenderer methods taking SubmitNodeCollector ---");
            for (Method m : lr.getClass().getDeclaredMethods()) {
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt.getSimpleName().contains("SubmitNodeCollector")
                            || pt.getSimpleName().contains("Collector")) {
                        System.err.println("[barry]   " + sig(m));
                        break;
                    }
                }
            }
            System.err.println("[barry] --- all LevelRenderer methods (>=2 params) ---");
            for (Method m : lr.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() >= 2 && !m.getName().startsWith("lambda$")) {
                    System.err.println("[barry]   " + sig(m));
                }
            }
            System.err.println("[barry] --- LevelRenderer static helpers (lineBox/shape) ---");
            for (Method m : lr.getClass().getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                String n = m.getName().toLowerCase();
                if (n.contains("line") || n.contains("shape") || n.contains("box")) {
                    System.err.println("[barry]   static " + sig(m));
                }
            }
        }
        // Rendering toolkit availability on this version.
        for (String fqcn : new String[]{
                "com.mojang.blaze3d.vertex.Tesselator",
                "com.mojang.blaze3d.vertex.PoseStack",
                "net.minecraft.client.renderer.RenderType",
                "net.minecraft.client.renderer.MultiBufferSource",
                "com.mojang.blaze3d.vertex.VertexConsumer",
                "net.minecraft.world.phys.AABB"}) {
            try { cl.loadClass(fqcn); System.err.println("[barry]   present: " + fqcn); }
            catch (Throwable t) { System.err.println("[barry]   MISSING: " + fqcn); }
        }
        // Deep-dump the submit API from submitHitOutline / submitBlockOutline params.
        if (lr != null) {
            for (Method m : lr.getClass().getDeclaredMethods()) {
                if (m.getName().equals("submitHitOutline") || m.getName().equals("submitBlockOutline")) {
                    System.err.println("[barry] >>> " + sig(m));
                    for (Class<?> pt : m.getParameterTypes()) {
                        dumpClassApi(pt, "line|box|outline|shape|submit|custom|order");
                    }
                }
            }
        }
    }

    private static void dumpClassApi(Class<?> c, String nameFilterRegex) {
        if (c == null || c.isPrimitive() || c.getName().startsWith("java.")
                || c.getName().startsWith("com.mojang.blaze3d.vertex.PoseStack")) return;
        System.err.println("[barry]   === " + c.getName() + " ===");
        try {
            for (var ctor : c.getConstructors()) {
                StringBuilder sb = new StringBuilder("ctor(");
                Class<?>[] p = ctor.getParameterTypes();
                for (int i = 0; i < p.length; i++) { if (i>0) sb.append(", "); sb.append(p[i].getSimpleName()); }
                System.err.println("[barry]     " + sb.append(")"));
            }
            int shown = 0;
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                String n = m.getName().toLowerCase();
                if (n.matches(".*(" + nameFilterRegex + ").*")) {
                    System.err.println("[barry]     " + sig(m));
                    if (++shown > 30) break;
                }
            }
        } catch (Throwable t) {
            System.err.println("[barry]     (dump err: " + t + ")");
        }
    }

    private static void dumpEspApi(Object mc) {
        System.err.println("[barry] --- ESP: window ---");
        Object window = findFieldValueByTypeName(mc, "Window");
        if (window != null) {
            for (Method m : window.getClass().getMethods()) {
                String n = m.getName();
                if (m.getParameterCount() == 0 && (n.contains("uiScaled") || n.contains("Width")
                        || n.contains("Height") || n.contains("Scale"))) {
                    System.err.println("[barry]   window." + sig(m));
                }
            }
        }

        System.err.println("[barry] --- ESP: gameRenderer matrix/camera methods ---");
        Object gr = findFieldValueByTypeName(mc, "GameRenderer");
        if (gr != null) {
            for (Method m : gr.getClass().getMethods()) {
                String rt = m.getReturnType().getSimpleName();
                String n = m.getName();
                if (rt.contains("Matrix") || rt.contains("Camera") || n.toLowerCase().contains("fov")
                        || n.toLowerCase().contains("projection")) {
                    System.err.println("[barry]   gameRenderer." + sig(m));
                }
            }
            // camera + its rotation/position api
            Object cam = invokeNoArgReturningTypeContains(gr, "Camera");
            System.err.println("[barry]   camera = " + typeOf(cam));
            if (cam != null) {
                for (Method m : cam.getClass().getMethods()) {
                    String n = m.getName();
                    if (m.getParameterCount() == 0 && (n.contains("Rot") || n.contains("osition")
                            || n.contains("Quaternion") || n.contains("rotation") || n.contains("Direction"))) {
                        System.err.println("[barry]     camera." + sig(m));
                    }
                }
            }
        }

        System.err.println("[barry] --- ESP: RenderSystem static matrix methods ---");
        try {
            Class<?> rs = mc.getClass().getClassLoader().loadClass("com.mojang.blaze3d.systems.RenderSystem");
            for (Method m : rs.getMethods()) {
                String rt = m.getReturnType().getSimpleName();
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && (rt.contains("Matrix") || m.getName().toLowerCase().contains("projection")
                            || m.getName().toLowerCase().contains("modelview"))) {
                    System.err.println("[barry]   RenderSystem." + sig(m));
                }
            }
        } catch (Throwable t) {
            System.err.println("[barry]   RenderSystem not found: " + t);
        }
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
