package gg.playit.playitforge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojang.logging.LogUtils;

import gg.playit.ChatColor;
import gg.playit.api.models.Notice;
import gg.playit.playitforge.config.PlayitForgeConfig;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(PlayitForge.MODID)
public class PlayitForge {

    // Define mod id in a common place for everything to reference
    public static final String MODID = "playit_forge";
    private static final Logger log = LogUtils.getLogger();
    final EventLoopGroup eventGroup = new NioEventLoopGroup();

    private final Object managerSync = new Object();
    private volatile PlayitManager playitManager;
    // private ObjectMapper mapper = new ObjectMapper();
    //private SimpleObjectIdResolver resolver = new SimpleObjectIdResolver();
    MinecraftServer server;

    public PlayitForge() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        // Register ourselves for server and other game events we are interested in
        ModLoadingContext.get()
            .registerConfig(ModConfig.Type.COMMON, PlayitForgeConfig.SPEC, "playit-forge-config.toml"); 
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        server = event.getServer();
        if (server.isDedicatedServer()) {
            var secretKey = PlayitForgeConfig.CFG_AGENT_SECRET_KEY.get();
            resetConnection(secretKey);
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerLoggedInEvent event) {
        Entity player = event.getEntity();
        PlayitManager manager = playitManager;

        if (player.hasPermissions(3)) {
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
    public static class ClientModEvents {

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }

    private void resetConnection(String secretKey) {
        if (secretKey != null) {
            PlayitForgeConfig.CFG_AGENT_SECRET_KEY.set(secretKey);
            PlayitForgeConfig.SPEC.save(); //TODO ?
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
}
