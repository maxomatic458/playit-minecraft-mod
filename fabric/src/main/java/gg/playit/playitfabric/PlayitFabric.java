package gg.playit.playitfabric;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import org.slf4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;

import gg.playit.api.models.Notice;
import gg.playit.playitfabric.config.PlayitFabricConfig;
import gg.playit.playitfabric.utils.ChatColor;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.api.EnvType;

@Environment(EnvType.SERVER)
public class PlayitFabric implements DedicatedServerModInitializer {

	public static final String MODID = "playit_fabric";
    static Logger log = LogUtils.getLogger();
	final EventLoopGroup eventGroup = new NioEventLoopGroup();
	final Object managerSync = new Object();
	volatile PlayitManager playitManager;

	MinecraftServer server;

	public PlayitFabricConfig config = PlayitFabricConfig.load();

	@Override
	public void onInitializeServer() {

		ServerPlayConnectionEvents.JOIN.register((ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) -> {
			ServerPlayerEntity player = handler.player;
			onPlayerJoin(player);
		});

		ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
			this.server = server;
			onServerStart(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			onRegisterCommands(dispatcher, registryAccess, environment);
		});
	}

	public void onServerStart(MinecraftServer server) {
		if (server.isDedicated() && config.CFG_AUTOSTART) {
			var secretKey = config.CFG_AGENT_SECRET_KEY;
			resetConnection(secretKey);
		}
	}

	public void onPlayerJoin(ServerPlayerEntity player) {
		PlayitManager manager = playitManager;

		if (manager == null) {
			return;
		}

		if (player.hasPermissionLevel(3)) {
			if (manager.isGuest()) {
                player.sendMessage(Text.literal(ChatColor.RED + "WARNING:" + ChatColor.RESET + " playit.gg is running with a guest account"));
            } else if (!manager.emailVerified()) {
                player.sendMessage(Text.literal(ChatColor.RED + "WARNING:" + ChatColor.RESET + " your email on playit.gg is not verified"));
            }
		}

		Notice notice = manager.getNotice();
        if (notice != null) {
            player.sendMessage(Text.literal(ChatColor.RED + "NOTICE:" + ChatColor.RESET + " " + notice.message));
            player.sendMessage(Text.literal(ChatColor.RED + "URL:" + ChatColor.RESET + " " + notice.url));
        }
	}

	public void onRegisterCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, RegistrationEnvironment environment) {
		if (environment.dedicated) {
			PlayitCommand playitCommand = new PlayitCommand(this);
			playitCommand.register(dispatcher);
		}
	}

    void resetConnection(String secretKey) {
        if (secretKey != null) {
            config.setAgentSecret(secretKey);
        }

        synchronized (managerSync) {
            if (playitManager != null) {
                playitManager.shutdown();
            }

            playitManager = new PlayitManager(this);
            try {
                int waitSeconds = config.CFG_CONNECTION_TIMEOUT_SECONDS;
                if (waitSeconds != 0) {
                    playitManager.connectionTimeoutSeconds = waitSeconds;
                }
            } catch (Exception ignore) {
            }

            new Thread(playitManager).start();
        }
    }
}
