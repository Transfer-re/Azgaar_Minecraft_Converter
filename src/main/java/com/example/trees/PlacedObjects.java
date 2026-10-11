package com.example.trees;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.util.math.ChunkPos;

/**
 * Where objects already stand, for the spacing rules of TreeGenerator's
 * WORLDGEN.md:
 *
 * <ul>
 *   <li>trees and dead trees: {@code distance >= k * (crown_a + crown_b)};</li>
 *   <li>shrubs: at least {@code tree base_radius + 1} from trunks, and
 *       {@code 0.7 * (r_a + r_b)} from each other;</li>
 *   <li>fallen logs: 2 blocks or more from trunks and from each other.</li>
 * </ul>
 *
 * <p>Vanilla features see only blocks, and blocks do not say how wide the crown
 * they belong to is, so each placement is recorded here: its paste point, layer,
 * radii and ground footprint. Neighbouring chunks decorate later and consult it.
 *
 * <p>This is a working set, not a record. It holds the generation front --
 * {@link #MAX_CHUNKS} chunks of it, oldest dropped first -- and is not saved, so
 * after a restart the first new chunks next to old ones do not see the old
 * objects here. The placer's block check (no wood into existing wood) still
 * holds there, so the worst case is a crown closer than the spacing would allow.
 */
final class PlacedObjects {

    static final byte TREE = 0, DEAD = 1, LOG = 2, SHRUB = 3;
    private static final int MAX_CHUNKS = 40_000;
    /** Largest crown radius in the set (vaultwood, 25.3), rounded up. */
    static final float MAX_CROWN = 26f;

    /** One placed object. The footprint is its rotated extent at ground level. */
    record Placed(int x, int z, byte layer, float crown, float base,
                  int minX, int maxX, int minZ, int maxZ) {
    }

    private final Map<Long, List<Placed>> byChunk = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> order = new ConcurrentLinkedQueue<>();

    private static final Map<Object, PlacedObjects> WORLDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static PlacedObjects of(Object world) {
        return WORLDS.computeIfAbsent(world, w -> new PlacedObjects());
    }

    static void clearAll() {
        WORLDS.clear();
    }

    void add(Placed p) {
        long key = ChunkPos.toLong(p.x() >> 4, p.z() >> 4);
        List<Placed> list = byChunk.get(key);
        if (list == null) {
            List<Placed> fresh = new CopyOnWriteArrayList<>();
            list = byChunk.putIfAbsent(key, fresh);
            if (list == null) {
                list = fresh;
                order.add(key);
                while (byChunk.size() > MAX_CHUNKS) {
                    Long old = order.poll();
                    if (old == null) break;
                    byChunk.remove(old);
                }
            }
        }
        list.add(p);
    }

    /**
     * True if {@code c} keeps its distance from everything recorded.
     *
     * @param k the stand's spacing factor (trees and dead trees), or the shrub one
     */
    boolean clear(Placed c, float k) {
        int reach = (int) Math.ceil(Math.max(k, 1.2f) * (c.crown() + MAX_CROWN)) + 4;
        int cx0 = (c.x() - reach) >> 4, cx1 = (c.x() + reach) >> 4;
        int cz0 = (c.z() - reach) >> 4, cz1 = (c.z() + reach) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                List<Placed> list = byChunk.get(ChunkPos.toLong(cx, cz));
                if (list == null) continue;
                for (Placed p : list) {
                    if (conflicts(c, p, k)) return false;
                }
            }
        }
        return true;
    }

    private static boolean conflicts(Placed c, Placed p, float k) {
        double d = Math.hypot(c.x() - p.x(), c.z() - p.z());
        boolean cStanding = c.layer() == TREE || c.layer() == DEAD;
        boolean pStanding = p.layer() == TREE || p.layer() == DEAD;
        if (cStanding && pStanding) {
            return d < k * (c.crown() + p.crown());
        }
        if (c.layer() == SHRUB && p.layer() == SHRUB) {
            return d < k * (c.crown() + p.crown());
        }
        if (c.layer() == LOG && p.layer() == LOG) {
            return rectGap(c, p) < 2;
        }
        if (c.layer() == LOG) {
            return distToRect(p.x(), p.z(), c) < p.base() + 2;
        }
        if (p.layer() == LOG) {
            return distToRect(c.x(), c.z(), p) < c.base() + 1;
        }
        // a shrub and a standing tree: keep the shrub off the trunk
        return d < c.base() + p.base() + 1;
    }

    private static double distToRect(int x, int z, Placed r) {
        double dx = Math.max(0, Math.max(r.minX() - x, x - r.maxX()));
        double dz = Math.max(0, Math.max(r.minZ() - z, z - r.maxZ()));
        return Math.hypot(dx, dz);
    }

    private static int rectGap(Placed a, Placed b) {
        int gx = Math.max(a.minX() - b.maxX(), b.minX() - a.maxX()) - 1;
        int gz = Math.max(a.minZ() - b.maxZ(), b.minZ() - a.maxZ()) - 1;
        return Math.max(gx, gz);
    }
}
