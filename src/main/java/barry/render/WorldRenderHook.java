package barry.render;

import barry.core.OreType;
import barry.ui.Overlay;
import barry.util.BlockPos;
import barry.xray.XrayEngine;

import java.lang.reflect.Method;

// Draws 3D ore outlines in the world using 26.3's retained-mode submit pipeline.
// GuiTransformer injects a call to onWorldOutline at the head of
// LevelRenderer.submitBlockOutline(PoseStack, SubmitNodeCollector, LevelRenderState),
// which runs during world rendering and hands us a PoseStack (camera-relative)
// and a SubmitNodeCollector. For each nearby ore we translate the pose to the
// block and submit a full-cube VoxelShape outline via
// SubmitNodeCollector.submitShapeOutline(pose, shape, lines, color, width, depthTest).
public final class WorldRenderHook {

    private static volatile XrayEngine engine;
    private static volatile boolean ready;
    private static volatile boolean resolveFailed;

    // Cached handles
    private static Object blockShape;        // Shapes.block() -> VoxelShape (unit cube, scaled per entity)
    private static volatile Object linesRenderType;   // RenderType for line outlines
    private static volatile Object capturedLineType;  // grabbed from submitHitOutline
    private static Method mSubmitShapeOutline;
    private static Method mPush, mTranslate, mPop;
    private static volatile Method mShapesBox;     // Shapes.box(6 doubles) -> VoxelShape
    private static final java.util.Map<Integer, Object> entityShapeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static Object camera;            // Camera (re-fetched each frame)
    private static Method mMainCamera, mPosition;
    private static java.lang.reflect.Field vecX, vecY, vecZ;
    private static Object gameRenderer;
    private static volatile long lastRenderLog;

    private WorldRenderHook() {}

    public static Object capturedLineType() { return capturedLineType; }

    public static void init(XrayEngine eng) { engine = eng; }
    public static void shutdown() { engine = null; ready = false; resolveFailed = false; capturedLineType = null; }

    // Injected at submitHitOutline — captures the game's own line RenderType so
    // we can reuse it (no static factory for it on 26.x).
    public static void captureLineRenderType(Object renderType) {
        if (capturedLineType == null && renderType != null) {
            capturedLineType = renderType;
            System.err.println("[barry] captured line RenderType: " + renderType.getClass().getName());
        }
    }

    public static void onWorldOutline(Object pose, Object collector) {
        XrayEngine eng = engine;
        if (eng == null || !Overlay.isVisible()) return;
        if (resolveFailed) return;
        try {
            if (!ready && !resolve(pose, collector)) { resolveFailed = true; return; }

            // Resolve the line RenderType lazily (captured from the hit outline).
            if (linesRenderType == null) {
                linesRenderType = (capturedLineType != null) ? capturedLineType : null;
                if (linesRenderType == null) return; // wait until we capture one
                installSeeThrough(linesRenderType); // make outlines render through terrain (xray)
                System.err.println("[barry] world outlines active");
            }

            // Camera position (pose is camera-relative).
            Object cam = mMainCamera.invoke(gameRenderer);
            Object pv = mPosition.invoke(cam);
            double camX = vecX.getDouble(pv), camY = vecY.getDouble(pv), camZ = vecZ.getDouble(pv);

            BlockPos player = eng.playerPos();
            var near = eng.currentMap().oresNear(player, 64.0, 0.0f);
            int total = 0, submitted = 0, debrisInRange = 0;
            for (var e : near) {
                total++;
                BlockPos p = e.getKey();
                OreType ore = OreType.fromBlockName(e.getValue().blockName());
                if (ore == OreType.ANCIENT_DEBRIS) debrisInRange++;
                if (ore != null && !ore.isEnabled()) continue; // filtered out in the menu

                int color;
                if (barry.ui.NetheriteAutoMode.isBrightDebrisEsp()
                        && barry.ui.NetheriteAutoMode.isEnabled()
                        && ore == OreType.ANCIENT_DEBRIS) {
                    color = barry.ui.NetheriteAutoMode.debrisColor(); // bright gold when auto mode active
                } else {
                    color = ore != null ? ore.color() : 0xFFFFFFFF;
                }
                mPush.invoke(pose);
                mTranslate.invoke(pose, p.x() - camX, p.y() - camY, p.z() - camZ);
                // (pose, shape, renderType, colorARGB, lineWidth, depthTest)
                mSubmitShapeOutline.invoke(collector, pose, blockShape, linesRenderType, color, 2.5f, false);
                mPop.invoke(pose);
                submitted++;
            }
            // --- Entity / player ESP outlines ---
            int entityCount = 0;
            if (barry.esp.EntityTypes.enabledCount() > 0) {
                entityCount = renderEntities(pose, collector, camX, camY, camZ);
            }

            long now = System.currentTimeMillis();
            if (now - lastRenderLog > 2000) {
                lastRenderLog = now;
                System.err.println("[barry] render: inRange=" + total + " submitted=" + submitted
                        + " debrisInRange=" + debrisInRange
                        + " debrisEnabled=" + OreType.ANCIENT_DEBRIS.isEnabled()
                        + " entities=" + entityCount
                        + " enTypes=" + barry.esp.EntityTypes.enabledCount()
                        + " trk=" + barry.esp.EntityTracker.tracked().size()
                        + " map=" + eng.currentDimension());
            }
        } catch (Throwable t) {
            // Never crash the render thread.
            if (!resolveFailed) {
                System.err.println("[barry] world outline error: " + t);
                resolveFailed = true;
            }
        }
    }

