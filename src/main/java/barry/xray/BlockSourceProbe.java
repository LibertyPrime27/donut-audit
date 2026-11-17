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

    // Everything needed to implement see-through outlines in one pass:
    //  (A) device.backend concrete class -> the compiled-pipeline cache map (evict path)
    //  (B) captured RenderType concrete class -> the RenderPipeline/Supplier field (clone path)
    //  (C) RenderPipeline constructors + builder (how to make a fresh ALWAYS_PASS pipeline)
    private static void dumpSeeThroughApi(ClassLoader cl) {
        System.err.println("[barry] ===== SEE-THROUGH API =====");

        // (A) backend concrete class
        try {
            Class<?> rs = cl.loadClass("com.mojang.blaze3d.systems.RenderSystem");
            Object device = null;
            for (Method m : rs.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().contains("GpuDevice")) { device = m.invoke(null); break; }
            }
            if (device != null) {
                Field bf = device.getClass().getDeclaredField("backend");
                bf.setAccessible(true);
                Object backend = bf.get(device);
                if (backend != null) {
                    Class<?> bc = backend.getClass();
                    System.err.println("[barry] BACKEND = " + bc.getName());
                    System.err.println("[barry] --- backend fields (Maps flagged with size) ---");
                    for (Field f : bc.getDeclaredFields()) {
                        String line = f.getName() + " : " + f.getType().getName();
                        try {
                            f.setAccessible(true);
                            Object v = f.get(backend);
                            if (v instanceof java.util.Map<?,?> mp) {
                                line += "  (Map size=" + mp.size() + ")";
                                // peek one key's type
                                for (Object k : mp.keySet()) { line += " keyType=" + (k==null?"null":k.getClass().getName()); break; }
                            }
                        } catch (Throwable ignored) {}
                        System.err.println("[barry]   " + line);
                    }
                    System.err.println("[barry] --- backend methods (compile/cache/pipeline/get/remove/clear) ---");
                    for (Method m : bc.getMethods()) {
                        String n = m.getName().toLowerCase();
                        if (n.contains("compile") || n.contains("cache") || n.contains("pipeline")
                                || n.contains("remove") || n.contains("clear") || n.contains("invalidate")) {
                            System.err.println("[barry]   " + sig(m));
                        }
                    }
                    // Deep-dump the recompiler: this manages compiled GL pipelines.
                    try {
                        Field rf = bc.getDeclaredField("recompiler");
                        rf.setAccessible(true);
                        Object rec = rf.get(backend);
                        if (rec != null) {
                            Class<?> rc = rec.getClass();
                            System.err.println("[barry] RECOMPILER = " + rc.getName());
                            System.err.println("[barry] --- recompiler fields (Maps flagged) ---");
                            for (Field f : rc.getDeclaredFields()) {
                                String line = f.getName() + " : " + f.getType().getName();
                                try {
                                    f.setAccessible(true);
                                    Object v = f.get(rec);
                                    if (v instanceof java.util.Map<?,?> mp) {
                                        line += "  (Map size=" + mp.size() + ")";
                                        for (Object k : mp.keySet()) { line += " keyType=" + (k==null?"null":k.getClass().getName()); break; }
                                    } else if (v instanceof java.util.Collection<?> col) {
                                        line += "  (Collection size=" + col.size() + ")";
                                    }
                                } catch (Throwable ignored) {}
                                System.err.println("[barry]   " + line);
                            }
                            System.err.println("[barry] --- recompiler methods (get/compile/remove/clear/invalidate/pipeline) ---");
                            for (Method m : rc.getMethods()) {
                                String n = m.getName().toLowerCase();
                                if (n.contains("compile") || n.contains("get") || n.contains("remove")
                                        || n.contains("clear") || n.contains("invalidate") || n.contains("pipeline")) {
                                    System.err.println("[barry]   " + sig(m));
                                }
                            }
                        }
                    } catch (Throwable t) { System.err.println("[barry] recompiler dump err: " + t); }
                }
            }
        } catch (Throwable t) { System.err.println("[barry] backend dump err: " + t); }

        // (B) captured RenderType concrete class: find the RenderPipeline (or Supplier) field
        Object rt = barry.render.WorldRenderHook.capturedLineType();
        if (rt != null) {
            System.err.println("[barry] RENDERTYPE = " + rt.getClass().getName());
            System.err.println("[barry] --- RenderType declared fields (self + supers) ---");
            Object renderSetup = null;
            for (Class<?> c = rt.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                System.err.println("[barry]   class " + c.getName());
                for (Field f : c.getDeclaredFields()) {
                    String line = f.getName() + " : " + f.getType().getName();
                    try {
                        f.setAccessible(true);
                        Object v = f.get(rt);
                        if (v != null) line += "  val=" + v.getClass().getName();
                        if (v != null && f.getType().getSimpleName().equals("RenderSetup")) renderSetup = v;
                    } catch (Throwable ignored) {}
                    System.err.println("[barry]     " + line);
                }
            }
            // RenderSetup holds the RenderPipeline — dump it so we can swap/clone.
            if (renderSetup != null) {
                System.err.println("[barry] RENDERSETUP = " + renderSetup.getClass().getName()
                        + " isRecord=" + renderSetup.getClass().isRecord());
                System.err.println("[barry] --- RenderSetup fields ---");
                for (Field f : renderSetup.getClass().getDeclaredFields()) {
                    String line = f.getName() + " : " + f.getType().getName();
                    try { f.setAccessible(true); Object v = f.get(renderSetup); if (v != null) line += "  val=" + v.getClass().getName(); }
                    catch (Throwable ignored) {}
                    System.err.println("[barry]     " + line);
                }
                if (renderSetup.getClass().isRecord()) {
                    System.err.println("[barry] --- RenderSetup record components ---");
                    for (var rcmp : renderSetup.getClass().getRecordComponents())
                        System.err.println("[barry]     " + rcmp.getName() + " : " + rcmp.getType().getName());
                    System.err.println("[barry] --- RenderSetup constructors ---");
                    for (var ctor : renderSetup.getClass().getDeclaredConstructors()) {
                        StringBuilder sb = new StringBuilder("ctor(");
                        Class<?>[] p = ctor.getParameterTypes();
                        for (int i = 0; i < p.length; i++) { if (i>0) sb.append(", "); sb.append(p[i].getSimpleName()); }
                        System.err.println("[barry]     " + sb.append(")"));
                    }
                }
            }
        }

        // (C) RenderPipeline constructors + static builder methods
        try {
            Class<?> rp = cl.loadClass("com.mojang.renderpearl.api.pipeline.RenderPipeline");
            System.err.println("[barry] --- RenderPipeline constructors ---");
            for (var ctor : rp.getDeclaredConstructors()) {
                StringBuilder sb = new StringBuilder("ctor(");
                Class<?>[] p = ctor.getParameterTypes();
                for (int i = 0; i < p.length; i++) { if (i>0) sb.append(", "); sb.append(p[i].getSimpleName()); }
                System.err.println("[barry]     " + sb.append(")"));
            }
            System.err.println("[barry] --- RenderPipeline static/builder methods ---");
            for (Method m : rp.getMethods()) {
                String n = m.getName().toLowerCase();
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        || n.contains("builder") || n.contains("tobuilder") || n.contains("copy")) {
                    System.err.println("[barry]   " + sig(m));
                }
            }
        } catch (Throwable t) { System.err.println("[barry] renderpipeline ctor dump err: " + t); }

        System.err.println("[barry] ===== END SEE-THROUGH API =====");
    }

    // Discover MC 26.3's keyboard input path so we can hook it and swallow keys
    // while our menu is open (type-to-search + Enter-to-enable).
    private static void dumpInputApi(Object mc) {
        System.err.println("[barry] ===== INPUT API =====");
        Object kh = findFieldValueByTypeName(mc, "KeyboardHandler");
        System.err.println("[barry] keyboardHandler = " + typeOf(kh));
        if (kh != null) {
            System.err.println("[barry] --- KeyboardHandler declared methods ---");
            for (Method m : kh.getClass().getDeclaredMethods()) {
                if (m.getName().startsWith("lambda$")) continue;
                System.err.println("[barry]   " + sig(m));
            }
            System.err.println("[barry] --- KeyboardHandler fields ---");
            for (Field f : kh.getClass().getDeclaredFields()) {
                System.err.println("[barry]   " + f.getName() + " : " + f.getType().getName());
            }
        }
        Object mh = findFieldValueByTypeName(mc, "MouseHandler");
        System.err.println("[barry] mouseHandler = " + typeOf(mh));
        // How is the current screen tracked? (so we can detect/avoid GUI states)
        System.err.println("[barry] --- Minecraft screen methods ---");
        for (Method m : mc.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if ((n.contains("screen") || n.equals("setscreen")) && m.getParameterCount() <= 1) {
                System.err.println("[barry]   " + sig(m));
            }
        }
        // Screen field on mc (null in-world).
        for (Field f : mc.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().equals("Screen")) {
                System.err.println("[barry]   screen field: " + f.getName() + " : " + f.getType().getName());
            }
        }
        // The SDL event dispatcher: any loaded class that references SDL keyboard events.
        System.err.println("[barry] --- candidate SDL input dispatchers (loaded) ---");
        try {
            var inst = barry.Bootstrap.instrumentation();
            if (inst != null) {
                int shown = 0;
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    String n = c.getName();
                    if ((n.startsWith("net.minecraft") || n.startsWith("com.mojang"))
                            && (n.toLowerCase().contains("keyboard") || n.toLowerCase().contains("input"))) {
                        System.err.println("[barry]   " + n);
                        if (++shown > 20) break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        System.err.println("[barry] ===== END INPUT API =====");
    }

    private static void dumpDeviceApi(ClassLoader cl) {
        try {
            Class<?> rs = cl.loadClass("com.mojang.blaze3d.systems.RenderSystem");
            Object device = null;
            for (Method m : rs.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType().getSimpleName().contains("GpuDevice")) {
                    device = m.invoke(null); break;
                }
            }
            if (device == null) { System.err.println("[barry] DEVICE: no getDevice"); return; }
            Class<?> dc = device.getClass();
            System.err.println("[barry] DEVICE: " + dc.getName());
            System.err.println("[barry] --- device methods (pipeline/cache/clear/compile/precompile) ---");
            for (Method m : dc.getMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("pipeline") || n.contains("cache") || n.contains("clear")
                        || n.contains("compile") || n.contains("preparepipeline")) {
                    System.err.println("[barry]   " + sig(m));
                }
            }
            System.err.println("[barry] --- ALL device declared fields ---");
            for (Field f : dc.getDeclaredFields()) {
                System.err.println("[barry]   " + f.getName() + " : " + f.getType().getName());
            }
            // Deep-dump pipelineBuilder (holds the compiled-pipeline cache).
            Field pbF = dc.getDeclaredField("pipelineBuilder");
            pbF.setAccessible(true);
            Object pb = pbF.get(device);
            if (pb != null) {
                Class<?> pbc = pb.getClass();
                System.err.println("[barry] === PipelineBuilder " + pbc.getName() + " ===");
                System.err.println("[barry] --- PipelineBuilder fields ---");
                for (Field f : pbc.getDeclaredFields()) {
                    String line = f.getName() + " : " + f.getType().getName();
                    try {
                        f.setAccessible(true);
                        Object v = f.get(pb);
                        if (v instanceof java.util.Map<?,?> m) line += "  (Map size=" + m.size() + ")";
                    } catch (Throwable ignored) {}
                    System.err.println("[barry]   " + line);
                }
                System.err.println("[barry] --- PipelineBuilder methods (get/compile/pipeline/clear/remove) ---");
                for (Method m : pbc.getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("pipeline") || n.contains("compile") || n.contains("get")
                            || n.contains("clear") || n.contains("remove") || n.contains("invalidate")) {
                        System.err.println("[barry]   " + sig(m));
                    }
                }
            }
        } catch (Throwable t) { System.err.println("[barry] device dump err: " + t); }
    }

    private static void dumpDepthApi() {
        Object rt = barry.render.WorldRenderHook.capturedLineType();
        if (rt == null) { System.err.println("[barry] DEPTH: no captured RenderType (look at a block, then HOME)"); return; }
        try {
            Object pipe = rt.getClass().getMethod("pipeline").invoke(rt);
            Class<?> pc = pipe.getClass();
            System.err.println("[barry] DEPTH: pipeline = " + pc.getName());
            System.err.println("[barry] --- RenderPipeline declared fields ---");
            for (Field f : pc.getDeclaredFields()) {
                System.err.println("[barry]   " + f.getName() + " : " + f.getType().getSimpleName()
                        + (java.lang.reflect.Modifier.isFinal(f.getModifiers()) ? " (final)" : ""));
            }
            Object dss = pc.getMethod("getDepthStencilState").invoke(pipe);
            System.err.println("[barry] DEPTH: depthStencilState = " + (dss == null ? "null" : dss.getClass().getName()));
            if (dss != null) {
                Class<?> dc = dss.getClass();
                System.err.println("[barry] DEPTH: isRecord=" + dc.isRecord());
                if (dc.isRecord()) {
                    for (var rc : dc.getRecordComponents()) {
                        Object val = null;
                        try { val = rc.getAccessor().invoke(dss); } catch (Throwable ignored) {}
                        System.err.println("[barry]   comp " + rc.getName() + " : " + rc.getType().getSimpleName() + " = " + val);
                    }
                }
                System.err.println("[barry] --- DepthStencilState declared fields ---");
                for (Field f : dc.getDeclaredFields()) {
                    System.err.println("[barry]   " + f.getName() + " : " + f.getType().getSimpleName()
                            + (java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? " (static)" : "")
                            + (java.lang.reflect.Modifier.isFinal(f.getModifiers()) ? " (final)" : ""));
                }
                System.err.println("[barry] --- DepthStencilState constructors ---");
                for (var ctor : dc.getConstructors()) {
                    StringBuilder sb = new StringBuilder("ctor(");
                    Class<?>[] p = ctor.getParameterTypes();
                    for (int i = 0; i < p.length; i++) { if (i>0) sb.append(", "); sb.append(p[i].getSimpleName()); }
                    System.err.println("[barry]   " + sb.append(")"));
                }
            }
            // DepthTestFunction-like enum: find a field/comp whose type name has "Depth"/"Function"/"Compare"
            System.err.println("[barry] --- depth-function enum candidates ---");
            if (dss != null) {
                for (Field f : dss.getClass().getDeclaredFields()) {
                    String tn = f.getType().getSimpleName();
                    if (f.getType().isEnum() || tn.contains("Function") || tn.contains("Compare") || tn.contains("Depth")) {
                        Class<?> et = f.getType();
                        System.err.println("[barry]   field " + f.getName() + " type " + et.getName() + (et.isEnum() ? " [ENUM]" : ""));
                        if (et.isEnum()) for (Object c : et.getEnumConstants()) System.err.println("[barry]     const " + c);
                    }
                }
            }
        } catch (Throwable t) { System.err.println("[barry] depth dump err: " + t); }
    }

    private static void dump() {
        System.err.println("[barry] ===== BLOCK SOURCE PROBE =====");
        Object mc = RenderHook.minecraftInstance();
        if (mc == null) { System.err.println("[barry] probe: no mc"); return; }

        try { dumpInputApi(mc); } catch (Throwable t) { System.err.println("[barry] input probe err: " + t); }
        try { dumpSeeThroughApi(mc.getClass().getClassLoader()); } catch (Throwable t) { System.err.println("[barry] seethrough probe err: " + t); }
        try { dumpDepthApi(); } catch (Throwable t) { System.err.println("[barry] depth probe err: " + t); }
        try { dumpDeviceApi(mc.getClass().getClassLoader()); } catch (Throwable t) { System.err.println("[barry] device probe err: " + t); }

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
