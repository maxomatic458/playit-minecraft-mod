package gg.playit.playitforge.config;
import net.minecraftforge.common.ForgeConfigSpec;

public class PlayitForgeConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> CFG_AGENT_SECRET_KEY;
    public static final ForgeConfigSpec.ConfigValue<Integer> CFG_CONNECTION_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.ConfigValue<Boolean> CFG_AUTOSTART;

    static {
        BUILDER.push("Config for PlayitForge");
        BUILDER.pop();

        CFG_AGENT_SECRET_KEY = BUILDER
                .define("agent-secret", "");

        CFG_CONNECTION_TIMEOUT_SECONDS = BUILDER
                .define("mc-timeout-sec", 30);

        CFG_AUTOSTART = BUILDER
                .comment("Automatically start the playit-tunnel when the server starts")
                .define("autostart", true);

        SPEC = BUILDER.build();
    }
}
