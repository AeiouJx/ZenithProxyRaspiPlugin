package org.example;

import com.zenith.plugin.api.Plugin;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.example.command.ExampleCommand;
import org.example.command.ExampleESPCommand;
import org.example.command.ExampleWanderCommand;
import org.example.module.ExampleESPModule;
import org.example.module.ExampleModule;
import org.example.module.ExampleWanderModule;
import org.example.module.AutoLoginModule;
import org.example.module.PromoterModule;
import org.example.command.AutoLoginCommand;
import org.example.command.PromoterCommand;
import org.example.command.BridgeCommand;
import org.example.module.BridgeModule;
import org.example.web.api.WebServer;
import org.example.web.command.WebAPICommand;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "ZenithProxy Raspi Plugin",
    url = "https://github.com/AeiouJx/ZenithProxyRaspiPlugin",
    authors = {"AeiouJx"},
    mcVersions = {BuildConstants.MC_VERSION} // to indicate any MC version: @Plugin(mcVersions = "*")
)
public class RaspiPlugin implements ZenithProxyPlugin {
    // public static for simple access from modules and commands
    // or alternatively, you could pass these around in constructors
    public static RaspiConfig PLUGIN_CONFIG;
    public static ComponentLogger LOG;
    public static WebServer WEB_SERVER;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        LOG.info("Raspi Plugin loading...");
        // initialize any configurations before modules or commands might need to read them
        PLUGIN_CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, RaspiConfig.class);
        //pluginAPI.registerModule(new ExampleModule());
        //pluginAPI.registerModule(new ExampleESPModule());
        //pluginAPI.registerModule(new ExampleWanderModule());
        //pluginAPI.registerCommand(new ExampleCommand());
        //pluginAPI.registerCommand(new ExampleESPCommand());
        //pluginAPI.registerCommand(new ExampleWanderCommand());

        pluginAPI.registerModule(new AutoLoginModule());
        pluginAPI.registerModule(new PromoterModule());
        pluginAPI.registerModule(new BridgeModule());
        pluginAPI.registerCommand(new AutoLoginCommand());
        pluginAPI.registerCommand(new PromoterCommand());
        pluginAPI.registerCommand(new BridgeCommand());
        WEB_SERVER = new WebServer();
        if (PLUGIN_CONFIG.webApi.enabled) {
            WEB_SERVER.start();
        }
        pluginAPI.registerCommand(new WebAPICommand());
        LOG.info("Raspi Plugin loaded!");
    }
}
