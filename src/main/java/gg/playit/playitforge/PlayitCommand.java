package gg.playit.playitforge;

import java.io.IOException;
import org.slf4j.Logger;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;

import gg.playit.ChatColor;
import gg.playit.api.ApiClient;
import gg.playit.api.ApiError;
import gg.playit.playitforge.config.PlayitForgeConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PlayitCommand {

    private static PlayitForge playitForge;
    private static final Logger log = LogUtils.getLogger();

    public PlayitCommand(PlayitForge playitForge) {
        this.playitForge = playitForge;
    }

    public void register(CommandDispatcher<CommandSourceStack>dispatcher) {

        dispatcher.register(Commands.literal("playit")
            .then(Commands.literal("agent")
                .then(Commands.literal("status")
                    .executes(ctx -> getStatus(ctx.getSource()))
                )
                .then(Commands.literal("restart")
                    .executes(ctx -> restart(ctx.getSource()))
                )
                .then(Commands.literal("reset")
                    .executes(ctx -> reset(ctx.getSource()))
                )
                .then(Commands.literal("shutdown")
                    .executes(ctx -> shutdown(ctx.getSource()))
                )
                .then(Commands.literal("set-secret")
                    .then(Commands.argument("secret", StringArgumentType.string())
                        .executes(ctx -> setSecret(ctx.getSource(), StringArgumentType.getString(ctx, "secret")))
                    )
                )
            )
            .then(Commands.literal("prop")
                .then(Commands.literal("get")
                    .then(Commands.literal("mc-timeout-sec")
                        .executes(ctx -> getProp(ctx.getSource(), "mc-timeout-sec"))
                    )
                    .then(Commands.literal("autostart")
                        .executes(ctx -> getProp(ctx.getSource(), "autostart"))
                    )
                )
                .then(Commands.literal("set")
                    .then(Commands.literal("mc-timeout-sec")
                        .then(Commands.argument("value", IntegerArgumentType.integer())
                            .executes(ctx -> setProp(ctx.getSource(), "mc-timeout-sec", IntegerArgumentType.getInteger(ctx, "value")))
                        )
                    )
                    .then(Commands.literal("autostart")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                            .executes(ctx -> setProp(ctx.getSource(), "autostart", BoolArgumentType.getBool(ctx, "value")))
                        )
                    )
                )
            )
            .then(Commands.literal("tunnel")
                .then(Commands.literal("get-address")
                    .executes(ctx -> getTunnelAddress(ctx.getSource()))
                )
            )
            .then(Commands.literal("account")
                .then(Commands.literal("guest-login-link")
                    .executes(ctx -> getGuestLoginLink(ctx.getSource()))
                )
            )
        );
    }

    private static int getStatus(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null || !player.hasPermissions(3)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "You do not have permission to use this command"));
            }
            return 0;
        }

        PlayitManager manager = playitForge.playitManager;
        if (manager == null) {
            String currentSecret = PlayitForgeConfig.CFG_AGENT_SECRET_KEY.get();
            if (currentSecret == null || currentSecret.isEmpty()) {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "Secret key not set"));
            } else {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "playit status: offline (or shutting down)"));
            }
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

        player.sendSystemMessage(Component.literal(ChatColor.BLUE + "playit status: " + ChatColor.RESET + message));
        return 0;
    }

    private static boolean hasOpPermission(ServerPlayer player) {
        if (player == null || !player.hasPermissions(3)) {
            if (player != null) {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "You do not have permission to use this command"));
            }
            return false;
        }
        return true;
    }
    
    private static int restart(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        playitForge.resetConnection(null);
        return 0;
    }

    private static int reset(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        PlayitForgeConfig.CFG_AGENT_SECRET_KEY.set("");
        PlayitForgeConfig.CFG_AGENT_SECRET_KEY.save();
        playitForge.resetConnection(null);
        return 0;
    }

    private static int shutdown(CommandSourceStack source) {
        // this will keep connected clients connected, but not allow new connections, same as in plugin
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        synchronized (playitForge.managerSync) {
            if (playitForge.playitManager != null) {
                playitForge.playitManager.shutdown();
                playitForge.playitManager = null;
            }
        }
        return 0;
    }

    private static int setSecret(CommandSourceStack source, String secret) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        PlayitForgeConfig.CFG_AGENT_SECRET_KEY.set(secret);
        PlayitForgeConfig.CFG_AGENT_SECRET_KEY.save();
        playitForge.resetConnection(null);
        return 0;
    }

    private static int getProp(CommandSourceStack source, String prop) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        String valueCfg;
        String valueCurrent;
        switch (prop) {
            case "mc-timeout-sec" -> {
                valueCfg = String.valueOf(PlayitForgeConfig.CFG_CONNECTION_TIMEOUT_SECONDS.get());
                valueCurrent = String.valueOf(playitForge.playitManager.connectionTimeoutSeconds);
            }
            case "autostart" -> {
                valueCfg = String.valueOf(PlayitForgeConfig.CFG_AUTOSTART.get());
                valueCurrent = valueCfg;
            }
            
            default -> {
                valueCfg = null;
                valueCurrent = null;
            }
        }

        if (valueCfg == null || valueCfg.isEmpty() || valueCurrent == null || valueCurrent.isEmpty()) {
            player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + prop + "is not set"));
            return 0;
        }
        player.sendSystemMessage(Component.literal(ChatColor.BLUE + prop + ChatColor.RESET + " current: " + valueCurrent + ", config: " + valueCfg));
        return 0;
    }

    private static int setProp(CommandSourceStack source, String prop, Object value) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        switch (prop) {
            case "mc-timeout-sec" -> {
                PlayitForgeConfig.CFG_CONNECTION_TIMEOUT_SECONDS.set((Integer) value);
                PlayitForgeConfig.CFG_CONNECTION_TIMEOUT_SECONDS.save();
            }
            case "autostart" -> {
                PlayitForgeConfig.CFG_AUTOSTART.set((Boolean) value);
                PlayitForgeConfig.CFG_AUTOSTART.save();
            }

            default -> {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "Unknown property " + prop));
                return 0;
            }
        }

        player.sendSystemMessage(Component.literal("set " + ChatColor.BLUE + prop + ChatColor.RESET + " to " + ChatColor.GREEN + value));
        if (prop == "mc-timeout-sec") {
            player.sendSystemMessage(Component.literal("run " + ChatColor.GREEN + "/playit agent restart" + ChatColor.RESET + " to apply changes"));
            return 0;
        }
        player.sendSystemMessage(Component.literal("changes will be applied on next restart"));
        return 0;
    }

    private static int getTunnelAddress(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        PlayitManager playitManager = playitForge.playitManager;

        if (playitManager != null) {
            var address = playitManager.getAddress();
            if (address != null) {
                player.sendSystemMessage(Component.literal(ChatColor.BLUE + "tunnel address: " + ChatColor.RESET + address));
                return 0;
            }
            player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "tunnel address is not set"));
            return 0;
        }

        player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "tunnel is not running"));
        return 0;

    }

    private static int getGuestLoginLink(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (!hasOpPermission(player)) {
            return 0;
        }

        String secret = PlayitForgeConfig.CFG_AGENT_SECRET_KEY.get();

        if (secret == null || secret.isEmpty()) {
            player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "secret is not set"));
            return 0;
        }
        
        player.sendSystemMessage(Component.literal("preparing login link..."));

        new Thread(() -> {
            try {
                var api = new ApiClient(secret);
                var session = api.createGuestWebSessionKey();

                var url = "https://playit.gg/login/guest-account/" + session;
                log.info("generated login url: " + url);

                player.sendSystemMessage(Component.literal("generated login url"));
                player.sendSystemMessage(Component.literal(ChatColor.BLUE + "URL: " + ChatColor.RESET + url));
            } catch (ApiError e) {
                log.warn("failed to create guest secret: " + e);
                player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "failed to create guest secret: " + e.getMessage()));
            } catch (IOException e) {
                log.error("failed to create guest secret: " + e);
            }
        }).start();

        return 0;
    }
}
