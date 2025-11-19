package com.dractical.fembyte.perf;

import ca.spottedleaf.moonrise.common.list.ShortList;
import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import ca.spottedleaf.moonrise.common.util.WorldUtil;
import ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection;
import com.dractical.fembyte.config.modules.performance.RandomTickModule;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

public final class DensityTickSystem {

    private static final double SECTION_INVERSE_VOLUME = 1.0D / (16.0D * 16.0D * 16.0D);

    private final ServerLevel level;
    private final SimpleThreadUnsafeRandom random;
    private final boolean doubleTickFluids;

    public DensityTickSystem(final ServerLevel level, final SimpleThreadUnsafeRandom random) {
        this.level = level;
        this.random = random;
        this.doubleTickFluids = !ca.spottedleaf.moonrise.common.PlatformHooks.get().configFixMC224294();
    }

    public void tick(final LevelChunk chunk, final int tickSpeed) {
        if (!RandomTickModule.ENABLED || RandomTickModule.MODE == RandomTickModule.Mode.VANILLA) {
            this.tickVanilla(chunk, tickSpeed);
            return;
        }

        this.tickDensity(chunk, tickSpeed);
    }

    private void tickDensity(final LevelChunk chunk, final int tickSpeed) {
        final LevelChunkSection[] sections = chunk.getSections();
        if (sections.length == 0) {
            return;
        }

        final ChunkPos chunkPos = chunk.getPos();
        final int minSection = WorldUtil.getMinSection(this.level);
        final int offsetX = chunkPos.x << 4;
        final int offsetZ = chunkPos.z << 4;

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            final LevelChunkSection section = sections[sectionIndex];
            if (!section.isRandomlyTickingBlocks()) {
                if (RandomTickModule.DENSITY_DISTRIBUTION == RandomTickModule.DensityDistribution.SMOOTHED_ACCUMULATOR) {
                    section.fembyte$setDensityAccumulator(0.0F);
                }
                continue;
            }

            final ShortList tickList = ((BlockCountingChunkSection) section).moonrise$getTickingBlockList();
            final int tickingBlocks = tickList.size();
            if (tickingBlocks <= 0) {
                if (RandomTickModule.DENSITY_DISTRIBUTION == RandomTickModule.DensityDistribution.SMOOTHED_ACCUMULATOR) {
                    section.fembyte$setDensityAccumulator(0.0F);
                }
                continue;
            }

            double lambda = tickSpeed * tickingBlocks * SECTION_INVERSE_VOLUME;
            if (RandomTickModule.DENSITY_MAX_LAMBDA > 0.0D) {
                lambda = Math.min(lambda, RandomTickModule.DENSITY_MAX_LAMBDA);
            }

            int ticks = this.sampleTickCount(section, lambda);
            if (RandomTickModule.DENSITY_MAX_TICKS_PER_SECTION > 0) {
                ticks = Math.min(ticks, RandomTickModule.DENSITY_MAX_TICKS_PER_SECTION);
            }

            if (ticks <= 0) {
                continue;
            }

            this.dispatchRandomTicks(section, tickList, ticks, offsetX, (sectionIndex + minSection) << 4, offsetZ);
        }
    }

    private int sampleTickCount(final LevelChunkSection section, final double lambda) {
        if (lambda <= 0.0D) {
            section.fembyte$setDensityAccumulator(0.0F);
            return 0;
        }

        return switch (RandomTickModule.DENSITY_DISTRIBUTION) {
            case POISSON -> this.samplePoisson(lambda);
            case SMOOTHED_ACCUMULATOR -> this.sampleWithAccumulator(section, lambda);
        };
    }

    private int samplePoisson(double lambda) {
        if (lambda <= 0.0D) {
            return 0;
        }

        if (lambda < RandomTickModule.DENSITY_POISSON_SLOW_PATH) {
            final double threshold = Math.exp(-lambda);
            int k = 0;
            double product = 1.0D;
            do {
                ++k;
                product *= this.random.nextDouble();
            } while (product > threshold);
            return Math.max(0, k - 1);
        }

        final double gaussian = this.random.nextGaussian();
        final double value = lambda + gaussian * Math.sqrt(lambda);
        final int rounded = (int) Math.round(value);
        return Math.max(0, rounded);
    }

    private int sampleWithAccumulator(final LevelChunkSection section, final double lambda) {
        double accumulator = section.fembyte$getDensityAccumulator() + lambda;
        int wholeTicks = (int) Math.floor(accumulator);
        accumulator -= wholeTicks;
        if (accumulator > 0.0D && this.random.nextDouble() < accumulator) {
            ++wholeTicks;
            accumulator -= 1.0D;
        }

        if (accumulator <= 0.0D) {
            section.fembyte$setDensityAccumulator(0.0F);
        } else {
            section.fembyte$setDensityAccumulator((float) accumulator);
        }

        return Math.max(0, wholeTicks);
    }

    private void dispatchRandomTicks(final LevelChunkSection section, final ShortList tickList, final int ticks, final int offsetX, final int offsetY, final int offsetZ) {
        final var states = section.states;
        for (int iteration = 0; iteration < ticks; iteration++) {
            final int location = (int) tickList.getRaw(this.random.nextInt(tickList.size())) & 0xFFFF;
            final BlockState state = states.get(location);
            final BlockPos pos = new BlockPos(
                    (location & 15) | offsetX,
                    ((location >>> 8) & 15) | offsetY,
                    ((location >>> 4) & 15) | offsetZ
            );

            state.randomTick(this.level, pos, this.random);
            if (this.doubleTickFluids) {
                final FluidState fluidState = state.getFluidState();
                if (fluidState.isRandomlyTicking()) {
                    fluidState.randomTick(this.level, pos, this.random);
                }
            }
        }
    }

    private void tickVanilla(final LevelChunk chunk, final int tickSpeed) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int minSection = WorldUtil.getMinSection(this.level);
        final ChunkPos cpos = chunk.getPos();
        final int offsetX = cpos.x << 4;
        final int offsetZ = cpos.z << 4;

        for (int sectionIndex = 0, sectionsLen = sections.length; sectionIndex < sectionsLen; sectionIndex++) {
            final int offsetY = (sectionIndex + minSection) << 4;
            final LevelChunkSection section = sections[sectionIndex];
            final var states = section.states;
            if (!section.isRandomlyTickingBlocks()) {
                continue;
            }

            final ShortList tickList = ((BlockCountingChunkSection) section).moonrise$getTickingBlockList();

            for (int i = 0; i < tickSpeed; ++i) {
                final int tickingBlocks = tickList.size();
                final int index = this.random.nextInt() & ((16 * 16 * 16) - 1);

                if (index >= tickingBlocks) {
                    continue;
                }

                final int location = (int) tickList.getRaw(index) & 0xFFFF;
                final BlockState state = states.get(location);

                final BlockPos pos = new BlockPos(
                        (location & 15) | offsetX,
                        ((location >>> (4 + 4)) & 15) | offsetY,
                        ((location >>> 4) & 15) | offsetZ
                );

                state.randomTick(this.level, pos, this.random);
                if (this.doubleTickFluids) {
                    final FluidState fluidState = state.getFluidState();
                    if (fluidState.isRandomlyTicking()) {
                        fluidState.randomTick(this.level, pos, this.random);
                    }
                }
            }
        }
    }
}
