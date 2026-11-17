package barry.launch;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "list" -> listCandidates();
            case "attach" -> {
                if (args.length < 2) {
                    System.err.println("Usage: attach <pid|index>");
                    System.exit(1);
                }
                attach(args[1]);
            }
            case "eject" -> {
                if (args.length < 2) {
                    System.err.println("Usage: eject <pid|index>");
                    System.exit(1);
                }
                eject(args[1]);
            }
            default -> printUsage();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: barry <command>");
        System.out.println("  list              List candidate Minecraft JVMs");
        System.out.println("  attach <pid|idx>  Attach to a Minecraft process");
        System.out.println("  eject  <pid|idx>  Eject from a Minecraft process");
    }

    static List<VirtualMachineDescriptor> findMinecraftProcesses() {
        List<VirtualMachineDescriptor> candidates = new ArrayList<>();
        for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
            String name = vmd.displayName().toLowerCase();
            if (name.contains("minecraft") || name.contains("net.minecraft")
                    || name.contains("cpw.mods") || name.contains("GradleStart")
                    || name.contains("launchwrapper") || name.contains("fabric")
                    || name.contains("lunar") || name.contains("badlion")) {
                candidates.add(vmd);
            }
        }
        return candidates;
    }

    private static void listCandidates() {
        List<VirtualMachineDescriptor> candidates = findMinecraftProcesses();
        if (candidates.isEmpty()) {
            System.out.println("No Minecraft JVMs found.");
            System.out.println("All JVMs:");
            for (VirtualMachineDescriptor vmd : VirtualMachine.list()) {
                System.out.printf("  [%s] %s%n", vmd.id(), vmd.displayName());
            }
            return;
        }
        System.out.println("Minecraft JVMs:");
        for (int i = 0; i < candidates.size(); i++) {
            VirtualMachineDescriptor vmd = candidates.get(i);
            System.out.printf("  %d) [PID %s] %s%n", i, vmd.id(), vmd.displayName());
        }
    }

    private static String resolvePid(String arg) {
        try {
            int index = Integer.parseInt(arg);
            List<VirtualMachineDescriptor> candidates = findMinecraftProcesses();
            if (index >= 0 && index < candidates.size()) {
                return candidates.get(index).id();
            }
            if (index > 100) {
                return arg;
            }
            System.err.println("Index " + index + " out of range (found " + candidates.size() + " candidates)");
            System.exit(1);
        } catch (NumberFormatException e) {
            // treat as pid string
        }
        return arg;
    }

    private static Path findAgentJar() {
        Path self = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        if (System.getProperty("os.name").toLowerCase().contains("win") && self.toString().startsWith("/")) {
            self = Path.of(self.toString().substring(1));
        }
        if (self.toString().endsWith(".jar")) {
            return self;
        }
        Path built = Path.of("build", "libs", "barry-0.1.0.jar");
        if (built.toFile().exists()) {
            return built.toAbsolutePath();
        }
        System.err.println("Cannot find agent JAR. Build with: gradlew jar");
        System.exit(1);
        return null;
    }

    private static void attach(String arg) throws Exception {
        String pid = resolvePid(arg);
        Path agentJar = findAgentJar();

        System.out.printf("Attaching to PID %s with agent %s%n", pid, agentJar);

        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agentJar.toAbsolutePath().toString(), "attach");
            System.out.println("Agent loaded successfully.");
        } finally {
            vm.detach();
        }
    }

    private static void eject(String arg) throws Exception {
        String pid = resolvePid(arg);
        Path agentJar = findAgentJar();

        System.out.printf("Ejecting from PID %s%n", pid);

        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(agentJar.toAbsolutePath().toString(), "eject");
            System.out.println("Eject signal sent.");
        } finally {
            vm.detach();
        }
    }
}
