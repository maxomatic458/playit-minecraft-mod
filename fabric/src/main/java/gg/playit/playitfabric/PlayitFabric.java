package gg.playit.playitfabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.NetworkUtils;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandManager.RegistrationEnvironment;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import org.slf4j.Logger;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;

import gg.playit.api.models.Notice;
import gg.playit.playitfabric.config.PlayitFabricConfig;
import gg.playit.playitfabric.utils.ChatColor;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public class PlayitFabric implements DedicatedServerModInitializer, ClientModInitializer {
	public static final String MODID = "playit_fabric";
    static Logger log = LogUtils.getLogger();
	final EventLoopGroup eventGroup = new NioEventLoopGroup();
	final Object managerSync = new Object();
	volatile PlayitManager playitManager;

	public MinecraftServer server;
	public MinecraftClient client;

	public PlayitFabricConfig config = PlayitFabricConfig.load();

	@Override
	public void onInitializeServer() {
		ServerPlayConnectionEvents.JOIN.register((ServerPlayNetworkHandler handler, PacketSender sender, MinecraftServer server) -> {
			ServerPlayerEntity player = handler.player;
			onPlayerJoinDedicated(player);
		});

		ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
			this.server = server;
			onDedicatedServerStart(server);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register((MinecraftServer server) -> {
			onServerStop(server);
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			onRegisterCommands(dispatcher, registryAccess, environment);
		});
	}

	@Override
	public void onInitializeClient() {
		client = MinecraftClient.getInstance();

		ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
			this.server = server;
		});
		
		ServerLifecycleEvents.SERVER_STOPPED.register((MinecraftServer server) -> {
			onServerStop(server);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.isInSingleplayer()) {
				onLanServerStart(server);
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			onRegisterCommands(dispatcher, registryAccess, environment);
		});
	}

	public void onDedicatedServerStart(MinecraftServer server) {
		if (server.isDedicated() && config.CFG_AUTOSTART) {
			var secretKey = config.CFG_AGENT_SECRET_KEY;
			resetConnection(secretKey);
		}
	}

	public void onServerStop(MinecraftServer server) {
		// server & client
		log.info("stopping playit");
		if (playitManager != null) {
			playitManager.shutdown();
			playitManager = null;
		}
	}

	public void onLanServerStart(MinecraftServer server) {
		if (!server.isDedicated() && config.CFG_AUTOSTART && client != null) {
			makeLanPublic();
		}
	}

	public void onPlayerJoinDedicated(ServerPlayerEntity player) {
		PlayitManager manager = playitManager;

		if (manager == null || server == null) {
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
		PlayitCommand playitCommand = new PlayitCommand(this);
		playitCommand.register(dispatcher);

		log.info("registered playit command");
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

	public void makeLanPublic() {
		if (client == null || server == null || !client.isInSingleplayer()) {
			return;
		}

		GameMode defaultGameMode = server.getDefaultGameMode();
		boolean cheatsAllowed = server.getPlayerManager().areCheatsAllowed();
		
		if (playitManager != null) {
			client.player.sendMessage(Text.literal(ChatColor.RED + "ERROR: " + ChatColor.RESET + "playit.gg is already running"));
			return;
		}

		if (server.isRemote()) {
			server.setServerIp("127.0.0.1");
			client.player.sendMessage(Text.literal("attaching tunnel to existing lan server"));
		} else {
			server.openToLan(defaultGameMode, cheatsAllowed, NetworkUtils.findLocalPort());
			server.setServerIp("127.0.0.1");

			client.player.sendMessage(Text.literal("opened to lan with the following settings:"));
			client.player.sendMessage(Text.literal(ChatColor.GREEN + "Gamemode: " + ChatColor.RESET + defaultGameMode.getName()));
			client.player.sendMessage(Text.literal(ChatColor.GREEN + "Cheats: " + ChatColor.RESET + cheatsAllowed));
		}

		client.player.sendMessage(Text.literal("Connecting to the playit.gg Network..."));
	
		var secretKey = config.CFG_AGENT_SECRET_KEY;
		resetConnection(secretKey);

		new Thread (() -> {
			while (playitManager.getAddress() == null) {
				try {
					Thread.sleep(3000);
				} catch (InterruptedException ignore) {
				}
			}
			String address = playitManager.getAddress().replace("craft.ply.gg", "joinmc.link");
			var msg = Text.literal("Your server is now public under: " + ChatColor.GREEN + address + ChatColor.RESET + " (click to copy)")
				.setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, address)));
			client.player.sendMessage(msg);
		}).start();
	} 

	public boolean isClient() {
		return client != null;
	}
}
