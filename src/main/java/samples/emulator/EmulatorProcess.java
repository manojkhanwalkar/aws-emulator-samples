package samples.emulator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts a private AWS Emulator from Java and stops it again: the way to give each test run its own,
 * empty emulator.
 *
 * <ul>
 *   <li>{@code -p 0} lets the operating system pick a free port, so it never clashes with an emulator
 *       you already run (on 9297) or with other test runs.</li>
 *   <li>{@code -t off} turns telemetry off for this run.</li>
 *   <li>{@code --idle-timeout} makes it stop by itself if this program dies before stopping it.</li>
 * </ul>
 *
 * <p>The emulator writes its log to standard output as JSON lines. Once it is ready it writes one
 * startup line whose {@code message} is {@code "emulator server information"}; its {@code endpoint_url}
 * field gives the address it serves on. That line is a stable interface you can rely on.
 *
 * <p>Stopping it with SIGTERM (what {@link Process#destroy()} sends) is a clean stop: the emulator
 * deletes its files in {@code ~/.aws/emulator/<port>/} and all the resources it held. Nothing carries
 * over to the next run.
 *
 * <pre>{@code
 * try (EmulatorProcess process = EmulatorProcess.start();
 *      Emulator emulator = process.emulator();
 *      S3Client s3 = emulator.client(S3Client.builder())) {
 *     ...
 * }   // the emulator stops here, and its state is gone
 * }</pre>
 */
public final class EmulatorProcess implements AutoCloseable {

    /** The {@code endpoint_url} field of the startup line, for example {@code "https://127.0.0.1:43517"}. */
    private static final Pattern ENDPOINT = Pattern.compile("\"endpoint_url\"\\s*:\\s*\"https://127\\.0\\.0\\.1:(\\d+)\"");
    private static final String STARTUP_MESSAGE = "emulator server information";

    private final Process process;
    private final int port;

    private EmulatorProcess(Process process, int port) {
        this.process = process;
        this.port = port;
    }

    /**
     * Starts an emulator on a free port and waits until it is ready (up to 30 seconds). It stops by
     * itself after 10 minutes without an API call, in case this program never gets to stop it.
     */
    public static EmulatorProcess start() {
        return start(Duration.ofMinutes(10));
    }

    /**
     * @param idleTimeout the emulator stops by itself after this long without an API call, in case this
     *     program never gets to stop it
     * @param extraOptions more {@code aws-emulator} options, for example {@code "--auth", "off"}
     */
    public static EmulatorProcess start(Duration idleTimeout, String... extraOptions) {
        List<String> command = new ArrayList<>(List.of("aws-emulator", "-p", "0", "-t", "off",
                "--idle-timeout", Long.toString(Math.max(1, idleTimeout.toSeconds()))));
        command.addAll(List.of(extraOptions));
        return start(command, Duration.ofSeconds(30));
    }

    /** Starts {@code command} as the emulator and waits up to {@code startupTimeout} for its startup line. */
    static EmulatorProcess start(List<String> command, Duration startupTimeout) {
        Process process;
        try {
            // Standard error only carries a one-line telemetry notice; the log we need is on standard output.
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start aws-emulator. Is it installed? See README.md (Installing AWS Emulator).", e);
        }
        // Read the log on another thread: we need the startup line, and the log must keep being read
        // afterwards so the emulator never blocks on a full pipe.
        CompletableFuture<Integer> startupPort = new CompletableFuture<>();
        Thread reader = new Thread(() -> readLog(process, startupPort), "aws-emulator-log");
        reader.setDaemon(true);
        reader.start();
        try {
            int port = startupPort.get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new EmulatorProcess(process, port);
        } catch (TimeoutException | ExecutionException e) {
            process.destroyForcibly();
            throw new IllegalStateException("aws-emulator did not start within " + startupTimeout.toSeconds() + " seconds", e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting aws-emulator", e);
        }
    }

    private static void readLog(Process process, CompletableFuture<Integer> startupPort) {
        try (BufferedReader log = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = log.readLine()) != null) {
                if (!startupPort.isDone() && line.contains(STARTUP_MESSAGE)) {
                    Matcher endpoint = ENDPOINT.matcher(line);
                    if (endpoint.find()) {
                        startupPort.complete(Integer.parseInt(endpoint.group(1)));
                    }
                }
            }
        } catch (IOException e) {
            startupPort.completeExceptionally(e);
        }
        startupPort.completeExceptionally(new IllegalStateException("aws-emulator exited before it was ready"));
    }

    /** The port the operating system gave this emulator. */
    public int port() {
        return port;
    }

    /** A connection to this emulator. Close it before (or together with) this process. */
    public Emulator emulator() {
        return Emulator.at(port);
    }

    /**
     * Stops the emulator cleanly (SIGTERM): it deletes its files and forgets every resource. The now
     * empty {@code ~/.aws/emulator/<port>/} folder is removed too, so runs on random ports leave nothing.
     */
    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try {
            // The emulator deletes its files but leaves the folder named after the port.
            Files.deleteIfExists(Path.of(System.getProperty("user.home"), ".aws", "emulator", Integer.toString(port)));
        } catch (IOException notEmptyOrGone) {
            // Not empty (it did not stop cleanly) or already gone: leave it.
        }
    }
}
