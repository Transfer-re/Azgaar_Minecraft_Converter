package com.example.towns;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;



public class CustomSettlementGenerator {
   // ---- terrain cell types (input) ----------------------------------
    public static final int BUILDABLE = 0;
    public static final int OCEAN = 1;
    public static final int RIVER = 2;
    public static final int MAIN_PATH = 3;
    public static final int BRIDGE = 4; // optional: caller can pre-mark known bridge cells; the
                                         // generator also auto-detects road/water crossings itself

    // ---- output cell types (superimposed on terrain) ------------------
    public static final int OUT_TERRAIN = 0;          // unchanged terrain (grass etc.)
    public static final int OUT_OCEAN = 1;
    public static final int OUT_RIVER = 2;
    public static final int OUT_MAIN_PATH = 3;
    public static final int OUT_CONNECTOR_PATH = 4;
    public static final int OUT_BUILDING_WALL = 5;
    public static final int OUT_BUILDING_FLOOR = 6;
    public static final int OUT_DOOR = 7;
    public static final int OUT_CITY_WALL = 8;
    public static final int OUT_GATE = 9;
    public static final int OUT_CITADEL_WALL = 10;
    public static final int OUT_CITADEL_FLOOR = 11;
    public static final int OUT_FORT_WALL = 12;
    public static final int OUT_FORT_FLOOR = 13;
    public static final int OUT_FARM_FENCE = 14;
    public static final int OUT_FARM_FIELD = 15;
    public static final int OUT_TOWER = 16;           // tower wall
    public static final int OUT_TOWER_FLOOR = 17;
    public static final int OUT_MARKET_WALL = 18;
    public static final int OUT_MARKET_FLOOR = 19;
    public static final int OUT_CHURCH_WALL = 20;
    public static final int OUT_CHURCH_FLOOR = 21;
    public static final int OUT_BRIDGE = 22;
    public static final int OUT_DOCK = 23;
    public static final int OUT_PREBUILT_WALL = 24;
    public static final int OUT_PREBUILT_FLOOR = 25;
    public static final int OUT_FARM_PATH = 26;

