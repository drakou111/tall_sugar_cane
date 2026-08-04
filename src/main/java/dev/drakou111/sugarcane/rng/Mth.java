package dev.drakou111.sugarcane.rng;

/**
 * Minecraft's {@code net.minecraft.util.Mth}, for the parts worldgen depends on.
 *
 * <p>{@code Mth.sin} and {@code Mth.cos} are <b>not</b> {@code Math.sin} and
 * {@code Math.cos}: they are a 65536-entry float lookup table, so they carry an
 * error of up to about 1e-4 and are quantised. The carvers walk a tunnel up to
 * 112 steps by adding {@code cos(yaw) * cos(pitch)} to a position each step, so
 * that error accumulates and moves the wall of a cave or canyon by a block.
 *
 * <p>Using {@code Math} instead is the kind of difference that shows up as a
 * one-block disagreement with the real game right at a cave boundary — which is
 * exactly where the sugar cane geometry lives.
 */
public final class Mth {

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    private Mth() {
    }

    public static float sin(float f) {
        return SIN[(int) (f * 10430.378f) & 0xFFFF];
    }

    public static float cos(float f) {
        return SIN[(int) (f * 10430.378f + 16384.0f) & 0xFFFF];
    }

    public static float sqrt(float f) {
        return (float) Math.sqrt(f);
    }

    public static int floor(double d) {
        int n = (int) d;
        return d < (double) n ? n - 1 : n;
    }

    public static int floor(float f) {
        int n = (int) f;
        return f < (float) n ? n - 1 : n;
    }

    public static int ceil(float f) {
        int n = (int) f;
        return f > (float) n ? n + 1 : n;
    }

    public static int ceil(double d) {
        int n = (int) d;
        return d > (double) n ? n + 1 : n;
    }

    /**
     * Writes {@code SIN} as big-endian float bits, for a native port to load instead of
     * recomputing it. Recomputing with C's {@code sin()} disagrees with Java at entry
     * 32768 -- 0x250D3132 against 0x250D3000 -- and while that particular value is
     * absorbed by the addition that follows it, two libms agreeing is not something to
     * build on.
     */
    public static void writeSinTable(java.io.OutputStream out) throws java.io.IOException {
        java.io.DataOutputStream d = new java.io.DataOutputStream(out);
        for (float v : SIN) {
            d.writeInt(Float.floatToRawIntBits(v));
        }
        d.flush();
    }

    public static void main(String[] args) throws java.io.IOException {
        if (args.length < 1) {
            System.err.println("usage: sin-table <file>");
            System.exit(2);
        }
        try (java.io.OutputStream o = new java.io.BufferedOutputStream(
                java.nio.file.Files.newOutputStream(java.nio.file.Path.of(args[0])))) {
            writeSinTable(o);
        }
        System.out.printf("wrote %d entries of Mth.SIN to %s%n", SIN.length, args[0]);
    }
}
