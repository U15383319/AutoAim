package dev.cosmos.autoaim.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.architectury.platform.Platform;
import dev.cosmos.autoaim.AutoAim;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Environment(EnvType.CLIENT)
public final class AutoAimConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_GLOW_COLOR = "#FF7A0D";
    private static final int DEFAULT_GLOW_COLOR_RGB = 0xFF7A0D;
    private static final List<String> DEFAULT_BLACKLIST = List.of(
            "minecraft:item",
            "minecraft:experience_orb",
            "minecraft:area_effect_cloud",
            "minecraft:arrow",
            "minecraft:spectral_arrow",
            "minecraft:trident",
            "minecraft:firework_rocket",
            "minecraft:fishing_bobber"
    );

    private static volatile int glowColor = DEFAULT_GLOW_COLOR_RGB;
    private static volatile Set<ResourceLocation> entityBlacklist = Set.of();

    private AutoAimConfig() {
    }

    public static void load() {
        Path configPath = Platform.getConfigFolder().resolve("autoaim.json");
        ConfigData data = new ConfigData();

        if (Files.exists(configPath)) {
            try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                if (loaded != null) {
                    data = loaded;
                }
            } catch (Exception exception) {
                AutoAim.LOGGER.error("Unable to read {}; using defaults", configPath, exception);
            }
        } else {
            try {
                Files.createDirectories(configPath.getParent());
                try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                    GSON.toJson(data, writer);
                }
            } catch (IOException exception) {
                AutoAim.LOGGER.error("Unable to create default config at {}", configPath, exception);
            }
        }

        glowColor = parseColor(data.glowColor);
        entityBlacklist = parseBlacklist(data.entityBlacklist);
        AutoAim.LOGGER.info(
                "Loaded Auto Aim config: glowColor=#{}, {} blacklisted entity types",
                String.format("%06X", glowColor),
                entityBlacklist.size()
        );
    }

    public static int glowColor() {
        return glowColor;
    }

    public static boolean isBlacklisted(Entity entity) {
        ResourceLocation entityTypeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return entityBlacklist.contains(entityTypeId);
    }

    private static int parseColor(String configuredColor) {
        if (configuredColor == null) {
            return DEFAULT_GLOW_COLOR_RGB;
        }

        String value = configuredColor.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        } else if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }

        if (value.length() != 6) {
            AutoAim.LOGGER.warn("Invalid glowColor '{}'; expected #RRGGBB", configuredColor);
            return DEFAULT_GLOW_COLOR_RGB;
        }

        try {
            return Integer.parseInt(value, 16) & 0xFFFFFF;
        } catch (NumberFormatException exception) {
            AutoAim.LOGGER.warn("Invalid glowColor '{}'; expected #RRGGBB", configuredColor);
            return DEFAULT_GLOW_COLOR_RGB;
        }
    }

    private static Set<ResourceLocation> parseBlacklist(List<String> configuredBlacklist) {
        List<String> entries = configuredBlacklist == null ? DEFAULT_BLACKLIST : configuredBlacklist;
        Set<ResourceLocation> parsed = new HashSet<>();
        for (String entry : entries) {
            ResourceLocation id = entry == null ? null : ResourceLocation.tryParse(entry.trim());
            if (id == null) {
                AutoAim.LOGGER.warn("Ignoring invalid entity id '{}' in entityBlacklist", entry);
            } else {
                parsed.add(id);
            }
        }
        return Set.copyOf(parsed);
    }

    private static final class ConfigData {
        private String glowColor = DEFAULT_GLOW_COLOR;
        private List<String> entityBlacklist = new ArrayList<>(DEFAULT_BLACKLIST);
    }
}
