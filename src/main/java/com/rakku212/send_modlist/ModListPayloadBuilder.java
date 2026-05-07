package com.rakku212.send_modlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class ModListPayloadBuilder {

    public static JsonObject build(String clientType, String loaderVersion, List<ModInfo> mods) {
        JsonArray modsJson = new JsonArray();
        for (ModInfo mod : mods) {
            JsonObject modJson = new JsonObject();
            modJson.addProperty("id", mod.id());
            modJson.addProperty("name", mod.name());
            modJson.addProperty("version", mod.version());
            modJson.addProperty("environment", mod.environment());
            modsJson.add(modJson);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("client", clientType);
        payload.addProperty("loaderVersion", loaderVersion);
        payload.add("mods", modsJson);
        return payload;
    }

    public record ModInfo(String id, String name, String version, String environment) {
    }
}
