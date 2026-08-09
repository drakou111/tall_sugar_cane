package dev.drakou111.sugarcane;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CUDA kernels that ship inside the jar.
 *
 * <p>The jar carries prebuilt kernels so a user needs the jar and nothing else — no toolkit, no
 * compiler, no build script. The cost of that convenience is binaries in the tree that can fall
 * out of step with their source, and editing a kernel while a stale executable kept running has
 * already wasted a debugging session once: the compile failed, the old exe stayed in place, and
 * the measurements looked fine.
 *
 * <p><b>Presence on disk is not the property that matters.</b> This test used to check only that,
 * and it passed for months while {@code noise_column.exe} and {@code two_chunk_lift.exe} were
 * being dropped from every clone by the {@code *.exe} rule in {@code .gitignore} — which only
 * ever negated {@code find_targets.exe}. Both existed locally, so nothing failed here, and the
 * jar built from a fresh clone silently lacked the lift that 6bc measured at 4.3x over 24 CPU
 * threads. Hence {@link #everyBundledKernelIsTrackedByGit()}: the question is whether a clone
 * gets them, not whether this machine has them.
 */
class BundledKernelTest {

    /** Every kernel the jar is expected to carry, as (source, bundled copy). */
    private static final List<String> KERNELS =
            List.of("find_targets", "noise_column", "two_chunk_lift", "stack_enum");

    private static Path source(String name) {
        return Path.of("cuda", name + ".cu");
    }

    private static Path bundled(String name) {
        return Path.of("src", "main", "resources", "cuda", name + ".exe");
    }

    @Test
    void everyKernelIsBundled() {
        for (String name : KERNELS) {
            assertNotNull(getClass().getResourceAsStream("/cuda/" + name + ".exe"),
                    "the jar must carry /cuda/" + name + ".exe; a kernel that only exists on "
                            + "the machine that built it is a kernel nobody else has");
        }
    }

    @Test
    void noBundledKernelIsOlderThanItsSource() throws IOException {
        for (String name : KERNELS) {
            Path cu = source(name);
            Path exe = bundled(name);
            if (!Files.isRegularFile(cu) || !Files.isRegularFile(exe)) {
                continue;       // building from something other than a full checkout
            }
            FileTime src = Files.getLastModifiedTime(cu);
            FileTime built = Files.getLastModifiedTime(exe);
            assertTrue(built.compareTo(src) >= 0,
                    cu + " is newer than the bundled " + exe + " (source " + src + ", binary "
                            + built + "). Run cuda/build.bat, which refreshes both copies. A "
                            + "stale kernel is worse than a slow one: it is silently different "
                            + "answers.");
        }
    }

    /**
     * The check the old test was missing: that a clone actually receives these files.
     *
     * <p>Skips when git is unavailable or this is not a checkout, so it never fails for a reason
     * that has nothing to do with the kernels.
     */
    @Test
    void everyBundledKernelIsTrackedByGit() throws Exception {
        if (!Files.isDirectory(Path.of(".git"))) {
            return;
        }
        for (String name : KERNELS) {
            Path exe = bundled(name);
            if (!Files.isRegularFile(exe)) {
                continue;
            }
            Process p;
            try {
                p = new ProcessBuilder("git", "ls-files", "--error-unmatch", exe.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
            } catch (IOException noGit) {
                return;
            }
            assertTrue(p.waitFor(60, TimeUnit.SECONDS), "git ls-files hung on " + exe);
            assertTrue(p.exitValue() == 0,
                    exe + " exists here but is not tracked by git, so a clone does not get it "
                            + "and the jar built from one silently loses this kernel. The *.exe "
                            + "rule in .gitignore needs a matching negation.");
        }
    }
}
