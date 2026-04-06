package com.example.border;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

public final class ClientBorderOverlay {

    private static volatile boolean enabled;
    private static volatile BorderMode mode = BorderMode.OFF;
    private static volatile int colorRgb = 0xFFFFFF;
    private static volatile String name = "";
    private static volatile int regionId;

    // Packed endpoint list: (x[0],z[0]) -> (x[1],z[1]) is segment 0, etc.
    private static volatile float[] xs = new float[0];
    private static volatile float[] zs = new float[0];

    private static final double WALL_HEIGHT = 256.0; // Tall enough to cover build height in all versions, including modded ones with higher limits.
    private static final double SURFACE_EPSILON = 0.05;
    private static final double MIN_BORDER_Y = 63.0;
    private static final int MAX_QUADS_PER_FRAME = 200_000;

    private ClientBorderOverlay() {
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(BorderDataPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (!payload.enabled()) {
                    clear();
                    return;
                }

                enabled = true;
                mode = BorderMode.fromId(payload.modeId());
                regionId = payload.regionId();
                colorRgb = payload.colorRgb();
                name = payload.name();
                xs = payload.xs();
                zs = payload.zs();
            });
        });

        WorldRenderEvents.LAST.register(context -> {
            float[] localXs = xs;
            float[] localZs = zs;
            int count = Math.min(localXs.length, localZs.length);

            if (!enabled || count < 2) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                return;
            }

            Camera camera = context.camera();
            Vec3d camPos = camera.getPos();

            MatrixStack matrices = context.matrixStack();
            matrices.push();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);

            Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
            // Lazily begin building only if we actually emit at least one quad.
            // (Otherwise builder.end() throws: "BufferBuilder was empty".)
            BufferBuilder[] builderRef = new BufferBuilder[1];

            float r = ((colorRgb >> 16) & 0xFF) / 255.0f;
            float g = ((colorRgb >> 8) & 0xFF) / 255.0f;
            float b = (colorRgb & 0xFF) / 255.0f;
            float a = 0.85f;

            int viewDistanceChunks = client.options.getClampedViewDistance();
            double viewDistanceBlocks = (double) viewDistanceChunks * 16.0;
            double viewDistanceSq = viewDistanceBlocks * viewDistanceBlocks;

            // Convert each received segment into a block-grid stair-step path (no diagonals),
            // then render each step as a vertical quad.
            StepBudget budget = new StepBudget(MAX_QUADS_PER_FRAME);
            for (int i = 0; i + 1 < count && budget.hasRemaining(); i += 2) {
                int x0 = (int) Math.floor(localXs[i]);
                int z0 = (int) Math.floor(localZs[i]);
                int x1 = (int) Math.floor(localXs[i + 1]);
                int z1 = (int) Math.floor(localZs[i + 1]);

                if (!segmentInRangeXZ(camPos.x, camPos.z, x0, z0, x1, z1, viewDistanceSq)) {
                    continue;
                }

                emitStaircaseSegments(x0, z0, x1, z1, (sx0, sz0, sx1, sz1) -> {
                    if (!budget.hasRemaining()) {
                        return false;
                    }

                    // Per-step distance cull: a long segment can intersect the view radius while
                    // most of its staircase steps are far away. Avoid spending budget/time on those.
                    if (!segmentInRangeXZ(camPos.x, camPos.z, sx0, sz0, sx1, sz1, viewDistanceSq)) {
                        return true;
                    }

                    // Heightmap sampling around the line so borders hug terrain without diagonal slopes.
                    int yA = sampleTopYNearLine(client, sx0, sz0, sx1, sz1);
                    int yB = sampleTopYNearLine(client, sx1, sz1, sx0, sz0);
                    double yBottom = Math.max(yA, yB) + SURFACE_EPSILON;
                    if (yBottom < MIN_BORDER_Y) {
                        return true;
                    }
                    double yTop = yBottom + WALL_HEIGHT;

                    // Budget counts emitted quads (actual drawn geometry), not visited steps.
                    if (!budget.tryConsume(1)) {
                        return false;
                    }

                    BufferBuilder builder = builderRef[0];
                    if (builder == null) {
                        builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                        builderRef[0] = builder;
                    }

                    emitVerticalQuad(builder, positionMatrix, sx0, sz0, sx1, sz1, yBottom, yTop, r, g, b, a);
                    return true;
                });
            }

            if (builderRef[0] != null) {
                BufferRenderer.drawWithGlobalProgram(builderRef[0].end());
            }

            RenderSystem.enableCull();
            RenderSystem.disableBlend();

            matrices.pop();
        });
    }

    private static void clear() {
        enabled = false;
        mode = BorderMode.OFF;
        regionId = 0;
        colorRgb = 0xFFFFFF;
        name = "";
        xs = new float[0];
        zs = new float[0];
    }


    @FunctionalInterface
    private interface SegmentEmitter {
        boolean emit(int x0, int z0, int x1, int z1);
    }

    private static final class StepBudget {
        private int remaining;

        private StepBudget(int remaining) {
            this.remaining = remaining;
        }

        boolean hasRemaining() {
            return remaining > 0;
        }

        boolean tryConsume(int amount) {
            if (remaining < amount) {
                remaining = 0;
                return false;
            }
            remaining -= amount;
            return true;
        }
    }

    /**
     * Emits an axis-aligned “staircase” polyline from (x0,z0) to (x1,z1) on the integer grid.
     * Any diagonal Bresenham step is split into two orthogonal steps.
     */
    private static void emitStaircaseSegments(int x0, int z0, int x1, int z1, SegmentEmitter out) {
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;

        int err = dx - dz;
        boolean xMajor = dx >= dz;

        int x = x0;
        int z = z0;

        while (true) {
            if (x == x1 && z == z1) {
                return;
            }

            int prevX = x;
            int prevZ = z;

            int e2 = err << 1;
            boolean stepX = e2 > -dz;
            boolean stepZ = e2 < dx;

            int nextX = x;
            int nextZ = z;
            int nextErr = err;

            if (stepX) {
                nextErr -= dz;
                nextX += sx;
            }
            if (stepZ) {
                nextErr += dx;
                nextZ += sz;
            }

            if (prevX != nextX && prevZ != nextZ) {
                if (xMajor) {
                    if (!out.emit(prevX, prevZ, nextX, prevZ)) {
                        return;
                    }
                    if (!out.emit(nextX, prevZ, nextX, nextZ)) {
                        return;
                    }
                } else {
                    if (!out.emit(prevX, prevZ, prevX, nextZ)) {
                        return;
                    }
                    if (!out.emit(prevX, nextZ, nextX, nextZ)) {
                        return;
                    }
                }
            } else {
                if (!out.emit(prevX, prevZ, nextX, nextZ)) {
                    return;
                }
            }

            x = nextX;
            z = nextZ;
            err = nextErr;
        }
    }

    private static boolean segmentInRangeXZ(double camX, double camZ, int x0, int z0, int x1, int z1, double rangeSq) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);

        double closestX = camX < minX ? minX : (camX > maxX ? maxX : camX);
        double closestZ = camZ < minZ ? minZ : (camZ > maxZ ? maxZ : camZ);

        double dx = camX - closestX;
        double dz = camZ - closestZ;
        return dx * dx + dz * dz <= rangeSq;
    }

    private static int sampleTopYNearLine(MinecraftClient client, int x0, int z0, int x1, int z1) {
        if (client.world == null) {
            return 0;
        }

        // For axis-aligned steps, sample both sides of the block boundary and take max.
        boolean alongX = z0 == z1 && x0 != x1;
        boolean alongZ = x0 == x1 && z0 != z1;

        int yMax = client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x0, z0);

        if (alongX) {
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x0, z0 - 1));
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x1, z1));
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x1, z1 - 1));
        } else if (alongZ) {
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x0 - 1, z0));
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x1, z1));
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x1 - 1, z1));
        } else {
            // Shouldn't happen (stair-step emits axis-aligned), but keep it safe.
            yMax = Math.max(yMax, client.world.getTopY(Heightmap.Type.WORLD_SURFACE, x1, z1));
        }

        return yMax;
    }

    private static void emitVerticalQuad(
            BufferBuilder builder,
            Matrix4f matrix,
            int x0,
            int z0,
            int x1,
            int z1,
            double yBottom,
            double yTop,
            float r,
            float g,
            float b,
            float a
    ) {
        if (x0 == x1 && z0 == z1) {
            return;
        }
        if (x0 != x1 && z0 != z1) {
            return;
        }

        // Ensure consistent winding.
        int ax0 = x0;
        int az0 = z0;
        int ax1 = x1;
        int az1 = z1;
        if (ax1 < ax0 || az1 < az0) {
            ax0 = x1;
            az0 = z1;
            ax1 = x0;
            az1 = z0;
        }

        float fx0 = ax0;
        float fz0 = az0;
        float fx1 = ax1;
        float fz1 = az1;
        float fy0 = (float) yBottom;
        float fy1 = (float) yTop;

        builder.vertex(matrix, fx0, fy0, fz0).color(r, g, b, a);
        builder.vertex(matrix, fx1, fy0, fz1).color(r, g, b, a);
        builder.vertex(matrix, fx1, fy1, fz1).color(r, g, b, a);
        builder.vertex(matrix, fx0, fy1, fz0).color(r, g, b, a);
    }
}
