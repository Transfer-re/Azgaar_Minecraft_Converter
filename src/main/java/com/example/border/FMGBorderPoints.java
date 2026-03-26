package com.example.border;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.fmg.FMGCell;
import com.example.fmg.FMGHeightSampler;
import com.example.fmg.FMGMapData;
import com.example.fmg.FMGProvince;
import com.example.fmg.FMGState;
import com.example.fmg.FMGVertex;

/**
 * Computes border line segments along the FMG Voronoi cell polygons.
 *
 * <p>We treat each cell polygon edge as a border edge if the neighbouring cell across that edge
 * is outside of the current region. This yields connected outlines (like biome border viewers)
 * instead of isolated points.</p>
 */
public final class FMGBorderPoints {

    /**
     * Treat all "no province" cells as one neutral region to avoid fragmented neutral borders
     * (some exports use -1, others 0, and MapData may have null entries).
     */
    public static final int NEUTRAL_PROVINCE_ID = 0;

    // If we have a tiny gap (missing edge(s) from export / float drift / truncation),
    // stitch it shut. Client stair-stepping will remove diagonals visually.
    private static final double CLOSE_GAP_MAX_DIST_BLOCKS = 8.0;

    // Keep a small budget for stitching so we can close gaps even when the border is dense.
    // This avoids the "hit maxSegments then return" failure mode.
    private static final int STITCH_SEGMENT_RESERVE = 16;

    // When center/radius is provided (border viewer), generate borders on the block grid
    // using the same lookup as the F3 debug HUD (findCellAtWorldPos + province/state id).
    // This makes borders match exactly where the HUD label changes.
    private static final boolean USE_F3_MATCHING_BLOCK_GRID = true;

    public record BorderPoint(double worldX, double worldZ) {}

    public record BorderSegment(BorderPoint a, BorderPoint b) {}

    private FMGBorderPoints() {
    }

    public static Result computeProvinceBorderPoints(FMGMapData map, int provinceId, int maxSegments) {
        return computeProvinceBorderPoints(map, provinceId, maxSegments, Double.NaN, Double.NaN, Double.NaN);
    }

    public static Result computeProvinceBorderPoints(
            FMGMapData map,
            int provinceId,
            int maxSegments,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks
    ) {
        if (map == null || map.getInfo() == null || map.getCells() == null || map.getVertices() == null) {
            return Result.empty();
        }

        // Normalize: any non-positive province id is treated as NEUTRAL.
        boolean neutral = provinceId <= 0;
        int normalizedProvinceId = neutral ? NEUTRAL_PROVINCE_ID : provinceId;

        FMGProvince province = neutral ? null : map.getProvince(normalizedProvinceId);

        Set<Integer> memberCells = new HashSet<>();
        if (province != null && province.getCells() != null && !province.getCells().isEmpty()) {
            memberCells.addAll(province.getCells());
        }

        // Always merge in per-cell assignment as a second source of truth.
        // Some exports have incomplete province.getCells(), which causes holes if we only trust that list.
        for (FMGCell cell : map.getCells()) {
            if (cell == null) {
                continue;
            }

            Integer cached = map.getProvinceIdForCell(cell.getI());
            Integer cellProvinceIdObj = cached != null ? cached : cell.getProvince();

            if (neutral) {
                // Neutral = anything that is unassigned / <= 0 / missing.
                int cellProvinceId = cellProvinceIdObj != null ? cellProvinceIdObj : NEUTRAL_PROVINCE_ID;
                if (cellProvinceId <= 0) {
                    memberCells.add(cell.getI());
                }
            } else {
                if (cellProvinceIdObj != null && cellProvinceIdObj == normalizedProvinceId) {
                    memberCells.add(cell.getI());
                }
            }
        }

        int fallbackNeutral = 0x7E7E7E;
        int fallbackUnknown = 0x9E9E9E;

        int colorRgb = FMGBorderUtil.parseRgbOrFallback(
                province != null ? province.getColor() : null,
            neutral ? fallbackNeutral : fallbackUnknown
        );

        String name;
        if (province != null && province.getName() != null && !province.getName().isBlank()) {
            name = province.getName();
        } else if (neutral) {
            name = "Neutral";
        } else {
            name = "Unknown Province";
        }

        boolean doCull = Double.isFinite(centerWorldX) && Double.isFinite(centerWorldZ) && Double.isFinite(radiusBlocks) && radiusBlocks > 0;
        if (USE_F3_MATCHING_BLOCK_GRID && doCull) {
            return computeProvinceBorderSegmentsBlockGrid(map, normalizedProvinceId, neutral, maxSegments, name, colorRgb, centerWorldX, centerWorldZ, radiusBlocks);
        }

        if (memberCells.isEmpty()) {
            return Result.empty();
        }

        return computeBorderSegments(map, memberCells::contains, maxSegments, name, colorRgb, centerWorldX, centerWorldZ, radiusBlocks);
    }

