package gg.skylands.localization;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import gg.skylands.localization.legacy.LegacyMiniMessageTranslator;
import gg.skylands.protocol.SkylandsRedis;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.CheckReturnValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;

/**
 * Represents a message that can be sent to a {@link Audience}
 * powered by MiniMessage and cached in Redis for fast access and
 * easy synchronization across multiple servers.
 */
public class Message {

    private static final String PREFIX = "Localization";

    private static final SkylandsRedis REDIS = SkylandsRedis.instance();

    private static MiniMessage DEFAULT_PROVIDER = MiniMessage.miniMessage();
    private static LocalizationPlatform PLATFORM = null;

    public static final LoadingCache<String, String> CACHE = CacheBuilder.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .build(new CacheLoader<>() {
                @Override
                public @NotNull String load(@NotNull String key) throws Exception {
                    String message = REDIS.runCommand(jedis -> jedis.hget(SkylandsRedis.PREFIX + PREFIX, key));
                    if (message == null) throw new Exception("Message not found for key: " + key);

                    return message;
                }
            });

    private final String key;
    private final String defaultMessage;

    /**
     * Creates a new message with a key and default message.
     * @param key The key of the message.
     * @param defaultMessage The default message of the message.
     */
    public Message(@NotNull String key, String... defaultMessage) {
        this.key = key;
        this.defaultMessage = String.join("<newline>", defaultMessage);

        REDIS.runCommand(jedis -> {
            boolean exists = jedis.hexists(SkylandsRedis.PREFIX + PREFIX, this.key);
            if (!exists) jedis.hset(SkylandsRedis.PREFIX + PREFIX, this.key, this.defaultMessage);

            String message = jedis.hget(SkylandsRedis.PREFIX + PREFIX, this.key);
            CACHE.put(key, message);

            return null;
        });
    }

    @NotNull
    public String content() {
        try {
            return CACHE.get(key);
        } catch (Exception e) {
            System.out.println("[skylands-localization] Failed to fetch message for key: " + key);
            return defaultMessage;
        }
    }

    @NotNull
    @CheckReturnValue
    public Builder create() {
        return new Builder(content());
    }

    @NotNull
    @CheckReturnValue
    public Builder create(@NotNull MiniMessage provider) {
        return new Builder(content(), provider);
    }

    /**
     * Sets the default provider for all messages.
     * @param instance The provider to use.
     */
    public static void setDefaultProvider(@NotNull MiniMessage instance) {
        DEFAULT_PROVIDER = instance;
    }

    /**
     * Sets the platform for message parsing.
     * @param platform The platform to use.
     */
    public static void setPlatform(@NotNull LocalizationPlatform platform) {
        PLATFORM = platform;
    }

    public static class Builder {

        private String current;
        private final MiniMessage provider;
        private final Map<String, String> stringPlaceholders = Maps.newHashMap();
        private final Map<String, Component> componentPlaceholders = Maps.newHashMap();

        public Builder(@NotNull String original) {
            this(original, DEFAULT_PROVIDER);
        }

        public Builder(@NotNull String original, @NotNull MiniMessage provider) {
            this.current = original;
            this.provider = provider;
        }

        /**
         * Replaces a placeholder with a string.
         * @param placeholder The placeholder to replace.
         * @param replacement The replacement string.
         * @return The builder instance.
         */
        @NotNull
        @CheckReturnValue
        public Builder replace(@NotNull String placeholder, @NotNull String replacement) {
            stringPlaceholders.put(placeholder, replacement);
            return this;
        }

        /**
         * Replaces a placeholder with a component.
         * @param placeholder The placeholder to replace.
         * @param replacement The replacement component.
         * @return The builder instance.
         */
        @NotNull
        @CheckReturnValue
        public Builder replace(@NotNull String placeholder, @NotNull Component replacement) {
            componentPlaceholders.put(placeholder, replacement);
            return this;
        }

        /**
         * Sends the message to the recipient.
         * @param recipient The recipient of the message.
         * @param <T> The type of the recipient.
         */
        public <T extends Audience> void send(@NotNull T recipient) {
            Component component = asComponent(recipient);
            recipient.sendMessage(component);
        }

        /**
         * Get a {@link Component} representation of the message.
         * @return The component representation of the message.
         */
        @NotNull
        public Component asComponent() {
            return asComponent(null);
        }

        /**
         * Get a {@link Component} representation of the message.
         * @param viewer The viewer of the message.
         * @return The component representation of the message.
         * @param <V> The type of the viewer.
         */
        public <V extends Audience> Component asComponent(@Nullable V viewer) {
            Component component = provider.deserialize(asMiniMessage(viewer));

            // Replacements for components
            for (Map.Entry<String, Component> entry : componentPlaceholders.entrySet()) {
                component = component.replaceText(TextReplacementConfig.builder().matchLiteral(entry.getKey()).replacement(entry.getValue()).build());
            }

            // Parse ItemsAdder unicodes if available.
            if (PLATFORM != null) {
                component = PLATFORM.parseItemsAdder(viewer, component);
            }

            return component;
        }

        /**
         * Get a {@link Component} representation of the message.
         * @param viewer The viewer of the message.
         * @return The component representation of the message.
         * @param <V> The type of the viewer.
         */
        @NotNull
        public <V extends Audience> String asMiniMessage(@Nullable V viewer) {
            if (PLATFORM != null) {
                return PLATFORM.parsePlaceholderApi(viewer, current);
            }

            // Parse legacy color codes if available.
            if (current.contains("&")) {
                current = LegacyMiniMessageTranslator.legacyToMiniMessage(current);
            }

            // Replacements for pre-serialized strings
            for (Map.Entry<String, String> entry : stringPlaceholders.entrySet()) {
                current = current.replaceAll(entry.getKey(), entry.getValue());
            }

            return current;
        }
    }
}
