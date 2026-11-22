package org.minecraftagents;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.minecraftagents.tools.AgentDemonstrations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public final class MinecraftAgentsClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("minecraft_agents");
    private static boolean clickedLeft;
    private static boolean clickedRight;

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Minecraft Agents] Skill learning ready.");
        ClientTickEvents.END_CLIENT_TICK.register(MinecraftAgentsClient::recordInput);
    }

    public static void mouseClicked(boolean left) {
        if (left) {
            clickedLeft = true;
        } else {
            clickedRight = true;
        }
    }

    private static void recordInput(MinecraftClient client) {
        boolean left = clickedLeft;
        boolean right = clickedRight;
        clickedLeft = false;
        clickedRight = false;
        var player = client.player;
        var server = client.getServer();
        if (player == null || server == null || client.currentScreen != null
                || !AgentDemonstrations.wantsPlayerInput(server, player.getUuid())) {
            return;
        }
        var keys = new ArrayList<String>();
        var options = client.options;
        if (options.forwardKey.isPressed()) keys.add("w");
        if (options.leftKey.isPressed()) keys.add("a");
        if (options.backKey.isPressed()) keys.add("s");
        if (options.rightKey.isPressed()) keys.add("d");
        if (options.jumpKey.isPressed()) keys.add("space");
        var buttons = new ArrayList<String>();
        if (left || options.attackKey.isPressed()) buttons.add("left");
        if (right || options.useKey.isPressed()) buttons.add("right");
        var id = player.getUuid();
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        int slot = player.getInventory().selectedSlot;
        String heldKeys = String.join("+", keys);
        String heldButtons = String.join("+", buttons);
        server.execute(() -> {
            var subject = server.getPlayerManager().getPlayer(id);
            if (subject != null) {
                AgentDemonstrations.playerInput(subject, heldKeys, heldButtons, yaw, pitch, slot);
            }
        });
    }
}
