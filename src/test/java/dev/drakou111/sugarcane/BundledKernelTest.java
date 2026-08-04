package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CUDA kernel that ships inside the jar.
 *
 * <p>The jar carries a prebuilt {@code find_targets.exe} so a user needs the jar and
 * nothing else — no toolkit, no compiler, no build script. The cost of that convenience is
 * a binary in the tree that can fall out of step with its source, and editing the kernel
 * while a stale executable kept running has already wasted a debugging session once: the
 * compile failed, the old exe stayed in place, and the measurements looked fine.
 *
 * <p>So the build refuses to ship a kernel older than its source. Rebuild with
 * {@code cuda/build.bat}, which refreshes both copies.
 */
class BundledKernelTest {

    private static final Path RESOURCE = Path.of("src", "main", "resources", "cuda",
            "find_targets.exe");
    private static final Path SOURCE = Path.of("cuda", "find_targets.cu");

    @Test
    void theKernelIsBundled() {
        assertNotNull(getClass().getResourceAsStream("/cuda/find_targets.exe"),
                "the jar must carry /cuda/find_targets.exe, or every user without a local "
                        + "build silently gets the 4.5x slower CPU path");
    }

    @Test
    void theBundledKernelIsNotOlderThanItsSource() throws IOException {
        if (!Files.isRegularFile(SOURCE) || !Files.isRegularFile(RESOURCE)) {
            return;     // building from something other than a full checkout
        }
        FileTime cu = Files.getLastModifiedTime(SOURCE);
        FileTime exe = Files.getLastModifiedTime(RESOURCE);
        assertTrue(exe.toMillis() >= cu.toMillis(),
                "cuda/find_targets.cu is newer than the bundled " + RESOURCE
                        + " (source " + cu + ", binary " + exe + "). Run cuda/build.bat: "
                        + "the jar would otherwise ship a kernel that does not match its "
                        + "source.");
    }
}
