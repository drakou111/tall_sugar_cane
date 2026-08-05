package dev.drakou111.sugarcane.gen;

import kaptainwutax.biomeutils.layer.BiomeLayer;
import kaptainwutax.biomeutils.layer.IntBiomeLayer;
import kaptainwutax.biomeutils.layer.LayerStack;
import kaptainwutax.biomeutils.layer.cache.IntLayerCache;
import kaptainwutax.biomeutils.source.OverworldBiomeSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Enlarges the biome layer caches.
 *
 * <p>Every {@code IntBiomeLayer} allocates a 1024-entry {@link IntLayerCache}: a
 * direct-mapped table of {@code long[] keys} and {@code int[] values}, no chaining,
 * so a colliding key evicts the old one outright. A hit returns immediately; a miss
 * calls {@code sample}, which queries this layer's parents, which miss in turn — one
 * eviction near the top of a forty-layer stack costs a recursive walk of the whole
 * thing.
 *
 * <p>A 32x32-chunk region covers 128x128 = 16,384 quart cells, sixteen times what
 * the table holds, and the search sweeps it block by block for every chunk. So the
 * top layers thrash almost completely. Profiling put the layer stack at about 20%
 * of the search.
 *
 * <p>This is a pure cache with no effect on what the layers compute, so replacing it
 * with a bigger one of the same class cannot change a single biome — only how often
 * the value has to be recomputed. Verified by {@code BiomeSourceTest} and by the
 * search reporting identical chunk and cane counts.
 */
public final class LayerCaches {

    /**
     * Measured, not reasoned: 24 threads, 6000 seeds, chunks/s against the stock
     * 1024.
     *
     * <pre>
     * 1&lt;&lt;10  13219   1&lt;&lt;12  14208   1&lt;&lt;14  13591
     * 1&lt;&lt;11  14043   1&lt;&lt;13  13965   1&lt;&lt;16   6571
     * </pre>
     *
     * Bigger is not better, and 64K is half the speed of stock. Forty layers times
     * 24 workers means the tables are competing for L2 against the noise data, so
     * past a point every added entry costs more in locality than it saves in
     * recomputation. 4096 buys 1.07x over stock; the library's default was already
     * a sensible choice, just tuned for a different access pattern.
     */
    private static final int CAPACITY = 1 << 12;

    private LayerCaches() {
    }

    /**
     * Best-effort: if the library's internals have moved, leave the caches alone.
     *
     * <p>The same applies to the JDK moving. {@code layerCache} is final, and newer
     * releases warn that mutating a final field reflectively will eventually be blocked:
     *
     * <pre>
     * WARNING: Final field layerCache in class ...IntBiomeLayer has been mutated
     *          reflectively by class ...LayerCaches
     * </pre>
     *
     * <p>When that day comes the {@code set} throws and this catch swallows it, so the
     * search keeps working on the library's stock 1024-entry caches and gives up the
     * 1.07x. Nothing here is load-bearing for correctness — the caches only decide how
     * often a value is recomputed, never what it is.
     *
     * <p>To silence the warning now, run with
     * {@code --enable-final-field-mutation=ALL-UNNAMED}. The GUI adds it automatically
     * when the JVM understands it.
     */
    public static void enlarge(OverworldBiomeSource source) {
        enlarge(source, CAPACITY);
    }

    public static void enlarge(OverworldBiomeSource source, int capacity) {
        try {
            Field layersField = findField(source.getClass(), "layers");
            layersField.setAccessible(true);
            LayerStack<?> stack = (LayerStack<?>) layersField.get(source);
            Field cacheField = IntBiomeLayer.class.getDeclaredField("layerCache");
            cacheField.setAccessible(true);
            Constructor<IntLayerCache> ctor = IntLayerCache.class.getConstructor(int.class);
            ctor.setAccessible(true);
            for (BiomeLayer layer : stack) {
                if (layer instanceof IntBiomeLayer) {
                    cacheField.set(layer, ctor.newInstance(capacity));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Correctness does not depend on this; only speed.
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name);
    }
}
