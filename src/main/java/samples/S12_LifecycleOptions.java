package samples;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import samples.emulator.Emulator;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Sample 12: keeping an emulator from outliving its use. Starts its own emulator: {@code aws-emulator}
 * only needs to be installed.
 *
 * <p><b>{@code --idle-timeout <seconds>}</b> (what this sample runs): the emulator stops by itself after
 * that many seconds without an API call, cleanly (it deletes its files, as on Ctrl+C). Every API call
 * restarts the clock. Use it for test runs, so a forgotten emulator doesn't keep running.
 *
 * <p><b>A memory cap</b> (described here, not run). The emulator keeps everything in memory: about
 * 160 MiB idle, growing with what your tests create. It has no memory option of its own; you cap it
 * from outside, as the emulator guide shows:
 * <pre>
 *   systemd-run --user --scope -p MemoryMax=2G -p MemorySwapMax=0 aws-emulator      (Linux, cgroup v2)
 *   docker run --rm --memory=2g --memory-swap=2g ... aws-emulator                   (Docker)</pre>
 * Over the cap, the kernel's out-of-memory killer stops it with SIGKILL: exit code 137 (128 + 9), its
 * resources are lost, and because that is not a clean stop, its files in {@code ~/.aws/emulator/<port>/}
 * stay behind (the next emulator on that port replaces them). This sample deliberately does not
 * demonstrate that: filling an emulator until it is killed sets off out-of-memory alerts on your desktop
 * and shows nothing your tests need. If your tests create a lot of data, set a cap and watch for exit
 * code 137.
 */
public final class S12_LifecycleOptions {

    private S12_LifecycleOptions() {}

    public static void main(String[] args) {
        run();
    }

    static void run() {
        System.out.println("== 12. Lifecycle: --idle-timeout ==");
        try {
            idleTimeout();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Sample 12 failed", e);
        }
        System.out.println("A memory cap is set from outside (systemd-run -p MemoryMax=2G, or docker --memory=2g);"
                + " over it the emulator is killed with exit code 137. See the comments and README.md.");
        System.out.println();
    }

    /** An emulator with a 3-second idle timeout stays up while it gets calls, then stops by itself. */
    private static void idleTimeout() throws IOException, InterruptedException {
        int port = freePort();
        Process process = startOn("aws-emulator", port, "--idle-timeout", "3");
        try (Emulator emulator = Emulator.at(port);
             StsClient sts = emulator.client(StsClient.builder())) {
            System.out.println("Started an emulator with --idle-timeout 3 on port " + port);

            // A call every second for 5 seconds: each one restarts the 3-second clock.
            long lastCall = 0;
            for (int i = 0; i < 5; i++) {
                sts.getCallerIdentity();
                lastCall = System.nanoTime();
                Checks.expect(!process.waitFor(1, TimeUnit.SECONDS), "Stopped although it got a call every second");
            }
            System.out.println("Still running after 5 seconds of calls (each call restarts the clock)");

            // Now no more calls: it should stop by itself about 3 seconds later.
            Checks.expect(process.waitFor(15, TimeUnit.SECONDS), "The emulator did not stop after its idle timeout");
            long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastCall);
            boolean filesGone = !Files.exists(emulator.directory().resolve("ca.pem"));
            System.out.println("Without calls it stopped by itself about " + seconds + " s after the last call, exit code "
                    + process.exitValue() + "; its files are deleted: " + filesGone + " (a clean stop)");
        } finally {
            stop(process, port);
        }
    }

    /**
     * Starts {@code executable} (normally {@code aws-emulator}) on {@code port} and waits for its health
     * check. This sample manages the process itself, rather than with {@code EmulatorProcess}, because it
     * watches the process exit.
     */
    static Process startOn(String executable, int port, String... options) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(executable, "-p", Integer.toString(port), "-t", "off"));
        command.addAll(List.of(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Path caFile = directoryOf(port).resolve("ca.pem");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(caFile) && Files.exists(caFile.resolveSibling("emulator_credentials"))) {
                try (Emulator emulator = Emulator.at(port)) {
                    if (emulator.isHealthy()) {
                        return process;
                    }
                }
            }
            Checks.pause(Duration.ofMillis(100));
        }
        stop(process, port);
        throw new IllegalStateException("aws-emulator did not start on port " + port);
    }

    /** Stops the emulator if it still runs (SIGTERM, a clean stop), and deletes what is left in its port's folder. */
    static void stop(Process process, int port) throws IOException, InterruptedException {
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
        }
        Path directory = directoryOf(port);
        if (Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(directory);
        }
    }

    /** A port that is free right now (the OS picks it; we close the socket and let the emulator take it). */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path directoryOf(int port) {
        return Path.of(System.getProperty("user.home"), ".aws", "emulator", Integer.toString(port));
    }
}
