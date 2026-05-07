package com.rakku212.send_modlist;

import net.minecraft.resources.ResourceLocation;

public final class ModListProtocol {
    public static final String CHANNEL_NAMESPACE = "send_modlist";
    public static final String CHANNEL_PATH = "modlist";
    public static final ResourceLocation CHANNEL_ID =
            new ResourceLocation(CHANNEL_NAMESPACE, CHANNEL_PATH);

    public static final String REQUEST_MODLIST = "REQUEST_MODLIST";
    public static final String REQUEST_MODLIST_SEPARATOR = "|";
    public static final String MODLIST_ACCEPTED = "MODLIST_ACCEPTED";
    public static final int MAX_PLUGIN_MESSAGE_SIZE = 32767;
}
