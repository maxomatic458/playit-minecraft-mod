package gg.playit.playitfabric;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import gg.playit.api.ApiClient;
import gg.playit.api.models.Notice;
import gg.playit.control.PlayitControlChannel;
import gg.playit.messages.ControlFeedReader;
import gg.playit.playitfabric.utils.ChatColor;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class PlayitManager implements Runnable {
    static Logger log = LogUtils.getLogger();
    private final AtomicInteger state = new AtomicInteger(STATE_INIT);
    private final PlayitConnectionTracker tracker = new PlayitConnectionTracker();
    private final PlayitFabric playitFabric;

    public PlayitManager(PlayitFabric playitFabric) {
        this.playitFabric = playitFabric;

        var secret = playitFabric.config.CFG_AGENT_SECRET_KEY;
        if (secret != null && secret.length() < 32) {
            secret = null;
        }

        setup = new PlayitKeysSetup(secret, state);
    }

    private final PlayitKeysSetup setup;
    private volatile PlayitKeysSetup.PlayitKeys keys;

    public boolean isGuest() {
        return keys != null && keys.isGuest;
    }

    public boolean emailVerified() {
        return keys == null || keys.isEmailVerified;
    }

    public String getAddress() {
        if (keys == null) {
            return null;
        }
        return keys.tunnelAddress;
    }

    public Notice getNotice() {
        var k = keys;
        if (k == null) {
            return null;
        }
        return k.notice;
    }

    public volatile int connectionTimeoutSeconds = 30;
    public static final int STATE_INIT = -1;
    public static final int STATE_OFFLINE = 10;
    public static final int STATE_CONNECTING = 11;
    public static final int STATE_ONLINE = 12;
    public static final int STATE_ERROR_WAITING = 13;
    public static final int STATE_SHUTDOWN = 0;
    public static final int STATE_INVALID_AUTH = 15;

    public void shutdown() {
        state.compareAndSet(STATE_ONLINE, STATE_SHUTDOWN);
    }

    public int state() {
        return state.get();
    }

    @Override
    public void run() {
        /* make sure we don't run two instances */
        if (!state.compareAndSet(STATE_INIT, PlayitKeysSetup.STATE_INIT)) {
            return;
        }

        while (state.get() != STATE_SHUTDOWN) {
            try {
                keys = setup.progress();

                if (keys != null) {
                    log.info("keys and tunnel setup");
                    break;
                }
            } catch (IOException e) {
                log.error("got error during setup: " + e);

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignore) {
                }

                continue;
            }

            if (state.get() == PlayitKeysSetup.STATE_MISSING_SECRET) {
                var code = setup.getClaimCode();
                if (code != null) {
                    var playerList = playitFabric.server.getPlayerManager().getPlayerList();
                    for (ServerPlayerEntity player : playerList) {
                        if (player.hasPermissionLevel(3) || (!playitFabric.server.isDedicated() && player.getUuid() == playitFabric.server.getHostProfile().getId())) {
                            // clickable link
                            var url = "https://playit.gg/mc/" + code;
                            var msg = Text.literal("Click " + ChatColor.RED + "here" + ChatColor.RESET + " to setup your playit.gg tunnel")
                                .setStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
                            
                            player.sendMessage(msg);
                        } else {
                            player.sendMessage(Text.literal("Check server logs to get playit.gg claim link to setup tunnel (or be a Server Operator)"));
                        }
                    }
                }

                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignore) {
                }
            }
        }

        if (keys == null) {
            log.info("shutdown reached, tunnel connection never started");
            return;
        }

        playitFabric.config.setAgentSecret(keys.secretKey);
        
        if (keys.isGuest) {
            // TODO: broadcasting

            var api = new ApiClient(keys.secretKey);

            try {
                var key = api.createGuestWebSessionKey();
                var url = "https://playit.gg/login/guest-account/" + key;
                log.info("setup playit.gg account: " + url);

                if (state.get() == STATE_SHUTDOWN) {
                    return;
                }

                var playerList = playitFabric.server.getPlayerManager().getPlayerList();
                for (ServerPlayerEntity player : playerList) {
                    if (player.hasPermissionLevel(3)) {
                        player.sendMessage(Text.literal("setup a playit.gg account"));
                        player.sendMessage(Text.literal(ChatColor.RED + "URL: " + ChatColor.RESET + url));
                    }
                }
            } catch (IOException e) {
                log.error("failed to generate web session key: " + e);
            }
        } else if (!keys.isEmailVerified) {
            // TODO: broadcasting
        }
        // TODO: broadcast success

        if (state.get() == STATE_SHUTDOWN) {
            return;
        }

        state.set(STATE_CONNECTING);

        while (state.get() == STATE_CONNECTING) {
            try (PlayitControlChannel channel = PlayitControlChannel.setup(keys.secretKey)) {
                state.compareAndSet(STATE_CONNECTING, STATE_ONLINE);

                while (state.get() == STATE_ONLINE) {
                    var messageOpt = channel.update();
                    if (messageOpt.isPresent()) {
                        var feedMessage = messageOpt.get();

                        if (feedMessage instanceof ControlFeedReader.NewClient newClient) {
                            log.info("got new client: " + feedMessage);

                            var key = newClient.peerAddr + "-" + newClient.connectAddr;
                            if (tracker.addConnection(key)) {
                                log.info("starting tcp tunnel for client");

                                new PlayitTcpTunnel(
                                        new InetSocketAddress(InetAddress.getByAddress(newClient.peerAddr.ipBytes), Short.toUnsignedInt(newClient.peerAddr.portNumber)),
                                        playitFabric.eventGroup,
                                        tracker,
                                        key,
                                        new InetSocketAddress(playitFabric.server.getServerIp(), playitFabric.server.getServerPort()),
                                        new InetSocketAddress(InetAddress.getByAddress(newClient.claimAddress.ipBytes), Short.toUnsignedInt(newClient.claimAddress.portNumber)),
                                        newClient.claimToken,
                                        playitFabric.server,
                                        connectionTimeoutSeconds
                                ).start();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                state.compareAndSet(STATE_ONLINE, STATE_ERROR_WAITING);
                log.error("failed when communicating with tunnel server, error: " + e);

                if (e.getMessage().contains("invalid authentication")) {
                    state.set(STATE_INVALID_AUTH);
                }

                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ignore) {
                }
            } finally {
                if (state.compareAndSet(STATE_SHUTDOWN, STATE_OFFLINE)) {
                    log.info("control channel shutdown");
                } else if (state.compareAndSet(STATE_ERROR_WAITING, STATE_CONNECTING)) {
                    log.info("trying to connect again");
                } else if (state.compareAndSet(STATE_ONLINE, STATE_CONNECTING)) {
                    log.warn("unexpected state ONLINE, moving to CONNECTING");
                }
                if (state.get() == STATE_CONNECTING) {
                    log.info("failed to connect, retrying");
                }
                if (state.get() == STATE_INVALID_AUTH) {
                    log.info("invalid auth, done trying");
                }
            }
        }
    }
}