    public static Result computeStateBorderPoints(FMGMapData map, int stateId, int maxSegments) {
        return computeStateBorderPoints(map, stateId, maxSegments, Double.NaN, Double.NaN, Double.NaN);
    }

    public static Result computeStateBorderPoints(
            FMGMapData map,
            int stateId,
            int maxSegments,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks
    ) {
        if (map == null || map.getInfo() == null || map.getCells() == null || map.getVertices() == null) {
            return Result.empty();
        }

        FMGState state = map.getState(stateId);
        int colorRgb = FMGBorderUtil.parseRgbOrFallback(state != null ? state.getColor() : null, 0xFBC02D);
        String name = state != null && state.getName() != null && !state.getName().isBlank() ? state.getName() : "Unknown State";

        boolean doCull = Double.isFinite(centerWorldX) && Double.isFinite(centerWorldZ) && Double.isFinite(radiusBlocks) && radiusBlocks > 0;
        if (USE_F3_MATCHING_BLOCK_GRID && doCull) {
            return computeStateBorderSegmentsBlockGrid(map, stateId, maxSegments, name, colorRgb, centerWorldX, centerWorldZ, radiusBlocks);
        }

        return computeBorderSegments(map, cellId -> {
            FMGCell cell = map.getCell(cellId);
            return cell != null && cell.getState() == stateId;
        }, maxSegments, name, colorRgb, centerWorldX, centerWorldZ, radiusBlocks);
    }

    private static Result computeProvinceBorderSegmentsBlockGrid(
            FMGMapData map,
            int normalizedProvinceId,
            boolean neutral,
            int maxSegments,
            String name,
            int colorRgb,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks
    ) {
        return computeBorderSegmentsBlockGrid(
                maxSegments,
                name,
                colorRgb,
                centerWorldX,
                centerWorldZ,
                radiusBlocks,
                (worldX, worldZ) -> {
                    FMGCell cell = FMGHeightSampler.findCellAtWorldPos(map, worldX, worldZ);
                    if (cell == null) {
                        return false;
                    }
                    Integer provinceIdObj = map.getProvinceIdForCell(cell.getI());
                    int provinceId = provinceIdObj != null ? provinceIdObj : NEUTRAL_PROVINCE_ID;
                    if (provinceId <= 0) {
                        provinceId = NEUTRAL_PROVINCE_ID;
                    }
                    return neutral ? (provinceId == NEUTRAL_PROVINCE_ID) : (provinceId == normalizedProvinceId);
                }
        );
    }

    private static Result computeStateBorderSegmentsBlockGrid(
            FMGMapData map,
            int stateId,
            int maxSegments,
            String name,
            int colorRgb,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks
    ) {
        return computeBorderSegmentsBlockGrid(
                maxSegments,
                name,
                colorRgb,
                centerWorldX,
                centerWorldZ,
                radiusBlocks,
                (worldX, worldZ) -> {
                    FMGCell cell = FMGHeightSampler.findCellAtWorldPos(map, worldX, worldZ);
                    return cell != null && cell.getState() == stateId;
                }
        );
    }

    @FunctionalInterface
    private interface WorldMembership {
        boolean inside(int worldX, int worldZ);
    }

