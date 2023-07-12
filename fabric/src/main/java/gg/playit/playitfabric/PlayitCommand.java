package gg.playit.playitfabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;

import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.playitfabric.utils.ChatColor;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.*;

import java.io.IOException;
import org.slf4j.Logger;

public class PlayitCommand {
    
    private static PlayitFabric playitFabric;
    private static final Logger log = LogUtils.getLogger();

    public PlayitCommand(PlayitFabric playitFabric) {
        PlayitCommand.playitFabric = playitFabric;
    }

    public void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("playit")
            .requires(source -> source.hasPermissionLevel(3))
            .then(literal("agent")
                .then(literal("status")
                    .executes(ctx -> getStatus(ctx.getSource()))
                )
                .then(literal("restart")
                    .executes(ctx -> restart(ctx.getSource()))
                )
                .then(literal("reset")
                    .executes(ctx -> reset(ctx.getSource()))
                )
                .then(literal("shutdown")
                    .executes(ctx -> shutdown(ctx.getSource()))
                )
                .then(literal("set-secret")
                    .then(argument("secret", StringArgumentType.string())
                        .executes(ctx -> setSecret(ctx.getSource(), StringArgumentType.getString(ctx, "secret")))
                    )
                )
            )

            .then(literal("prop")
                .then(literal("get")
                    .then(literal("mc-timeout-sec")
                        .executes(ctx -> getProp(ctx.getSource(), "mc-timeout-sec"))
                    )
                    .then(literal("autostart")
                        .executes(ctx -> getProp(ctx.getSource(), "autostart"))
                    )
                )

                .then(literal("set")
                    .then(literal("mc-timeout-sec")
                        .then(argument("value", IntegerArgumentType.integer(1))
                            .executes(ctx -> setProp(ctx.getSource(), "mc-timeout-sec", IntegerArgumentType.getInteger(ctx, "value")))
                        )
                    )
                    .then(literal("autostart")
                        .then(argument("value", BoolArgumentType.bool())
                            .executes(ctx -> setProp(ctx.getSource(), "autostart", BoolArgumentType.getBool(ctx, "value")))
                        )
                    )
                )
            )

            .then(literal("tunnel")
                .then(literal("get-address")
                    .executes(ctx -> getTunnelAddress(ctx.getSource()))
                )
            )

