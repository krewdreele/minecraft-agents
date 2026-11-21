package org.minecraftagents.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.yggdrasil.ServicesKeySet;
import net.minecraft.datafixer.Schemas;
import net.minecraft.resource.ResourcePackManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.SaveLoader;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;
import net.minecraft.util.SystemDetails;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.world.level.storage.LevelStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LocalConsoleServer extends MinecraftServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalConsoleServer.class);
    private static final long STATUS_INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private static final ApiServices LOCAL_SERVICES = new ApiServices(null, ServicesKeySet.EMPTY, null, null);

    private long nextStatusTime;
    private boolean crashed;
    private boolean completed;

    LocalConsoleServer(Thread thread, LevelStorage.Session session, ResourcePackManager packs, SaveLoader save) {
        super(thread, session, packs, save, Proxy.NO_PROXY, Schemas.getFixer(), LOCAL_SERVICES,
                WorldGenerationProgressLogger::new);
    }

    @Override
    protected boolean setupServer() {
        setPlayerManager(new PlayerManager(this, getCombinedDynamicRegistries(), saveHandler, 1) { });
        loadWorld();
        LOGGER.info("Opened \"{}\" at world tick {}. Type status, save, stop, or /<Minecraft command>.",
                getSaveProperties().getLevelName(), getOverworld().getTime());
        nextStatusTime = System.nanoTime() + STATUS_INTERVAL;

        Thread input = new Thread(this::readConsole, "Minecraft console input");
        input.setDaemon(true);
        input.start();
        return true;
    }

    @Override
    public void tick(BooleanSupplier shouldKeepTicking) {
        super.tick(shouldKeepTicking);
        long now = System.nanoTime();
        if (now - nextStatusTime >= 0) {
            reportStatus();
            nextStatusTime = now + STATUS_INTERVAL;
        }
    }

    private void readConsole() {
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.strip();
                execute(() -> runConsoleCommand(command));
                if (command.equals("stop")) {
                    return;
                }
            }
        } catch (IOException failure) {
            LOGGER.warn("Could not read console input", failure);
        }
    }

    private void runConsoleCommand(String command) {
        switch (command) {
            case "" -> { }
            case "status" -> reportStatus();
            case "save" -> {
                saveAll(true, true, true);
                LOGGER.info("Save finished at world tick {}.", getOverworld().getTime());
            }
            case "stop" -> stop(false);
            default -> {
                if (command.startsWith("/")) {
                    getCommandManager().executeWithPrefix(getCommandSource(), command);
                } else {
                    LOGGER.info("Type status, save, stop, or /<Minecraft command>.");
                }
            }
        }
    }

    private void reportStatus() {
        LOGGER.info("World tick {} ({} ticks this run).", getOverworld().getTime(), getTicks());
    }

    @Override
    public void sendMessage(Text message) {
        LOGGER.info("{}", message.getString());
    }

    @Override
    public void setCrashReport(CrashReport report) {
        crashed = true;
    }

    @Override
    public void shutdown() {
        LOGGER.info("Saving and closing \"{}\".", getSaveProperties().getLevelName());
        super.shutdown();
        completed = !crashed;
        LOGGER.info("Console run stopped.");
    }

    boolean completed() {
        return completed;
    }

    @Override
    public int getOpPermissionLevel() {
        return 4;
    }

    @Override
    public int getFunctionPermissionLevel() {
        return 2;
    }

    @Override
    public int getRateLimit() {
        return 0;
    }

    @Override
    public SystemDetails addExtraSystemDetails(SystemDetails details) {
        return details;
    }

    @Override
    public boolean areCommandBlocksEnabled() {
        return false;
    }

    @Override
    public boolean isDedicated() {
        return false;
    }

    @Override
    public boolean isRemote() {
        return false;
    }

    @Override
    public boolean isHost(GameProfile profile) {
        return false;
    }

    @Override
    public boolean isUsingNativeTransport() {
        return false;
    }

    @Override
    public boolean shouldBroadcastRconToOps() {
        return false;
    }

    @Override
    public boolean shouldBroadcastConsoleToOps() {
        return false;
    }
}
