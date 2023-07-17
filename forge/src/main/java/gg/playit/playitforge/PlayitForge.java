package gg.playit.playitforge;

import com.mojang.logging.LogUtils;
import gg.playit.api.models.Notice;
import gg.playit.playitforge.config.PlayitForgeConfig;
import gg.playit.playitforge.utils.ChatColor;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import org.slf4j.Logger;

@Mod(PlayitForge.MODID)
public class PlayitForge {

    public static final String MODID = "playit_forge";
    private static final Logger log = LogUtils.getLogger();
    final EventLoopGroup eventGroup = new NioEventLoopGroup();
    final Object managerSync = new Object();
    volatile PlayitManager playitManager;

    public MinecraftServer server;
    public Minecraft client;

    public static class ClientSideHandler {
        public PlayitForge playitForge;

        public ClientSideHandler(PlayitForge playitForge) {
            this.playitForge = playitForge;
        }

        @SubscribeEvent
        public void onClientJoin(ClientPlayerNetworkEvent.LoggingIn event) {
            // client only
            // onClientJoin, because we need to wait for the player
            if (playitForge.server != null && !playitForge.server.isDedicatedServer() && PlayitForgeConfig.CFG_AUTOSTART.get() && event.getPlayer().isLocalPlayer()) {
                playitForge.makeLanPublic();
            }
        }
    }

    public PlayitForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            client = Minecraft.getInstance();
            modEventBus.addListener(this::clientSetup);
            MinecraftForge.EVENT_BUS.register(new ClientSideHandler(this));
        }

        ModLoadingContext.get()
            .registerConfig(ModConfig.Type.COMMON, PlayitForgeConfig.SPEC, "playit-forge-config.toml"); 
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    private void clientSetup(final FMLClientSetupEvent event) {
    }


    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PlayitCommand playitCommand = new PlayitCommand(this);
        playitCommand.register(event.getDispatcher());

        log.info("registered playit command");
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (server.isDedicatedServer() && PlayitForgeConfig.CFG_AUTOSTART.get()) {
            var secretKey = PlayitForgeConfig.CFG_AGENT_SECRET_KEY.get();
            resetConnection(secretKey);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // server & client
        log.info("stopping playit");
        if (playitManager != null) {
            playitManager.shutdown();
            playitManager = null;
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        PlayitManager manager = playitManager;

        if (manager == null || server == null) {
            return;
        }

        if ((player.hasPermissions(3) && server.isDedicatedServer()) || (!server.isDedicatedServer() && player.getUUID().equals(server.getSingleplayerProfile().getId()))) {
            if (manager.isGuest()) {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "WARNING:" + ChatColor.RESET + " playit.gg is running with a guest account"));
            } else if (!manager.emailVerified()) {
                player.sendSystemMessage(Component.literal(ChatColor.RED + "WARNING:" + ChatColor.RESET + " your email on playit.gg is not verified"));
            }
        }

        Notice notice = manager.getNotice();
        if (notice != null) {
            player.sendSystemMessage(Component.literal(ChatColor.RED + "NOTICE:" + ChatColor.RESET + " " + notice.message));
            player.sendSystemMessage(Component.literal(ChatColor.RED + "URL:" + ChatColor.RESET + " " + notice.url));
        }
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public class ClientModEvents {
    }

    void resetConnection(String secretKey) {
        if (secretKey != null) {
            PlayitForgeConfig.CFG_AGENT_SECRET_KEY.set(secretKey);
            PlayitForgeConfig.CFG_AGENT_SECRET_KEY.save();
        }

        synchronized (managerSync) {
            if (playitManager != null) {
                playitManager.shutdown();
            }

            playitManager = new PlayitManager(this);
            try {
                int waitSeconds = PlayitForgeConfig.CFG_CONNECTION_TIMEOUT_SECONDS.get();
                if (waitSeconds != 0) {
                    playitManager.connectionTimeoutSeconds = waitSeconds;
                }
            } catch (Exception ignore) {
            }

            new Thread(playitManager).start();
        }
    }

    public void makeLanPublic() {
        if (client == null || server == null || !client.isLocalServer()) {
            return;
        }
        
        GameType defaultGameMode = server.getDefaultGameType();
        boolean cheatsAllowed = server.getPlayerList().isAllowCheatsForAllPlayers();

        if (playitManager != null) {
            client.player.sendSystemMessage(Component.literal(ChatColor.RED + "ERROR:" + ChatColor.RESET + " playit.gg is already running"));
            return;
        }

        if (server.isPublished()) {
            client.player.sendSystemMessage(Component.literal("attaching tunnel to existing lan server"));
        } else {

            server.publishServer(defaultGameMode, cheatsAllowed, HttpUtil.getAvailablePort());
            server.setLocalIp("127.0.0.1");
            
            client.player.sendSystemMessage(Component.literal("opened to lan with the following settings"));
            client.player.sendSystemMessage(Component.literal(ChatColor.GREEN + "Gamemode: " + ChatColor.RESET + defaultGameMode));
            client.player.sendSystemMessage(Component.literal(ChatColor.GREEN + "Cheats: " + ChatColor.RESET + cheatsAllowed));
        }

        client.player.sendSystemMessage(Component.literal("Connecting to the playit.gg Network..."));

        var secretKey = PlayitForgeConfig.CFG_AGENT_SECRET_KEY.get();
        resetConnection(secretKey);

        new Thread (() -> {
            while (playitManager.getAddress() == null) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignore) {
                }
            }
            String address = playitManager.getAddress().replace("craft.ply.gg", "joinmc.link");
            var msg = Component.literal("Your server is now public under: " + ChatColor.GREEN + address + ChatColor.RESET + " (click to copy)")
                .withStyle(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, address)));
            client.player.sendSystemMessage(msg);
        }).start();
    }

    public boolean isClient() {
        return client != null;
    }
}