    // Draw see-through outlines for every tracked entity. Uses a per-size
    // VoxelShape (same submit path as the ore outlines, which is known to work)
    // rather than scaling the unit cube.
    private static int renderEntities(Object pose, Object collector,
                                      double camX, double camY, double camZ) {
        try {
            if (linesRenderType == null) return 0;

            var ents = barry.esp.EntityTracker.tracked();
            int count = 0;
            for (var e : ents) {
                if (e.distance() > 256) continue;
                double w = e.width() > 0 ? e.width() : 0.6;
                double h = e.height() > 0 ? e.height() : 1.8;
                Object shape = entityShape(w, h);
                if (shape == null) continue;
                // Translate to the hitbox corner (feet, centered horizontally).
                mPush.invoke(pose);
                mTranslate.invoke(pose, e.x() - w / 2.0 - camX, e.y() - camY, e.z() - w / 2.0 - camZ);
                mSubmitShapeOutline.invoke(collector, pose, shape, linesRenderType, e.color(), 2.5f, false);
                mPop.invoke(pose);
                count++;
            }
            return count;
        } catch (Throwable t) {
            if (lastRenderLog == 0) System.err.println("[barry] renderEntities error: " + t);
            return 0;
        }
    }

    // Cached VoxelShape sized w × h × w (quantized to 0.05 to bound the cache).
    private static Object entityShape(double w, double h) {
        try {
            int key = (((int) Math.round(w * 20)) << 16) | ((int) Math.round(h * 20) & 0xFFFF);
            Object s = entityShapeCache.get(key);
            if (s != null) return s;
            if (mShapesBox == null) {
                ClassLoader cl = RenderHook.minecraftInstance().getClass().getClassLoader();
                Class<?> shapes = cl.loadClass("net.minecraft.world.phys.shapes.Shapes");
                mShapesBox = shapes.getMethod("box", double.class, double.class, double.class,
                        double.class, double.class, double.class);
            }
            s = mShapesBox.invoke(null, 0.0, 0.0, 0.0, w, h, w);
            entityShapeCache.put(key, s);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean resolve(Object pose, Object collector) {
        try {
            ClassLoader cl = RenderHook.minecraftInstance().getClass().getClassLoader();

            // Shapes.block() -> VoxelShape
            Class<?> shapes = cl.loadClass("net.minecraft.world.phys.shapes.Shapes");
            blockShape = shapes.getMethod("block").invoke(null);

            // Try a static lines() factory; if absent we capture one from the
            // game's hit-outline render instead (set lazily in onWorldOutline).
            try {
                Class<?> rtClass = cl.loadClass("net.minecraft.client.renderer.rendertype.RenderType");
                linesRenderType = findLinesRenderType(rtClass);
            } catch (Throwable ignored) {}

            // PoseStack push/translate/pop
            Class<?> poseClass = pose.getClass();
            mPush = poseClass.getMethod("pushPose");
            mPop = poseClass.getMethod("popPose");
            mTranslate = poseClass.getMethod("translate", double.class, double.class, double.class);

            // submitShapeOutline(PoseStack, VoxelShape, RenderType, int, float, boolean)
            for (Method m : collector.getClass().getMethods()) {
                if (m.getName().equals("submitShapeOutline") && m.getParameterCount() == 6) {
                    mSubmitShapeOutline = m; break;
                }
            }
            if (mSubmitShapeOutline == null) { System.err.println("[barry] no submitShapeOutline"); return false; }

            // Camera handles
            Object mc = RenderHook.minecraftInstance();
            gameRenderer = fieldByTypeName(mc, "GameRenderer");
            for (Method m : gameRenderer.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().equals("Camera")) { mMainCamera = m; break; }
            }
            Object cam = mMainCamera.invoke(gameRenderer);
            for (Method m : cam.getClass().getMethods()) {
                if (m.getName().equals("position") && m.getParameterCount() == 0) { mPosition = m; break; }
            }
            Object pv = mPosition.invoke(cam);
            vecX = pv.getClass().getField("x");
            vecY = pv.getClass().getField("y");
            vecZ = pv.getClass().getField("z");

            ready = true;
            System.err.println("[barry] WorldRenderHook ready (3D outlines via submitShapeOutline)");
            return true;
        } catch (Throwable t) {
            System.err.println("[barry] WorldRenderHook resolve failed: " + t);
            return false;
        }
    }

    // Make the outlines render through terrain (true xray). The compiled GL
    // pipeline is cached by the backend keyed on the RenderPipeline object, and
    // renderpearl exposes no way to evict or recompile it, so mutating the
    // existing pipeline's depth state has no effect. Instead we build a FRESH
    // RenderPipeline (a copy of the captured line pipeline, but with
    // DepthStencilState(ALWAYS_PASS, writeDepth=false) and a unique location) and
    // swap it into the RenderType's RenderSetup. A brand-new pipeline object is a
    // cache miss, so the backend compiles it fresh with depth test/write off.
    private static void installSeeThrough(Object renderType) {
        try {
            ClassLoader cl = renderType.getClass().getClassLoader();
            initUnsafe(cl);

            // RenderType.state (RenderSetup) -> pipeline field
            java.lang.reflect.Field stateF = renderType.getClass().getDeclaredField("state");
            stateF.setAccessible(true);
            Object renderSetup = stateF.get(renderType);

            java.lang.reflect.Field pipelineF = renderSetup.getClass().getDeclaredField("pipeline");
            pipelineF.setAccessible(true);
            Object pipeline = pipelineF.get(renderSetup);
            Class<?> rpClass = pipeline.getClass();

            // DepthStencilState(ALWAYS_PASS, false): no depth test, no depth write.
            Class<?> compareOp = cl.loadClass("com.mojang.renderpearl.api.pipeline.CompareOp");
            Object alwaysPass = Enum.valueOf((Class) compareOp, "ALWAYS_PASS");
            Class<?> dssClass = cl.loadClass("com.mojang.renderpearl.api.pipeline.DepthStencilState");
            Object noDepth = dssClass.getConstructor(compareOp, boolean.class).newInstance(alwaysPass, false);

            // The 12-arg RenderPipeline constructor (location, shaders, shaderDefines,
            // bindGroupLayouts, colorTargetStates[], depthStencilState, polygonMode,
            // cull, vertexFormatPerBuffer[], primitiveTopology, pushConstantSize, sortKey).
            java.lang.reflect.Constructor<?> ctor = null;
            for (var c : rpClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 12) { ctor = c; break; }
            }
            if (ctor == null) { System.err.println("[barry] no 12-arg RenderPipeline ctor"); return; }
            ctor.setAccessible(true);
            Class<?>[] pt = ctor.getParameterTypes();

            Object uniqueLoc = suffixedIdentifier(cl, field(rpClass, pipeline, "location"));

            Object[] args = new Object[12];
            args[0]  = uniqueLoc;
            args[1]  = field(rpClass, pipeline, "shaders");
            args[2]  = field(rpClass, pipeline, "shaderDefines");
            args[3]  = field(rpClass, pipeline, "bindGroupLayouts");
            args[4]  = toArray(field(rpClass, pipeline, "colorTargetStates"), pt[4].getComponentType());
            args[5]  = noDepth;
            args[6]  = field(rpClass, pipeline, "polygonMode");
            args[7]  = field(rpClass, pipeline, "cull");
            args[8]  = toArray(field(rpClass, pipeline, "vertexFormatPerBuffer"), pt[8].getComponentType());
            args[9]  = field(rpClass, pipeline, "primitiveTopology");
            args[10] = field(rpClass, pipeline, "pushConstantSize");
            args[11] = field(rpClass, pipeline, "sortKey");

            Object freshPipeline = ctor.newInstance(args);
            try { rpClass.getMethod("updateSortKeySeed").invoke(freshPipeline); } catch (Throwable ignored) {}

            // Clone the RenderSetup + RenderType so ONLY our outlines use the
            // see-through pipeline. Mutating the captured (shared) RenderSetup in
            // place would also make the vanilla block-highlight see-through, which
            // we don't want — the vanilla outline must stay normal.
            Object cloneSetup = allocateInstance(renderSetup.getClass());
            copyFields(renderSetup, cloneSetup);
            unsafeSet(cloneSetup, pipelineF, freshPipeline);

            Object cloneType = allocateInstance(renderType.getClass());
            copyFields(renderType, cloneType);
            unsafeSet(cloneType, stateF, cloneSetup);

            linesRenderType = cloneType; // our outlines draw with the see-through clone
            System.err.println("[barry] see-through RenderType installed (isolated clone, ALWAYS_PASS)");
        } catch (Throwable t) {
            System.err.println("[barry] installSeeThrough failed: " + t);
        }
    }

