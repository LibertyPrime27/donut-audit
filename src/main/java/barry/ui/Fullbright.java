package barry.ui;

import barry.render.RenderHook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

// Client-side fullbright via gamma override — the standard approach (cf.
// GameBlaus Fullbright, Gamma Tweaks): force Minecraft's internal gamma value
// far past the vanilla 0..1 slider clamp so the lightmap renders everything at
// max brightness. Purely local (no packets), so it is invisible to servers.
// Applied each tick because vanilla rewrites the lightmap from this value.
public final class Fullbright {

    private static final double FULLBRIGHT_GAMMA = 1000.0;

    private static volatile boolean on;
    private static volatile boolean resolved;
    private static Object gammaOption;     // OptionInstance<Double>
    private static Field valueField;       // OptionInstance.value
    private static Double saved;            // user's original gamma

    private Fullbright() {}

    public static boolean isOn() { return on; }

    public static void toggle() { on = !on; apply(); }

    public static void set(boolean enable) { on = enable; apply(); }

    // Assert the override (or restore). Safe to call every tick.
    public static void apply() {
        try {
            if (!resolve()) return;
            Object cur = valueField.get(gammaOption);
            double curD = (cur instanceof Number n) ? n.doubleValue() : 0.0;
            if (on) {
                if (saved == null && curD < 100.0) saved = curD;
                if (curD < 100.0) valueField.set(gammaOption, FULLBRIGHT_GAMMA);
            } else if (saved != null) {
                valueField.set(gammaOption, saved);
                saved = null;
            }
        } catch (Throwable ignored) {}
    }

    private static boolean resolve() {
        if (resolved) return gammaOption != null && valueField != null;
        try {
            Object mc = RenderHook.minecraftInstance();
            if (mc == null) return false; // not ready yet; retry next tick

            Object options = null;
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (f.getType().getSimpleName().equals("Options")) {
                    f.setAccessible(true);
                    options = f.get(mc);
                    break;
                }
            }
            if (options == null) return false;

            for (Method m : options.getClass().getMethods()) {
                if (m.getName().equals("gamma") && m.getParameterCount() == 0) {
                    gammaOption = m.invoke(options);
                    break;
                }
            }
            if (gammaOption == null) return false;

            for (Field f : gammaOption.getClass().getDeclaredFields()) {
                if (f.getName().equals("value")) { f.setAccessible(true); valueField = f; break; }
            }
            if (valueField == null) {
                for (Field f : gammaOption.getClass().getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && (t == Object.class || t == Double.class || t == double.class)) {
                        f.setAccessible(true); valueField = f; break;
                    }
                }
            }
            resolved = true;
            System.err.println("[barry] Fullbright resolved: gammaOption=" + (gammaOption != null)
                    + " valueField=" + (valueField != null ? valueField.getName() : "null"));
            return gammaOption != null && valueField != null;
        } catch (Throwable t) {
            System.err.println("[barry] Fullbright resolve err: " + t);
            return false;
        }
    }
}
