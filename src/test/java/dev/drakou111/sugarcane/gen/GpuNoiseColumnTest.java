package dev.drakou111.sugarcane.gen;

import kaptainwutax.mcutils.rand.ChunkRand;
import kaptainwutax.noiseutils.perlin.PerlinNoiseSampler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The CUDA octave must agree with {@link ColumnPerlin} bit for bit, or it is worse than
 * not having it.
 *
 * <p>{@code ColumnPerlin} is itself held to TerrainUtils by {@code TruncatedNoiseTest}, so
 * agreeing with it is the whole verification. The inputs are derived from the index with
 * exactly representable arithmetic on both sides, so only the permutation and the sampler
 * origins have to cross — nothing here depends on the two agreeing about parsing.
 *
 * <p>Skipped, not failed, when there is no usable GPU. The CPU path is the one that must
 * always work; the device is an accelerator and every machine without one has to keep
 * building. That is the same contract {@code GpuChainFilter} holds for the target builder,
 * where {@code detect()} falls back quietly and {@code --cpu} forces it.
 */
class GpuNoiseColumnTest {

    private static final String RESOURCE = "/cuda/noise_column.exe";

    @Test
    void theNoiseKernelIsBundled() {
        assertNotNull(getClass().getResourceAsStream(RESOURCE),
                "the jar must carry " + RESOURCE + " so a user needs the jar and nothing "
                        + "else - no toolkit, no compiler, no build script");
    }

    @Test
    void theKernelAgreesWithColumnPerlinBitForBit() throws Exception {
        Path exe = unpack();
        assumeTrue(exe != null, "no bundled noise kernel");

        long n = 400_000L;
        PerlinNoiseSampler sampler = new PerlinNoiseSampler(new ChunkRand(987654321L));
        Field f = Class.forName("kaptainwutax.noiseutils.noise.Noise")
                .getDeclaredField("permutations");
        f.setAccessible(true);
        byte[] perm = (byte[]) f.get(sampler);
        StringBuilder hex = new StringBuilder(512);
        for (int i = 0; i < 256; i++) {
            hex.append(String.format("%02x", perm[i] & 255));
        }

        ColumnPerlin cpu = new ColumnPerlin(sampler);
        long expected = 0;
        for (long i = 0; i < n; i++) {
            cpu.beginColumn((i % 4096) * 0.25 - 512.0, ((i / 4096) % 4096) * 0.25 - 512.0);
            double v = cpu.sampleY((i % 384) * 0.5, (i % 3 == 0) ? 0.0 : 4.0, 32.0);
            expected ^= Double.doubleToRawLongBits(v) * 0x9E3779B97F4A7C15L + i;
        }

        ProcessBuilder pb = new ProcessBuilder(exe.toString(), hex.toString(),
                Double.toString(sampler.originX), Double.toString(sampler.originY),
                Double.toString(sampler.originZ), Long.toString(n));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        // No device, no driver, a card with no matching cubin: all of them mean skip, not
        // fail. Only a device that ran and disagreed is a defect.
        assumeTrue(done && p.exitValue() == 0, "no usable GPU for the noise kernel: " + out);

        String checksum = null;
        for (String line : List.of(out.split("\\R"))) {
            if (line.startsWith("checksum ")) {
                checksum = line.substring("checksum ".length()).trim();
            }
        }
        assertNotNull(checksum, "kernel printed no checksum: " + out);
        assertEquals(String.format("%016x", expected), checksum,
                "the CUDA octave diverged from ColumnPerlin. Rebuild with cuda/build.bat "
                        + "and check -fmad=false is still there: nvcc contracts a*b+c into "
                        + "an FMA by default, which is more accurate than Java's separate "
                        + "multiply and add and therefore a different double.");
    }

    private static Path unpack() throws IOException {
        try (InputStream in = GpuNoiseColumnTest.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            Path dir = Files.createTempDirectory("noise-kernel-");
            Path exe = dir.resolve("noise_column.exe");
            Files.copy(in, exe, StandardCopyOption.REPLACE_EXISTING);
            exe.toFile().setExecutable(true);
            exe.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return exe;
        }
    }
}
