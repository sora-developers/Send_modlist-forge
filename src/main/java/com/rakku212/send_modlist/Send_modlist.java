package com.rakku212.send_modlist;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.event.EventNetworkChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.vertex.PoseStack;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Mod(Constants.MOD_ID)
public class Send_modlist {
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_NAME);
    private static final Component VERIFYING_MODLIST_MESSAGE = new TextComponent("mod verifying...");
    private static boolean awaitingModListAccepted;
    private static boolean modListPayloadDelivered;
    private static byte[] sessionHmacKey;
    private static byte[] pendingModListPayloadBytes;
    private static boolean modListPayloadBuildFailed;
    private static int trySendLogCooldown;

    public static final EventNetworkChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(ModListProtocol.CHANNEL_ID)
            .networkProtocolVersion(() -> "1")
            .clientAcceptedVersions(ignored -> true)
            .serverAcceptedVersions(ignored -> true)
            .eventNetworkChannel();

    public Send_modlist() {
        CommonClass.init();
        CHANNEL.addListener(Send_modlist::onCustomPayload);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(Send_modlist::onClientTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(Send_modlist::onLoggingOut);
    }

    private static void onCustomPayload(NetworkEvent.ServerCustomPayloadEvent event) {
        FriendlyByteBuf payload = event.getPayload();
        if (payload == null || payload.readableBytes() <= 0) {
            return;
        }
        byte[] bytes = new byte[payload.readableBytes()];
        payload.readBytes(bytes);
        handleServerMessage(new String(bytes, StandardCharsets.UTF_8).trim());
    }

    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ensureVerifyingScreenVisible();
        tryDeliverModListPayload();
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        resetModListSession();
    }

    private static void handleServerMessage(String message) {
        String requestPrefix =
                ModListProtocol.REQUEST_MODLIST + ModListProtocol.REQUEST_MODLIST_SEPARATOR;
        if (message.startsWith(requestPrefix)) {
            String keyB64 = message.substring(requestPrefix.length());
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
            return;
        }

        if (ModListProtocol.REQUEST_MODLIST.equals(message)) {
            LOGGER.warn("Received REQUEST_MODLIST without HMAC session key; server requires signed payload");
            return;
        }

        if (ModListProtocol.MODLIST_ACCEPTED.equals(message)) {
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
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof VerifyingScreen) {
            client.setScreen(null);
        }
        awaitingModListAccepted = false;
        modListPayloadDelivered = false;
        sessionHmacKey = null;
        pendingModListPayloadBytes = null;
        modListPayloadBuildFailed = false;
        trySendLogCooldown = 0;
    }

    private static void ensureVerifyingScreenVisible() {
        Minecraft client = Minecraft.getInstance();
        if (!awaitingModListAccepted) {
            if (client.screen instanceof VerifyingScreen) {
                client.setScreen(null);
            }
            return;
        }
        if (client.screen == null) {
            client.setScreen(new VerifyingScreen());
        }
    }

    private static void fillOverlay(PoseStack poseStack, int width, int height) {
        Screen.fill(poseStack, 0, 0, width, height, 0xC0101010);
        Screen.drawCenteredString(
                poseStack,
                Minecraft.getInstance().font,
                VERIFYING_MODLIST_MESSAGE,
                width / 2,
                height / 2 - 9,
                0xFFFFFF
        );
    }

    private static void tryDeliverModListPayload() {
        if (!awaitingModListAccepted || modListPayloadDelivered) {
            return;
        }
        if (modListPayloadBuildFailed) {
            return;
        }
        if (pendingModListPayloadBytes == null) {
            pendingModListPayloadBytes = buildModListPayloadBytes();
            if (pendingModListPayloadBytes == null) {
                modListPayloadBuildFailed = true;
                return;
            }
        }

        if (Minecraft.getInstance().getConnection() == null) {
            bumpWaitLog();
            return;
        }

        try {
            FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.wrappedBuffer(pendingModListPayloadBytes));
            Minecraft.getInstance().getConnection().getConnection().send(
                    new ServerboundCustomPayloadPacket(ModListProtocol.CHANNEL_ID, payload)
            );
            modListPayloadDelivered = true;
            LOGGER.info("Sent mod list payload (play): {} bytes", pendingModListPayloadBytes.length);
        } catch (Exception e) {
            LOGGER.warn("Play modlist send failed (retry next tick): {}", e.getMessage());
        }
    }

    private static void bumpWaitLog() {
        if (trySendLogCooldown > 0) {
            trySendLogCooldown--;
        } else {
            trySendLogCooldown = 100;
            LOGGER.debug("Waiting for play connection before modlist send");
        }
    }

    private static byte[] buildModListPayloadBytes() {
        List<ModListPayloadBuilder.ModInfo> mods = new ArrayList<>();
        for (IModInfo mod : FMLLoader.getLoadingModList().getMods()) {
            mods.add(new ModListPayloadBuilder.ModInfo(
                    mod.getModId(),
                    mod.getDisplayName(),
                    mod.getVersion().toString(),
                    "CLIENT"
            ));
        }

        String loaderVersion = FMLLoader.versionInfo().forgeVersion();
        String jsonBody = ModListPayloadBuilder
                .build("forge", loaderVersion, mods)
                .toString();
        if (sessionHmacKey == null) {
            LOGGER.warn("Cannot build mod list payload: no HMAC session key");
            return null;
        }
        byte[] payloadBytes = ModListHmac.utf8SignedPayload(sessionHmacKey, jsonBody);

        if (payloadBytes.length > ModListProtocol.MAX_PLUGIN_MESSAGE_SIZE) {
            LOGGER.warn("Mod list payload is too large to send: {} bytes", payloadBytes.length);
            return null;
        }

        return payloadBytes;
    }

    private static final class VerifyingScreen extends Screen {
        private VerifyingScreen() {
            super(VERIFYING_MODLIST_MESSAGE);
        }

        @Override
        public boolean shouldCloseOnEsc() {
            return false;
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void render(PoseStack poseStack, int mouseX, int mouseY, float partialTick) {
            fillOverlay(poseStack, this.width, this.height);
        }
    }
}
