package gg.playit.playitfabric.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class PlayitFabricConfig {
    private static final String CFG_FILENAME = "playit-fabric-config.cfg";
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve(CFG_FILENAME);;

    public String CFG_AGENT_SECRET_KEY;
    public int CFG_CONNECTION_TIMEOUT_SECONDS;
    public boolean CFG_AUTOSTART;

    private static String configNameAgentSecret = "agent-secret";
    private static String configNameConnectionTimeoutSeconds = "mc-timeout-seconds";
    private static String configNameAutostart = "autostart";

    private PlayitFabricConfig() {
        CFG_AGENT_SECRET_KEY = "";
        CFG_CONNECTION_TIMEOUT_SECONDS = 30;
        CFG_AUTOSTART = FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER; // server: true, client: false
    }

    public static PlayitFabricConfig load() {
        PlayitFabricConfig config = new PlayitFabricConfig();

        try {
            if (Files.exists(CONFIG_PATH)) {
                Properties properties = new Properties();
                try (InputStream inputStream = Files.newInputStream(CONFIG_PATH)) {
                    properties.load(inputStream);
                }

                config.CFG_AGENT_SECRET_KEY = properties.getProperty(configNameAgentSecret, config.CFG_AGENT_SECRET_KEY);
                config.CFG_CONNECTION_TIMEOUT_SECONDS = Integer.parseInt(properties.getProperty(configNameConnectionTimeoutSeconds,
                        Integer.toString(config.CFG_CONNECTION_TIMEOUT_SECONDS)));
                config.CFG_AUTOSTART = Boolean.parseBoolean(properties.getProperty(configNameAutostart,
                        Boolean.toString(config.CFG_AUTOSTART)));
            } else {
                config.save();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return config;
    }

    public void save() {
        try {
            Properties properties = new Properties();
            properties.setProperty(configNameAgentSecret, CFG_AGENT_SECRET_KEY);
            properties.setProperty(configNameConnectionTimeoutSeconds, Integer.toString(CFG_CONNECTION_TIMEOUT_SECONDS));
            properties.setProperty(configNameAutostart, Boolean.toString(CFG_AUTOSTART));

            try (OutputStream outputStream = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(outputStream, "PlayitFabric Config");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setAgentSecret(String agentSecret) {
        this.CFG_AGENT_SECRET_KEY = agentSecret;
        save();
    }

    public void setConnectionTimeoutSeconds(int connectionTimeoutSeconds) {
        this.CFG_CONNECTION_TIMEOUT_SECONDS = connectionTimeoutSeconds;
        save();
    }

    public void setAutostart(boolean autostart) {
        this.CFG_AUTOSTART = autostart;
        save();
    }

}    
