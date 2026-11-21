package org.minecraftagents;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import org.minecraftagents.tools.AgentCombat;
import org.minecraftagents.tools.AgentCommands;
import org.minecraftagents.tools.AgentLoop;
import org.minecraftagents.tools.AgentNavigation;
import org.minecraftagents.tools.AgentSkills;
import org.minecraftagents.tools.AgentTrial;

public final class MinecraftAgents implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) ->
                AgentCommands.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            AgentNavigation.tick(world);
            AgentCombat.tick(world);
            AgentTrial.tick(world);
            AgentSkills.tick(world);
            AgentLoop.tick(world);
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            AgentLoop.clear(world);
            AgentSkills.clear(world);
            AgentCombat.clear(world);
            AgentNavigation.clear(world);
            AgentTrial.clear(world);
        });
    }
}
