package org.minecraftagents.console;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class ConsoleBootstrap {
    private ConsoleBootstrap() {
    }

    public static void main(String[] args) throws Throwable {
        PrintStream terminalError = System.err;
        var config = ConsoleBootstrap.class.getResource("/minecraft-agents-console-log4j2.xml");
        System.setProperty("log4j.configurationFile", config.toExternalForm());
        System.setProperty("minecraftAgents.console.pid", Long.toString(ProcessHandle.current().pid()));

        // Let Fabric apply its dev transforms before any mapped Minecraft classes are loaded.
        ClassLoader gameLoader = new Knot(EnvType.SERVER).init(new String[0]);
        Class<?> launcher = gameLoader.loadClass("org.minecraftagents.console.ConsoleLauncher");
        try {
            launcher.getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (InvocationTargetException failure) {
            // Minecraft redirects stderr through its logger during bootstrap.
            System.setErr(terminalError);
            throw failure.getCause();
        }
    }
}
