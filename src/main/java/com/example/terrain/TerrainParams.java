package com.example.terrain;

/**
 * Tunables for the blended terrain bake. Serialised as the {@code terrain}
 * section of {@code config/fantasymapgenerator/heightmap.json}; every value is
 * part of the bake signature, so editing one rebuilds the bake on next load.
 *
 * <p>Distances are in blocks, heights in blocks above sea level unless noted.
 * refinemap/terrain.py is the Python workbench with the same parameters.</p>
 */
public final class TerrainParams {

    // ---- raster -------------------------------------------------------
    /** Blocks per raster cell. 0 = auto: half the map scale, at least 4. */
    public double cellSize = 0;
    /** Upper bound on raster cells; the cell size grows to stay under it. */
    public int maxCells = 12_000_000;

    // ---- elevation curve ------------------------------------------------
    /** Height above sea of the highest point of the macro (pre-noise) shape. */
    public double peak = 420;
    /** Exponent of the FMG height -> Y curve. Higher = flatter lowlands, sharper mountains. */
    public double gamma = 1.8;
    /** Blur (FMG pixels) that removes the Voronoi facets of the source cells. */
    public double defacet = 4.0;
    /**
     * Mountain compression: above {@code mountainKnee} blocks over sea level every
     * further block of height counts only {@code mountainScale} blocks (eased in
     * over {@code mountainSoftness}). Lowers and softens the mountains without
     * touching the lowlands below the knee. mountainScale 1 = off.
     */
    public double mountainKnee = 80;
    public double mountainScale = 0.55;
    public double mountainSoftness = 60;

    // ---- coast / ocean --------------------------------------------------
    public double coastBlur = 2.5;
    public double coastRough = 0.35;
    public double coastRamp = 90;
    public double oceanDepth = 50;
    public double shelf = 200;

    // ---- lowlands -------------------------------------------------------
    /** Blur (blocks) on biome borders when mixing their terrain styles. */
    public double biomeBlend = 120;
    /** Std of the large foothill hills (blocks); grows in with elevation. */
    public double hillAmp = 9;
    public double duneWavelength = 100;

    // ---- mountains / erosion ------------------------------------------
    /** Main ridge wavelength. */
    public double feature = 1400;
    /** Ridge std as a fraction of {@link #peak}. */
    public double relief = 0.24;
    public int iterations = 50;
    public double erodibility = 0.12;
    /** Catchment (blocks^2) at which channels start to cut. */
    public double channelArea = 15000;
    public double diffusion = 0.05;
    /** Steepest stable slope (height per horizontal block). */
    public double talus = 1.1;
    public double maxCarve = 220;
    public double maxCarveLow = 10;

    // ---- blend weight ---------------------------------------------------
    /** Fraction of {@link #peak} where eroded terrain starts to replace lowland noise. */
    public double mountainStart = 0.14;
    /** ...and where it has fully replaced it. */
    public double mountainFull = 0.40;
    public double coastCalmStart = 100;
    public double coastCalmEnd = 450;
    /** Catchment (blocks^2) that counts as a valley for downstream persistence. */
    public double valleyArea = 4e5;
    /** How far (blocks) a mountain valley persists downstream into the lowlands. */
    public double valleyReach = 1800;
    public double valleySide = 90;
    public double valleySoften = 24;

    // ---- rivers ---------------------------------------------------------
    public double meander = 35;
    public double meanderWavelength = 420;
    public double burnSlope = 1.2;
    public double burnReach = 60;
    /** Extra floodplain half-width for the largest river. */
    public double floodplain = 90;
    /** Valley wall width as a multiple of the floodplain, lowlands / mountains. */
    public double wallLow = 1.6;
    public double wallHigh = 0.6;
    public double kmToBlocks = 2000.0 / 12.0;
    /**
     * Where a road runs along a river, keep the river's bank this many blocks
     * beyond the road's shoulder (the river moves to the lower side). 0 = off.
     */
    public double riverRoadClearance = 5;

    // ---- lakes ----------------------------------------------------------
    public double lakeShore = 250;

    // ---- roads ------------------------------------------------------------
    /** Grade roads into the terrain at bake time (cut and fill). */
    public boolean gradeRoads = true;
    /** Preferred road grade (rise per block). */
    public double roadGrade = 0.25;
    /** Grade allowed where the preferred one would need a huge cut or fill. */
    public double roadMaxGrade = 0.8;
    public double roadMaxCut = 10;
    public double roadMaxFill = 5;
    /** Profile smoothing along the road (blocks). */
    public double roadSmoothing = 30;
    /** Embankment / cutting side slope, as horizontal blocks per block of height. */
    public double roadSideRatio = 2.5;

    // ---- per-block detail -------------------------------------------------
    /** Detail noise amplitude on gentle ground (blocks). */
    public double fineDetail = 1.4;
    /** Multiplier on the rock/crag noise added on steep ground. */
    public double cragDetail = 1.0;

    /** Keep the terrain at least this far under the build limit. */
    public double topMargin = 24;

    /** Height above sea after mountain compression (identity below the knee). */
    public double compress(double above) {
        if (above <= mountainKnee || mountainScale >= 1) return above;
        double y = above - mountainKnee;
        double s = Math.max(1e-6, mountainSoftness);
        return mountainKnee + mountainScale * y + (1 - mountainScale) * s * (1 - Math.exp(-y / s));
    }
}
