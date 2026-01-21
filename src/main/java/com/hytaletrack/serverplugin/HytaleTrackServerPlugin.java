package com.hytaletrack.serverplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HytaleTrack Server Plugin
 *
 * Submits server data to HytaleTrack for display on player and server profiles.
 *
 * @see <a href="https://hytaletrack.com">HytaleTrack</a>
 * @see <a href="https://github.com/BlockhostOfficial/hytaletrack-server-plugin">GitHub</a>
 */
public class HytaleTrackServerPlugin extends JavaPlugin {
    private static final Gson GSON = new Gson();
    private static final String API_URL_ENV = "HYTALETRACK_API_URL";
    private static final String API_KEY_ENV = "HYTALETRACK_API_KEY";
    private static final String DEFAULT_API_URL = "https://hytaletrack.com/api/server-plugin/submit";
    private static final int STATUS_INTERVAL_SECONDS = 30;

    private String apiKey;
    private String apiUrl;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, PlayerInfo> onlinePlayers = new ConcurrentHashMap<>();

    // Simple record to store player info
    private record PlayerInfo(UUID uuid, String name) {}

    public HytaleTrackServerPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Load configuration from environment
        apiKey = System.getenv(API_KEY_ENV);
        apiUrl = System.getenv(API_URL_ENV);
        if (apiUrl == null || apiUrl.isBlank()) {
            apiUrl = DEFAULT_API_URL;
        }

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[HytaleTrack] ERROR: HYTALETRACK_API_KEY environment variable is not set!");
            System.err.println("[HytaleTrack] Plugin will not submit data. Visit https://hytaletrack.com to get your API key.");
            return;
        }

        if (!apiKey.startsWith("htk_")) {
            System.err.println("[HytaleTrack] WARNING: API key doesn't have expected format (htk_...)");
        }

        // Initialize HTTP client
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Register event handlers
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);

        // Start periodic status updates
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::submitStatus,
                STATUS_INTERVAL_SECONDS,
                STATUS_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );

        System.out.println("[HytaleTrack] Server Plugin loaded and connected!");
        System.out.println("[HytaleTrack] Submitting data to: " + apiUrl);

        // Submit initial status
        submitStatus();
    }

    protected void teardown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }
        System.out.println("[HytaleTrack] Server Plugin unloaded.");
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUuid();
        String playerName = player.getDisplayName();

        // Track the player
        onlinePlayers.put(playerUuid, new PlayerInfo(playerUuid, playerName));

        // Submit player join event asynchronously
        JsonObject data = new JsonObject();
        data.addProperty("playerUuid", playerUuid.toString());
        data.addProperty("playerName", playerName);
        submitAsync("player_join", data);

        System.out.println("[HytaleTrack] Player joined: " + playerName + " (" + onlinePlayers.size() + " online)");
    }

    /**
     * Call this method when a player disconnects.
     * Since we can't easily hook into disconnect events, server owners can call this from their own code.
     */
    public void onPlayerLeave(UUID playerUuid) {
        PlayerInfo info = onlinePlayers.remove(playerUuid);
        if (info != null) {
            JsonObject data = new JsonObject();
            data.addProperty("playerUuid", info.uuid.toString());
            data.addProperty("playerName", info.name);
            submitAsync("player_leave", data);

            System.out.println("[HytaleTrack] Player left: " + info.name + " (" + onlinePlayers.size() + " online)");
        }
    }

    /**
     * Alternative: Call this with player name if UUID is not available.
     */
    public void onPlayerLeave(String playerName) {
        onlinePlayers.values().stream()
                .filter(info -> info.name.equals(playerName))
                .findFirst()
                .ifPresent(info -> onPlayerLeave(info.uuid));
    }

    private void submitStatus() {
        if (apiKey == null) return;

        JsonObject data = new JsonObject();
        data.addProperty("playerCount", onlinePlayers.size());
        data.addProperty("isOnline", true);

        submitAsync("status", data);
    }

    private void submitAsync(String type, JsonObject data) {
        if (httpClient == null || apiKey == null) return;

        // Run in a separate thread to avoid blocking
        Thread.startVirtualThread(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("type", type);
                body.add("data", data);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Api-Key", apiKey)
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    String errorMsg = parseErrorMessage(response.body());
                    System.err.println("[HytaleTrack] Failed to submit " + type + ": " + response.statusCode() + " - " + errorMsg);
                }
            } catch (java.net.ConnectException e) {
                System.err.println("[HytaleTrack] Connection error: " + e.getMessage());
            } catch (java.net.http.HttpTimeoutException e) {
                System.err.println("[HytaleTrack] Request timeout: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("[HytaleTrack] Error submitting " + type + ": " + e.getMessage());
            }
        });
    }

    private String parseErrorMessage(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("error")) {
                return json.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return responseBody;
    }
}
