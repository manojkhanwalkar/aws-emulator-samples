package samples.emulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The helpers' failure paths: what a user sees when the emulator is missing, hung or not running. */
class EmulatorHelpersTest {

    @Test
    void aMissingEmulatorCommandSaysHowToInstallIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmulatorProcess.start(List.of("no-such-aws-emulator-command"), Duration.ofSeconds(5)));
        assertTrue(e.getMessage().contains("Is it installed?"), e.getMessage());
    }

    @Test
    void anEmulatorThatNeverReportsReadyTimesOut() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmulatorProcess.start(List.of("sleep", "30"), Duration.ofMillis(500)));
        assertTrue(e.getMessage().contains("did not start within PT0.5S"), e.getMessage());
    }

    @Test
    void anEmulatorThatExitsBeforeItIsReadyIsReported() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EmulatorProcess.start(List.of("true"), Duration.ofSeconds(10)));
        assertTrue(e.getMessage().contains("exited before it was ready"), e.getMessage());
    }

    @Test
    void filesLeftBehindWithoutARunningEmulatorAreNotMistakenForOne(@TempDir Path home) throws Exception {
        assumeTrue(EmulatorBackedTestSupport.isInstalled(), "aws-emulator is not installed: skipping");
        int unused;
        try (ServerSocket socket = new ServerSocket(0)) {
            unused = socket.getLocalPort();
        }
        String realHome = System.getProperty("user.home");
        Path realFolder;
        try (EmulatorProcess process = EmulatorProcess.start()) {
            realFolder = Path.of(realHome, ".aws", "emulator", Integer.toString(process.port()));
            try (Emulator running = process.emulator()) {
                // A crashed emulator leaves its files behind: copy a real run's files to a port where nothing runs.
                Path stale = Files.createDirectories(home.resolve(".aws/emulator/" + unused));
                for (String file : List.of("ca.pem", "emulator_credentials", "config")) {
                    Files.copy(running.directory().resolve(file), stale.resolve(file));
                }
            }
            String previousPort = System.getProperty("emulator.port");
            // user.home points at the temporary folder only inside this block, and is restored before
            // the emulator stops, so close() cleans up the real folder.
            System.setProperty("user.home", home.toString());
            System.setProperty("emulator.port", Integer.toString(unused));
            try {
                try (Emulator stalePort = Emulator.at(unused)) {
                    assertFalse(stalePort.isHealthy());
                }
                IllegalStateException e = assertThrows(IllegalStateException.class, Emulator::connect);
                assertTrue(e.getMessage().contains("does not answer its health check"), e.getMessage());
            } finally {
                System.setProperty("user.home", realHome);
                if (previousPort == null) {
                    System.clearProperty("emulator.port");
                } else {
                    System.setProperty("emulator.port", previousPort);
                }
            }
        }
        assertFalse(Files.exists(realFolder), "close() must remove " + realFolder);
    }

    @Test
    void aDamagedCertificateFileIsReportedClearly(@TempDir Path home) throws Exception {
        Path dir = Files.createDirectories(home.resolve(".aws/emulator/9399"));
        Files.writeString(dir.resolve("ca.pem"), "not a certificate");
        Files.writeString(dir.resolve("emulator_credentials"), "[default]\naws_access_key_id = X\naws_secret_access_key = Y\n");
        String realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Emulator.at(9399));
            assertTrue(e.getMessage().contains("Cannot read the emulator's certificate"), e.getMessage());
        } finally {
            System.setProperty("user.home", realHome);
        }
    }

    @Test
    void anInterruptedStartStopsTheProcessAndKeepsTheInterrupt() {
        Thread.currentThread().interrupt();
        try {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> EmulatorProcess.start(List.of("sleep", "30"), Duration.ofSeconds(10)));
            assertTrue(e.getMessage().contains("Interrupted"), e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // clear it for the next test
        }
    }

    @Test
    void anInterruptedHealthCheckAnswersNoAndKeepsTheInterrupt() {
        assumeTrue(EmulatorBackedTestSupport.isInstalled(), "aws-emulator is not installed: skipping");
        try (EmulatorProcess process = EmulatorProcess.start();
             Emulator emulator = process.emulator()) {
            Thread.currentThread().interrupt();
            try {
                assertFalse(emulator.isHealthy());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