    /**
     * Builds a block-grid outline (axis-aligned) for a region, matching the debug HUD's lookup.
     *
     * <p>We sample membership at integer world X/Z positions (block coordinates). Whenever
     * membership differs across a block edge, we emit that block edge as part of the border.
     * Adjacent unit edges are merged into longer segments to keep segment counts low.</p>
     */
    private static Result computeBorderSegmentsBlockGrid(
            int maxSegments,
            String name,
            int colorRgb,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks,
            WorldMembership membership
    ) {
        int cx = (int) Math.floor(centerWorldX);
        int cz = (int) Math.floor(centerWorldZ);
        int r = (int) Math.ceil(radiusBlocks);

        // Keep the scan window bounded so the server doesn't stall on very large view distances.
        // IMPORTANT: clamp instead of returning empty so the user still sees borders.
        int maxDim = 1024;
        int maxAllowedRadius = (maxDim - 1) / 2;
        if (r > maxAllowedRadius) {
            r = maxAllowedRadius;
        }

        int minX = cx - r;
        int maxX = cx + r;
        int minZ = cz - r;
        int maxZ = cz + r;

        int width = maxX - minX + 1;
        int height = maxZ - minZ + 1;
        if (width <= 1 || height <= 1) {
            return new Result(name, colorRgb, List.of());
        }

        List<BorderSegment> segments = new ArrayList<>(Math.min(maxSegments, 2048));

        boolean[] prevRow = new boolean[width];
        boolean[] row = new boolean[width];

        // Track vertical-edge runs for each x boundary (between x-1 and x)
        int[] vRunStartZ = new int[width];
        for (int i = 0; i < vRunStartZ.length; i++) {
            vRunStartZ[i] = Integer.MIN_VALUE;
        }

        for (int z = minZ; z <= maxZ; z++) {
            int zi = z - minZ;

            // Sample membership for this row.
            for (int x = minX; x <= maxX; x++) {
                int xi = x - minX;
                row[xi] = membership.inside(x, z);
            }

            // Horizontal borders (between prev row z-1 and current row z): merge along X.
            if (zi > 0) {
                int runStartX = Integer.MIN_VALUE;
                int zBoundary = z;
                for (int xi = 0; xi < width; xi++) {
                    boolean a = prevRow[xi];
                    boolean b = row[xi];
                    boolean border = a != b;

                    int worldX = minX + xi;

                    if (border) {
                        if (runStartX == Integer.MIN_VALUE) {
                            runStartX = worldX;
                        }
                    } else {
                        if (runStartX != Integer.MIN_VALUE) {
                            int runEndX = worldX;
                            segments.add(new BorderSegment(new BorderPoint(runStartX, zBoundary), new BorderPoint(runEndX, zBoundary)));
                            if (segments.size() >= maxSegments) {
                                return new Result(name, colorRgb, segments);
                            }
                            runStartX = Integer.MIN_VALUE;
                        }
                    }
                }
                if (runStartX != Integer.MIN_VALUE) {
                    segments.add(new BorderSegment(new BorderPoint(runStartX, zBoundary), new BorderPoint(maxX + 1, zBoundary)));
                    if (segments.size() >= maxSegments) {
                        return new Result(name, colorRgb, segments);
                    }
                }
            }

            // Vertical borders (between x-1 and x): merge along Z using per-column run tracking.
            // We treat each xi as the boundary at worldX = minX + xi (between xi-1 and xi).
            for (int xi = 1; xi < width; xi++) {
                boolean left = row[xi - 1];
                boolean right = row[xi];
                boolean border = left != right;
                int worldXBoundary = minX + xi;

                if (border) {
                    if (vRunStartZ[xi] == Integer.MIN_VALUE) {
                        vRunStartZ[xi] = z;
                    }
                } else {
                    if (vRunStartZ[xi] != Integer.MIN_VALUE) {
                        int startZ = vRunStartZ[xi];
                        int endZ = z;
                        segments.add(new BorderSegment(new BorderPoint(worldXBoundary, startZ), new BorderPoint(worldXBoundary, endZ)));
                        if (segments.size() >= maxSegments) {
                            return new Result(name, colorRgb, segments);
                        }
                        vRunStartZ[xi] = Integer.MIN_VALUE;
                    }
                }
            }

            // Swap rows
            boolean[] tmp = prevRow;
            prevRow = row;
            row = tmp;
        }

        // Flush vertical runs at end of scan.
        for (int xi = 1; xi < width; xi++) {
            if (vRunStartZ[xi] != Integer.MIN_VALUE) {
                int worldXBoundary = minX + xi;
                segments.add(new BorderSegment(new BorderPoint(worldXBoundary, vRunStartZ[xi]), new BorderPoint(worldXBoundary, maxZ + 1)));
                if (segments.size() >= maxSegments) {
                    return new Result(name, colorRgb, segments);
                }
            }
        }

        return new Result(name, colorRgb, segments);
    }

    public interface CellMembership {
        boolean contains(int cellId);
    }

    public record Result(String name, int colorRgb, List<BorderSegment> segments) {
        public static Result empty() {
            return new Result("", 0xFFFFFF, List.of());
        }
    }

    private static Result computeBorderSegments(
            FMGMapData map,
            CellMembership membership,
            int maxSegments,
            String name,
            int colorRgb
    ) {
        return computeBorderSegments(map, membership, maxSegments, name, colorRgb, Double.NaN, Double.NaN, Double.NaN);
    }

