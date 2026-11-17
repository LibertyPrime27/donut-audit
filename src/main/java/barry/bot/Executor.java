package barry.bot;

import barry.attach.Remap;
import barry.core.BlockMap;
import barry.util.BlockPos;
import barry.util.Dimension;
import barry.xray.XrayEngine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Executor {

    private final XrayEngine engine;
    private final Planner planner;
    private final Governor governor;
    private final Inventory inventory;

    private volatile Thread executorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Planner.Task currentTask;

    // Game interaction handles (resolved at runtime)
    private volatile Object mcInstance;
    private volatile Object playerEntity;
    private volatile ClassLoader gameClassLoader;

    // Safety limits
    private int maxDebris = 0; // 0 = unlimited
    private int maxMinutes = 0;
    private int healthFloor = 4; // half-hearts
    private long startTime;

    // Counters
    private int debrisCollected = 0;
    private int blocksMinced = 0;
    private double distanceTraveled = 0;
    private BlockPos lastPos;

    public Executor(XrayEngine engine) {
        this.engine = engine;
        BlockMap map = engine.currentMap();
        this.planner = new Planner(map, engine.currentDimension());
        this.governor = new Governor();
        this.inventory = new Inventory();
    }

    public void start() {
        if (running.getAndSet(true)) return;

        gameClassLoader = barry.Bootstrap.findMinecraftClassLoader();
        startTime = System.currentTimeMillis();

        executorThread = new Thread(this::executionLoop, "barry-executor");
        executorThread.setDaemon(true);
        executorThread.start();

        System.err.println("[barry] Executor started.");
    }

    public void stop() {
        running.set(false);
        if (executorThread != null) {
            executorThread.interrupt();
            executorThread = null;
        }
        System.err.println("[barry] Executor stopped. Debris: " + debrisCollected);
    }

    public boolean isRunning() { return running.get(); }
    public Planner.Task currentTask() { return currentTask; }
    public Planner planner() { return planner; }
    public Governor governor() { return governor; }
    public Inventory inventory() { return inventory; }

    private void executionLoop() {
        while (running.get()) {
            try {
                // Safety checks
                if (!safetyCheck()) {
                    System.err.println("[barry] Safety check failed, stopping.");
                    stop();
                    return;
                }

                // Update position
                BlockPos pos = engine.playerPos();
                planner.setPlayerPos(pos);
                trackDistance(pos);

                // Rate governor: should we pause?
                long pauseMs = governor.shouldPause(debrisCollected, startTime);
                if (pauseMs > 0) {
                    currentTask = Planner.Task.wait((int)(pauseMs / 50));
                    Thread.sleep(pauseMs);
                    continue;
                }

                // Get next task
                currentTask = planner.nextTask();
                if (currentTask == null) {
                    Thread.sleep(1000);
                    continue;
                }

                executeTask(currentTask);

            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                System.err.println("[barry] Executor error: " + t.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void executeTask(Planner.Task task) throws Exception {
        switch (task.action()) {
            case MOVE_TO -> moveTo(task.target());
            case PLACE_BED -> placeBed(task.target());
            case MINE_BLOCK -> mineBlock(task.target());
            case COLLECT_DEBRIS -> collectDebris(task.target());
            case TUNNEL -> executeTunnel(task);
            case WAIT -> {
                int ticks = (int) task.params().getOrDefault("ticks", 20);
                Thread.sleep(ticks * 50L);
            }
            case INVENTORY -> manageInventory();
            case IDLE -> Thread.sleep(5000);
            case PORTAL -> {} // TODO: portal management
        }
    }

    private void moveTo(BlockPos target) throws Exception {
        // Pathfinding: A* through the block map, lava-aware cost model
        // For now, simple direct movement via game commands
        if (gameClassLoader == null) return;

        BlockPos current = engine.playerPos();
        if (current.distanceTo(target) < 2.0) return;

        // Send movement packets toward target
        // This requires hooking into the player controller
        // Real implementation: set look toward target, hold W, handle obstacles
        sendLookAt(target);
        sendMoveForward(true);

        // Wait until close enough or timeout
        long timeout = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < timeout && running.get()) {
            BlockPos pos = engine.playerPos();
            if (pos.distanceTo(target) < 2.0) break;
            Thread.sleep(50);
        }

        sendMoveForward(false);
    }

    private void placeBed(BlockPos target) throws Exception {
        // Bed placement in the nether triggers an explosion
        // 1. Look at the block below target
        // 2. Use bed item (right-click)
        // 3. The explosion is handled by onExplosion in XrayEngine

        // First, ensure we have a bed in hotbar
        if (!inventory.hasBed()) {
            // Craft or fetch bed — requires inventory management
            System.err.println("[barry] No bed in inventory, skipping probe");
            return;
        }

        // Look at placement position
        sendLookAt(target.offset(0, -1, 0));
        Thread.sleep(100);

        // Switch to bed slot and use
        inventory.switchToBedSlot();
        Thread.sleep(50);

        sendUseItem();
        Thread.sleep(100);

        // The bed explodes immediately in the nether
        // Block updates will be processed by XrayEngine.onExplosion()
        engine.onBedPlacement(target);

        Thread.sleep(500); // wait for explosion updates
    }

    private void mineBlock(BlockPos target) throws Exception {
        sendLookAt(target);
        Thread.sleep(50);

        // Start mining (hold left click)
        sendStartBreaking(target);

        // Wait for break to complete (vanilla speed, no acceleration)
        // Break time depends on tool and block hardness
        long breakTimeMs = estimateBreakTime(target);
        Thread.sleep(breakTimeMs);

        sendStopBreaking();
        blocksMinced++;
    }

    private void collectDebris(BlockPos target) throws Exception {
        // The debris is now a dropped item — walk over it
        moveTo(target);
        Thread.sleep(200);
        debrisCollected++;
        governor.recordDebris();
    }

    private void executeTunnel(Planner.Task task) throws Exception {
        int dx = (int) task.params().getOrDefault("dx", 1);
        int dz = (int) task.params().getOrDefault("dz", 0);
        int length = (int) task.params().getOrDefault("length", 16);

        BlockPos pos = task.target();
        for (int i = 0; i < length && running.get(); i++) {
            BlockPos next = pos.offset(dx * i, 0, dz * i);

            // Mine a 1x2 tunnel (or width x 2)
            mineBlock(next);
            mineBlock(next.offset(0, 1, 0));

            // Check for newly revealed debris after each break
            Planner.Task detour = planner.peekTask();
            if (detour != null && detour.action() == Planner.Action.COLLECT_DEBRIS) {
                break; // planner found debris, switch to collection
            }

            Thread.sleep(50);
        }
    }

    private void manageInventory() throws Exception {
        // Check if inventory is full
        // Deposit junk items
        // Repair tools if needed
        inventory.compactInventory();
    }

    private boolean safetyCheck() {
        // Max debris reached?
        if (maxDebris > 0 && debrisCollected >= maxDebris) return false;

        // Max time reached?
        if (maxMinutes > 0) {
            long elapsed = (System.currentTimeMillis() - startTime) / 60000;
            if (elapsed >= maxMinutes) return false;
        }

        // Health too low?
        float health = getPlayerHealth();
        if (health > 0 && health < healthFloor) return false;

        return true;
    }

    private float getPlayerHealth() {
        try {
            Object player = getPlayer();
            if (player == null) return -1;
            Method getHealth = player.getClass().getMethod("getHealth");
            return (float) getHealth.invoke(player);
        } catch (Throwable ignored) { return -1; }
    }

    private Object getPlayer() {
        try {
            if (mcInstance == null) {
                String mcClassName = Remap.resolveClass("net.minecraft.client.Minecraft");
                Class<?> mcClass = gameClassLoader.loadClass(mcClassName);
                for (Method m : mcClass.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == mcClass
                            && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        mcInstance = m.invoke(null);
                        break;
                    }
                }
            }
            if (mcInstance == null) return null;

            String playerClassName = Remap.resolveClass("net.minecraft.client.player.LocalPlayer");
            Class<?> playerClass = gameClassLoader.loadClass(playerClassName);
            for (Field f : mcInstance.getClass().getDeclaredFields()) {
                if (playerClass.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f.get(mcInstance);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void trackDistance(BlockPos pos) {
        if (lastPos != null && pos != null) {
            distanceTraveled += lastPos.distanceTo(pos);
        }
        lastPos = pos;
    }

    private long estimateBreakTime(BlockPos target) {
        // Netherrack with Efficiency V + Haste II = instant (1 tick)
        // Ancient debris with Netherite pick + Efficiency V = ~8 seconds
        // Default: 500ms (conservative, adjusted by tool detection)
        return 500;
    }

    // --- Game interaction stubs ---
    // These require hooking into Minecraft's player controller
    // via reflection. The actual implementation reads and writes
    // to the client's packet output stream.

    private void sendLookAt(BlockPos target) {
        // Calculate yaw and pitch from player position to target
        // Send ServerboundMovePlayerPacket.Rot
    }

    private void sendMoveForward(boolean forward) {
        // Set KeyMapping state for forward movement
        // or directly write to the input handler
    }

    private void sendUseItem() {
        // Send ServerboundUseItemPacket
    }

    private void sendStartBreaking(BlockPos target) {
        // Send ServerboundPlayerActionPacket (START_DESTROY_BLOCK)
    }

    private void sendStopBreaking() {
        // Send ServerboundPlayerActionPacket (STOP_DESTROY_BLOCK)
    }

    // --- Settings ---

    public void setMaxDebris(int max) { this.maxDebris = max; }
    public void setMaxMinutes(int max) { this.maxMinutes = max; }
    public void setHealthFloor(int floor) { this.healthFloor = floor; }

    // --- Stats ---

    public int debrisCollected() { return debrisCollected; }
    public int blocksMinced() { return blocksMinced; }
    public double distanceTraveled() { return distanceTraveled; }
    public long runTimeMs() { return System.currentTimeMillis() - startTime; }
}