    private static final int[][] DIRS4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] MOORE_DIRS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    // ===================================================================
    // basic value types
    // ===================================================================

    /** Grid coordinate. Comparable the same way Python compares (x, y) tuples. */
    public record Point(int x, int y) implements Comparable<Point> {
        @Override
        public int compareTo(Point o) {
            if (x != o.x) return Integer.compare(x, o.x);
            return Integer.compare(y, o.y);
        }
    }

    /** Axis-aligned rectangle: top-left (x, y) plus width/height. */
    public record Rect(int x, int y, int w, int h) {}

    /** A settlement seed: center point + cost-distance growth radius. */
    public record Town(int cx, int cy, double radius) {}

    /** Caller-supplied fixed-size building to be stamped into a gap. */
    public static class PrebuiltStructure {
        public String name;
        public int width;
        public int height;
        public PrebuiltStructure(String name, int width, int height) {
            this.name = name;
            this.width = width;
            this.height = height;
        }
    }

    public static class PlacedStructure {
        public String name;
        public Rect rect;
        public PlacedStructure(String name, Rect rect) {
            this.name = name;
            this.rect = rect;
        }
    }

    public static class Tower {
        public int x0, y0, w, h;
        public Point entrance;
        public Tower(int x0, int y0, int w, int h, Point entrance) {
            this.x0 = x0; this.y0 = y0; this.w = w; this.h = h; this.entrance = entrance;
        }
    }

    public static class WallPocketFort {
        public Rect rect;
        public Set<Point> attach;
        public WallPocketFort(Rect rect, Set<Point> attach) {
            this.rect = rect;
            this.attach = attach;
        }
    }

    public static class Dock {
        public String kind;
        public List<Point> cells;
        public Point origin;
        public Dock(String kind, List<Point> cells, Point origin) {
            this.kind = kind;
            this.cells = cells;
            this.origin = origin;
        }
    }

    /** Everything generate_settlement used to stash in its Python result dict. */
    public static class Result {
        public IntGrid out;
        public BoolGrid extent;
        public List<Rect> buildings;
        public Map<Integer, String> landmarks = new LinkedHashMap<>();
        public Set<Point> wall;                 // null unless wall=true
        public Set<Point> gates;                // null unless wall=true
        public List<Tower> towers;              // null unless wall=true
        public WallPocketFort wallPocketFort;   // null unless wall=true and one was placed
        public List<PlacedStructure> placedStructures = new ArrayList<>();
        public List<String> unplacedStructures = new ArrayList<>();
        /**
         * Chosen door per building, keyed by index into {@code buildings} for
         * {@code i < buildings.size()}, then by index into {@code placedStructures}
         * for the rest (same order {@code connectBuildingsToNetwork} was called
         * with). Absent for buildings that never got a road connection.
         */
        public Map<Integer, DoorInfo> doors = Map.of();
        public List<Dock> docks;                // null unless wall && docks
        public BoolGrid farmFields;             // null unless wall=true and farms=true
        public BoolGrid farmPaths;              // null unless wall=true and farms=true
    }

    // ===================================================================
    // RNG helper (java.util.Random doesn't reproduce Python's Mersenne
    // Twister bit-for-bit, but exposes the same operations Python's
    // random.Random provides: randint, random(), uniform, shuffle, choice)
    // ===================================================================
    public static class Rng {
        private final Random r;

        public Rng(Long seed) {
            this.r = (seed == null) ? new Random() : new Random(seed);
        }

        /** Inclusive on both ends, like Python's random.randint(a, b). */
        public int randint(int lo, int hi) {
            return lo + r.nextInt(hi - lo + 1);
        }

        /** [0.0, 1.0), like Python's random.random(). */
        public double random() {
            return r.nextDouble();
        }

        public double uniform(double lo, double hi) {
            return lo + r.nextDouble() * (hi - lo);
        }

        public <T> void shuffle(List<T> list) {
            Collections.shuffle(list, r);
        }

        public <T> T choice(List<T> list) {
            return list.get(r.nextInt(list.size()));
        }
    }

    // ===================================================================
    // grid wrappers (stand-in for numpy boolean/int arrays); data is
    // stored [y][x], matching numpy's (row, col) = (y, x) convention
    // ===================================================================
    public static class BoolGrid {
        public final int h, w;
        public final boolean[][] d;

        public BoolGrid(int h, int w) {
            this.h = h;
            this.w = w;
            this.d = new boolean[h][w];
        }

        public boolean get(int x, int y) { return d[y][x]; }
        public void set(int x, int y, boolean v) { d[y][x] = v; }
        public boolean inBounds(int x, int y) { return x >= 0 && x < w && y >= 0 && y < h; }

        public BoolGrid copy() {
            BoolGrid c = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) c.d[y] = d[y].clone();
            return c;
        }

        public BoolGrid or(BoolGrid o) {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = d[y][x] || o.d[y][x];
            return res;
        }

        public void orInPlace(BoolGrid o) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) d[y][x] = d[y][x] || o.d[y][x];
        }

        public BoolGrid and(BoolGrid o) {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = d[y][x] && o.d[y][x];
            return res;
        }

        public void andInPlace(BoolGrid o) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) d[y][x] = d[y][x] && o.d[y][x];
        }

        /** this & ~o */
        public BoolGrid andNot(BoolGrid o) {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = d[y][x] && !o.d[y][x];
            return res;
        }

        public BoolGrid not() {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = !d[y][x];
            return res;
        }

        public int sum() {
            int c = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (d[y][x]) c++;
            return c;
        }

        public boolean any() {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (d[y][x]) return true;
            return false;
        }

        public List<Point> where() {
            List<Point> pts = new ArrayList<>();
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (d[y][x]) pts.add(new Point(x, y));
            return pts;
        }
    }

    public static class IntGrid {
        public final int h, w;
        public final int[][] d;

        public IntGrid(int h, int w) {
            this.h = h;
            this.w = w;
            this.d = new int[h][w];
        }

        public int get(int x, int y) { return d[y][x]; }
        public void set(int x, int y, int v) { d[y][x] = v; }
        public boolean inBounds(int x, int y) { return x >= 0 && x < w && y >= 0 && y < h; }

        public IntGrid copy() {
            IntGrid c = new IntGrid(h, w);
            for (int y = 0; y < h; y++) c.d[y] = d[y].clone();
            return c;
        }

        public BoolGrid maskEquals(int v) {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = (d[y][x] == v);
            return res;
        }

        public BoolGrid maskNotEquals(int v) {
            BoolGrid res = new BoolGrid(h, w);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) res.d[y][x] = (d[y][x] != v);
            return res;
        }
    }

    // ===================================================================
    // small utilities
    // ===================================================================
    public static boolean water(int v) { return v == OCEAN || v == RIVER; }
    private static boolean hardBlocked(int v) { return v == OCEAN; }

    /** Rounds half-to-even, matching Python's round(). */
    private static long pyRound(double x) {
        double floor = Math.floor(x);
        double diff = x - floor;
        long f = (long) floor;
        if (diff < 0.5) return f;
        if (diff > 0.5) return f + 1;
        return (f % 2 == 0) ? f : f + 1;
    }

    // ===================================================================
    // 1. settlement extent: per-town, radius-limited growth from a center
    //    point. Distance is accumulated cost, not raw geometry -- stepping
    //    along a MAIN_PATH cell is cheap, so the radius reaches noticeably
    //    further along roads than it does through open terrain, and water
    //    is impassable so it can never be crossed regardless of radius.
    // ===================================================================

    private record DijkstraNode(double d, int x, int y) implements Comparable<DijkstraNode> {
        @Override
        public int compareTo(DijkstraNode o) {
            int c = Double.compare(d, o.d);
            if (c != 0) return c;
            c = Integer.compare(x, o.x);
            if (c != 0) return c;
            return Integer.compare(y, o.y);
        }
    }

    public static BoolGrid growTown(IntGrid terrain, int centerX, int centerY, double radius, Rng rng,
                                     double pathCost, double jitterFrac) {
        int h = terrain.h, w = terrain.w;
        double[][] cost = new double[h][w];
        for (double[] row : cost) Arrays.fill(row, Double.POSITIVE_INFINITY);
        boolean[][] visited = new boolean[h][w];
        BoolGrid claimed = new BoolGrid(h, w);

        int cx = centerX, cy = centerY;
        if (!terrain.inBounds(cx, cy) || hardBlocked(terrain.get(cx, cy))) {
            // snap to nearest non-ocean cell
            Deque<int[]> q = new ArrayDeque<>();
            q.add(new int[]{cx, cy, 0});
            Set<Point> seen = new HashSet<>();
            seen.add(new Point(cx, cy));
            int[] found = null;
            while (!q.isEmpty()) {
                int[] cur = q.poll();
                int x = cur[0], y = cur[1], d = cur[2];
                if (terrain.inBounds(x, y) && !hardBlocked(terrain.get(x, y))) {
                    found = new int[]{x, y};
                    break;
                }
                if (d > 8) continue;
                for (int[] dir : DIRS4) {
                    int nx = x + dir[0], ny = y + dir[1];
                    Point np = new Point(nx, ny);
                    if (!seen.contains(np)) {
                        seen.add(np);
                        q.add(new int[]{nx, ny, d + 1});
                    }
                }
            }
            if (found == null) return claimed;
            cx = found[0];
            cy = found[1];
        }

        cost[cy][cx] = 0.0;
        PriorityQueue<DijkstraNode> heap = new PriorityQueue<>();
        heap.add(new DijkstraNode(0.0, cx, cy));
        while (!heap.isEmpty()) {
            DijkstraNode node = heap.poll();
            double d = node.d();
            int x = node.x(), y = node.y();
            if (visited[y][x]) continue;
            visited[y][x] = true;
            if (d > radius) continue;
            int t0 = terrain.get(x, y);
            if (t0 == BUILDABLE || t0 == RIVER) claimed.set(x, y, true);
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (!terrain.inBounds(nx, ny) || visited[ny][nx]) continue;
                int t = terrain.get(nx, ny);
                if (hardBlocked(t)) continue;
                double step = (t == MAIN_PATH) ? pathCost : 1.0;
                step *= (1.0 + rng.uniform(-jitterFrac, jitterFrac));
                double nd = d + step;
                if (nd <= radius && nd < cost[ny][nx]) {
                    cost[ny][nx] = nd;
                    heap.add(new DijkstraNode(nd, nx, ny));
                }
            }
        }
        return claimed;
    }

    public static BoolGrid growTown(IntGrid terrain, int centerX, int centerY, double radius, Rng rng) {
        return growTown(terrain, centerX, centerY, radius, rng, 0.35, 0.12);
    }

    /** towns: list of center + radius. Returns the union of every town's claimed-cell mask. */
    public static BoolGrid growExtent(IntGrid terrain, List<Town> towns, Rng rng) {
        BoolGrid extent = new BoolGrid(terrain.h, terrain.w);
        for (Town town : towns) {
            extent.orInPlace(growTown(terrain, town.cx(), town.cy(), town.radius(), rng));
        }
        return extent;
    }

    /** 4-connected components of a boolean grid -> list of boolean masks. */
    public static List<BoolGrid> connectedComponents(BoolGrid mask) {
        int h = mask.h, w = mask.w;
        boolean[][] seen = new boolean[h][w];
        List<BoolGrid> comps = new ArrayList<>();
        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                if (!mask.d[sy][sx] || seen[sy][sx]) continue;
                BoolGrid comp = new BoolGrid(h, w);
                Deque<Point> q = new ArrayDeque<>();
                q.add(new Point(sx, sy));
                seen[sy][sx] = true;
                while (!q.isEmpty()) {
                    Point p = q.poll();
                    comp.set(p.x(), p.y(), true);
                    for (int[] dir : DIRS4) {
                        int nx = p.x() + dir[0], ny = p.y() + dir[1];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && mask.d[ny][nx] && !seen[ny][nx]) {
                            seen[ny][nx] = true;
                            q.add(new Point(nx, ny));
                        }
                    }
                }
                comps.add(comp);
            }
        }
        return comps;
    }

    // ===================================================================
    // 2. BSP subdivision of the extent's bounding box into district blocks
    // ===================================================================
    public static class District {
        public int x0, y0, x1, y1;
        public District(int x0, int y0, int x1, int y1) {
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
        }
        public int w() { return x1 - x0; }
        public int h() { return y1 - y0; }
        public int cx() { return (x0 + x1) / 2; }
        public int cy() { return (y0 + y1) / 2; }
    }

    public static List<District> bspDistricts(BoolGrid extent, Rng rng, int minBlock, int maxBlock, int streetGap) {
        List<Point> pts = extent.where();
        if (pts.isEmpty()) return new ArrayList<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (Point p : pts) {
            x0 = Math.min(x0, p.x());
            x1 = Math.max(x1, p.x());
            y0 = Math.min(y0, p.y());
            y1 = Math.max(y1, p.y());
        }
        x1 += 1;
        y1 += 1;
        District root = new District(x0, y0, x1, y1);

        List<District> leaves = new ArrayList<>();
        Deque<District> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            District d = stack.pop();
            boolean canSplitX = d.w() > maxBlock;
            boolean canSplitY = d.h() > maxBlock;
            if (!canSplitX && !canSplitY) {
                leaves.add(d);
                continue;
            }
            boolean splitVertical;
            if (canSplitX && canSplitY) splitVertical = d.w() >= d.h();
            else splitVertical = canSplitX;

            if (splitVertical) {
                int lo = d.x0 + minBlock;
                int hi = d.x1 - minBlock - streetGap;
                if (lo >= hi) { leaves.add(d); continue; }
                int cut = rng.randint(lo, hi);
                stack.push(new District(d.x0, d.y0, cut, d.y1));
                stack.push(new District(cut + streetGap, d.y0, d.x1, d.y1));
            } else {
                int lo = d.y0 + minBlock;
                int hi = d.y1 - minBlock - streetGap;
                if (lo >= hi) { leaves.add(d); continue; }
                int cut = rng.randint(lo, hi);
                stack.push(new District(d.x0, d.y0, d.x1, cut));
                stack.push(new District(d.x0, cut + streetGap, d.x1, d.y1));
            }
        }
        return leaves;
    }

    public static List<District> bspDistricts(BoolGrid extent, Rng rng) {
        return bspDistricts(extent, rng, 12, 26, 2);
    }

    // ===================================================================
    // 3. greedy rectangular building packing inside a district
    // ===================================================================

    /**
     * Greedy random rectangle packing with shrink-to-fit, run in several
     * size tiers (biggest first) so leftover gaps get filled with
     * progressively smaller buildings instead of staying empty. Tiers are
     * derived from minSize/maxSize so minSize is an actual floor -- no
     * tier ever goes below it.
     */
    public static List<Rect> packBuildings(District district, BoolGrid extent, BoolGrid occupied, Rng rng,
                                            int minSize, int maxSize, int margin, List<int[]> infillTiersIn) {
        List<int[]> infillTiers = infillTiersIn;
        if (infillTiers == null) {
            infillTiers = new ArrayList<>();
            int hi = maxSize;
            while (true) {
                int lo = Math.max(minSize, hi - 4);
                infillTiers.add(new int[]{lo, hi});
                if (lo <= minSize) break;
                hi = lo;
            }
        }

        List<Rect> buildings = new ArrayList<>();
        int x0 = district.x0, y0 = district.y0, x1 = district.x1, y1 = district.y1;

        for (int[] tier : infillTiers) {
            int tierMin = tier[0], tierMax = tier[1];
            List<Point> cells = new ArrayList<>();
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) cells.add(new Point(x, y));
            rng.shuffle(cells);
            for (Point sp : cells) {
                int sx = sp.x(), sy = sp.y();
                if (!(extent.inBounds(sx, sy) && extent.get(sx, sy) && !occupied.get(sx, sy))) continue;
                int bw = rng.randint(tierMin, tierMax);
                int bh = rng.randint(tierMin, tierMax);
                bw = Math.min(bw, x1 - sx);
                bh = Math.min(bh, y1 - sy);
                if (bw < minSize || bh < minSize) continue;
                boolean ok = false;
                while (bw >= minSize && bh >= minSize) {
                    boolean fits = true;
                    outer:
                    for (int yy = sy - margin; yy < sy + bh + margin; yy++) {
                        for (int xx = sx - margin; xx < sx + bw + margin; xx++) {
                            if (!extent.inBounds(xx, yy) || !extent.get(xx, yy)) { fits = false; break outer; }
                            if (occupied.get(xx, yy)) { fits = false; break outer; }
                        }
                    }
                    if (fits) { ok = true; break; }
                    if (bw > bh) bw -= 1; else bh -= 1;
                }
                if (!ok) continue;
                for (int yy = sy - margin; yy < sy + bh + margin; yy++) {
                    for (int xx = sx - margin; xx < sx + bw + margin; xx++) {
                        if (extent.inBounds(xx, yy)) occupied.set(xx, yy, true);
                    }
                }
                buildings.add(new Rect(sx, sy, bw, bh));
            }
        }
        return buildings;
    }

    public static List<Rect> packBuildings(District district, BoolGrid extent, BoolGrid occupied, Rng rng) {
        return packBuildings(district, extent, occupied, rng, 5, 10, 1, null);
    }

    // ===================================================================
    // 4. connect districts / buildings with thin paths (A* on free cells)
    // ===================================================================

    private record AStarNode(int f, int g, int x, int y) implements Comparable<AStarNode> {
        @Override
        public int compareTo(AStarNode o) {
            int c = Integer.compare(f, o.f);
            if (c != 0) return c;
            c = Integer.compare(g, o.g);
            if (c != 0) return c;
            c = Integer.compare(x, o.x);
            if (c != 0) return c;
            return Integer.compare(y, o.y);
        }
    }

    public static List<Point> aStar(Point start, Point goal, BoolGrid blocked, IntGrid terrain) {
        return aStar(start, goal, blocked, terrain, 0, 0, terrain.w - 1, terrain.h - 1);
    }

    /**
     * As {@link #aStar(Point, Point, BoolGrid, IntGrid)} but expansion is
     * confined to the cell window [minX..maxX] x [minY..maxY] (clamped to the
     * grid). Callers that know start and goal are close pass a tight window so
     * a single failed route can't fan out across the whole map.
     */
    public static List<Point> aStar(Point start, Point goal, BoolGrid blocked, IntGrid terrain,
                                     int minX, int minY, int maxX, int maxY) {
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(terrain.w - 1, maxX);
        maxY = Math.min(terrain.h - 1, maxY);

        PriorityQueue<AStarNode> open = new PriorityQueue<>();
        open.add(new AStarNode(heuristic(start, goal), 0, start.x(), start.y()));
        Map<Point, Point> cameFrom = new HashMap<>();
        Map<Point, Integer> gScore = new HashMap<>();
        gScore.put(start, 0);
        Set<Point> visited = new HashSet<>();

        while (!open.isEmpty()) {
            AStarNode node = open.poll();
            Point cur = new Point(node.x(), node.y());
            if (visited.contains(cur)) continue;
            visited.add(cur);
            if (cur.equals(goal)) {
                List<Point> path = new ArrayList<>();
                path.add(cur);
                while (cameFrom.containsKey(cur)) {
                    cur = cameFrom.get(cur);
                    path.add(cur);
                }
                Collections.reverse(path);
                return path;
            }
            int x = cur.x(), y = cur.y();
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (nx < minX || nx > maxX || ny < minY || ny > maxY) continue;
                Point np = new Point(nx, ny);
                boolean blockedHere = blocked.get(nx, ny) || water(terrain.get(nx, ny));
                if (!np.equals(goal) && blockedHere) continue;
                int ng = node.g() + 1;
                Integer existing = gScore.get(np);
                if (existing == null || ng < existing) {
                    gScore.put(np, ng);
                    cameFrom.put(np, cur);
                    open.add(new AStarNode(ng + heuristic(np, goal), ng, nx, ny));
                }
            }
        }
        return null;
    }

    private static int heuristic(Point a, Point b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y());
    }

    /**
     * For a building rect, return the valid exterior anchor cells on each
     * of its 4 sides: the free (non-water, non-building) cell just outside
     * the wall midpoint, plus the wall cell that would become the door if
     * that side is chosen. Value = {fx, fy, wx, wy}.
     */
    public static Map<String, int[]> buildingSideAnchors(Rect building, IntGrid terrain, BoolGrid occupied) {
        int bx = building.x(), by = building.y(), bw = building.w(), bh = building.h();
        Map<String, int[]> candidates = new LinkedHashMap<>();
        candidates.put("N", new int[]{bx + bw / 2, by - 1, bx + bw / 2, by});
        candidates.put("S", new int[]{bx + bw / 2, by + bh, bx + bw / 2, by + bh - 1});
        candidates.put("W", new int[]{bx - 1, by + bh / 2, bx, by + bh / 2});
        candidates.put("E", new int[]{bx + bw, by + bh / 2, bx + bw - 1, by + bh / 2});
        Map<String, int[]> valid = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : candidates.entrySet()) {
            int fx = e.getValue()[0], fy = e.getValue()[1];
            if (terrain.inBounds(fx, fy) && !water(terrain.get(fx, fy)) && !occupied.get(fx, fy)) {
                valid.put(e.getKey(), e.getValue());
            }
        }
        return valid;
    }

    /**
     * A generated connector is 1 cell wide at minimum, but reads better
     * with a little shoulder -- for each step, try to claim one adjacent
     * perpendicular cell (whichever side is actually free: not water, not
     * a building). If neither side is free it just stays 1 wide, which is
     * fine -- this is a nice-to-have, not a requirement.
     */
    public static Set<Point> widenGeneratedPath(List<Point> path, BoolGrid occupied, IntGrid terrain) {
        Set<Point> extra = new LinkedHashSet<>();
        for (int i = 0; i < path.size() - 1; i++) {
            int x0 = path.get(i).x(), y0 = path.get(i).y();
            int x1 = path.get(i + 1).x(), y1 = path.get(i + 1).y();
            int dx = x1 - x0, dy = y1 - y0;
            int[][] options = {{-dy, dx}, {dy, -dx}};
            for (int[] opt : options) {
                int cx = x0 + opt[0], cy = y0 + opt[1];
                if (terrain.inBounds(cx, cy) && !water(terrain.get(cx, cy)) && !occupied.get(cx, cy)) {
                    extra.add(new Point(cx, cy));
                    break;
                }
            }
        }
        return extra;
    }

    public static class DoorInfo {
        public Point wall;
        public Point anchor;
        public DoorInfo(Point wall, Point anchor) { this.wall = wall; this.anchor = anchor; }
    }

    public static class ConnectResult {
        public Set<Point> connectorCells;
        public Map<Integer, DoorInfo> doors;
        public ConnectResult(Set<Point> connectorCells, Map<Integer, DoorInfo> doors) {
            this.connectorCells = connectorCells;
            this.doors = doors;
        }
    }

    /**
     * Grows a network one building at a time (nearest-first, Prim-style),
     * starting from the main path itself as a virtual root. Each new building
     * picks whichever of its 4 sides is closest to the point it's connecting
     * to -- that side becomes its door. A* then carves the actual corridor
     * (never through water, never through another building).
     *
     * Implementation notes -- this used to be O(buildings^2 x pathCells): it
     * re-scanned every path cell for every building on every step, on top of
     * an O(buildings^3) pairwise search. Now:
     *   - one multi-source BFS gives every cell its nearest MAIN_PATH cell
     *     (obstacle-free, so it matches the old "min Manhattan to any path
     *     point" metric exactly), computed once -- O(cells);
     *   - the Prim loop keeps one "best connection so far" per unconnected
     *     building and relaxes it whenever a building joins the tree -- the
     *     same minimum-spanning-tree result in O(buildings^2);
     *   - each connector A* is confined to a window around its two endpoints.
     */
    public static ConnectResult connectBuildingsToNetwork(List<Rect> buildings, List<Point> pathPoints,
                                                            BoolGrid occupied, IntGrid terrain) {
        Set<Point> connectorCells = new LinkedHashSet<>();
        Map<Integer, DoorInfo> doors = new LinkedHashMap<>();
        int n = buildings.size();
        // no buildings, or no road to hang the network off of -> nothing to do
        // (matches the old loop, which broke out immediately with empty pathPoints)
        if (n == 0 || pathPoints.isEmpty()) return new ConnectResult(connectorCells, doors);

        int h = terrain.h, w = terrain.w;

        // nearest MAIN_PATH cell for every grid cell (multi-source BFS, 4-
        // connected, obstacles ignored -> exact Manhattan distance to the
        // closest path cell, same as the old inner loop)
        int[] pDist = new int[h * w];
        int[] pNear = new int[h * w];
        Arrays.fill(pDist, Integer.MAX_VALUE);
        ArrayDeque<Integer> bfs = new ArrayDeque<>();
        for (Point p : pathPoints) {
            int idx = p.y() * w + p.x();
            if (pDist[idx] != 0) {
                pDist[idx] = 0;
                pNear[idx] = idx;
                bfs.add(idx);
            }
        }
        while (!bfs.isEmpty()) {
            int idx = bfs.poll();
            int x = idx % w, y = idx / w;
            int nd = pDist[idx] + 1;
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                int nidx = ny * w + nx;
                if (nd < pDist[nidx]) {
                    pDist[nidx] = nd;
                    pNear[nidx] = pNear[idx];
                    bfs.add(nidx);
                }
            }
        }

        int[] bcx = new int[n], bcy = new int[n];
        for (int i = 0; i < n; i++) {
            Rect r = buildings.get(i);
            bcx[i] = r.x() + r.w() / 2;
            bcy[i] = r.y() + r.h() / 2;
        }

        // Prim: each not-yet-connected building tracks its cheapest link into
        // the current tree (initially: to the road). bestTgt is the point on
        // the tree it would connect toward.
        boolean[] inTree = new boolean[n];
        int[] bestDist = new int[n];
        int[] bestTgtX = new int[n];
        int[] bestTgtY = new int[n];
        for (int i = 0; i < n; i++) {
            int cxi = Math.max(0, Math.min(w - 1, bcx[i]));
            int cyi = Math.max(0, Math.min(h - 1, bcy[i]));
            int ci = cyi * w + cxi;
            bestDist[i] = pDist[ci];
            int near = pNear[ci];
            bestTgtX[i] = near % w;
            bestTgtY[i] = near / w;
        }

        for (int step = 0; step < n; step++) {
            int b = -1, bd = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!inTree[i] && bestDist[i] < bd) { bd = bestDist[i]; b = i; }
            }
            if (b == -1) break;
            inTree[b] = true;

            Map<String, int[]> sides = buildingSideAnchors(buildings.get(b), terrain, occupied);
            if (sides.isEmpty()) continue; // no reachable side -> no door, as before

            int tgtX = bestTgtX[b], tgtY = bestTgtY[b];
            int[] bestAnchor = null;
            int bestSideDist = Integer.MAX_VALUE;
            for (int[] a : sides.values()) {
                int dd = Math.abs(a[0] - tgtX) + Math.abs(a[1] - tgtY);
                if (dd < bestSideDist) { bestSideDist = dd; bestAnchor = a; }
            }
            int fx = bestAnchor[0], fy = bestAnchor[1], wx = bestAnchor[2], wy = bestAnchor[3];

            int manh = Math.abs(fx - tgtX) + Math.abs(fy - tgtY);
            int margin = Math.max(24, manh);
            List<Point> path = aStar(new Point(fx, fy), new Point(tgtX, tgtY), occupied, terrain,
                    Math.min(fx, tgtX) - margin, Math.min(fy, tgtY) - margin,
                    Math.max(fx, tgtX) + margin, Math.max(fy, tgtY) + margin);
            if (path == null && margin < Math.max(h, w)) {
                path = aStar(new Point(fx, fy), new Point(tgtX, tgtY), occupied, terrain);
            }
            if (path != null) {
                connectorCells.addAll(path);
                connectorCells.addAll(widenGeneratedPath(path, occupied, terrain));
            }
            doors.put(b, new DoorInfo(new Point(wx, wy), new Point(fx, fy)));

            // b is now in the tree; relax everyone still outside against b's door anchor
            for (int i = 0; i < n; i++) {
                if (inTree[i]) continue;
                int dd = Math.abs(bcx[i] - fx) + Math.abs(bcy[i] - fy);
                if (dd < bestDist[i]) {
                    bestDist[i] = dd;
                    bestTgtX[i] = fx;
                    bestTgtY[i] = fy;
                }
            }
        }

        return new ConnectResult(connectorCells, doors);
    }

    // ===================================================================
    // 5. wall ring + gates, optional citadel
    //    Walls are traced as the convex hull of each settled landmass, not
    //    the raw jagged boundary -- straight lines between the outermost
    //    points, except where a straight segment would cross water, in
    //    which case that stretch is simply left open (a natural harbour
    //    front / cliff edge instead of a wall).
    // ===================================================================

    public static List<Point> convexHull(List<Point> pointsIn) {
        List<Point> pts = new ArrayList<>(new TreeSet<>(pointsIn));
        if (pts.size() <= 2) return pts;

        List<Point> lower = new ArrayList<>();
        for (Point p : pts) {
            while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.get(lower.size() - 1), p) <= 0) {
                lower.remove(lower.size() - 1);
            }
            lower.add(p);
        }
        List<Point> upper = new ArrayList<>();
        for (int i = pts.size() - 1; i >= 0; i--) {
            Point p = pts.get(i);
            while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.get(upper.size() - 1), p) <= 0) {
                upper.remove(upper.size() - 1);
            }
            upper.add(p);
        }
        List<Point> hull = new ArrayList<>(lower.subList(0, lower.size() - 1));
        hull.addAll(upper.subList(0, upper.size() - 1));
        return hull;
    }

    private static long cross(Point o, Point a, Point b) {
        return (long) (a.x() - o.x()) * (b.y() - o.y()) - (long) (a.y() - o.y()) * (b.x() - o.x());
    }

    public static List<Point> bresenham(Point p0, Point p1) {
        int x0 = p0.x(), y0 = p0.y(), x1 = p1.x(), y1 = p1.y();
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = (x0 < x1) ? 1 : -1;
        int sy = (y0 < y1) ? 1 : -1;
        int err = dx + dy;
        List<Point> cells = new ArrayList<>();
        int x = x0, y = y0;
        while (true) {
            cells.add(new Point(x, y));
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x += sx; }
            if (e2 <= dx) { err += dx; y += sy; }
        }
        return cells;
    }

    /**
     * Shift a boolean mask by (dy, dx), filling the vacated edge with
     * false. Used instead of numpy's roll for dilation/erosion so nothing
     * wraps around the far edge of the map.
     */
    public static BoolGrid shiftMask(BoolGrid mask, int dy, int dx) {
        int h = mask.h, w = mask.w;
        BoolGrid out = new BoolGrid(h, w);
        int sy0 = Math.max(0, -dy), sy1 = h + Math.min(0, -dy);
        int sx0 = Math.max(0, -dx), sx1 = w + Math.min(0, -dx);
        int dy0 = Math.max(0, dy), dy1 = h + Math.min(0, dy);
        int dx0 = Math.max(0, dx), dx1 = w + Math.min(0, dx);
        if (sy1 > sy0 && sx1 > sx0) {
            for (int y = sy0; y < sy1; y++) {
                for (int x = sx0; x < sx1; x++) {
                    out.d[y - sy0 + dy0][x - sx0 + dx0] = mask.d[y][x];
                }
            }
        }
        return out;
    }

    /**
     * Same as shiftMask but for any int-labeled grid, with a caller-chosen
     * fill value for the vacated edge -- used for the Voronoi label grid,
     * where false/0 isn't a meaningful fill value.
     */
    public static int[][] shiftIntArray(int[][] arr, int dy, int dx, int fill) {
        int h = arr.length, w = arr[0].length;
        int[][] out = new int[h][w];
        for (int[] row : out) Arrays.fill(row, fill);
        int sy0 = Math.max(0, -dy), sy1 = h + Math.min(0, -dy);
        int sx0 = Math.max(0, -dx), sx1 = w + Math.min(0, -dx);
        int dy0 = Math.max(0, dy), dy1 = h + Math.min(0, dy);
        int dx0 = Math.max(0, dx), dx1 = w + Math.min(0, dx);
        if (sy1 > sy0 && sx1 > sx0) {
            for (int y = sy0; y < sy1; y++) {
                for (int x = sx0; x < sx1; x++) {
                    out[y - sy0 + dy0][x - sx0 + dx0] = arr[y][x];
                }
            }
        }
        return out;
    }

    public static BoolGrid dilateMask(BoolGrid mask, int r) {
        BoolGrid out = mask;
        for (int i = 0; i < r; i++) {
            out = out.or(shiftMask(out, 1, 0)).or(shiftMask(out, -1, 0))
                     .or(shiftMask(out, 0, 1)).or(shiftMask(out, 0, -1));
        }
        return out;
    }

    public static BoolGrid erodeMask(BoolGrid mask, int r) {
        return dilateMask(mask.not(), r).not();
    }

    /** Dilate then erode by the same radius: fills small notches/holes up to ~2r wide. */
    public static BoolGrid closeMask(BoolGrid mask, int r) {
        return erodeMask(dilateMask(mask, r), r);
    }

    /**
     * Town mask -> close tiny holes -> expand outward. This becomes the
     * settlement's true wall footprint, replacing a convex hull.
     */
    public static BoolGrid organicFootprint(BoolGrid compMask, int closeR, int expand) {
        BoolGrid m = closeMask(compMask, closeR);
        m = dilateMask(m, expand);
        return m;
    }

    /**
     * Ordered Moore-neighbor boundary trace of a single connected
     * foreground blob (8-connected). Returns a closed loop of points
     * walking the outer edge in order -- unlike a convex hull this follows
     * every concavity, so a wall traced from it can never end up cutting
     * through the inside of the actual footprint.
     */
    public static List<Point> traceBoundary(BoolGrid mask) {
        int h = mask.h, w = mask.w;
        List<Point> pts = mask.where();
        if (pts.isEmpty()) return new ArrayList<>();
        int startY = Integer.MAX_VALUE;
        for (Point p : pts) startY = Math.min(startY, p.y());
        int startX = Integer.MAX_VALUE;
        for (Point p : pts) if (p.y() == startY) startX = Math.min(startX, p.x());
        Point start = new Point(startX, startY);

        boolean anyNeighbor = false;
        for (int[] dir : MOORE_DIRS) {
            int nx = start.x() + dir[0], ny = start.y() + dir[1];
            if (nx >= 0 && nx < w && ny >= 0 && ny < h && mask.d[ny][nx]) { anyNeighbor = true; break; }
        }
        if (!anyNeighbor) {
            List<Point> single = new ArrayList<>();
            single.add(start);
            return single;
        }

        List<Point> boundary = new ArrayList<>();
        boundary.add(start);
        Point cur = start;
        Point b = new Point(start.x() - 1, start.y()); // guaranteed background: start is leftmost on its row
        long safety = (long) h * w * 8 + 32;
        while (safety > 0) {
            safety--;
            int bdx = b.x() - cur.x(), bdy = b.y() - cur.y();
            int startIdx = 0;
            for (int i = 0; i < 8; i++) {
                if (MOORE_DIRS[i][0] == bdx && MOORE_DIRS[i][1] == bdy) { startIdx = i; break; }
            }
            Point found = null;
            for (int k = 1; k <= 8; k++) {
                int idx = (startIdx + k) % 8;
                int dx = MOORE_DIRS[idx][0], dy = MOORE_DIRS[idx][1];
                int nx = cur.x() + dx, ny = cur.y() + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && mask.d[ny][nx]) {
                    found = new Point(nx, ny);
                    int pIdx = (idx - 1 + 8) % 8;
                    b = new Point(cur.x() + MOORE_DIRS[pIdx][0], cur.y() + MOORE_DIRS[pIdx][1]);
                    break;
                }
            }
            if (found == null) break;
            cur = found;
            if (cur.equals(start) && boundary.size() > 2) break;
            boundary.add(cur);
            if (boundary.size() > (long) h * w) break;
        }
        return boundary;
    }

    /**
     * Light moving-average smoothing so the wall doesn't slavishly
     * follow every single-cell staircase step, followed by occasional small
     * random offsets so it doesn't read as a perfect machine-drawn curve
     * either.
     */
    public static List<Point> smoothAndJitterBoundary(List<Point> boundary, int window, double jitterProb,
                                                        double jitterAmount, Rng rng) {
        int n = boundary.size();
        if (n < 5) return new ArrayList<>(boundary);
        double[][] smoothed = new double[n][2];
        for (int i = 0; i < n; i++) {
            double sx = 0, sy = 0;
            int cnt = 0;
            for (int k = -window; k <= window; k++) {
                Point p = boundary.get(((i + k) % n + n) % n);
                sx += p.x();
                sy += p.y();
                cnt++;
            }
            smoothed[i][0] = sx / cnt;
            smoothed[i][1] = sy / cnt;
        }
        if (rng != null) {
            for (int i = 0; i < n; i++) {
                if (rng.random() < jitterProb) {
                    smoothed[i][0] += rng.uniform(-jitterAmount, jitterAmount);
                    smoothed[i][1] += rng.uniform(-jitterAmount, jitterAmount);
                }
            }
        }
        List<Point> out = new ArrayList<>();
        for (double[] xy : smoothed) {
            Point p = new Point((int) pyRound(xy[0]), (int) pyRound(xy[1]));
            if (out.isEmpty() || !out.get(out.size() - 1).equals(p)) out.add(p);
        }
        if (out.size() > 1 && out.get(0).equals(out.get(out.size() - 1))) out.remove(out.size() - 1);
        return out;
    }

    public static List<Point> smoothAndJitterBoundary(List<Point> boundary, Rng rng) {
        return smoothAndJitterBoundary(boundary, 2, 0.06, 1.0, rng);
    }

    public static class RingResult {
        public Set<Point> wallCells;
        public List<List<Point>> runs;
        public RingResult(Set<Point> wallCells, List<List<Point>> runs) {
            this.wallCells = wallCells;
            this.runs = runs;
        }
    }

    /**
     * Connects consecutive boundary points with straight (bresenham)
     * segments, then splits the resulting ring into contiguous land runs at
     * every water cell -- any run shorter than minWallLength is dropped as
     * too short to read as a wall.
     */
    public static RingResult rasterizeWallRing(List<Point> boundary, IntGrid terrain, int minWallLength) {
        int n = boundary.size();
        if (n < 3) return new RingResult(new LinkedHashSet<>(), new ArrayList<>());
        List<Point> ring = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Point p0 = boundary.get(i), p1 = boundary.get((i + 1) % n);
            List<Point> seg = bresenham(p0, p1);
            if (!ring.isEmpty() && !seg.isEmpty() && seg.get(0).equals(ring.get(ring.size() - 1))) {
                seg = seg.subList(1, seg.size());
            }
            ring.addAll(seg);
        }

        int m = ring.size();
        boolean[] landFlags = new boolean[m];
        boolean allLand = true, anyLand = false;
        for (int i = 0; i < m; i++) {
            Point p = ring.get(i);
            landFlags[i] = terrain.inBounds(p.x(), p.y()) && !water(terrain.get(p.x(), p.y()));
            allLand &= landFlags[i];
            anyLand |= landFlags[i];
        }

        Set<Point> wallCells = new LinkedHashSet<>();
        List<List<Point>> keptRuns = new ArrayList<>();
        if (allLand) {
            if (m >= minWallLength) {
                wallCells.addAll(ring);
                keptRuns.add(new ArrayList<>(ring));
            }
            return new RingResult(wallCells, keptRuns);
        }
        int start = -1;
        for (int k = 0; k < m; k++) {
            if (landFlags[k] && !landFlags[((k - 1) % m + m) % m]) { start = k; break; }
        }
        if (start == -1) return new RingResult(wallCells, keptRuns);
        int idx = start, visited = 0;
        while (visited < m) {
            if (landFlags[idx]) {
                List<Point> run = new ArrayList<>();
                while (landFlags[idx] && visited < m) {
                    run.add(ring.get(idx));
                    idx = (idx + 1) % m;
                    visited++;
                }
                if (run.size() >= minWallLength) {
                    wallCells.addAll(run);
                    keptRuns.add(run);
                }
            } else {
                idx = (idx + 1) % m;
                visited++;
            }
        }
        return new RingResult(wallCells, keptRuns);
    }

    /**
     * Turning angle (radians, 0=straight through pi=hairpin) at every
     * boundary point, measured between the vectors arriving from and
     * leaving to points `span` steps away -- wide enough to smooth over
     * single-cell staircase noise while still catching real bends.
     */
    public static double[] scoreCorners(List<Point> boundary, int span) {
        int n = boundary.size();
        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            Point pPrev = boundary.get(((i - span) % n + n) % n);
            Point p = boundary.get(i);
            Point pNext = boundary.get((i + span) % n);
            double v1x = p.x() - pPrev.x(), v1y = p.y() - pPrev.y();
            double v2x = pNext.x() - p.x(), v2y = pNext.y() - p.y();
            double l1 = Math.hypot(v1x, v1y);
            double l2 = Math.hypot(v2x, v2y);
            if (l1 < 1e-6 || l2 < 1e-6) { scores[i] = 0.0; continue; }
            double cosang = (v1x * v2x + v1y * v2y) / (l1 * l2);
            cosang = Math.max(-1.0, Math.min(1.0, cosang));
            scores[i] = Math.acos(cosang);
        }
        return scores;
    }

    /**
     * Outward-facing unit normal at a boundary point, estimated from the
     * local distribution of interior cells nearby -- correct at concave AND
     * convex points, unlike a global centroid-based estimate.
     */
    public static double[] localOutwardNormal(int x, int y, BoolGrid interiorMask, int radius) {
        int h = interiorMask.h, w = interiorMask.w;
        double sx = 0, sy = 0;
        int cnt = 0;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && interiorMask.d[ny][nx]) {
                    sx += dx; sy += dy; cnt++;
                }
            }
        }
        if (cnt == 0) return new double[]{0.0, -1.0};
        double mx = sx / cnt, my = sy / cnt;
        double norm = Math.sqrt(mx * mx + my * my);
        if (norm < 1e-6) return new double[]{0.0, -1.0};
        return new double[]{-mx / norm, -my / norm};
    }

    public static double[] localOutwardNormal(int x, int y, BoolGrid interiorMask) {
        return localOutwardNormal(x, y, interiorMask, 4);
    }

    /**
     * Convex hull of a settled landmass, inflated outward by `inset` cells
     * so it sits just beyond the outermost buildings. Shared by wall
     * tracing and gap-filling (both need the same hull).
     *
     * The hull is computed on the RAW points first, then the resulting hull
     * vertices are inflated -- not the other way around, so the wall can
     * never end up on the wrong side of a building.
     */
    public static List<Point> componentHull(BoolGrid compMask, int inset) {
        List<Point> points = compMask.where();
        if (points.size() < 3) return points;
        List<Point> hull0 = convexHull(points);
        if (hull0.size() < 3) return hull0;
        double cx = 0, cy = 0;
        for (Point p : hull0) { cx += p.x(); cy += p.y(); }
        cx /= hull0.size();
        cy /= hull0.size();
        List<Point> inflated = new ArrayList<>();
        for (Point p : hull0) {
            double vx = p.x() - cx, vy = p.y() - cy;
            double norm = Math.sqrt(vx * vx + vy * vy);
            int x = p.x(), y = p.y();
            if (norm > 0) {
                x = (int) pyRound(x + vx / norm * inset);
                y = (int) pyRound(y + vy / norm * inset);
            }
            inflated.add(new Point(x, y));
        }
        return inflated;
    }

    /** Scanline fill of a polygon (list of points) into a boolean mask of the given shape. */
    public static BoolGrid polygonFillMask(List<Point> hull, int h, int w) {
        BoolGrid mask = new BoolGrid(h, w);
        if (hull.size() < 3) return mask;
        int miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
        for (Point p : hull) { miny = Math.min(miny, p.y()); maxy = Math.max(maxy, p.y()); }
        miny = Math.max(0, miny);
        maxy = Math.min(h - 1, maxy);
        int n = hull.size();
        for (int y = miny; y <= maxy; y++) {
            List<Double> xs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Point a = hull.get(i), b = hull.get((i + 1) % n);
                int y1 = a.y(), y2 = b.y();
                if (y1 == y2) continue;
                if ((y1 <= y && y < y2) || (y2 <= y && y < y1)) {
                    double t = (double) (y - y1) / (y2 - y1);
                    xs.add(a.x() + t * (b.x() - a.x()));
                }
            }
            Collections.sort(xs);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                int xa = Math.max(0, (int) pyRound(xs.get(i)));
                int xb = Math.min(w - 1, (int) pyRound(xs.get(i + 1)));
                for (int x = xa; x <= xb; x++) mask.d[y][x] = true;
            }
        }
        return mask;
    }

    /**
     * Cells inside the settlement's wall footprint that are dry land but
     * not yet a building, path, or water -- pockets that would otherwise
     * read as dead space inside the wall.
     */
    public static List<BoolGrid> findWallGaps(BoolGrid interiorMask, IntGrid terrain, BoolGrid occupied, int minArea) {
        int h = terrain.h, w = terrain.w;
        BoolGrid dry = new BoolGrid(h, w);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int t = terrain.get(x, y);
            dry.d[y][x] = t != OCEAN && t != RIVER;
        }
        BoolGrid gap = new BoolGrid(h, w);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            gap.d[y][x] = interiorMask.d[y][x] && dry.d[y][x] && !occupied.d[y][x] && terrain.get(x, y) != MAIN_PATH;
        }
        List<BoolGrid> comps = connectedComponents(gap);
        List<BoolGrid> filtered = new ArrayList<>();
        for (BoolGrid c : comps) if (c.sum() >= minArea) filtered.add(c);
        filtered.sort((a, b) -> Integer.compare(b.sum(), a.sum()));
        return filtered;
    }

    public static List<BoolGrid> findWallGaps(BoolGrid interiorMask, IntGrid terrain, BoolGrid occupied) {
        return findWallGaps(interiorMask, terrain, occupied, 1);
    }

    /**
     * All top-left positions where a width x height rectangle fits entirely
     * within True cells of mask, via a summed-area table.
     */
    public static List<Point> findRectPositions(BoolGrid mask, int width, int height) {
        int h = mask.h, w = mask.w;
        if (width > w || height > h) return new ArrayList<>();
        long[][] ii = new long[h + 1][w + 1];
        for (int y = 0; y < h; y++) {
            long rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += mask.d[y][x] ? 1 : 0;
                ii[y + 1][x + 1] = ii[y][x + 1] + rowSum;
            }
        }
        List<Point> positions = new ArrayList<>();
        long target = (long) width * height;
        for (int y = 0; y + height <= h; y++) {
            for (int x = 0; x + width <= w; x++) {
                long s = ii[y + height][x + width] - ii[y][x + width] - ii[y + height][x] + ii[y][x];
                if (s == target) positions.add(new Point(x, y));
            }
        }
        return positions;
    }

    public static class PlacePrebuiltResult {
        public List<PlacedStructure> placed;
        public List<PrebuiltStructure> unplaced;
        public PlacePrebuiltResult(List<PlacedStructure> placed, List<PrebuiltStructure> unplaced) {
            this.placed = placed;
            this.unplaced = unplaced;
        }
    }

    /**
     * Slots caller-supplied fixed-size structures into the biggest
     * available gaps, largest structures first, trying both orientations.
     * Multiple structures can land in the same gap if there's room for more
     * than one. Returns placed + names that didn't fit anywhere.
     */
    public static PlacePrebuiltResult placePrebuiltStructures(List<BoolGrid> gaps, List<PrebuiltStructure> structures,
                                                               BoolGrid occupied, Rng rng) {
        List<PlacedStructure> placed = new ArrayList<>();
        List<PrebuiltStructure> remaining = new ArrayList<>(structures);
        remaining.sort((a, b) -> Integer.compare(b.width * b.height, a.width * a.height));

        for (BoolGrid gap : gaps) {
            if (remaining.isEmpty()) break;
            BoolGrid localFree = gap.andNot(occupied);
            List<PrebuiltStructure> stillRemaining = new ArrayList<>();
            for (PrebuiltStructure struct : remaining) {
                int wDim = struct.width, hDim = struct.height;
                List<int[]> orientations = new ArrayList<>();
                orientations.add(new int[]{wDim, hDim});
                if (wDim != hDim) orientations.add(new int[]{hDim, wDim});
                Point chosenPos = null;
                int tw = 0, th = 0;
                for (int[] orient : orientations) {
                    List<Point> positions = findRectPositions(localFree, orient[0], orient[1]);
                    if (!positions.isEmpty()) {
                        chosenPos = rng.choice(positions);
                        tw = orient[0]; th = orient[1];
                        break;
                    }
                }
                if (chosenPos != null) {
                    int x0 = chosenPos.x(), y0 = chosenPos.y();
                    for (int yy = y0; yy < y0 + th; yy++) {
                        for (int xx = x0; xx < x0 + tw; xx++) {
                            occupied.set(xx, yy, true);
                            localFree.set(xx, yy, false);
                        }
                    }
                    placed.add(new PlacedStructure(struct.name != null ? struct.name : "structure",
                            new Rect(x0, y0, tw, th)));
                } else {
                    stillRemaining.add(struct);
                }
            }
            remaining = stillRemaining;
        }
        return new PlacePrebuiltResult(placed, remaining);
    }

    /**
     * Classic largest-rectangle-of-True-cells-in-a-boolean-matrix, via the
     * histogram method, O(h*w). Returns the best rect, or null if the mask
     * is empty. This finds the true largest axis-aligned rectangle, unlike
     * a naive shrink-from-the-bounding-box approach.
     */
    public static Rect largestRectInMask(BoolGrid mask) {
        int h = mask.h, w = mask.w;
        int[] heights = new int[w];
        long bestArea = 0;
        Rect bestRect = null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) heights[x] = mask.d[y][x] ? heights[x] + 1 : 0;
            Deque<Integer> stack = new ArrayDeque<>();
            int x = 0;
            while (x <= w) {
                int cur = (x < w) ? heights[x] : 0;
                if (stack.isEmpty() || cur >= heights[stack.peek()]) {
                    stack.push(x);
                    x++;
                } else {
                    int top = stack.pop();
                    int left = stack.isEmpty() ? 0 : stack.peek() + 1;
                    int width = x - left;
                    long area = (long) heights[top] * width;
                    if (area > bestArea) {
                        bestArea = area;
                        bestRect = new Rect(left, y - heights[top] + 1, width, heights[top]);
                    }
                }
            }
        }
        return bestRect;
    }

    /**
     * Largest axis-aligned rectangle that fits entirely inside a gap
     * component. No margin is required.
     */
    public static Rect carveFortRect(BoolGrid gapMask, int minSide) {
        Rect rect = largestRectInMask(gapMask);
        if (rect == null) return null;
        if (rect.w() < minSide || rect.h() < minSide) return null;
        return rect;
    }

    public static class FillGapsResult {
        public List<Rect> newBuildings;
        public Map<Integer, String> landmarkKinds;
        public List<PlacedStructure> placedPrebuilt;
        public List<PrebuiltStructure> unplacedPrebuilt;
        public FillGapsResult(List<Rect> newBuildings, Map<Integer, String> landmarkKinds,
                               List<PlacedStructure> placedPrebuilt, List<PrebuiltStructure> unplacedPrebuilt) {
            this.newBuildings = newBuildings;
            this.landmarkKinds = landmarkKinds;
            this.placedPrebuilt = placedPrebuilt;
            this.unplacedPrebuilt = unplacedPrebuilt;
        }
    }

    /**
     * Deals with the empty pockets a wall footprint encloses. Order of
     * priority: (1) caller-supplied prebuilt structures get first claim on
     * the biggest gaps; (2) whatever's left of the biggest few pockets
     * becomes a market square or a church; (3) everything smaller gets
     * infilled with ordinary houses.
     */
    public static FillGapsResult fillGaps(BoolGrid interiorMask, IntGrid terrain, BoolGrid occupied, Rng rng,
                                           int fortThreshold, int fortMinSide, int maxLandmarks,
                                           List<PrebuiltStructure> prebuiltStructures) {
        List<BoolGrid> gaps = findWallGaps(interiorMask, terrain, occupied);

        List<PlacedStructure> placedPrebuilt = new ArrayList<>();
        List<PrebuiltStructure> unplacedPrebuilt = (prebuiltStructures != null) ? new ArrayList<>(prebuiltStructures) : new ArrayList<>();
        if (prebuiltStructures != null && !prebuiltStructures.isEmpty()) {
            PlacePrebuiltResult r = placePrebuiltStructures(gaps, prebuiltStructures, occupied, rng);
            placedPrebuilt = r.placed;
            unplacedPrebuilt = r.unplaced;
        }

        List<Rect> newBuildings = new ArrayList<>();
        Map<Integer, String> landmarkKinds = new LinkedHashMap<>();
        int landmarkCount = 0;
        List<String> marketOrChurch = List.of("market", "church");
        for (BoolGrid gap : gaps) {
            BoolGrid freeGap = gap.andNot(occupied);
            int area = freeGap.sum();
            if (area >= fortThreshold && landmarkCount < maxLandmarks) {
                Rect rect = carveFortRect(freeGap, fortMinSide);
                if (rect != null) {
                    for (int yy = rect.y(); yy < rect.y() + rect.h(); yy++) {
                        for (int xx = rect.x(); xx < rect.x() + rect.w(); xx++) {
                            if (occupied.inBounds(xx, yy)) occupied.set(xx, yy, true);
                        }
                    }
                    String kind = rng.choice(marketOrChurch);
                    landmarkKinds.put(newBuildings.size(), kind);
                    newBuildings.add(rect);
                    landmarkCount++;
                    continue;
                }
                // fell through: too irregular a shape for one big rect, infill it like a small gap instead
            }
            List<Point> pts = freeGap.where();
            if (pts.isEmpty()) continue;
            int gx0 = Integer.MAX_VALUE, gx1 = Integer.MIN_VALUE, gy0 = Integer.MAX_VALUE, gy1 = Integer.MIN_VALUE;
            for (Point p : pts) {
                gx0 = Math.min(gx0, p.x()); gx1 = Math.max(gx1, p.x());
                gy0 = Math.min(gy0, p.y()); gy1 = Math.max(gy1, p.y());
            }
            gx1 += 1; gy1 += 1;
            District district = new District(gx0, gy0, gx1, gy1);
            List<Rect> blds = packBuildings(district, freeGap, occupied, rng, 5, 8, 1, null);
            newBuildings.addAll(blds);
        }
        return new FillGapsResult(newBuildings, landmarkKinds, placedPrebuilt, unplacedPrebuilt);
    }

    public static FillGapsResult fillGaps(BoolGrid interiorMask, IntGrid terrain, BoolGrid occupied, Rng rng,
                                           int maxLandmarks, List<PrebuiltStructure> prebuiltStructures) {
        return fillGaps(interiorMask, terrain, occupied, rng, 70, 8, maxLandmarks, prebuiltStructures);
    }

    /**
     * Rasterizes the component's hull perimeter (see componentHull) as an
     * ordered ring of cells. The ring is split into contiguous land runs at
     * every water cell; any run shorter than minWallLength is dropped.
     * (Kept for parity with the Python source; the main pipeline below uses
     * traceBoundary + rasterizeWallRing instead.)
     */
    public static Set<Point> traceWallComponent(BoolGrid compMask, IntGrid terrain, int inset, int minWallLength) {
        List<Point> hull = componentHull(compMask, inset);
        if (hull.size() < 3) return new LinkedHashSet<>();

        List<Point> ring = new ArrayList<>();
        int n = hull.size();
        for (int i = 0; i < n; i++) {
            Point p0 = hull.get(i), p1 = hull.get((i + 1) % n);
            List<Point> line = bresenham(p0, p1);
            if (!ring.isEmpty() && !line.isEmpty() && ring.get(ring.size() - 1).equals(line.get(0))) {
                line = line.subList(1, line.size());
            }
            ring.addAll(line);
        }
        if (ring.isEmpty()) return new LinkedHashSet<>();

        int m = ring.size();
        boolean[] landFlags = new boolean[m];
        boolean allLand = true, anyLand = false;
        for (int i = 0; i < m; i++) {
            Point p = ring.get(i);
            landFlags[i] = terrain.inBounds(p.x(), p.y()) && !water(terrain.get(p.x(), p.y()));
            allLand &= landFlags[i];
            anyLand |= landFlags[i];
        }

        if (allLand) return (m >= minWallLength) ? new LinkedHashSet<>(ring) : new LinkedHashSet<>();
        if (!anyLand) return new LinkedHashSet<>();

        int start = -1;
        for (int i = 0; i < m; i++) if (!landFlags[i]) { start = i; break; }
        List<Point> rotatedRing = new ArrayList<>();
        boolean[] rotatedFlags = new boolean[m];
        for (int i = 0; i < m; i++) {
            rotatedRing.add(ring.get((start + i) % m));
            rotatedFlags[i] = landFlags[(start + i) % m];
        }

        Set<Point> wallCells = new LinkedHashSet<>();
        int i = 0;
        while (i < m) {
            if (rotatedFlags[i]) {
                int j = i;
                while (j < m && rotatedFlags[j]) j++;
                List<Point> run = rotatedRing.subList(i, j);
                if (run.size() >= minWallLength) wallCells.addAll(run);
                i = j;
            } else {
                i++;
            }
        }
        return wallCells;
    }

    /**
     * A convex wall can end up genuinely far from where a real road
     * penetrates the settlement -- find each road's closest approach to the
     * wall and, if it doesn't already reach (within maxGap), pave a short
     * A* corridor the rest of the way so punchGates has something to find.
     */
    public static Set<Point> bridgeRoadsToWall(BoolGrid pathMask, Set<Point> wallCells, BoolGrid occupied,
                                                IntGrid terrain, int maxGap) {
        Set<Point> bridges = new LinkedHashSet<>();
        if (wallCells.isEmpty()) return bridges;
        List<Point> wallList = new ArrayList<>(wallCells);
        List<BoolGrid> roadComps = connectedComponents(pathMask);
        for (BoolGrid comp : roadComps) {
            List<Point> pts = comp.where();
            if (pts.isEmpty()) continue;
            int bestI = -1, bestJ = -1, bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                Point p = pts.get(i);
                int localBestJ = -1, localBestDist = Integer.MAX_VALUE;
                for (int j = 0; j < wallList.size(); j++) {
                    Point wp = wallList.get(j);
                    int d = Math.abs(p.x() - wp.x()) + Math.abs(p.y() - wp.y());
                    if (d < localBestDist) { localBestDist = d; localBestJ = j; }
                }
                if (localBestDist < bestDist) { bestDist = localBestDist; bestI = i; bestJ = localBestJ; }
            }
            if (bestDist <= maxGap) continue; // already close enough, punchGates will find it
            Point start = pts.get(bestI);
            Point goal = wallList.get(bestJ);
            List<Point> path = aStar(start, goal, occupied, terrain);
            if (path != null) bridges.addAll(path);
        }
        return bridges;
    }

    public static Set<Point> punchGates(Set<Point> wallCells, Set<Point> pathCells) {
        Set<Point> gates = new LinkedHashSet<>();
        for (Point p : pathCells) {
            for (int[] dir : DIRS4) {
                Point np = new Point(p.x() + dir[0], p.y() + dir[1]);
                if (wallCells.contains(np)) gates.add(np);
            }
        }
        return gates;
    }

    /**
     * A gate is a doorway, not a length of open wall. Where a wide road runs
     * alongside the wall, punchGates can mark a long ribbon of cells -- this
     * collapses each contiguous (8-connected) run of gate cells to at most
     * {@code maxWidth} cells centred on the run and hands the trimmed cells
     * back to {@code wallCells}. The wall burn step still skips any cell that
     * sits on a road, so a genuine crossing stays open.
     */
    public static Set<Point> capGateRuns(Set<Point> gates, Set<Point> wallCells, int maxWidth) {
        if (gates.size() <= maxWidth) return gates;
        Set<Point> remaining = new LinkedHashSet<>(gates);
        Set<Point> kept = new LinkedHashSet<>();
        while (!remaining.isEmpty()) {
            Point seed = remaining.iterator().next();
            remaining.remove(seed);
            List<Point> run = new ArrayList<>();
            Deque<Point> q = new ArrayDeque<>();
            q.add(seed);
            while (!q.isEmpty()) {
                Point p = q.poll();
                run.add(p);
                for (int[] d : MOORE_DIRS) {
                    Point np = new Point(p.x() + d[0], p.y() + d[1]);
                    if (remaining.remove(np)) q.add(np);
                }
            }
            if (run.size() <= maxWidth) {
                kept.addAll(run);
                continue;
            }
            Collections.sort(run); // Point sorts by (x, y) -- roughly along the run
            int start = (run.size() - maxWidth) / 2;
            for (int i = 0; i < run.size(); i++) {
                if (i >= start && i < start + maxWidth) kept.add(run.get(i));
                else wallCells.add(run.get(i));
            }
        }
        return kept;
    }

    /**
     * Scores every boundary point by local turning angle and picks a
     * sparse, sensible set of tower locations instead of one per corner:
     * major/sharp bends get a high chance, long straight runs get an
     * occasional tower, gates get one flanking each side, and anything in a
     * wall run too short to read as a wall gets none at all.
     */
    public static List<Point> selectTowerAnchors(List<Point> boundary, List<List<Point>> wallRuns, Set<Point> gates,
                                                  Rng rng, int span, double sharpThresh, double majorThresh,
                                                  int minSpacing, int straightInterval, int minRunLength) {
        int n = boundary.size();
        if (n < 8) return new ArrayList<>();
        double[] scores = scoreCorners(boundary, span);
        Set<Integer> validIdx = new LinkedHashSet<>();
        Map<Point, Integer> idxLookup = new HashMap<>();
        for (int i = 0; i < n; i++) idxLookup.put(boundary.get(i), i);
        for (List<Point> run : wallRuns) {
            if (run.size() < minRunLength) continue;
            for (Point p : run) {
                Integer i = idxLookup.get(p);
                if (i != null) validIdx.add(i);
            }
        }

        List<Integer> chosen = new ArrayList<>();

        // (local helper) is index i far enough from every chosen index so far
        // (min circular distance >= minSpacing)

        // gate-flanking towers: forced, highest priority
        for (Point g : gates) {
            int nearestI = 0;
            int bestDist = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                Point p = boundary.get(i);
                int d = Math.abs(p.x() - g.x()) + Math.abs(p.y() - g.y());
                if (d < bestDist) { bestDist = d; nearestI = i; }
            }
            for (int off : new int[]{-6, 6}) {
                int i = ((nearestI + off) % n + n) % n;
                if (validIdx.contains(i) && farEnoughFromChosen(chosen, i, n, minSpacing)) {
                    chosen.add(i);
                }
            }
        }

        // sharp/major bends, sorted strongest first
        List<Integer> candidates = new ArrayList<>();
        for (int i : validIdx) if (scores[i] >= sharpThresh) candidates.add(i);
        candidates.sort((a, b) -> Double.compare(scores[b], scores[a]));
        for (int i : candidates) {
            boolean strongerNearby = false;
            for (int j = Math.max(0, i - span); j < Math.min(n, i + span + 1); j++) {
                if (scores[j] > scores[i]) { strongerNearby = true; break; }
            }
            if (strongerNearby) continue;
            double weight = (scores[i] >= majorThresh) ? 0.85 : 0.4;
            if (farEnoughFromChosen(chosen, i, n, minSpacing) && rng.random() < weight) {
                chosen.add(i);
            }
        }

        // occasional towers along long straight stretches with nothing chosen yet
        int sinceLast = 0;
        for (int i = 0; i < n; i++) {
            if (validIdx.contains(i) && scores[i] < sharpThresh) sinceLast++;
            else sinceLast = 0;
            if (sinceLast >= straightInterval && farEnoughFromChosen(chosen, i, n, minSpacing)) {
                chosen.add(i);
                sinceLast = 0;
            }
        }

        List<Point> result = new ArrayList<>();
        for (int i : chosen) result.add(boundary.get(i));
        return result;
    }

    private static boolean farEnoughFromChosen(List<Integer> chosen, int i, int n, int minSpacing) {
        for (int j : chosen) {
            int d = Math.min(((i - j) % n + n) % n, ((j - i) % n + n) % n);
            if (d < minSpacing) return false;
        }
        return true;
    }

    public static List<Point> selectTowerAnchors(List<Point> boundary, List<List<Point>> wallRuns, Set<Point> gates, Rng rng) {
        return selectTowerAnchors(boundary, wallRuns, gates, rng, 5, 0.45, 0.9, 9, 45, 10);
    }

    /**
     * A tower is a small hollow structure straddling the wall line (half
     * in, half out along the local outward normal), not just a recolored
     * wall cell. Fixed 4x5 footprint -- oriented so the longer side runs
     * along the wall, the shorter side pokes out through it. Returns null
     * if it doesn't fit at all.
     */
    public static Rect placeTowerStructure(Point anchor, BoolGrid interiorMask, BoolGrid occupied, IntGrid terrain,
                                            Rng rng, int sizeA, int sizeB) {
        int ax = anchor.x(), ay = anchor.y();
        double[] normal = localOutwardNormal(ax, ay, interiorMask);
        double nx = normal[0], ny = normal[1];
        int h = terrain.h, w = terrain.w;
        int shortSide = Math.min(sizeA, sizeB), longSide = Math.max(sizeA, sizeB);
        int tw, th;
        if (Math.abs(nx) >= Math.abs(ny)) { tw = shortSide; th = longSide; }
        else { tw = longSide; th = shortSide; }

        int offsetDim = (Math.abs(nx) >= Math.abs(ny)) ? tw : th;
        double cx = ax + nx * (offsetDim / 2.0);
        double cy = ay + ny * (offsetDim / 2.0);
        int tx0 = (int) pyRound(cx - tw / 2.0);
        int ty0 = (int) pyRound(cy - th / 2.0);

        boolean overlaps = false;
        outer:
        for (int yy = ty0; yy < ty0 + th; yy++) {
            for (int xx = tx0; xx < tx0 + tw; xx++) {
                if (!(xx >= 0 && xx < w && yy >= 0 && yy < h)) { overlaps = true; break outer; }
                if (occupied.d[yy][xx]) { overlaps = true; break outer; }
                // never straddle a road -- a tower belongs on the wall line
                if (terrain.get(xx, yy) == MAIN_PATH) { overlaps = true; break outer; }
            }
        }
        if (!overlaps) return new Rect(tx0, ty0, tw, th);
        return null;
    }

    public static Rect placeTowerStructure(Point anchor, BoolGrid interiorMask, BoolGrid occupied, IntGrid terrain, Rng rng) {
        return placeTowerStructure(anchor, interiorMask, occupied, terrain, rng, 5, 5);
    }

    // ===================================================================
    // main entry point
    // ===================================================================

    /**
     * Detects where a road logically crosses a body of water: a run of
     * water cells (in a single row or column) with a MAIN_PATH cell on both
     * ends -- allowing a small perpendicular tolerance. Marked cells are
     * conceptually "this is water, but a bridge deck sits on top and it's
     * traversable" -- distinct from ordinary road and from open water in
     * both rendering and pathing.
     */
    public static Set<Point> findBridges(IntGrid terrain, int maxSpan, int tolerance) {
        int h = terrain.h, w = terrain.w;
        boolean[][] pathMask = new boolean[h][w];
        boolean[][] waterMask = new boolean[h][w];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            pathMask[y][x] = terrain.get(x, y) == MAIN_PATH;
            waterMask[y][x] = terrain.get(x, y) == RIVER || terrain.get(x, y) == OCEAN;
        }
        Set<Point> bridges = new LinkedHashSet<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (terrain.get(x, y) == BRIDGE) bridges.add(new Point(x, y));
        }

        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (waterMask[y][x]) {
                    int x0 = x;
                    while (x < w && waterMask[y][x]) x++;
                    int x1 = x;
                    if (x0 > 0 && x1 < w && (x1 - x0) <= maxSpan) {
                        boolean leftOk = false, rightOk = false;
                        for (int dy = -tolerance; dy <= tolerance; dy++) {
                            int ny = y + dy;
                            if (ny >= 0 && ny < h && pathMask[ny][x0 - 1]) leftOk = true;
                            if (ny >= 0 && ny < h && pathMask[ny][x1]) rightOk = true;
                        }
                        if (leftOk && rightOk) {
                            for (int xx = x0; xx < x1; xx++) bridges.add(new Point(xx, y));
                        }
                    }
                } else {
                    x++;
                }
            }
        }

        for (int x = 0; x < w; x++) {
            int y = 0;
            while (y < h) {
                if (waterMask[y][x]) {
                    int y0 = y;
                    while (y < h && waterMask[y][x]) y++;
                    int y1 = y;
                    if (y0 > 0 && y1 < h && (y1 - y0) <= maxSpan) {
                        boolean topOk = false, bottomOk = false;
                        for (int dx = -tolerance; dx <= tolerance; dx++) {
                            int nx = x + dx;
                            if (nx >= 0 && nx < w && pathMask[y0 - 1][nx]) topOk = true;
                            if (nx >= 0 && nx < w && pathMask[y1][nx]) bottomOk = true;
                        }
                        if (topOk && bottomOk) {
                            for (int yy = y0; yy < y1; yy++) bridges.add(new Point(x, yy));
                        }
                    }
                } else {
                    y++;
                }
            }
        }
        return bridges;
    }

    public static Set<Point> findBridges(IntGrid terrain, int maxSpan) {
        return findBridges(terrain, maxSpan, 3);
    }

    public record DockSpec(int lengthLo, int lengthHi, int width, int weight) {}

    public static final Map<String, DockSpec> DOCK_TYPES = Map.of(
        "fishing", new DockSpec(4, 12, 1, 3),
        "merchant", new DockSpec(10, 22, 2, 1)
    );

    public record ShoreEntry(int x, int y, int dx, int dy, String kind) {}

    /**
     * Buildable, settled cells directly adjacent to open water -- candidate
     * origins for a pier. Each entry also records which kind of water it
     * touches (ocean vs river).
     */
    public static List<ShoreEntry> findShoreline(BoolGrid compMask, IntGrid terrain) {
        List<ShoreEntry> shore = new ArrayList<>();
        for (Point p : compMask.where()) {
            int x = p.x(), y = p.y();
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (terrain.inBounds(nx, ny) && water(terrain.get(nx, ny))) {
                    String kind = (terrain.get(nx, ny) == OCEAN) ? "ocean" : "river";
                    shore.add(new ShoreEntry(x, y, dir[0], dir[1], kind));
                    break;
                }
            }
        }
        return shore;
    }

    /**
     * Attempts to extend a pier out from (x, y) in direction (dx, dy).
     * Starts from a randomly chosen target length and shrinks from there --
     * and, as a last resort, shrinks width too -- before giving up.
     */
    public static List<Point> tryBuildPier(int x, int y, int dx, int dy, String kind, IntGrid terrain,
                                            BoolGrid occupied, Rng rng) {
        DockSpec spec = DOCK_TYPES.get(kind);
        int lo = spec.lengthLo(), hi = spec.lengthHi();
        int start = (rng != null) ? rng.randint(lo, hi) : hi;
        List<Integer> widths = (spec.width() > 1) ? List.of(spec.width(), 1) : List.of(1);
        for (int width : widths) {
            for (int length = start; length > 1; length--) {
                List<Point> cells = new ArrayList<>();
                boolean ok = true;
                for (int step = 1; step <= length; step++) {
                    int px = x + dx * step, py = y + dy * step;
                    if (!terrain.inBounds(px, py) || !water(terrain.get(px, py))) { ok = false; break; }
                    int perpX = -dy, perpY = dx;
                    List<Point> row = new ArrayList<>();
                    for (int wstep = 0; wstep < width; wstep++) {
                        double offset = wstep - (width - 1) / 2.0;
                        int wx = (int) pyRound(px + perpX * offset);
                        int wy = (int) pyRound(py + perpY * offset);
                        if (!terrain.inBounds(wx, wy)) { ok = false; break; }
                        row.add(new Point(wx, wy));
                    }
                    if (!ok) break;
                    cells.addAll(row);
                }
                if (ok && cells.size() >= 2) return cells;
            }
        }
        return null;
    }

    /**
     * Picks shoreline cells spread around the coastline (greedy spacing)
     * and extends a small pier or dock structure out over the water. If the
     * settlement touches open ocean anywhere, at least one ocean dock is
     * guaranteed regardless of spacing; every further dock must be at least
     * minSpacing from every other.
     */
    public static List<Dock> placeDocks(BoolGrid compMask, IntGrid terrain, BoolGrid occupied, List<Point> pathPoints,
                                         Rng rng, int maxDocks, int minSpacing, int nearRoadThresh) {
        List<ShoreEntry> shore = findShoreline(compMask, terrain);
        if (shore.isEmpty()) return new ArrayList<>();

        List<ShoreEntry> oceanShore = new ArrayList<>();
        List<ShoreEntry> riverShore = new ArrayList<>();
        for (ShoreEntry s : shore) (s.kind().equals("ocean") ? oceanShore : riverShore).add(s);

        List<Dock> docks = new ArrayList<>();
        List<Point> chosen = new ArrayList<>();

        java.util.function.BiFunction<Integer, Integer, Boolean> farEnough = (x, y) -> {
            for (Point o : chosen) if (Math.abs(x - o.x()) + Math.abs(y - o.y()) < minSpacing) return false;
            return true;
        };

        // guarantee: if the settlement touches ocean anywhere, at least one ocean dock gets placed
        List<ShoreEntry> oceanPool = new ArrayList<>(oceanShore);
        rng.shuffle(oceanPool);
        for (ShoreEntry s : oceanPool) {
            List<Point> cells = attemptDock(s, terrain, occupied, pathPoints, rng, nearRoadThresh);
            if (cells != null) {
                docks.add(new Dock("ocean_dock", cells, new Point(s.x(), s.y())));
                chosen.add(new Point(s.x(), s.y()));
                break;
            }
        }

        for (List<ShoreEntry> pool : List.of(oceanShore, riverShore)) {
            List<ShoreEntry> candidates = new ArrayList<>(pool);
            rng.shuffle(candidates);
            for (ShoreEntry s : candidates) {
                if (docks.size() >= maxDocks) break;
                if (!farEnough.apply(s.x(), s.y())) continue;
                List<Point> cells = attemptDock(s, terrain, occupied, pathPoints, rng, nearRoadThresh);
                if (cells != null) {
                    docks.add(new Dock(s.kind(), cells, new Point(s.x(), s.y())));
                    chosen.add(new Point(s.x(), s.y()));
                }
            }
            if (docks.size() >= maxDocks) break;
        }
        return docks;
    }

    private static List<Point> attemptDock(ShoreEntry s, IntGrid terrain, BoolGrid occupied, List<Point> pathPoints,
                                            Rng rng, int nearRoadThresh) {
        int distToRoad;
        if (pathPoints.isEmpty()) {
            distToRoad = 999;
        } else {
            int best = Integer.MAX_VALUE;
            for (Point p : pathPoints) best = Math.min(best, Math.abs(s.x() - p.x()) + Math.abs(s.y() - p.y()));
            distToRoad = best;
        }
        boolean nearRoad = distToRoad <= nearRoadThresh;
        String kind = (s.kind().equals("ocean") && nearRoad && rng.random() < 0.5) ? "merchant" : "fishing";
        List<Point> cells = tryBuildPier(s.x(), s.y(), s.dx(), s.dy(), kind, terrain, occupied, rng);
        if (cells == null && kind.equals("merchant")) {
            cells = tryBuildPier(s.x(), s.y(), s.dx(), s.dy(), "fishing", terrain, occupied, rng);
        }
        return cells;
    }

    public static List<Dock> placeDocks(BoolGrid compMask, IntGrid terrain, BoolGrid occupied, List<Point> pathPoints, Rng rng) {
        return placeDocks(compMask, terrain, occupied, pathPoints, rng, 8, 14, 14);
    }

    /**
     * BFS ring outward from the wall, across ordinary buildable land, up to
     * `depth` cells deep. Anything inside the wall (interiorMask) is
     * excluded no matter how close its BFS distance. Water blocks the
     * search from spreading further but a field can sit right up against a
     * riverbank.
     */
    public static BoolGrid farmlandRing(Set<Point> wallCells, IntGrid terrain, BoolGrid occupied,
                                         BoolGrid interiorMask, int depth) {
        int h = terrain.h, w = terrain.w;
        int[][] dist = new int[h][w];
        for (int[] row : dist) Arrays.fill(row, -1);
        Deque<Point> q = new ArrayDeque<>();
        for (Point p : wallCells) {
            if (terrain.inBounds(p.x(), p.y()) && dist[p.y()][p.x()] == -1) {
                dist[p.y()][p.x()] = 0;
                q.add(p);
            }
        }
        while (!q.isEmpty()) {
            Point p = q.poll();
            int x = p.x(), y = p.y();
            int d = dist[y][x];
            if (d >= depth) continue;
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (!terrain.inBounds(nx, ny) || dist[ny][nx] != -1) continue;
                if (terrain.get(nx, ny) == OCEAN) continue;
                dist[ny][nx] = d + 1;
                q.add(new Point(nx, ny));
            }
        }
        BoolGrid result = new BoolGrid(h, w);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            result.d[y][x] = dist[y][x] >= 1 && dist[y][x] <= depth && terrain.get(x, y) == BUILDABLE
                    && !occupied.d[y][x] && !interiorMask.d[y][x];
        }
        return result;
    }

    /**
     * The only kind of fort in the generator: an outward bulge attached
     * directly to the main wall, like a bastion -- not a freestanding
     * building out in the farmland. Picks a candidate point along a wall
     * run that's long enough to anchor one, tries to grow a square outward
     * from the local wall normal, and only commits if it clears
     * buildings/water and stays in bounds. Returns null if nothing fits.
     */
    public static WallPocketFort placeWallPocketFort(BoolGrid footprint, Set<Point> wallCells, List<List<Point>> runs,
                                                      IntGrid terrain, BoolGrid occupied, Rng rng,
                                                      int minSize, int maxSize, double maxWaterFrac, int minRunLength) {
        List<Point> candidates = new ArrayList<>();
        for (List<Point> run : runs) {
            if (run.size() < minRunLength) continue;
            int step = Math.max(5, run.size() / 3);
            int lo = run.size() / 4;
            int hi = Math.max(run.size() / 4 + 1, 3 * run.size() / 4);
            for (int i = lo; i < hi; i += step) candidates.add(run.get(i));
        }
        if (candidates.isEmpty()) return null;
        rng.shuffle(candidates);
        int h = terrain.h, w = terrain.w;

        Set<Integer> sizeSet = new TreeSet<>(Collections.reverseOrder());
        sizeSet.add(minSize);
        sizeSet.add((minSize + maxSize) / 2);
        sizeSet.add(maxSize);
        List<Integer> sizes = new ArrayList<>(sizeSet);

        for (Point anchor : candidates) {
            int ax = anchor.x(), ay = anchor.y();
            double[] normal = localOutwardNormal(ax, ay, footprint);
            double nx = normal[0], ny = normal[1];
            int dirx, diry;
            if (Math.abs(nx) >= Math.abs(ny)) { dirx = (nx >= 0) ? 1 : -1; diry = 0; }
            else { dirx = 0; diry = (ny >= 0) ? 1 : -1; }

            for (int size : sizes) {
                int fx0, fy0;
                if (dirx != 0) {
                    fx0 = (dirx > 0) ? ax : ax - size + 1;
                    fy0 = ay - size / 2;
                } else {
                    fy0 = (diry > 0) ? ay : ay - size + 1;
                    fx0 = ax - size / 2;
                }
                if (!(fx0 >= 0 && fx0 + size <= w && fy0 >= 0 && fy0 + size <= h)) continue;
                List<Point> cells = new ArrayList<>();
                for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) cells.add(new Point(fx0 + i, fy0 + j));
                int waterCount = 0;
                for (Point c : cells) if (water(terrain.get(c.x(), c.y()))) waterCount++;
                if ((double) waterCount / cells.size() > maxWaterFrac) continue;
                boolean anyOccupied = false;
                for (Point c : cells) if (occupied.get(c.x(), c.y())) { anyOccupied = true; break; }
                if (anyOccupied) continue;

                for (Point c : cells) occupied.set(c.x(), c.y(), true);
                Set<Point> attach = new LinkedHashSet<>();
                if (dirx != 0) {
                    int attachX = (dirx > 0) ? fx0 : fx0 + size - 1;
                    for (int y = fy0; y < fy0 + size; y++) attach.add(new Point(attachX, y));
                } else {
                    int attachY = (diry > 0) ? fy0 : fy0 + size - 1;
                    for (int x = fx0; x < fx0 + size; x++) attach.add(new Point(x, attachY));
                }
                return new WallPocketFort(new Rect(fx0, fy0, size, size), attach);
            }
        }
        return null;
    }

    public static WallPocketFort placeWallPocketFort(BoolGrid footprint, Set<Point> wallCells, List<List<Point>> runs,
                                                       IntGrid terrain, BoolGrid occupied, Rng rng) {
        // ~3x the original 7-12 bastion: a proper outwork, not a turret.
        // minRunLength tracks maxSize so there's always a wall stretch long
        // enough to anchor it; if nothing that big fits, no fort is placed.
        return placeWallPocketFort(footprint, wallCells, runs, terrain, occupied, rng, 21, 36, 0.35, 40);
    }

    /**
     * Same greedy shrink-to-fit packer as packBuildings, just bigger and
     * sparser -- real fields are big flat rectangles, not a tightly packed
     * district, so a single size tier and a wider margin is enough.
     */
    public static List<Rect> packFarms(BoolGrid mask, BoolGrid occupied, Rng rng, int minSize, int maxSize, int margin) {
        List<Point> pts = mask.where();
        if (pts.isEmpty()) return new ArrayList<>();
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
        for (Point p : pts) {
            x0 = Math.min(x0, p.x()); x1 = Math.max(x1, p.x());
            y0 = Math.min(y0, p.y()); y1 = Math.max(y1, p.y());
        }
        x1 += 1; y1 += 1;
        District district = new District(x0, y0, x1, y1);
        List<int[]> tiers = new ArrayList<>();
        tiers.add(new int[]{minSize, maxSize});
        return packBuildings(district, mask, occupied, rng, minSize, maxSize, margin, tiers);
    }

    public static List<Rect> packFarms(BoolGrid mask, BoolGrid occupied, Rng rng) {
        return packFarms(mask, occupied, rng, 5, 9, 1);
    }

    // ===================================================================
    // farmland: Voronoi-subdivided fields grown outward from the wall
    // (supersedes the farmlandRing/packFarms rectangle-packer above, which
    // is kept only because the Python source still leaves it in place)
    // ===================================================================

    private record FarmHeapNode(double d, long counter, int x, int y) implements Comparable<FarmHeapNode> {
        @Override
        public int compareTo(FarmHeapNode o) {
            int c = Double.compare(d, o.d);
            if (c != 0) return c;
            return Long.compare(counter, o.counter);
        }
    }

    /**
     * Cost-distance growth outward from the wall, in the same style as
     * growTown: stepping onto a MAIN_PATH cell costs much less than open
     * ground, so the claimed region naturally reaches further and clusters
     * along the roads leaving the city rather than forming a uniform ring
     * -- closer to how real farmland actually sits around a settlement,
     * following the cart roads out rather than expanding evenly in every
     * direction. Ocean stops growth outright; the town's own interior is
     * never claimed, and neither is anything already occupied (buildings,
     * docks, etc.).
     */
    public static BoolGrid growFarmlandRegion(Set<Point> wallCells, IntGrid terrain, BoolGrid occupied,
                                               BoolGrid interiorMask, int depth, double pathCost,
                                               double jitterFrac, Rng rng) {
        int h = terrain.h, w = terrain.w;
        double[][] cost = new double[h][w];
        for (double[] row : cost) Arrays.fill(row, Double.POSITIVE_INFINITY);
        boolean[][] visited = new boolean[h][w];
        BoolGrid claimed = new BoolGrid(h, w);
        PriorityQueue<FarmHeapNode> heap = new PriorityQueue<>();
        long counter = 0;
        for (Point p : wallCells) {
            int x = p.x(), y = p.y();
            if (terrain.inBounds(x, y) && !hardBlocked(terrain.get(x, y)) && cost[y][x] > 0) {
                cost[y][x] = 0.0;
                heap.add(new FarmHeapNode(0.0, counter, x, y));
                counter++;
            }
        }
        while (!heap.isEmpty()) {
            FarmHeapNode node = heap.poll();
            double d = node.d();
            int x = node.x(), y = node.y();
            if (visited[y][x]) continue;
            visited[y][x] = true;
            if (d > depth) continue;
            if (!interiorMask.get(x, y) && !occupied.get(x, y)) claimed.set(x, y, true);
            for (int[] dir : DIRS4) {
                int nx = x + dir[0], ny = y + dir[1];
                if (!terrain.inBounds(nx, ny) || visited[ny][nx]) continue;
                if (hardBlocked(terrain.get(nx, ny))) continue;
                double stepCost = (terrain.get(nx, ny) == MAIN_PATH) ? pathCost : 1.0;
                double jitter = 1.0 + ((rng != null) ? rng.uniform(-jitterFrac, jitterFrac) : 0.0);
                double nd = d + stepCost * jitter;
                if (nd < cost[ny][nx]) {
                    cost[ny][nx] = nd;
                    heap.add(new FarmHeapNode(nd, counter, nx, ny));
                    counter++;
                }
            }
        }
        return claimed;
    }

    public static BoolGrid growFarmlandRegion(Set<Point> wallCells, IntGrid terrain, BoolGrid occupied,
                                               BoolGrid interiorMask, int depth, Rng rng) {
        return growFarmlandRegion(wallCells, terrain, occupied, interiorMask, depth, 0.35, 0.15, rng);
    }

    /**
     * Rejection-sampled seed points inside mask, each at least `spacing`
     * from every other -- rough Poisson-disk spacing, good enough for field
     * sizing without a full Bridson's-algorithm implementation.
     */
    public static List<Point> scatterVoronoiSeeds(BoolGrid mask, double spacing, Rng rng, int maxSeeds) {
        List<Point> candidates = mask.where();
        if (candidates.isEmpty()) return new ArrayList<>();
        rng.shuffle(candidates);
        List<Point> seeds = new ArrayList<>();
        double spacing2 = spacing * spacing;
        for (Point p : candidates) {
            if (seeds.size() >= maxSeeds) break;
            int x = p.x(), y = p.y();
            boolean farEnough = true;
            for (Point s : seeds) {
                double dx = x - s.x(), dy = y - s.y();
                if (dx * dx + dy * dy < spacing2) { farEnough = false; break; }
            }
            if (farEnough) seeds.add(p);
        }
        return seeds;
    }

    public static List<Point> scatterVoronoiSeeds(BoolGrid mask, double spacing, Rng rng) {
        return scatterVoronoiSeeds(mask, spacing, rng, 400);
    }

    /**
     * Nearest-seed label for every true cell in mask, via brute-force
     * distance -- fine for the seed counts a field-sized spacing actually
     * produces (tens to low hundreds).
     */
    public static int[][] voronoiLabels(BoolGrid mask, List<Point> seeds) {
        int h = mask.h, w = mask.w;
        int[][] labels = new int[h][w];
        for (int[] row : labels) Arrays.fill(row, -1);
        if (seeds.isEmpty()) return labels;
        for (Point p : mask.where()) {
            int x = p.x(), y = p.y();
            int best = 0;
            long bestD2 = Long.MAX_VALUE;
            for (int i = 0; i < seeds.size(); i++) {
                Point s = seeds.get(i);
                long dx = x - s.x(), dy = y - s.y();
                long d2 = dx * dx + dy * dy;
                if (d2 < bestD2) { bestD2 = d2; best = i; }
            }
            labels[y][x] = best;
        }
        return labels;
    }

    /**
     * Cells where the nearest-seed label differs from a 4-neighbor's -- the
     * raw 1-cell Voronoi edge -- dilated out to roughly `thickness` cells
     * wide. These become the cart paths between fields.
     */
    public static BoolGrid voronoiBoundaryMask(int[][] labels, BoolGrid mask, int thickness) {
        int h = mask.h, w = mask.w;
        BoolGrid edge = new BoolGrid(h, w);
        for (int[] dir : DIRS4) {
            int dy = dir[1], dx = dir[0];
            int[][] shifted = shiftIntArray(labels, dy, dx, -999);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (mask.d[y][x] && labels[y][x] != shifted[y][x]) edge.d[y][x] = true;
                }
            }
        }
        BoolGrid boundary = dilateMask(edge, Math.max(1, thickness / 2));
        return boundary.and(mask);
    }

    public record FarmlandResult(BoolGrid fieldMask, BoolGrid pathMask) {}

    /**
     * Puts it all together: grow a road-focused farmland region outside the
     * wall, split it into fields with a Voronoi diagram, and turn the cell
     * boundaries -- plus every real road already running through the area
     * -- into a consistent boundaryThickness-wide non-farmable path
     * network, so fields never grow over an existing road and always leave
     * room for a cart path between them.
     */
    public static FarmlandResult generateFarmland(BoolGrid interiorMask, Set<Point> wallCells, IntGrid terrain,
                                                   BoolGrid occupied, Rng rng, int depth, double fieldSpacing,
                                                   int boundaryThickness) {
        BoolGrid region = growFarmlandRegion(wallCells, terrain, occupied, interiorMask, depth, rng);
        if (!region.any()) return new FarmlandResult(region, new BoolGrid(region.h, region.w));

        BoolGrid roadMask = terrain.maskEquals(MAIN_PATH).and(region);
        List<Point> seeds = scatterVoronoiSeeds(region, fieldSpacing, rng);
        if (seeds.isEmpty()) {
            // region too small to subdivide into fields -- treat the whole
            // thing as one field, still keeping any road cut out of it
            return new FarmlandResult(region.andNot(roadMask), roadMask);
        }

        int[][] labels = voronoiLabels(region, seeds);
        BoolGrid boundary = voronoiBoundaryMask(labels, region, boundaryThickness);
        boundary = boundary.or(roadMask);
        BoolGrid fieldMask = region.andNot(boundary);
        return new FarmlandResult(fieldMask, boundary);
    }

    /**
     * Predrawn MAIN_PATH cells come in as a single-cell line; dilate them
     * out to `width` cells so real roads read as roads rather than a
     * footpath, without ever painting over water.
     */
    public static IntGrid widenMainPath(IntGrid terrain, int width) {
        if (width <= 1) return terrain;
        int radius = (width - 1) / 2;
        List<Point> pathCells = new ArrayList<>();
        for (int y = 0; y < terrain.h; y++) for (int x = 0; x < terrain.w; x++) {
            if (terrain.get(x, y) == MAIN_PATH) pathCells.add(new Point(x, y));
        }
        IntGrid outTerrain = terrain.copy();
        int h = terrain.h, w = terrain.w;
        for (Point p : pathCells) {
            int y = p.y(), x = p.x();
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int ny = y + dy, nx = x + dx;
                    if (ny >= 0 && ny < h && nx >= 0 && nx < w && !water(outTerrain.get(nx, ny))) {
                        outTerrain.set(nx, ny, MAIN_PATH);
                    }
                }
            }
        }
        return outTerrain;
    }

    private record CompFootprint(BoolGrid footprint, List<Point> smoothed, Set<Point> wallCells, List<List<Point>> runs) {}

    private static boolean regionHasValue(IntGrid terrain, Rect r, int value) {
        for (int y = r.y(); y < r.y() + r.h(); y++) {
            for (int x = r.x(); x < r.x() + r.w(); x++) {
                if (terrain.get(x, y) == value) return true;
            }
        }
        return false;
    }

    /**
     * terrain: 2D int grid of BUILDABLE / OCEAN / RIVER / MAIN_PATH
     *          (BRIDGE cells are also accepted -- pre-marked known crossings)
     * towns:   each settlement gets its own center point and its own size.
     *          Radius is a cost-distance, not raw pixels: it reaches much
     *          further along MAIN_PATH cells than through open terrain, and
     *          never crosses water no matter how large.
     * prebuiltStructures: fixed-size important buildings that get first
     *          claim on the biggest gaps inside the wall, largest
     *          structures into biggest gaps first, before
     *          market/church/ordinary-house infill runs. Structures that
     *          don't fit anywhere are reported back in
     *          result.unplacedStructures rather than silently dropped.
     */
    public static Result generateSettlement(IntGrid terrainIn, List<Town> towns, boolean wall, boolean citadel,
                                              boolean farms, boolean docks, boolean externalForts, int farmDepth,
                                              int farmFieldSpacing, int farmPathThickness, int predrawnRoadWidth,
                                              Long seed, int maxLandmarks,
                                              List<PrebuiltStructure> prebuiltStructures) {
        Rng rng = new Rng(seed);
        IntGrid terrainOriginal = terrainIn;
        int h = terrainOriginal.h, w = terrainOriginal.w;

        // bridges: detect roads crossing water (or honor pre-tagged BRIDGE
        // cells) and fold them into a "traversal" terrain where they act as
        // ordinary MAIN_PATH for every downstream algorithm -- growth, A*,
        // gate punching -- while still rendering distinctly as a bridge deck
        Set<Point> bridgeSeed = findBridges(terrainOriginal, 12);
        IntGrid algoTerrain = terrainOriginal.copy();
        for (Point p : bridgeSeed) {
            if (algoTerrain.inBounds(p.x(), p.y())) algoTerrain.set(p.x(), p.y(), MAIN_PATH);
        }
        algoTerrain = widenMainPath(algoTerrain, predrawnRoadWidth);
        IntGrid terrain = algoTerrain;

        Set<Point> bridgeCells = new LinkedHashSet<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            if (terrain.get(x, y) == MAIN_PATH && water(terrainOriginal.get(x, y))) bridgeCells.add(new Point(x, y));
        }

        IntGrid out = new IntGrid(h, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int tOrig = terrainOriginal.get(x, y);
                int val;
                if (tOrig == OCEAN) val = OUT_OCEAN;
                else if (tOrig == RIVER) val = OUT_RIVER;
                else if (terrain.get(x, y) == MAIN_PATH) val = OUT_MAIN_PATH;
                else val = OUT_TERRAIN;
                out.set(x, y, val);
            }
        }
        for (Point p : bridgeCells) out.set(p.x(), p.y(), OUT_BRIDGE);

        BoolGrid extent = growExtent(terrain, towns, rng);
        BoolGrid occupied = new BoolGrid(h, w);
        BoolGrid pathMask = terrain.maskEquals(MAIN_PATH);
        List<Point> pathPoints = pathMask.where();

        // merge claimed cells through path cells before splitting into
        // landmasses -- a road running through a settlement shouldn't count
        // as cutting it into separate "towns" for wall/citadel purposes, only
        // water actually crossing the extent should do that
        BoolGrid bridgeMask = extent.or(pathMask);
        List<BoolGrid> rawComponents = connectedComponents(bridgeMask);
        List<BoolGrid> components = new ArrayList<>();
        for (BoolGrid raw : rawComponents) {
            BoolGrid compBuildable = raw.and(extent);
            if (compBuildable.any()) components.add(compBuildable);
        }
        List<Rect> allBuildings = new ArrayList<>();
        List<Object[]> componentDistricts = new ArrayList<>(); // {BoolGrid comp, List<District> districts}
        for (BoolGrid comp : components) {
            List<District> districts = bspDistricts(comp, rng);
            List<Rect> compBuildings = new ArrayList<>();
            for (District d : districts) {
                List<Rect> blds = packBuildings(d, comp, occupied, rng);
                compBuildings.addAll(blds);
            }
            allBuildings.addAll(compBuildings);
            componentDistricts.add(new Object[]{comp, districts});
        }

        // river was treated as land during growth/packing so it wouldn't
        // fracture the settlement -- now cull any building that actually
        // landed on a river cell, and rebuild the occupancy grid from what's
        // left (no margin needed anymore, packing is done)
        {
            List<Rect> filtered = new ArrayList<>();
            for (Rect b : allBuildings) if (!regionHasValue(terrain, b, RIVER)) filtered.add(b);
            allBuildings = filtered;
        }
        occupied = new BoolGrid(h, w);
        for (Rect b : allBuildings) {
            for (int yy = b.y(); yy < b.y() + b.h(); yy++)
                for (int xx = b.x(); xx < b.x() + b.w(); xx++)
                    occupied.set(xx, yy, true);
        }

        Result result = new Result();
        result.out = out;
        result.extent = extent;
        result.buildings = allBuildings;

        // organic wall footprint per landmass: close small holes, expand
        // outward, then trace the actual (non-convex) boundary -- replaces
        // the old convex-hull wall, so a wall segment can never end up
        // cutting through the inside of the real footprint
        List<CompFootprint> compFootprints = new ArrayList<>();
        BoolGrid interiorMask = new BoolGrid(h, w);
        Set<Point> allWallCells = new LinkedHashSet<>();
        Set<Point> allGates = new LinkedHashSet<>();
        if (wall) {
            for (Object[] cd : componentDistricts) {
                BoolGrid comp = (BoolGrid) cd[0];
                // Fold the stretch of road running through (or right up to)
                // this component into the footprint input. Otherwise a road
                // wider than the closing radius stays an unclaimed channel and
                // traceBoundary walks the wall down both of its banks deep into
                // town -- which then reads as a long ribbon of gates with a
                // tower flanking every one. Clipped to near the town so the
                // wall doesn't chase roads off across the whole sampled area.
                BoolGrid roadThroughTown = pathMask.and(dilateMask(comp, 6));
                BoolGrid footprint = organicFootprint(comp.or(roadThroughTown), 2, 3);
                List<Point> boundary = traceBoundary(footprint);
                if (boundary.size() < 8) continue;
                List<Point> smoothed = smoothAndJitterBoundary(boundary, rng);
                RingResult rr = rasterizeWallRing(smoothed, terrain, 6);
                compFootprints.add(new CompFootprint(footprint, smoothed, rr.wallCells, rr.runs));
                interiorMask.orInPlace(footprint);
                allWallCells.addAll(rr.wallCells);
            }

            // a road can enter through a concave dent that even the organic
            // wall's smoothing pass rounds slightly differently than the raw
            // footprint -- bridge any remaining gap so every real road
            // actually reaches the wall it's punching a gate in
            Set<Point> bridgesToWall = bridgeRoadsToWall(pathMask, allWallCells, occupied, terrain, 3);
            for (Point p : bridgesToWall) {
                if (out.get(p.x(), p.y()) == OUT_TERRAIN) out.set(p.x(), p.y(), OUT_MAIN_PATH);
            }

            Set<Point> gatePathCells = new LinkedHashSet<>(pathPoints);
            gatePathCells.addAll(bridgesToWall);
            allGates = punchGates(allWallCells, gatePathCells);
            allGates = capGateRuns(allGates, allWallCells, 6);
            allWallCells.removeAll(allGates);

            // scored tower placement: real 4x5 structures straddling the
            // wall line, not just a recolored cell -- sparse, weighted toward
            // major bends and gate flanks, occasional on long straight runs
            List<Tower> allTowers = new ArrayList<>();
            for (CompFootprint cf : compFootprints) {
                List<Point> anchors = selectTowerAnchors(cf.smoothed(), cf.runs(), allGates, rng);
                for (Point anchor : anchors) {
                    Rect placed = placeTowerStructure(anchor, interiorMask, occupied, terrain, rng);
                    if (placed == null) continue;
                    int tx0 = placed.x(), ty0 = placed.y(), tw = placed.w(), th = placed.h();
                    // the tower structure replaces the ordinary wall in its
                    // footprint -- drop any regular wall cells it overlaps
                    for (int yy = ty0; yy < ty0 + th; yy++) {
                        for (int xx = tx0; xx < tx0 + tw; xx++) {
                            allWallCells.remove(new Point(xx, yy));
                            if (occupied.inBounds(xx, yy)) occupied.set(xx, yy, true);
                        }
                    }
                    // entrance: the footprint cell nearest the original
                    // anchor point opens onto the town instead of being wall
                    int ax = anchor.x(), ay = anchor.y();
                    Point entrance = null;
                    int bestD = Integer.MAX_VALUE;
                    for (int yy = ty0; yy < ty0 + th; yy++) {
                        for (int xx = tx0; xx < tx0 + tw; xx++) {
                            int dd = Math.abs(xx - ax) + Math.abs(yy - ay);
                            if (dd < bestD) { bestD = dd; entrance = new Point(xx, yy); }
                        }
                    }
                    allTowers.add(new Tower(tx0, ty0, tw, th, entrance));
                }
            }

            // the one and only kind of "fort": an outward bastion attached
            // directly to the wall, only if a spot is found that actually
            // fits -- tried after towers so it doesn't fight them for space
            WallPocketFort wallPocketFort = null;
            if (externalForts && rng.random() < 0.5) {
                List<CompFootprint> fortCandidates = new ArrayList<>(compFootprints);
                rng.shuffle(fortCandidates);
                for (CompFootprint cf : fortCandidates) {
                    wallPocketFort = placeWallPocketFort(cf.footprint(), allWallCells, cf.runs(), terrain, occupied, rng);
                    if (wallPocketFort != null) break;
                }
            }

            for (Point p : allWallCells) {
                // defensive: never physically block a path with a wall tile,
                // even if geometry says they coincide -- a path/bridge cell
                // always wins and reads as an opening, not a solid block
                int cur = out.get(p.x(), p.y());
                if (cur == OUT_CONNECTOR_PATH || cur == OUT_MAIN_PATH || cur == OUT_BRIDGE) continue;
                out.set(p.x(), p.y(), OUT_CITY_WALL);
            }
            for (Point p : allGates) out.set(p.x(), p.y(), OUT_GATE);
            for (Tower t : allTowers) {
                for (int yy = t.y0; yy < t.y0 + t.h; yy++) {
                    for (int xx = t.x0; xx < t.x0 + t.w; xx++) {
                        if (!terrain.inBounds(xx, yy)) continue;
                        // a path/gate cell always wins -- never stamp tower over a road
                        int cur = out.get(xx, yy);
                        if (cur == OUT_MAIN_PATH || cur == OUT_BRIDGE || cur == OUT_CONNECTOR_PATH || cur == OUT_GATE) continue;
                        boolean edge = (xx == t.x0 || xx == t.x0 + t.w - 1 || yy == t.y0 || yy == t.y0 + t.h - 1);
                        if (xx == t.entrance.x() && yy == t.entrance.y()) out.set(xx, yy, OUT_DOOR);
                        else out.set(xx, yy, edge ? OUT_TOWER : OUT_TOWER_FLOOR);
                    }
                }
            }
            if (wallPocketFort != null) {
                Rect fr = wallPocketFort.rect;
                Set<Point> attach = wallPocketFort.attach;
                for (int yy = fr.y(); yy < fr.y() + fr.h(); yy++) {
                    for (int xx = fr.x(); xx < fr.x() + fr.w(); xx++) {
                        boolean edge = (xx == fr.x() || xx == fr.x() + fr.w() - 1 || yy == fr.y() || yy == fr.y() + fr.h() - 1);
                        if (attach.contains(new Point(xx, yy))) out.set(xx, yy, OUT_FORT_FLOOR); // open seam into town, not a wall
                        else out.set(xx, yy, edge ? OUT_FORT_WALL : OUT_FORT_FLOOR);
                    }
                }
                for (Point p : attach) allWallCells.remove(p);
            }
            result.wall = allWallCells;
            result.gates = allGates;
            result.towers = allTowers;
            result.wallPocketFort = wallPocketFort;
        }

        // fill the empty pockets a wall footprint encloses: prebuilt
        // structures get first claim, then market/church, then ordinary
        // house infill for whatever's left
        Map<Integer, String> landmarkKinds = new LinkedHashMap<>();
        List<PlacedStructure> placedPrebuiltAll = new ArrayList<>();
        List<PrebuiltStructure> remainingStructs = (prebuiltStructures != null) ? new ArrayList<>(prebuiltStructures) : new ArrayList<>();
        if (wall) {
            List<Rect> extraBuildings = new ArrayList<>();
            Map<Integer, String> extraKinds = new LinkedHashMap<>();
            for (CompFootprint cf : compFootprints) {
                FillGapsResult fgr = fillGaps(cf.footprint(), terrain, occupied, rng, maxLandmarks, remainingStructs);
                int base = allBuildings.size() + extraBuildings.size();
                for (Map.Entry<Integer, String> e : fgr.landmarkKinds.entrySet()) {
                    extraKinds.put(base + e.getKey(), e.getValue());
                }
                extraBuildings.addAll(fgr.newBuildings);
                placedPrebuiltAll.addAll(fgr.placedPrebuilt);
                remainingStructs = fgr.unplacedPrebuilt;
            }
            allBuildings.addAll(extraBuildings);
            landmarkKinds = extraKinds;
            occupied = new BoolGrid(h, w);
            for (Rect b : allBuildings) {
                for (int yy = b.y(); yy < b.y() + b.h(); yy++)
                    for (int xx = b.x(); xx < b.x() + b.w(); xx++)
                        occupied.set(xx, yy, true);
            }
            for (PlacedStructure p : placedPrebuiltAll) {
                Rect pr = p.rect;
                for (int yy = pr.y(); yy < pr.y() + pr.h(); yy++)
                    for (int xx = pr.x(); xx < pr.x() + pr.w(); xx++)
                        occupied.set(xx, yy, true);
            }
        }

        Map<String, int[]> landmarkColors = Map.of(
            "market", new int[]{OUT_MARKET_WALL, OUT_MARKET_FLOOR},
            "church", new int[]{OUT_CHURCH_WALL, OUT_CHURCH_FLOOR}
        );

        // burn buildings into output grid (doors decided below, once we know
        // which side actually faces the path network). Landmarks get their
        // own wall/floor colors per kind instead of the ordinary house palette.
        for (int i = 0; i < allBuildings.size(); i++) {
            Rect b = allBuildings.get(i);
            String kind = landmarkKinds.get(i);
            int[] colors = (kind != null) ? landmarkColors.get(kind) : null;
            int wallColor = (colors != null) ? colors[0] : OUT_BUILDING_WALL;
            int floorColor = (colors != null) ? colors[1] : OUT_BUILDING_FLOOR;
            for (int yy = b.y(); yy < b.y() + b.h(); yy++) {
                for (int xx = b.x(); xx < b.x() + b.w(); xx++) {
                    boolean edge = (xx == b.x() || xx == b.x() + b.w() - 1 || yy == b.y() || yy == b.y() + b.h() - 1);
                    out.set(xx, yy, edge ? wallColor : floorColor);
                }
            }
        }

        for (PlacedStructure p : placedPrebuiltAll) {
            Rect pr = p.rect;
            for (int yy = pr.y(); yy < pr.y() + pr.h(); yy++) {
                for (int xx = pr.x(); xx < pr.x() + pr.w(); xx++) {
                    boolean edge = (xx == pr.x() || xx == pr.x() + pr.w() - 1 || yy == pr.y() || yy == pr.y() + pr.h() - 1);
                    out.set(xx, yy, edge ? OUT_PREBUILT_WALL : OUT_PREBUILT_FLOOR);
                }
            }
        }
        result.placedStructures = placedPrebuiltAll;
        result.unplacedStructures = (prebuiltStructures != null && !prebuiltStructures.isEmpty())
                ? new ArrayList<>(remainingStructs.stream().map(s -> s.name != null ? s.name : "structure").toList())
                : new ArrayList<>();

        // connector network: grows from the main path outward, one building at
        // a time; each building's door is whichever wall side ends up facing
        // the point it connects to (nearest already-connected building or path)
        List<Rect> connectTargets = new ArrayList<>(allBuildings);
        for (PlacedStructure p : placedPrebuiltAll) connectTargets.add(p.rect);
        BoolGrid blockedForPath = occupied.copy();
        ConnectResult cr = connectBuildingsToNetwork(connectTargets, pathPoints, blockedForPath, terrain);
        for (Point p : cr.connectorCells) {
            if (out.get(p.x(), p.y()) == OUT_TERRAIN) out.set(p.x(), p.y(), OUT_CONNECTOR_PATH);
        }
        for (DoorInfo info : cr.doors.values()) {
            int wx = info.wall.x(), wy = info.wall.y();
            if (out.get(wx, wy) != OUT_PREBUILT_WALL) out.set(wx, wy, OUT_DOOR);
        }
        result.doors = cr.doors;
        result.landmarks = landmarkKinds;

        // farmland: a Voronoi-subdivided field mask grown outward from the
        // wall (cost-distance, so it follows roads rather than forming a
        // uniform ring), with cell boundaries and every real road folded in
        // as a consistent path network between fields
        if (wall && farms) {
            FarmlandResult fr = generateFarmland(interiorMask, allWallCells, terrain, occupied, rng,
                    farmDepth, farmFieldSpacing, farmPathThickness);
            for (Point p : fr.fieldMask().where()) {
                if (out.get(p.x(), p.y()) == OUT_TERRAIN) out.set(p.x(), p.y(), OUT_FARM_FIELD);
            }
            for (Point p : fr.pathMask().where()) {
                if (out.get(p.x(), p.y()) == OUT_TERRAIN) out.set(p.x(), p.y(), OUT_FARM_PATH);
            }
            occupied.orInPlace(fr.fieldMask());
            occupied.orInPlace(fr.pathMask());
            result.farmFields = fr.fieldMask();
            result.farmPaths = fr.pathMask();
        }

        if (wall && docks) {
            List<Dock> allDocks = new ArrayList<>();
            for (Object[] cd : componentDistricts) {
                BoolGrid comp = (BoolGrid) cd[0];
                allDocks.addAll(placeDocks(comp, terrainOriginal, occupied, pathPoints, rng));
            }
            for (Dock dock : allDocks) {
                for (Point p : dock.cells) {
                    if (out.inBounds(p.x(), p.y())) out.set(p.x(), p.y(), OUT_DOCK);
                }
            }
            result.docks = allDocks;
        }

        if (citadel) {
            for (Object[] cd : componentDistricts) {
                @SuppressWarnings("unchecked")
                BoolGrid comp = (BoolGrid) cd[0];
                @SuppressWarnings("unchecked")
                List<District> districts = (List<District>) cd[1];
                if (districts.isEmpty()) continue;
                List<Point> pts = comp.where();
                double ecx = 0, ecy = 0;
                for (Point p : pts) { ecx += p.x(); ecy += p.y(); }
                ecx /= pts.size();
                ecy /= pts.size();
                District best = null;
                double bestD = Double.MAX_VALUE;
                for (District d : districts) {
                    double dd = Math.pow(d.cx() - ecx, 2) + Math.pow(d.cy() - ecy, 2);
                    if (dd < bestD) { bestD = dd; best = d; }
                }
                int cx0 = best.x0 - 1, cy0 = best.y0 - 1, cx1 = best.x1 + 1, cy1 = best.y1 + 1;
                for (int yy = cy0; yy < cy1; yy++) {
                    for (int xx = cx0; xx < cx1; xx++) {
                        if (!terrain.inBounds(xx, yy) || water(terrain.get(xx, yy))) continue;
                        boolean edge = (xx == cx0 || xx == cx1 - 1 || yy == cy0 || yy == cy1 - 1);
                        if (edge) {
                            out.set(xx, yy, OUT_CITADEL_WALL);
                        } else {
                            int cur = out.get(xx, yy);
                            if (cur == OUT_TERRAIN || cur == OUT_CONNECTOR_PATH) out.set(xx, yy, OUT_CITADEL_FLOOR);
                        }
                    }
                }
            }
        }

        return result;
    }

    /** Convenience overload with the same defaults as the Python source. */
    public static Result generateSettlement(IntGrid terrain, List<Town> towns) {
        return generateSettlement(terrain, towns, true, true, true, true, true, 30, 22, 4, 3, null, 3, null);
    }

}
