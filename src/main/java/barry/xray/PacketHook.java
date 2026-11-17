package barry.xray;

import barry.attach.AgentLoader;
import barry.attach.Remap;
import barry.util.BlockPos;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;

public final class PacketHook {

    private static volatile XrayEngine engine;
    private static volatile ClassLoader gameClassLoader;
    private static volatile Object connectionInstance;

    // Reflective handles cached after first resolution
    private static volatile Method handleChunkDataMethod;
    private static volatile Method handleBlockUpdateMethod;
    private static volatile Method handleSectionBlocksUpdateMethod;
    private static volatile Method handleExplodeMethod;

    // Thread that polls the connection listener for packets
    private static volatile Thread pollThread;
    private static volatile boolean running = false;

    private PacketHook() {}

    public static void init(XrayEngine eng, Instrumentation inst) {
        engine = eng;
        gameClassLoader = barry.Bootstrap.findMinecraftClassLoader();
        if (gameClassLoader == null) {
            System.err.println("[barry] PacketHook: no game classloader found");
            return;
        }

        // We use two approaches to intercept packets:
        //
        // 1. Polling approach (M1): periodically read the client's chunk cache
        //    and connection state via reflection. Lower risk, works without
        //    bytecode modification.
        //
        // 2. Transformer approach (M3+): inject callbacks into packet handler
        //    methods via ClassFileTransformer. Higher fidelity, catches every
        //    packet, but requires class retransformation.
        //
        // Starting with approach 1 for M1.

        resolveHandles();
        startPolling();
    }

    public static void shutdown() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            pollThread = null;
        }
        engine = null;
        gameClassLoader = null;
    }

    private static void resolveHandles() {
        if (!Remap.isReady()) {
            System.err.println("[barry] PacketHook: remap not ready, using reflection scan");
        }

        try {
            // Find the ClientPacketListener class (handles incoming packets)
            String listenerClassName = Remap.resolveClass(
                "net.minecraft.client.multiplayer.ClientPacketListener");
            Class<?> listenerClass = gameClassLoader.loadClass(listenerClassName);

            // Find the connection field on Minecraft -> getConnection()
            String mcClassName = Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = gameClassLoader.loadClass(mcClassName);

            // Get Minecraft instance
            Object mcInstance = null;
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    mcInstance = m.invoke(null);
                    break;
                }
            }

            if (mcInstance == null) {
                System.err.println("[barry] PacketHook: couldn't get Minecraft instance");
                return;
            }

            // Find connection/listener field
            for (Field f : mcInstance.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(mcInstance);
                if (val != null && listenerClass.isAssignableFrom(val.getClass())) {
                    connectionInstance = val;
                    break;
                }
            }

            if (connectionInstance == null) {
                // Try getConnection() method
                for (Method m : mcClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && listenerClass.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        connectionInstance = m.invoke(mcInstance);
                        break;
                    }
                }
            }

            System.err.println("[barry] PacketHook: connection instance " +
                (connectionInstance != null ? "found" : "NOT found"));

        } catch (Throwable t) {
            System.err.println("[barry] PacketHook: resolution failed: " + t.getMessage());
        }
    }

    private static void startPolling() {
        running = true;
        pollThread = new Thread(() -> {
            while (running) {
                try {
                    pollChunkCache();
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    // Fail closed: don't crash the game
                }
            }
        }, "barry-packet-poll");
        pollThread.setDaemon(true);
        pollThread.setPriority(Thread.MIN_PRIORITY);
        pollThread.start();
    }

    private static void pollChunkCache() {
        if (engine == null || gameClassLoader == null) return;

        try {
            // Read player position from Minecraft
            String mcClassName = Remap.resolveClass("net.minecraft.client.Minecraft");
            Class<?> mcClass = gameClassLoader.loadClass(mcClassName);

            Object mcInstance = null;
            for (Method m : mcClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.setAccessible(true);
                    mcInstance = m.invoke(null);
                    break;
                }
            }

            if (mcInstance == null) return;

            // Get player entity
            Object player = findPlayer(mcInstance);
            if (player == null) return;

            // Read position
            double[] pos = readEntityPos(player);
            if (pos != null) {
                engine.setPlayerPos(new BlockPos((int) pos[0], (int) pos[1], (int) pos[2]));
            }

            // Read dimension
            String dimId = readDimension(mcInstance);
            if (dimId != null) {
                engine.setDimension(barry.util.Dimension.fromId(dimId));
            }

        } catch (Throwable ignored) {}
    }

    private static Object findPlayer(Object mc) {
        try {
            // Look for LocalPlayer field (the client player entity)
            String playerClassName = Remap.resolveClass("net.minecraft.client.player.LocalPlayer");
            Class<?> playerClass = gameClassLoader.loadClass(playerClassName);

            for (Field f : mc.getClass().getDeclaredFields()) {
                if (playerClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(mc);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static double[] readEntityPos(Object entity) {
        try {
            // Entity has getX(), getY(), getZ() methods or x, y, z fields
            Class<?> cls = entity.getClass();

            // Try methods first (Mojmap: getX/getY/getZ)
            for (String prefix : new String[]{"getX", "getY", "getZ"}) {
                try {
                    Method m = cls.getMethod(prefix);
                    if (m.getReturnType() == double.class) {
                        double x = (double) cls.getMethod("getX").invoke(entity);
                        double y = (double) cls.getMethod("getY").invoke(entity);
                        double z = (double) cls.getMethod("getZ").invoke(entity);
                        return new double[]{x, y, z};
                    }
                } catch (NoSuchMethodException ignored) {}
            }

            // Try position() method returning Vec3
            for (Method m : cls.getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("Vec3")) {
                    m.setAccessible(true);
                    Object vec = m.invoke(entity);
                    if (vec != null) {
                        Field xf = vec.getClass().getDeclaredField("x");
                        Field yf = vec.getClass().getDeclaredField("y");
                        Field zf = vec.getClass().getDeclaredField("z");
                        xf.setAccessible(true); yf.setAccessible(true); zf.setAccessible(true);
                        return new double[]{xf.getDouble(vec), yf.getDouble(vec), zf.getDouble(vec)};
                    }
                }
            }

            // Try direct fields
            Field xf = findDoubleField(cls, "x", "posX", "field_70165_t");
            Field yf = findDoubleField(cls, "y", "posY", "field_70163_u");
            Field zf = findDoubleField(cls, "z", "posZ", "field_70161_v");
            if (xf != null && yf != null && zf != null) {
                return new double[]{xf.getDouble(entity), yf.getDouble(entity), zf.getDouble(entity)};
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findDoubleField(Class<?> cls, String... names) {
        for (String name : names) {
            try {
                Field f = cls.getDeclaredField(name);
                if (f.getType() == double.class) {
                    f.setAccessible(true);
                    return f;
                }
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static String readDimension(Object mc) {
        try {
            // mc.level.dimension().location().toString()
            Object level = null;
            for (Field f : mc.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(mc);
                if (val != null && val.getClass().getName().contains("ClientLevel")) {
                    level = val;
                    break;
                }
            }
            if (level == null) return null;

            // dimension() -> ResourceKey<Level>
            for (Method m : level.getClass().getMethods()) {
                if (m.getName().equals("dimension") && m.getParameterCount() == 0) {
                    Object dimKey = m.invoke(level);
                    if (dimKey != null) {
                        // ResourceKey.location() -> ResourceLocation
                        Method locMethod = dimKey.getClass().getMethod("location");
                        Object loc = locMethod.invoke(dimKey);
                        if (loc != null) return loc.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
