package com.pirro.minigames.plugin;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

/**
 * Thread-safe empty generator. Pillars are placed after the world is created,
 * never from a generation callback.
 */
final class VoidChunkGenerator extends ChunkGenerator {
    private final int fixedSpawnX;
    private final int fixedSpawnY;
    private final int fixedSpawnZ;

    VoidChunkGenerator(int fixedSpawnX, int fixedSpawnY, int fixedSpawnZ) {
        this.fixedSpawnX = fixedSpawnX;
        this.fixedSpawnY = fixedSpawnY;
        this.fixedSpawnZ = fixedSpawnZ;
    }

    @Override
    public int getBaseHeight(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int x,
            int z,
            @NotNull HeightMap heightMap
    ) {
        return worldInfo.getMinHeight();
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(
                world,
                fixedSpawnX + 0.5,
                fixedSpawnY + 1.0,
                fixedSpawnZ + 0.5
        );
    }
}