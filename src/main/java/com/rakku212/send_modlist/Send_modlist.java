package com.rakku212.send_modlist;

import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.common.network.FMLEventChannel;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Arrays;

@Mod(modid = Constants.MOD_ID, name = Constants.MOD_NAME, version = Constants.MOD_VERSION, acceptedMinecraftVersions = "[1.11.2]")
public class Send_modlist {
    private static final Logger LOGGER = LogManager.getLogger(Constants.MOD_NAME);
    private static final String VERIFYING_MODLIST_MESSAGE = "mod verifying...";
    private static boolean awaitingModListAccepted;
    private static boolean modListPayloadDelivered;
    private static byte[] sessionHmacKey;
    private static byte[] pendingModListPayloadBytes;
    private static boolean modListPayloadBuildFailed;
    private static int trySendLogCooldown;
    private static boolean channelRegistrationSent;

    public static final FMLEventChannel LEGACY_CHANNEL =
            NetworkRegistry.INSTANCE.newEventDrivenChannel(ModListProtocol.CHANNEL_ID_LEGACY);
    public static final FMLEventChannel NAMESPACED_CHANNEL =
            NetworkRegistry.INSTANCE.newEventDrivenChannel(ModListProtocol.CHANNEL_ID_NAMESPACED);
    private static String replyChannel = ModListProtocol.CHANNEL_ID_LEGACY;