    private static Result computeBorderSegments(
            FMGMapData map,
            CellMembership membership,
            int maxSegments,
            String name,
            int colorRgb,
            double centerWorldX,
            double centerWorldZ,
            double radiusBlocks
    ) {
        double scale = FMGHeightSampler.sampleScale();
        double mapW = map.getInfo().getWidth();
        double mapH = map.getInfo().getHeight();
        double offsetX = -(mapW * scale) / 2.0;
        double offsetZ = -(mapH * scale) / 2.0;

        boolean doCull = Double.isFinite(centerWorldX) && Double.isFinite(centerWorldZ) && Double.isFinite(radiusBlocks) && radiusBlocks > 0;
        double radiusSq = radiusBlocks * radiusBlocks;

        // Closure validation/repair: border outlines are closed when every border vertex has even degree.
        // (Typically degree 2, but can be 4+ at junctions.)
        Map<Integer, Integer> borderVertexDegree = new HashMap<>();

        // Cache projected vertex coords (avoid repeated map.getVertex calls and projection math).
        Map<Integer, BorderPoint> projected = new HashMap<>();

        // De-dupe edges by canonicalizing vertex-id pairs.
        Set<Long> seenEdges = new HashSet<>();
        List<BorderSegment> segments = new ArrayList<>(Math.min(maxSegments, 2048));

        // Reserve a little room for stitch segments so closure repair can still run.
        int maxPrimarySegments = maxSegments;
        if (maxSegments > STITCH_SEGMENT_RESERVE + 8) {
            maxPrimarySegments = maxSegments - STITCH_SEGMENT_RESERVE;
        }

        boolean truncated = false;

        for (FMGCell cell : map.getCells()) {
            if (cell == null) {
                continue;
            }
            if (!membership.contains(cell.getI())) {
                continue;
            }

            int[] vs = cell.getV();
            int[] cs = cell.getC();
            if (vs == null || vs.length < 2) {
                continue;
            }
            int n = vs.length;
            boolean alignedNeighbors = cs != null && cs.length == n;

            for (int i = 0; i < n; i++) {
                int neighborCellId = alignedNeighbors ? cs[i] : -1;
                boolean neighborInside = neighborCellId >= 0 && membership.contains(neighborCellId);
                if (neighborInside) {
                    continue;
                }

                int v0Id = vs[i];
                int v1Id = vs[(i + 1) % n];
                if (v0Id == v1Id) {
                    continue;
                }

                int min = Math.min(v0Id, v1Id);
                int max = Math.max(v0Id, v1Id);
                long key = (((long) min) << 32) | (max & 0xFFFF_FFFFL);
                if (!seenEdges.add(key)) {
                    continue;
                }

                BorderPoint a = projected.computeIfAbsent(v0Id, id -> projectVertex(map, id, scale, offsetX, offsetZ));
                BorderPoint b = projected.computeIfAbsent(v1Id, id -> projectVertex(map, id, scale, offsetX, offsetZ));
                if (a == null || b == null) {
                    continue;
                }

                if (doCull && !segmentInRangeXZ(centerWorldX, centerWorldZ, a.worldX(), a.worldZ(), b.worldX(), b.worldZ(), radiusSq)) {
                    continue;
                }

                borderVertexDegree.merge(v0Id, 1, Integer::sum);
                borderVertexDegree.merge(v1Id, 1, Integer::sum);

                segments.add(new BorderSegment(a, b));
                if (segments.size() >= maxPrimarySegments) {
                    truncated = true;
                    break;
                }
            }

            if (truncated) {
                break;
            }
        }

        // Attempt to close small gaps by connecting the two endpoints of any open chain
        // (per connected component). This targets the common "first and last not connected" case.
        int maxAdd = Math.max(0, maxSegments - segments.size());
        if (maxAdd > 0) {
            closeSmallGaps(
                    projected,
                    seenEdges,
                    borderVertexDegree,
                    segments,
                    Math.min(maxAdd, STITCH_SEGMENT_RESERVE),
                    centerWorldX,
                    centerWorldZ,
                    radiusSq,
                    doCull
            );
        }

        // Lightweight reconfirmation: if odd-degree vertices exist, the outline isn't closed.
        // We don't fail hard here; we just try to make diagnosis easier.
        int oddCount = 0;
        for (int degree : borderVertexDegree.values()) {
            if ((degree & 1) != 0) {
                oddCount++;
            }
        }
        if (oddCount > 0) {
            // Keep this as stderr so it shows up even with minimal logging.
            System.err.println("[FMG] Border outline not closed (odd-degree vertices present). name=" + name + " oddVertices=" + oddCount + " segments=" + segments.size());
        }

        return new Result(name, colorRgb, segments);
    }

