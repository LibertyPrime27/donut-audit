package barry.attach;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AgentLoader {

    private static Instrumentation instrumentation;
    private static final List<ClassFileTransformer> TRANSFORMERS = new CopyOnWriteArrayList<>();

    private AgentLoader() {}

    public static void init(Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    public static void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
        if (instrumentation == null) return;
        instrumentation.addTransformer(transformer, canRetransform);
        TRANSFORMERS.add(transformer);
    }

    public static void removeTransformer(ClassFileTransformer transformer) {
        if (instrumentation == null) return;
        instrumentation.removeTransformer(transformer);
        TRANSFORMERS.remove(transformer);
    }

    public static void retransform(Class<?>... classes) throws Exception {
        if (instrumentation == null) return;
        instrumentation.retransformClasses(classes);
    }

    public static void removeAllTransformers() {
        if (instrumentation == null) return;
        for (ClassFileTransformer t : TRANSFORMERS) {
            instrumentation.removeTransformer(t);
        }
        TRANSFORMERS.clear();
    }

    public static Class<?>[] findLoadedClasses(String... classNames) {
        if (instrumentation == null) return new Class<?>[0];
        Class<?>[] allLoaded = instrumentation.getAllLoadedClasses();
        List<Class<?>> matches = new java.util.ArrayList<>();
        for (Class<?> c : allLoaded) {
            for (String name : classNames) {
                if (c.getName().equals(name)) {
                    matches.add(c);
                }
            }
        }
        return matches.toArray(new Class<?>[0]);
    }

    public static void shutdown() {
        removeAllTransformers();
        instrumentation = null;
    }
}
