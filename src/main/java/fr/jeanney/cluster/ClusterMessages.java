package fr.jeanney.cluster;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.jeanney.MultiFabricServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.NonNull;

import static fr.jeanney.MultiFabricServer.MOD_ID;

public final class ClusterMessages {
    public static final String FALLBACK_LOCALE = "en_us";

    private static final String LANGUAGE_RESOURCE_DIRECTORY = "data/" + MOD_ID + "/lang";
    private static final int CURRENT_LANGUAGE_VERSION = 1;
    private static final char ESCAPED_AMPERSAND = '\ue000';
    private static final char ESCAPED_SECTION_SIGN = '\ue001';
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, LanguageMessages> LANGUAGES = new LinkedHashMap<>();
    private static LanguageMessages fallbackMessages = LanguageMessages.empty(FALLBACK_LOCALE);
    private static boolean initialized;

    private ClusterMessages() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        reload();
        initialized = true;
    }

    public static synchronized void reload() {
        Map<String, LanguageMessages> bundledMessages = loadBundledMessages();
        fallbackMessages = bundledMessages.getOrDefault(FALLBACK_LOCALE, LanguageMessages.empty(FALLBACK_LOCALE));

        Path langDirectory = languageDirectory();
        try {
            Files.createDirectories(langDirectory);
            for (Map.Entry<String, LanguageMessages> bundledLanguage : bundledMessages.entrySet()) {
                Path languagePath = langDirectory.resolve(bundledLanguage.getKey() + ".json");
                mergeLanguageFile(languagePath, bundledLanguage.getValue());
            }
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.warn(
                    "Failed to prepare MultiFabricServer language files at {}",
                    langDirectory,
                    ioException);
        }

        LANGUAGES.clear();
        LANGUAGES.putAll(loadConfiguredMessages(langDirectory));
        LANGUAGES.putIfAbsent(FALLBACK_LOCALE, fallbackMessages);
    }

    public static @NonNull MutableComponent component(ServerPlayer player, String key, Arg... args) {
        return parseFormatted(resolve(playerLocale(player), key, args));
    }

    public static @NonNull MutableComponent component(CommandSourceStack source, String key, Arg... args) {
        return parseFormatted(resolve(sourceLocale(source), key, args));
    }

    public static String locale(CommandSourceStack source) {
        return sourceLocale(source);
    }

    public static @NonNull MutableComponent component(String locale, String key, Arg... args) {
        return parseFormatted(resolve(locale, key, args));
    }

    public static String raw(String locale, String key, Arg... args) {
        return resolve(locale, key, args);
    }

    public static Arg arg(String name, Object value) {
        return new Arg(name, value, false);
    }

    public static Arg formattedArg(String name, Object value) {
        return new Arg(name, value, true);
    }

    public static String playerLocale(ServerPlayer player) {
        if (player == null) {
            return FALLBACK_LOCALE;
        }
        return normalizeLocale(player.clientInformation().language());
    }

    private static String sourceLocale(CommandSourceStack source) {
        if (source != null && source.getEntity() instanceof ServerPlayer player) {
            return playerLocale(player);
        }
        return FALLBACK_LOCALE;
    }

    private static synchronized String resolve(String locale, String key, Arg... args) {
        initialize();
        String normalizedLocale = normalizeLocale(locale);
        LanguageMessages messages = LANGUAGES.getOrDefault(normalizedLocale, fallbackMessages);
        String template = messages.message(key);
        if (template == null) {
            template = fallbackMessages.message(key);
        }
        if (template == null) {
            template = key;
        }
        return applyArgs(template, args);
    }

    private static String applyArgs(String template, Arg... args) {
        String result = template;
        if (args == null) {
            return result;
        }
        for (Arg arg : args) {
            if (arg == null || arg.name() == null || arg.name().isBlank()) {
                continue;
            }
            String value = String.valueOf(arg.value());
            result = result.replace("{" + arg.name() + "}", arg.formatted() ? value : escapeFormatting(value));
        }
        return result;
    }

    private static String escapeFormatting(String value) {
        return value.replace('&', ESCAPED_AMPERSAND).replace('§', ESCAPED_SECTION_SIGN);
    }

    private static @NonNull MutableComponent parseFormatted(String value) {
        MutableComponent root = Component.empty();
        if (value == null || value.isEmpty()) {
            return root;
        }

        StringBuilder segment = new StringBuilder();
        List<ChatFormatting> formats = new ArrayList<>();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == ESCAPED_AMPERSAND) {
                segment.append('&');
                continue;
            }
            if (current == ESCAPED_SECTION_SIGN) {
                segment.append('§');
                continue;
            }
            if ((current == '&' || current == '§') && index + 1 < value.length()) {
                ChatFormatting formatting = ChatFormatting.getByCode(value.charAt(index + 1));
                if (formatting != null) {
                    appendSegment(root, segment, formats);
                    if (formatting == ChatFormatting.RESET) {
                        formats.clear();
                    } else if (formatting.isColor()) {
                        formats.clear();
                        formats.add(formatting);
                    } else if (!formats.contains(formatting)) {
                        formats.add(formatting);
                    }
                    index++;
                    continue;
                }
            }
            segment.append(current);
        }
        appendSegment(root, segment, formats);
        return root;
    }

    private static void appendSegment(
            @NonNull MutableComponent root, StringBuilder segment, List<ChatFormatting> formats) {
        if (segment.isEmpty()) {
            return;
        }
        String text = Objects.requireNonNull(segment.toString());
        MutableComponent component = Component.literal(text);
        for (ChatFormatting format : formats) {
            component.withStyle(Objects.requireNonNull(format));
        }
        root.append(component);
        segment.setLength(0);
    }

    private static Path languageDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("lang");
    }

    private static Map<String, LanguageMessages> loadBundledMessages() {
        Map<String, LanguageMessages> loaded = new LinkedHashMap<>();
        for (String locale : discoverBundledLocales()) {
            readBundledMessages(locale).ifPresent(messages -> loaded.put(locale, messages));
        }
        return loaded;
    }

    private static List<String> discoverBundledLocales() {
        List<String> discoveredLocales = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .flatMap(container -> container.findPath(LANGUAGE_RESOURCE_DIRECTORY))
                .map(ClusterMessages::localesInDirectory)
                .orElseGet(List::of);

        LinkedHashSet<String> locales = new LinkedHashSet<>();
        if (discoveredLocales.isEmpty() || discoveredLocales.contains(FALLBACK_LOCALE)) {
            locales.add(FALLBACK_LOCALE);
        }
        locales.addAll(discoveredLocales);
        return List.copyOf(locales);
    }

    private static List<String> localesInDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                    .map(ClusterMessages::normalizeLocale)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.warn("Failed to discover bundled language files at {}", directory, ioException);
            return List.of();
        }
    }

    private static Optional<LanguageMessages> readBundledMessages(String locale) {
        String resourcePath = LANGUAGE_RESOURCE_DIRECTORY + "/" + locale + ".json";
        try (InputStream input = ClusterMessages.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                return Optional.empty();
            }
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return Optional.of(parseLanguage(locale, JsonParser.parseReader(reader)));
            }
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn("Failed to load bundled language file {}", resourcePath, exception);
            return Optional.empty();
        }
    }

    private static void mergeLanguageFile(Path path, LanguageMessages defaults) throws IOException {
        Optional<LanguageMessages> loadedLanguage = Files.exists(path)
                ? readConfiguredLanguage(path)
                : Optional.empty();
        LanguageMessages current = loadedLanguage.orElse(defaults);
        Map<String, String> mergedMessages = new LinkedHashMap<>(current.messages());
        boolean changed = !Files.exists(path) || loadedLanguage.isEmpty()
                || current.version() < CURRENT_LANGUAGE_VERSION;

        for (Map.Entry<String, String> defaultMessage : defaults.messages().entrySet()) {
            if (!mergedMessages.containsKey(defaultMessage.getKey())) {
                mergedMessages.put(defaultMessage.getKey(), defaultMessage.getValue());
                changed = true;
            }
        }

        if (changed) {
            writeLanguage(path, new LanguageMessages(
                    defaults.locale(),
                    CURRENT_LANGUAGE_VERSION,
                    mergedMessages));
        }
    }

    private static Map<String, LanguageMessages> loadConfiguredMessages(Path langDirectory) {
        Map<String, LanguageMessages> loaded = new LinkedHashMap<>();
        if (!Files.isDirectory(langDirectory)) {
            return loaded;
        }

        try (var paths = Files.list(langDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> readConfiguredLanguage(path)
                            .ifPresent(messages -> loaded.put(messages.locale(), messages)));
        } catch (IOException ioException) {
            MultiFabricServer.LOGGER.warn("Failed to load MultiFabricServer language files at {}", langDirectory,
                    ioException);
        }
        return loaded;
    }

    private static Optional<LanguageMessages> readConfiguredLanguage(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String locale = path.getFileName().toString().replaceFirst("\\.json$", "");
            return Optional.of(parseLanguage(locale, JsonParser.parseReader(reader)));
        } catch (Exception exception) {
            MultiFabricServer.LOGGER.warn("Failed to load MultiFabricServer language file {}", path, exception);
            return Optional.empty();
        }
    }

    private static LanguageMessages parseLanguage(String locale, JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return LanguageMessages.empty(locale);
        }

        JsonObject root = element.getAsJsonObject();
        int version = root.has("version") && root.get("version").isJsonPrimitive()
                ? root.get("version").getAsInt()
                : 0;
        JsonObject messagesObject = root.has("messages") && root.get("messages").isJsonObject()
                ? root.getAsJsonObject("messages")
                : root;

        Map<String, String> messages = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : messagesObject.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                messages.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        messages.remove("version");
        return new LanguageMessages(normalizeLocale(locale), version, messages);
    }

    private static void writeLanguage(Path path, LanguageMessages language) throws IOException {
        Files.createDirectories(path.getParent());

        JsonObject root = new JsonObject();
        root.addProperty("version", CURRENT_LANGUAGE_VERSION);

        JsonObject messages = new JsonObject();
        for (Map.Entry<String, String> entry : language.messages().entrySet()) {
            messages.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("messages", messages);

        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return FALLBACK_LOCALE;
        }
        return locale.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    public record Arg(String name, Object value, boolean formatted) {
    }

    private record LanguageMessages(
            String locale,
            int version,
            Map<String, String> messages) {
        private static LanguageMessages empty(String locale) {
            return new LanguageMessages(normalizeLocale(locale), 0, Map.of());
        }

        private LanguageMessages {
            locale = normalizeLocale(locale);
            messages = messages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        }

        private String message(String key) {
            return messages.get(key);
        }
    }
}