    private static int closeSmallGaps(
            Map<Integer, BorderPoint> projected,
            Set<Long> seenEdges,
            Map<Integer, Integer> degree,
            List<BorderSegment> segments,
            int maxAdd,
            double centerWorldX,
            double centerWorldZ,
            double radiusSq,
            boolean doCull
    ) {
        Map<BorderPoint, Integer> reverse = new HashMap<>(Math.max(16, projected.size() * 2));
        for (var entry : projected.entrySet()) {
            if (entry.getValue() != null) {
                reverse.put(entry.getValue(), entry.getKey());
            }
        }

        // Build adjacency from current segments.
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (BorderSegment s : segments) {
            Integer vaObj = reverse.get(s.a());
            Integer vbObj = reverse.get(s.b());
            int va = vaObj != null ? vaObj : -1;
            int vb = vbObj != null ? vbObj : -1;
            if (va == -1 || vb == -1) {
                continue;
            }
            adj.computeIfAbsent(va, __ -> new ArrayList<>()).add(vb);
            adj.computeIfAbsent(vb, __ -> new ArrayList<>()).add(va);
        }

        // Collect odd-degree vertices.
        Set<Integer> oddVertices = new HashSet<>();
        for (var entry : degree.entrySet()) {
            if ((entry.getValue() & 1) != 0) {
                oddVertices.add(entry.getKey());
            }
        }
        if (oddVertices.size() < 2) {
            return 0;
        }

        double maxDistSq = CLOSE_GAP_MAX_DIST_BLOCKS * CLOSE_GAP_MAX_DIST_BLOCKS;
        Set<Integer> visited = new HashSet<>();
        int added = 0;

        for (int start : adj.keySet()) {
            if (added >= maxAdd) {
                break;
            }
            if (!visited.add(start)) {
                continue;
            }

            // BFS component
            ArrayList<Integer> queue = new ArrayList<>();
            queue.add(start);
            int qIdx = 0;
            List<Integer> componentOdd = new ArrayList<>(2);

            while (qIdx < queue.size()) {
                int v = queue.get(qIdx++);
                if (oddVertices.contains(v)) {
                    componentOdd.add(v);
                }
                List<Integer> ns = adj.get(v);
                if (ns == null) {
                    continue;
                }
                for (int n : ns) {
                    if (visited.add(n)) {
                        queue.add(n);
                    }
                }
            }

            // Typical open chain: exactly 2 odd endpoints in the component.
            if (componentOdd.size() != 2) {
                continue;
            }

            int v0 = componentOdd.get(0);
            int v1 = componentOdd.get(1);
            BorderPoint p0 = projected.get(v0);
            BorderPoint p1 = projected.get(v1);
            if (p0 == null || p1 == null) {
                continue;
            }

            double dx = p0.worldX() - p1.worldX();
            double dz = p0.worldZ() - p1.worldZ();
            double distSq = dx * dx + dz * dz;
            if (distSq > maxDistSq) {
                continue;
            }

            if (doCull && !segmentInRangeXZ(centerWorldX, centerWorldZ, p0.worldX(), p0.worldZ(), p1.worldX(), p1.worldZ(), radiusSq)) {
                continue;
            }

            int min = Math.min(v0, v1);
            int max = Math.max(v0, v1);
            long key = (((long) min) << 32) | (max & 0xFFFF_FFFFL);
            if (!seenEdges.add(key)) {
                continue;
            }

            segments.add(new BorderSegment(p0, p1));
            degree.merge(v0, 1, Integer::sum);
            degree.merge(v1, 1, Integer::sum);
            added++;
        }

        return added;
    }

    private static boolean segmentInRangeXZ(
            double centerX,
            double centerZ,
            double x0,
            double z0,
            double x1,
            double z1,
            double rangeSq
    ) {
        double minX = Math.min(x0, x1);
        double maxX = Math.max(x0, x1);
        double minZ = Math.min(z0, z1);
        double maxZ = Math.max(z0, z1);

        double closestX = centerX < minX ? minX : (centerX > maxX ? maxX : centerX);
        double closestZ = centerZ < minZ ? minZ : (centerZ > maxZ ? maxZ : centerZ);

        double dx = centerX - closestX;
        double dz = centerZ - closestZ;
        return dx * dx + dz * dz <= rangeSq;
    }

    private static BorderPoint projectVertex(FMGMapData map, int vertexId, double scale, double offsetX, double offsetZ) {
        FMGVertex v = map.getVertex(vertexId);
        if (v == null) {
            return null;
        }
        double worldX = v.getPx() * scale + offsetX;
        double worldZ = v.getPy() * scale + offsetZ;
        return new BorderPoint(worldX, worldZ);
    }
}
