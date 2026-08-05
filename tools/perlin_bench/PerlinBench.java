import java.util.concurrent.atomic.AtomicLong;

/** The CPU side of the FP64 Perlin comparison. Same arithmetic as perlin_bench.cu. */
public final class PerlinBench {

    static final int[][] GRAD = {
        {1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},
        {1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},
        {1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}
    };
    static final int OCTAVES = 16;
    static final short[] P = new short[256];

    static {
        long seed = 12345;
        for (int i = 0; i < 256; i++) {
            P[i] = (short) i;
        }
        for (int i = 0; i < 256; i++) {
            seed = (seed * 0x5DEECE66DL + 0xB) & ((1L << 48) - 1);
            int j = (int) ((seed >>> 16) % (256 - i)) + i;
            short t = P[i];
            P[i] = P[j];
            P[j] = t;
        }
    }

    static double gradDot(int hash, double x, double y, double z) {
        int[] g = GRAD[hash & 15];
        return g[0] * x + g[1] * y + g[2] * z;
    }

    static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    static double perlin(double x, double y, double z) {
        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;
        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double zf = z - Math.floor(z);
        double u = fade(xf), v = fade(yf), w = fade(zf);

        int a  = P[xi] + yi;
        int aa = P[a & 255] + zi;
        int ab = P[(a + 1) & 255] + zi;
        int b  = P[(xi + 1) & 255] + yi;
        int ba = P[b & 255] + zi;
        int bb = P[(b + 1) & 255] + zi;

        double d0 = gradDot(P[aa & 255],       xf,       yf,       zf);
        double d1 = gradDot(P[ba & 255],       xf - 1.0, yf,       zf);
        double d2 = gradDot(P[ab & 255],       xf,       yf - 1.0, zf);
        double d3 = gradDot(P[bb & 255],       xf - 1.0, yf - 1.0, zf);
        double d4 = gradDot(P[(aa + 1) & 255], xf,       yf,       zf - 1.0);
        double d5 = gradDot(P[(ba + 1) & 255], xf - 1.0, yf,       zf - 1.0);
        double d6 = gradDot(P[(ab + 1) & 255], xf,       yf - 1.0, zf - 1.0);
        double d7 = gradDot(P[(bb + 1) & 255], xf - 1.0, yf - 1.0, zf - 1.0);

        return lerp(w, lerp(v, lerp(u, d0, d1), lerp(u, d2, d3)),
                       lerp(v, lerp(u, d4, d5), lerp(u, d6, d7)));
    }

    public static void main(String[] args) throws Exception {
        long n = args.length > 0 ? Long.parseLong(args[0]) : (1L << 24);
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 24;

        // The GPU's thread 0 walks s = 0, stride, 2*stride, ... with stride = 512*256.
        // Reproduce exactly that walk so out[0] can be diffed bit for bit.
        long stride = 512L * 256L;
        double acc = 0.0;
        for (long s = 0; s < n; s += stride) {
            acc += octaves(s);
        }
        System.out.printf("CPU: out[0] = %.17g%n", acc);
        System.out.printf("CPU: perm[0..7] = %d %d %d %d %d %d %d %d%n",
                P[0], P[1], P[2], P[3], P[4], P[5], P[6], P[7]);

        for (int pass = 0; pass < 2; pass++) {
            AtomicLong next = new AtomicLong();
            long block = 1 << 16;
            double[] sinks = new double[threads];
            Thread[] pool = new Thread[threads];
            long t0 = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                pool[t] = new Thread(() -> {
                    double local = 0;
                    for (long b = next.getAndIncrement(); b * block < n;
                            b = next.getAndIncrement()) {
                        long to = Math.min(n, b * block + block);
                        for (long s = b * block; s < to; s++) {
                            local += octaves(s);
                        }
                    }
                    sinks[id] = local;
                });
                pool[t].start();
            }
            for (Thread th : pool) {
                th.join();
            }
            double ms = (System.nanoTime() - t0) / 1e6;
            double sink = 0;
            for (double d : sinks) {
                sink += d;
            }
            if (pass == 1) {
                System.out.printf("CPU: %d samples x %d octaves on %d threads in %.1f ms "
                                + "-> %.1f M octave-evals/s   (sink %.3f)%n",
                        n, OCTAVES, threads, ms, n * (double) OCTAVES / (ms * 1e3), sink);
            }
        }
    }

    static double octaves(long s) {
        double x = (s % 1024) * 0.0625;
        double y = ((s / 1024) % 256) * 0.125;
        double z = (s % 733) * 0.03125;
        double amp = 1.0, freq = 1.0, acc = 0.0;
        for (int o = 0; o < OCTAVES; o++) {
            acc += perlin(x * freq, y * freq, z * freq) * amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return acc;
    }
}