    public Send_modlist() {
        CommonClass.init();
        LOGGER.info("[modlist-debug] constructor loaded");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LOGGER.info("[modlist-debug] init side={}", event.getSide());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientOnlyHandler handler = new ClientOnlyHandler();
            LEGACY_CHANNEL.register(handler);
            NAMESPACED_CHANNEL.register(handler);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(handler);
            LOGGER.info("[modlist-debug] client handlers registered");
        }
    }

    @SideOnly(Side.CLIENT)
    private static class ClientOnlyHandler {
        @SubscribeEvent
        public void onConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
            LOGGER.info("[modlist-debug] connected local={}", event.isLocal());
            resetModListSession();
        }

        @SubscribeEvent
        public void onDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
            LOGGER.info("[modlist-debug] disconnected");
            resetModListSession();
        }

        @SubscribeEvent
        public void onCustomPayload(FMLNetworkEvent.ClientCustomPacketEvent event) {
            FMLProxyPacket packet = event.getPacket();
            if (packet == null || packet.payload() == null || packet.payload().readableBytes() <= 0) {
                LOGGER.debug("[modlist-debug] payload ignored");
                return;
            }
            LOGGER.info("[modlist-debug] payload received channel={} bytes={}", packet.channel(), packet.payload().readableBytes());
            replyChannel = packet.channel();
            byte[] bytes = new byte[packet.payload().readableBytes()];
            packet.payload().readBytes(bytes);
            String body = new String(bytes, StandardCharsets.UTF_8);
            LOGGER.info("[modlist-debug] payload body={}", body);
            handleServerMessage(body.trim());
        }

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            if (Minecraft.getMinecraft().getConnection() == null) {
                if (hasActiveSessionState()) {
                    LOGGER.info("[modlist-debug] tick disconnected -> reset");
                    resetModListSession();
                }
                return;
            }
            trySendChannelRegisterPacket();
            ensureVerifyingScreenVisible();
            tryDeliverModListPayload();
        }
    }

    private static void trySendChannelRegisterPacket() {
        if (channelRegistrationSent) {
            return;
        }
        if (Minecraft.getMinecraft().getConnection() == null) {
            LOGGER.debug("[modlist-debug] register skipped no connection");
            return;
        }
        try {
            String registerBody = String.join("\u0000", ModListProtocol.REGISTER_CHANNELS);
            byte[] channelBytes = registerBody.getBytes(StandardCharsets.UTF_8);
            PacketBuffer registerPayload = new PacketBuffer(Unpooled.wrappedBuffer(channelBytes));
            Minecraft.getMinecraft().getConnection().sendPacket(
                    new CPacketCustomPayload("REGISTER", registerPayload)
            );
            channelRegistrationSent = true;
            LOGGER.info("Registered plugin channels: {}", Arrays.toString(ModListProtocol.REGISTER_CHANNELS));
        } catch (Exception e) {
            LOGGER.warn("Failed to register plugin channel: {}", e.getMessage());
        }
    }

    private static boolean hasActiveSessionState() {
        if (awaitingModListAccepted || modListPayloadDelivered || sessionHmacKey != null
                || pendingModListPayloadBytes != null || modListPayloadBuildFailed || channelRegistrationSent) {
            return true;
        }
        Minecraft client = Minecraft.getMinecraft();
        return client.currentScreen instanceof VerifyingScreen;
    }

    private static void handleServerMessage(String message) {
        LOGGER.info("[modlist-debug] handle message={}", message);
        String requestPrefix = ModListProtocol.REQUEST_MODLIST + ModListProtocol.REQUEST_MODLIST_SEPARATOR;
        if (message.startsWith(requestPrefix)) {
            String keyB64 = message.substring(requestPrefix.length());
            LOGGER.info("[modlist-debug] request received keyLen={}", keyB64.length());
            byte[] decodedKey;
            try {
                decodedKey = Base64.getDecoder().decode(keyB64);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid HMAC session key (base64): {}", e.getMessage());
                return;
            }
            if (decodedKey.length == 0) {
                LOGGER.warn("Empty HMAC session key");
                return;
            }
            sessionHmacKey = decodedKey;
            awaitingModListAccepted = true;
            modListPayloadDelivered = false;
            pendingModListPayloadBytes = null;
            modListPayloadBuildFailed = false;
            trySendLogCooldown = 0;
            LOGGER.info("[modlist-debug] key accepted bytes={}", decodedKey.length);
            return;
        }

        if (ModListProtocol.REQUEST_MODLIST.equals(message)) {
            LOGGER.warn("Received REQUEST_MODLIST without HMAC session key; server requires signed payload");
            return;
        }

        if (ModListProtocol.MODLIST_ACCEPTED.equals(message)) {
            LOGGER.info("[modlist-debug] server accepted modlist");
            clearVerifyingOverlay();
            return;
        }

        LOGGER.debug("Received unknown modlist channel message: {}", message);
    }

    private static void clearVerifyingOverlay() {
        if (!awaitingModListAccepted) {
            return;
        }
        resetModListSession();
    }

    private static void resetModListSession() {
        Minecraft client = Minecraft.getMinecraft();
        if (client.currentScreen instanceof VerifyingScreen) {
            client.displayGuiScreen(null);
        }
        awaitingModListAccepted = false;
        modListPayloadDelivered = false;
        sessionHmacKey = null;
        pendingModListPayloadBytes = null;
        modListPayloadBuildFailed = false;
        trySendLogCooldown = 0;
        channelRegistrationSent = false;
        replyChannel = ModListProtocol.CHANNEL_ID_LEGACY;
        LOGGER.info("[modlist-debug] state reset");
    }

    private static void ensureVerifyingScreenVisible() {
        Minecraft client = Minecraft.getMinecraft();
        GuiScreen current = client.currentScreen;
        if (!awaitingModListAccepted) {
            if (current instanceof VerifyingScreen) {
                client.displayGuiScreen(null);
            }
            return;
        }
        if (current == null) {
            client.displayGuiScreen(new VerifyingScreen());
        }
    }

    private static void tryDeliverModListPayload() {
        if (!awaitingModListAccepted || modListPayloadDelivered) {
            return;
        }
        if (modListPayloadBuildFailed) {
            return;
        }
        if (pendingModListPayloadBytes == null) {
            LOGGER.info("[modlist-debug] building payload");
            pendingModListPayloadBytes = buildModListPayloadBytes();
            if (pendingModListPayloadBytes == null) {
                modListPayloadBuildFailed = true;
                LOGGER.warn("[modlist-debug] payload build failed");
                return;
            }
            LOGGER.info("[modlist-debug] payload built bytes={}", pendingModListPayloadBytes.length);
        }

        if (Minecraft.getMinecraft().getConnection() == null) {
            bumpWaitLog();
            return;
        }

        try {
            PacketBuffer payload = new PacketBuffer(Unpooled.wrappedBuffer(pendingModListPayloadBytes));
            Minecraft.getMinecraft().getConnection().sendPacket(new CPacketCustomPayload(replyChannel, payload));
            modListPayloadDelivered = true;
            LOGGER.info("Sent mod list payload (play): {} bytes via channel={}", pendingModListPayloadBytes.length, replyChannel);
            LOGGER.info("[modlist-debug] payload sent");
        } catch (Exception e) {
            LOGGER.warn("Play modlist send failed (retry next tick): {}", e.getMessage());
        }
    }

    private static void bumpWaitLog() {
        if (trySendLogCooldown > 0) {
            trySendLogCooldown--;
        } else {
            trySendLogCooldown = 100;
            LOGGER.info("[modlist-debug] waiting for connection before payload send");
        }
    }

    private static byte[] buildModListPayloadBytes() {
        List<ModListPayloadBuilder.ModInfo> mods = new ArrayList<>();
        for (ModContainer mod : Loader.instance().getModList()) {
            mods.add(new ModListPayloadBuilder.ModInfo(
                    mod.getModId(),
                    mod.getName(),
                    mod.getVersion(),
                    "CLIENT"
            ));
        }
        LOGGER.info("[modlist-debug] mods collected={}", mods.size());

        String loaderVersion = "13.20.1";
        String jsonBody = ModListPayloadBuilder
                .build("forge", loaderVersion, mods)
                .toString();
        LOGGER.info("[modlist-debug] json chars={}", jsonBody.length());
        if (sessionHmacKey == null) {
            LOGGER.warn("Cannot build mod list payload: no HMAC session key");
            return null;
        }
        byte[] payloadBytes = ModListHmac.utf8SignedPayload(sessionHmacKey, jsonBody);

        if (payloadBytes.length > ModListProtocol.MAX_PLUGIN_MESSAGE_SIZE) {
            LOGGER.warn("Mod list payload is too large to send: {} bytes", payloadBytes.length);
            return null;
        }

        LOGGER.info("[modlist-debug] signed payload bytes={}", payloadBytes.length);
        return payloadBytes;
    }

    @SideOnly(Side.CLIENT)
    private static final class VerifyingScreen extends GuiScreen {

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            // Disable closing while verification is pending.
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            this.drawDefaultBackground();
            drawCenteredString(
                    this.fontRendererObj,
                    VERIFYING_MODLIST_MESSAGE,
                    this.width / 2,
                    this.height / 2 - 9,
                    0xFFFFFF
            );
        }
    }
}
