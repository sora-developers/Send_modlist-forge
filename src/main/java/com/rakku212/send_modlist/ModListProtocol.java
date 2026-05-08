package com.rakku212.send_modlist;

public final class ModListProtocol {
    public static final String CHANNEL_ID_LEGACY = "send_modlist";
    public static final String CHANNEL_ID_NAMESPACED = "send_modlist:modlist";
    public static final String[] REGISTER_CHANNELS = new String[] {
            CHANNEL_ID_LEGACY,
            CHANNEL_ID_NAMESPACED
    };

    public static final String REQUEST_MODLIST = "REQUEST_MODLIST";
    public static final String REQUEST_MODLIST_SEPARATOR = "|";
    public static final String MODLIST_ACCEPTED = "MODLIST_ACCEPTED";
    public static final int MAX_PLUGIN_MESSAGE_SIZE = 32767;
}
