package com.rakku212.send_modlist;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

public final class ModListPayloadBuilder {

    public static JsonObject build(String clientType, String loaderVersion, List<ModInfo> mods) {
        JsonArray modsJson = new JsonArray();
        for (ModInfo mod : mods) {
            JsonObject modJson = new JsonObject();
            modJson.addProperty("id", mod.getId());
            modJson.addProperty("name", mod.getName());
            modJson.addProperty("version", mod.getVersion());
            modJson.addProperty("environment", mod.getEnvironment());
            modsJson.add(modJson);
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("client", clientType);
        payload.addProperty("loaderVersion", loaderVersion);
        payload.add("mods", modsJson);
        return payload;
    }

    public static final class ModInfo {
        private final String id;
        private final String name;
        private final String version;
        private final String environment;

        public ModInfo(String id, String name, String version, String environment) {
            this.id = id;
            this.name = name;
            this.version = version;
            this.environment = environment;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getEnvironment() {
            return environment;
        }
    }
}