    // Copy every non-static instance field (whole hierarchy) from src to dst via
    // Unsafe, so final fields copy cleanly.
    private static void copyFields(Object src, Object dst) throws Exception {
        for (Class<?> c = src.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                long off = (long) mObjectFieldOffset.invoke(unsafe, f);
                Class<?> t = f.getType();
                if (!t.isPrimitive()) {
                    mPutObject.invoke(unsafe, dst, off, f.get(src));
                } else if (t == boolean.class) {
                    mPutBoolean.invoke(unsafe, dst, off, f.getBoolean(src));
                } else if (t == int.class) {
                    mPutInt.invoke(unsafe, dst, off, f.getInt(src));
                } else if (t == long.class) {
                    mPutLong.invoke(unsafe, dst, off, f.getLong(src));
                } else if (t == float.class) {
                    mPutFloat.invoke(unsafe, dst, off, f.getFloat(src));
                } else if (t == double.class) {
                    mPutDouble.invoke(unsafe, dst, off, f.getDouble(src));
                } else if (t == byte.class) {
                    mPutByte.invoke(unsafe, dst, off, f.getByte(src));
                } else if (t == short.class) {
                    mPutShort.invoke(unsafe, dst, off, f.getShort(src));
                } else if (t == char.class) {
                    mPutChar.invoke(unsafe, dst, off, f.getChar(src));
                }
            }
        }
    }

    private static Object allocateInstance(Class<?> c) throws Exception {
        return mAllocateInstance.invoke(unsafe, c);
    }

    private static Object field(Class<?> c, Object obj, String name) throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static Object toArray(Object listObj, Class<?> comp) {
        java.util.List<?> list = (java.util.List<?>) listObj;
        Object arr = java.lang.reflect.Array.newInstance(comp, list.size());
        for (int i = 0; i < list.size(); i++) java.lang.reflect.Array.set(arr, i, list.get(i));
        return arr;
    }

    // --- Unsafe (for allocating clones and writing possibly-final fields) ---
    private static Object unsafe;
    private static Method mObjectFieldOffset, mAllocateInstance;
    private static Method mPutObject, mPutBoolean, mPutInt, mPutLong, mPutFloat, mPutDouble, mPutByte, mPutShort, mPutChar;

    private static void initUnsafe(ClassLoader cl) throws Exception {
        if (unsafe != null) return;
        Class<?> u = cl.loadClass("sun.misc.Unsafe");
        java.lang.reflect.Field tu = u.getDeclaredField("theUnsafe");
        tu.setAccessible(true);
        unsafe = tu.get(null);
        mObjectFieldOffset = u.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
        mAllocateInstance = u.getMethod("allocateInstance", Class.class);
        mPutObject  = u.getMethod("putObject", Object.class, long.class, Object.class);
        mPutBoolean = u.getMethod("putBoolean", Object.class, long.class, boolean.class);
        mPutInt     = u.getMethod("putInt", Object.class, long.class, int.class);
        mPutLong    = u.getMethod("putLong", Object.class, long.class, long.class);
        mPutFloat   = u.getMethod("putFloat", Object.class, long.class, float.class);
        mPutDouble  = u.getMethod("putDouble", Object.class, long.class, double.class);
        mPutByte    = u.getMethod("putByte", Object.class, long.class, byte.class);
        mPutShort   = u.getMethod("putShort", Object.class, long.class, short.class);
        mPutChar    = u.getMethod("putChar", Object.class, long.class, char.class);
    }

    private static void unsafeSet(Object obj, java.lang.reflect.Field f, Object val) throws Exception {
        long off = (long) mObjectFieldOffset.invoke(unsafe, f);
        mPutObject.invoke(unsafe, obj, off, val);
    }

    private static Object suffixedIdentifier(ClassLoader cl, Object oldLoc) {
        try {
            Class<?> idClass = cl.loadClass("net.minecraft.resources.Identifier");
            String ns = (String) idClass.getMethod("getNamespace").invoke(oldLoc);
            String path = (String) idClass.getMethod("getPath").invoke(oldLoc);
            // Identifier.fromNamespaceAndPath(String, String) or of(String, String)
            try {
                return idClass.getMethod("fromNamespaceAndPath", String.class, String.class)
                        .invoke(null, ns, path + "_barry");
            } catch (NoSuchMethodException e) {
                return idClass.getMethod("of", String.class, String.class)
                        .invoke(null, ns, path + "_barry");
            }
        } catch (Throwable t) {
            System.err.println("[barry] suffixedIdentifier err: " + t);
            return null;
        }
    }

    private static Object findLinesRenderType(Class<?> rtClass) {
        try {
            for (Method m : rtClass.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getName().equals("lines")
                        && rtClass.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null);
                }
            }
            // fallback: any static no-arg returning RenderType with "line" in the name
            for (Method m : rtClass.getMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && m.getName().toLowerCase().contains("line")
                        && rtClass.isAssignableFrom(m.getReturnType())) {
                    return m.invoke(null);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object fieldByTypeName(Object obj, String typeContains) {
        for (var f : obj.getClass().getDeclaredFields()) {
            if (f.getType().getSimpleName().contains(typeContains)) {
                try { f.setAccessible(true); Object v = f.get(obj); if (v != null) return v; }
                catch (Throwable ignored) {}
            }
        }
        return null;
    }
}
