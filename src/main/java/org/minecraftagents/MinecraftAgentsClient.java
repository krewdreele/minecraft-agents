package org.minecraftagents;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftAgentsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_agents");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Minecraft Agents] Phase 1 mod loaded.");
    }
}