            .then(literal("account")
                .then(literal("guest-login-link")
                    .executes(ctx -> getGuestLoginLink(ctx.getSource()))
                )
            )
        );
    }

    private static int getStatus(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        PlayitManager manager = playitFabric.playitManager;

        if (manager == null) {
            String currentSecret = playitFabric.config.CFG_AGENT_SECRET_KEY;
            if (currentSecret == null || currentSecret.isEmpty()) {
                player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "Secret key not set"));
                return 0;
            }
            player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "playit status: offline (or shutting down)"));
            return 0;
        }
        String message = switch (manager.state()) {
            case PlayitKeysSetup.STATE_INIT -> "preparing secret";
            case PlayitKeysSetup.STATE_MISSING_SECRET -> "waiting for claim";
            case PlayitKeysSetup.STATE_CHECKING_SECRET -> "checking secret";
            case PlayitKeysSetup.STATE_CREATING_TUNNEL -> "preparing tunnel";
            case PlayitKeysSetup.STATE_ERROR -> "error setting up key / tunnel";

            case PlayitManager.STATE_CONNECTING -> "connecting";
            case PlayitManager.STATE_ONLINE -> "connected";
            case PlayitManager.STATE_OFFLINE -> "offline";
            case PlayitManager.STATE_ERROR_WAITING -> "got error, retrying";
            case PlayitManager.STATE_INVALID_AUTH -> "invalid secret key";

            case PlayitManager.STATE_SHUTDOWN -> "shutdown";
            default -> "unknown";
        };

        player.sendMessage(Text.literal(ChatColor.BLUE + "playit status: " + ChatColor.RESET + message));
        return 0;
    }
    
    private static int restart(ServerCommandSource source) {
        playitFabric.resetConnection(null);
        return 0;
    }

    private static int reset(ServerCommandSource source) {
        playitFabric.config.setAgentSecret("");
        playitFabric.resetConnection(null);
        return 0;
    }

    private static int shutdown(ServerCommandSource source) {
        synchronized (playitFabric.managerSync) {
            if (playitFabric.playitManager != null) {
                playitFabric.playitManager.shutdown();
                playitFabric.playitManager = null;
            }
        }
        return 0;
    }

    private static int setSecret(ServerCommandSource source, String secret) {
        playitFabric.config.setAgentSecret(secret);
        playitFabric.resetConnection(null);
        return 0;
    }

    private static int getProp(ServerCommandSource source, String prop) {
        ServerPlayerEntity player = source.getPlayer();

        String valueCfg;
        String valueCurrent;
        switch (prop) {
            case "mc-timeout-sec" -> {
                valueCfg = String.valueOf(playitFabric.config.CFG_CONNECTION_TIMEOUT_SECONDS);
                valueCurrent = String.valueOf(playitFabric.playitManager.connectionTimeoutSeconds);
            }
            case "autostart" -> {
                valueCfg = String.valueOf(playitFabric.config.CFG_AUTOSTART);
                valueCurrent = valueCfg;
            }
            
            default -> {
                valueCfg = null;
                valueCurrent = null;
            }
        }

        if (valueCfg == null || valueCfg.isEmpty() || valueCurrent == null || valueCurrent.isEmpty()) {
            player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + prop + "is not set"));
            return 0;
        }
        player.sendMessage(Text.literal(ChatColor.BLUE + prop + ChatColor.RESET + " current: " + valueCurrent + ", config: " + valueCfg));
        return 0;
    }

    private static int setProp(ServerCommandSource source, String prop, Object value) {
        ServerPlayerEntity player = source.getPlayer();

        switch (prop) {
            case "mc-timeout-sec" -> {
                playitFabric.config.setConnectionTimeoutSeconds((Integer) value);
            }
            case "autostart" -> {
                playitFabric.config.setAutostart((Boolean) value);
            }

            default -> {
                player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "Unknown property " + prop));
                return 0;
            }
        }

        player.sendMessage(Text.literal("set " + ChatColor.BLUE + prop + ChatColor.RESET + " to " + ChatColor.GREEN + value));
        if (prop == "mc-timeout-sec") {
            player.sendMessage(Text.literal("run " + ChatColor.GREEN + "/playit agent restart" + ChatColor.RESET + " to apply changes"));
            return 0;
        }
        player.sendMessage(Text.literal("changes will be applied on next restart"));
        return 0;
    }

    private static int getTunnelAddress(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();    
        PlayitManager playitManager = playitFabric.playitManager;
    
        if (playitManager != null) {
            var address = playitManager.getAddress();
            if (address != null) {
                player.sendMessage(Text.literal(ChatColor.BLUE + "tunnel address: " + ChatColor.RESET + address));
                return 0;
            }
            player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "tunnel address is not set"));
            return 0;
        }

        player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "tunnel is not running"));
        return 0;

    }

    private static int getGuestLoginLink(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        String secret = playitFabric.config.CFG_AGENT_SECRET_KEY;

        if (secret == null || secret.isEmpty()) {
            player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "secret is not set"));
            return 0;
        }
        
        player.sendMessage(Text.literal("preparing login link..."));

        new Thread(() -> {
            try {
                var api = new ApiClient(secret);
                var session = api.createGuestWebSessionKey();

                var url = "https://playit.gg/login/guest-account/" + session;
                log.info("generated login url: " + url);

                player.sendMessage(Text.literal("generated login url"));
                player.sendMessage(Text.literal(ChatColor.BLUE + "URL: " + ChatColor.RESET + url));
            } catch (ApiError e) {
                log.warn("failed to create guest secret: " + e);
                player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "failed to create guest secret: " + e.getMessage()));
            } catch (IOException e) {
                log.error("failed to create guest secret: " + e);
            }
        }).start();

        return 0;
    }
}
