package org.minecraftagents.console;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.impl.game.minecraft.Hooks;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.SaveLoading;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;

public final class ConsoleLauncher {
    private ConsoleLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Launch with runConsole and select a world with -PconsoleWorld.");
        }
        Path savesDirectory = Path.of(args[0]);
        String worldFolder = args[1];
        Path folder = Path.of(worldFolder);
        if (worldFolder.isBlank() || !folder.equals(folder.getFileName())
                || worldFolder.equals(".") || worldFolder.equals("..")) {
            throw new IllegalArgumentException("Use a single world folder name with -PconsoleWorld.");
        }

        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        Hooks.startServer(null, null);

        LevelStorage storage = LevelStorage.create(savesDirectory);
        try (LevelStorage.Session session = storage.createSession(worldFolder)) {
            ResourcePackManager packs = VanillaDataPackProvider.createManager(session);
            SaveLoader save = loadWorld(session, packs);
            LocalConsoleServer server;
            try {
                server = MinecraftServer.startServer(thread -> new LocalConsoleServer(thread, session, packs, save));
            } catch (RuntimeException | Error failure) {
                save.close();
                throw failure;
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(true), "Console shutdown"));
            try {
                server.getThread().join();
            } catch (InterruptedException interruption) {
                server.stop(true);
                Thread.currentThread().interrupt();
                throw interruption;
            }
            if (!server.completed()) {
                throw new IllegalStateException("Minecraft stopped unexpectedly. See the console log.");
            }
        } finally {
            Util.shutdownExecutors();
        }
    }

    private static SaveLoader loadWorld(LevelStorage.Session session, ResourcePackManager packs) throws Exception {
        var summary = session.getLevelSummary();
        boolean existingWorld = summary != null;
        if (existingWorld && (summary.isFutureLevel() || !summary.isVersionAvailable() || summary.requiresConversion())) {
            throw new IOException("The world is incompatible with Minecraft " + SharedConstants.getGameVersion().getName());
        }
        if (!existingWorld && (Files.exists(session.getDirectory(WorldSavePath.LEVEL_DAT))
                || Files.exists(session.getDirectory(WorldSavePath.LEVEL_DAT_OLD))
                || Files.exists(session.getDirectory(WorldSavePath.ROOT).resolve("region")))) {
            throw new IOException("Could not read the existing world's metadata for " + session.getDirectoryName());
        }

        DataConfiguration dataConfig = existingWorld ? session.getDataPackSettings() : DataConfiguration.SAFE_MODE;
        if (dataConfig == null) {
            throw new IOException("Could not read the world's datapack settings.");
        }
        var dataPacks = new SaveLoading.DataPacks(packs, dataConfig, false, !existingWorld);
        var config = new SaveLoading.ServerConfig(dataPacks, CommandManager.RegistrationEnvironment.INTEGRATED, 2);
        return Util.waitAndApply(executor -> SaveLoading.<SaveProperties, SaveLoader>load(config,
                context -> readWorld(session, existingWorld, context), SaveLoader::new,
                Util.getMainWorkerExecutor(), executor)).get();
    }

    private static SaveLoading.LoadContext<SaveProperties> readWorld(LevelStorage.Session session,
            boolean existingWorld, SaveLoading.LoadContextSupplierContext context) {
        var registries = context.worldGenRegistryManager();
        var dimensions = context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION);
        if (existingWorld) {
            var save = session.readLevelProperties(RegistryOps.of(NbtOps.INSTANCE, registries),
                    context.dataConfiguration(), dimensions, registries.getRegistryLifecycle());
            if (save == null) {
                throw new IllegalStateException("Could not load the existing world " + session.getDirectoryName());
            }
            return new SaveLoading.LoadContext<>(save.getFirst(), save.getSecond().toDynamicRegistryManager());
        }

        var preset = registries.get(RegistryKeys.WORLD_PRESET).entryOf(WorldPresets.DEFAULT).value();
        var dimensionConfig = preset.createDimensionsRegistryHolder().toConfig(dimensions);
        var levelInfo = new LevelInfo(session.getDirectoryName(), GameMode.SURVIVAL, false, Difficulty.NORMAL,
                false, new GameRules(), context.dataConfiguration());
        var properties = new LevelProperties(levelInfo, GeneratorOptions.createRandom(),
                dimensionConfig.specialWorldProperty(), dimensionConfig.getLifecycle().add(registries.getRegistryLifecycle()));
        return new SaveLoading.LoadContext<>(properties, dimensionConfig.toDynamicRegistryManager());
    }
}
