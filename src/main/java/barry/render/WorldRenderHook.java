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
    private static Object blockShape;        // Shapes.block() -> VoxelShape
    private static volatile Object linesRenderType;   // RenderType for line outlines
    private static volatile Object capturedLineType;  // grabbed from submitHitOutline
    private static Method mSubmitShapeOutline;
    private static Method mPush, mTranslate, mPop;
    private static Object camera;            // Camera (re-fetched each frame)
    private static Method mMainCamera, mPosition;
    private static java.lang.reflect.Field vecX, vecY, vecZ;
    private static Object gameRenderer;

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
            for (var e : near) {
                BlockPos p = e.getKey();
                OreType ore = OreType.fromBlockName(e.getValue().blockName());
                int color = ore != null ? ore.color() : 0xFFFFFFFF;
                mPush.invoke(pose);
                mTranslate.invoke(pose, p.x() - camX, p.y() - camY, p.z() - camZ);
                // (pose, shape, renderType, colorARGB, lineWidth, depthTest)
                mSubmitShapeOutline.invoke(collector, pose, blockShape, linesRenderType, color, 2.5f, false);
                mPop.invoke(pose);
            }
        } catch (Throwable t) {
            // Never crash the render thread.
            if (!resolveFailed) {
                System.err.println("[barry] world outline error: " + t);
                resolveFailed = true;
            }
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

            // Swap it in (Unsafe handles the possibly-final field).
            unsafeSet(renderSetup, pipelineF, freshPipeline);

            System.err.println("[barry] see-through pipeline installed (fresh ALWAYS_PASS, no depth write)");
        } catch (Throwable t) {
            System.err.println("[barry] installSeeThrough failed: " + t);
        }
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

    // --- Unsafe (for swapping a possibly-final field) ---
    private static Object unsafe;
    private static Method mObjectFieldOffset, mPutObject;

    private static void initUnsafe(ClassLoader cl) throws Exception {
        if (unsafe != null) return;
        Class<?> u = cl.loadClass("sun.misc.Unsafe");
        java.lang.reflect.Field tu = u.getDeclaredField("theUnsafe");
        tu.setAccessible(true);
        unsafe = tu.get(null);
        mObjectFieldOffset = u.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
        mPutObject = u.getMethod("putObject", Object.class, long.class, Object.class);
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
