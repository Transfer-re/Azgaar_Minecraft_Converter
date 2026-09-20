package com.example.fmg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.util.math.ChunkPos;

/**
 * Chunk-bucketed spatial index of every FMG route spline segment, in world
 * coordinates. Built once per FMG export and cached.
 *
 * <p>{@link com.example.worldgen.ImageMapChunkGenerator}'s terrain pass and
 * {@link FMGRiverNetwork}'s road-crossing detection both need "which route
 * segments are near this area" -- without an index they were each iterating all
 * ~200k segments of the whole map for every chunk / river sample.
 */
public final class FMGRouteIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("FMGRouteIndex");
    private static final Map<String, FMGRouteIndex> CACHE = new ConcurrentHashMap<>();

    /** Over-inclusive; a segment lands in every chunk its inflated AABB touches. */
    private static final double STAMP_RADIUS = 10.0;

    /** One spline segment a->b in world coords, plus which route + sample it came from. */
    public static final class Seg {
        public final int routeId;
        public final FMGRouteSampler.RouteKind kind;
        public final double ax, az, bx, bz;
        public final int sampleIndex;

        Seg(int routeId, FMGRouteSampler.RouteKind kind, double ax, double az, double bx, double bz, int sampleIndex) {
            this.routeId = routeId;
            this.kind = kind;
            this.ax = ax;
            this.az = az;
            this.bx = bx;
            this.bz = bz;
            this.sampleIndex = sampleIndex;
        }
    }

    private final Map<Long, List<Seg>> byChunk = new HashMap<>();

    private FMGRouteIndex() {
    }

    public static FMGRouteIndex get(String fmgExport, FMGMapData map) {
        FMGRouteIndex idx = CACHE.get(fmgExport);
        if (idx != null) return idx;
        synchronized (CACHE) {
            idx = CACHE.get(fmgExport);
            if (idx != null) return idx;
            idx = build(map);
            CACHE.put(fmgExport, idx);
            return idx;
        }
    }

    public static void invalidate() {
        CACHE.clear();
    }

    private static FMGRouteIndex build(FMGMapData map) {
        long t0 = System.currentTimeMillis();
        FMGRouteIndex idx = new FMGRouteIndex();
        if (map == null || map.getInfo() == null || map.getRoutes() == null) {
            return idx;
        }
        double scale = FMGHeightSampler.sampleScale();
        double offX = -(map.getInfo().getWidth() * scale) / 2.0;
        double offZ = -(map.getInfo().getHeight() * scale) / 2.0;

        int segCount = 0;
        for (FMGRoute route : map.getRoutes()) {
            if (route == null || route.getGroup() == null) continue;
            FMGRouteSampler.RouteKind kind;
            if ("roads".equals(route.getGroup())) kind = FMGRouteSampler.RouteKind.ROAD;
            else if ("trails".equals(route.getGroup())) kind = FMGRouteSampler.RouteKind.TRAIL;
            else continue;

            List<FMGRouteSampler.RouteSample> samples = FMGRouteSampler.getSampledRoute(route.getI());
            if (samples == null || samples.size() < 2) continue;

            double ax = samples.get(0).x() * scale + offX;
            double az = samples.get(0).y() * scale + offZ;
            for (int si = 1; si < samples.size(); si++) {
                double bx = samples.get(si).x() * scale + offX;
                double bz = samples.get(si).y() * scale + offZ;
                idx.add(new Seg(route.getI(), kind, ax, az, bx, bz, si - 1));
                segCount++;
                ax = bx;
                az = bz;
            }
        }
        LOGGER.info("FMGRouteIndex build: {} segments, {} buckets, {} ms",
                segCount, idx.byChunk.size(), System.currentTimeMillis() - t0);
        return idx;
    }

    private void add(Seg s) {
        int cxLo = ((int) Math.floor(Math.min(s.ax, s.bx) - STAMP_RADIUS)) >> 4;
        int cxHi = ((int) Math.floor(Math.max(s.ax, s.bx) + STAMP_RADIUS)) >> 4;
        int czLo = ((int) Math.floor(Math.min(s.az, s.bz) - STAMP_RADIUS)) >> 4;
        int czHi = ((int) Math.floor(Math.max(s.az, s.bz) + STAMP_RADIUS)) >> 4;
        for (int cx = cxLo; cx <= cxHi; cx++) {
            for (int cz = czLo; cz <= czHi; cz++) {
                byChunk.computeIfAbsent(ChunkPos.toLong(cx, cz), k -> new ArrayList<>(4)).add(s);
            }
        }
    }

    // ---- queries ----

    /**
     * Every segment in the chunk buckets overlapping the world box
     * {@code [x0,x1] x [z0,z1]}. A segment straddling a bucket edge appears
     * more than once -- callers must be idempotent per segment (both are).
     */
    public void collectSegments(int x0, int z0, int x1, int z1, List<Seg> out) {
        int cxLo = x0 >> 4, cxHi = x1 >> 4;
        int czLo = z0 >> 4, czHi = z1 >> 4;
        for (int cx = cxLo; cx <= cxHi; cx++) {
            for (int cz = czLo; cz <= czHi; cz++) {
                List<Seg> b = byChunk.get(ChunkPos.toLong(cx, cz));
                if (b != null) out.addAll(b);
            }
        }
    }

    /** True if any route segment passes within {@code radius} blocks of {@code (x,z)}. */
    public boolean anyWithin(double x, double z, double radius) {
        if (byChunk.isEmpty()) return false;
        int reach = (int) Math.ceil(radius / 16.0) + 1;
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        double r2 = radius * radius;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                List<Seg> b = byChunk.get(ChunkPos.toLong(cx + dx, cz + dz));
                if (b == null) continue;
                for (Seg s : b) {
                    if (distSqToSegment(x, z, s) <= r2) return true;
                }
            }
        }
        return false;
    }

    private static double distSqToSegment(double px, double pz, Seg s) {
        double vx = s.bx - s.ax, vz = s.bz - s.az;
        double lenSq = vx * vx + vz * vz;
        double t = lenSq <= 0 ? 0 : ((px - s.ax) * vx + (pz - s.az) * vz) / lenSq;
        t = t < 0 ? 0 : (t > 1 ? 1 : t);
        double dx = px - (s.ax + t * vx);
        double dz = pz - (s.az + t * vz);
        return dx * dx + dz * dz;
    }

    public boolean isEmpty() {
        return byChunk.isEmpty();
    }

    // ---- bake persistence ----

    /** Install a pre-built (baked) index so {@link #get} skips the rebuild. */
    public static void seed(String fmgExport, FMGRouteIndex idx) {
        CACHE.putIfAbsent(fmgExport, idx);
    }

    public void writeTo(java.io.DataOutputStream out) throws java.io.IOException {
        java.util.Set<Seg> unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (List<Seg> bucket : byChunk.values()) {
            unique.addAll(bucket);
        }
        out.writeInt(unique.size());
        for (Seg s : unique) {
            out.writeInt(s.routeId);
            out.writeByte(s.kind.ordinal());
            out.writeDouble(s.ax);
            out.writeDouble(s.az);
            out.writeDouble(s.bx);
            out.writeDouble(s.bz);
            out.writeInt(s.sampleIndex);
        }
    }

    public static FMGRouteIndex readFrom(java.io.DataInputStream in) throws java.io.IOException {
        FMGRouteIndex idx = new FMGRouteIndex();
        FMGRouteSampler.RouteKind[] kinds = FMGRouteSampler.RouteKind.values();
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            int routeId = in.readInt();
            FMGRouteSampler.RouteKind kind = kinds[in.readUnsignedByte()];
            double ax = in.readDouble();
            double az = in.readDouble();
            double bx = in.readDouble();
            double bz = in.readDouble();
            int sampleIndex = in.readInt();
            idx.add(new Seg(routeId, kind, ax, az, bx, bz, sampleIndex));
        }
        return idx;
    }
}
