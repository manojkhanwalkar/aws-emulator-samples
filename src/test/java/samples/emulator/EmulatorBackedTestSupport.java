package samples.emulator;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared by the tests: whether {@code aws-emulator} is on the PATH, so emulator-backed tests can skip. */
public final class EmulatorBackedTestSupport {

    private EmulatorBackedTestSupport() {}

    public static boolean isInstalled() {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, "aws-emulator"))) {
                return true;
            }
        }
        return false;
    }
}
