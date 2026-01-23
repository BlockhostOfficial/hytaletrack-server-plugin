package com.hytaletrack.serverplugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final String STATUS_INTERVAL_ENV = "HYTALETRACK_STATUS_INTERVAL";
    private static final String DEFAULT_API_URL = "https://hytaletrack.com/api/server-plugin/submit";
    private static final int DEFAULT_STATUS_INTERVAL_SECONDS = 30;
    private static final int MIN_STATUS_INTERVAL_SECONDS = 10;
    private static final int MAX_STATUS_INTERVAL_SECONDS = 300;

    // Connection and request timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    // Retry configuration
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final long MAX_BACKOFF_MS = 30000;

    // Concurrency limits for virtual threads
    private static final int MAX_CONCURRENT_REQUESTS = 15;

    private String apiKey;
    private String apiUrl;
    private int statusIntervalSeconds;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private Semaphore requestSemaphore;
    private final ConcurrentHashMap<UUID, PlayerInfo> onlinePlayers = new ConcurrentHashMap<>();
    private final AtomicInteger playerCount = new AtomicInteger(0);

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

        // Validate HTTPS URL
        if (!isValidHttpsUrl(apiUrl)) {
            System.err.println("[HytaleTrack] ERROR: API URL must be a valid HTTPS URL: " + sanitizeUrl(apiUrl));
            return;
        }

        // Parse status interval from environment
        statusIntervalSeconds = parseStatusInterval();

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("[HytaleTrack] ERROR: HYTALETRACK_API_KEY environment variable is not set!");
            System.err.println("[HytaleTrack] Plugin will not submit data. Visit https://hytaletrack.com to get your API key.");
            return;
        }

        if (!apiKey.startsWith("htk_")) {
            System.err.println("[HytaleTrack] WARNING: API key doesn't have expected format (htk_...)");
        }

        // Initialize semaphore for limiting concurrent requests
        requestSemaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);

        // Initialize HTTP client with connect timeout
        httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        // Register event handlers
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onPlayerReady);
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onPlayerDisconnect);

        // Start periodic status updates
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HytaleTrack-Scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::submitStatus,
                statusIntervalSeconds,
                statusIntervalSeconds,
                TimeUnit.SECONDS
        );

        System.out.println("[HytaleTrack] Server Plugin loaded and connected!");
        System.out.println("[HytaleTrack] Submitting data to: " + sanitizeUrl(apiUrl));
        System.out.println("[HytaleTrack] Status interval: " + statusIntervalSeconds + " seconds");

        // Submit initial status
        submitStatus();
    }

    @Override
    protected void teardown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        httpClient = null;
        System.out.println("[HytaleTrack] Server Plugin unloaded.");
    }

    /**
     * Validates that the URL is a proper HTTPS URL.
     */
    private boolean isValidHttpsUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sanitizes URL for logging (removes query params that might contain sensitive data).
     */
    private String sanitizeUrl(String url) {
        if (url == null) {
            return "[null]";
        }
        try {
            URI uri = URI.create(url);
            // Return just scheme + host + path, no query params
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                    + (uri.getPath() != null ? uri.getPath() : "");
        } catch (Exception e) {
            return "[invalid-url]";
        }
    }

    /**
     * Parses status interval from environment variable with validation.
     */
    private int parseStatusInterval() {
        String intervalStr = System.getenv(STATUS_INTERVAL_ENV);
        if (intervalStr == null || intervalStr.isBlank()) {
            return DEFAULT_STATUS_INTERVAL_SECONDS;
        }
        try {
            int interval = Integer.parseInt(intervalStr.trim());
            if (interval < MIN_STATUS_INTERVAL_SECONDS) {
                System.err.println("[HytaleTrack] WARNING: Status interval too low, using minimum: " + MIN_STATUS_INTERVAL_SECONDS);
                return MIN_STATUS_INTERVAL_SECONDS;
            }
            if (interval > MAX_STATUS_INTERVAL_SECONDS) {
                System.err.println("[HytaleTrack] WARNING: Status interval too high, using maximum: " + MAX_STATUS_INTERVAL_SECONDS);
                return MAX_STATUS_INTERVAL_SECONDS;
            }
            return interval;
        } catch (NumberFormatException e) {
            System.err.println("[HytaleTrack] WARNING: Invalid status interval, using default: " + DEFAULT_STATUS_INTERVAL_SECONDS);
            return DEFAULT_STATUS_INTERVAL_SECONDS;
        }
    }

    private void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        String playerName = player.getDisplayName();

        if (playerUuid == null) {
            System.err.println("[HytaleTrack] Player joined with null UUID, skipping tracking");
            return;
        }

        if (playerName == null || playerName.isBlank()) {
            playerName = "Unknown";
        }

        // Track the player atomically
        PlayerInfo newInfo = new PlayerInfo(playerUuid, playerName);
        PlayerInfo existing = onlinePlayers.putIfAbsent(playerUuid, newInfo);
        if (existing == null) {
            // Only increment if this is a new player (not a reconnect with same UUID)
            int count = playerCount.incrementAndGet();

            // Submit player join event asynchronously
            JsonObject data = new JsonObject();
            data.addProperty("playerUuid", playerUuid.toString());
            data.addProperty("playerName", playerName);
            submitAsync("player_join", data);

            System.out.println("[HytaleTrack] Player joined: " + playerName + " (" + count + " online)");
        }
    }

    /**
     * Event handler for player disconnect events.
     */
    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (playerUuid != null) {
            handlePlayerLeave(playerUuid);
        }
    }

    /**
     * Call this method when a player disconnects.
     * Can also be called manually by server owners if needed.
     */
    public void onPlayerLeave(UUID playerUuid) {
        if (playerUuid != null) {
            handlePlayerLeave(playerUuid);
        }
    }

    /**
     * Alternative: Call this with player name if UUID is not available.
     */
    public void onPlayerLeave(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        onlinePlayers.values().stream()
                .filter(info -> playerName.equals(info.name))
                .findFirst()
                .ifPresent(info -> handlePlayerLeave(info.uuid));
    }

    /**
     * Internal method to handle player leave logic.
     */
    private void handlePlayerLeave(UUID playerUuid) {
        PlayerInfo info = onlinePlayers.remove(playerUuid);
        if (info != null) {
            int count = playerCount.decrementAndGet();

            JsonObject data = new JsonObject();
            data.addProperty("playerUuid", info.uuid.toString());
            data.addProperty("playerName", info.name);
            submitAsync("player_leave", data);

            System.out.println("[HytaleTrack] Player left: " + info.name + " (" + count + " online)");
        }
    }

    private void submitStatus() {
        if (apiKey == null) return;

        // Take a snapshot of the player count for consistency
        int currentCount = playerCount.get();

        JsonObject data = new JsonObject();
        data.addProperty("playerCount", currentCount);
        data.addProperty("isOnline", true);

        // Also include player list snapshot
        List<Map<String, String>> playerList = onlinePlayers.values().stream()
                .map(info -> Map.of("uuid", info.uuid.toString(), "name", info.name))
                .toList();
        data.add("players", GSON.toJsonTree(playerList));

        submitAsync("status", data);
    }

    private void submitAsync(String type, JsonObject data) {
        if (httpClient == null || apiKey == null) return;

        // Try to acquire a permit, skip if too many concurrent requests
        if (!requestSemaphore.tryAcquire()) {
            System.err.println("[HytaleTrack] Too many concurrent requests, skipping " + type);
            return;
        }

        // Run in a bounded virtual thread
        Thread.startVirtualThread(() -> {
            try {
                submitWithRetry(type, data);
            } finally {
                requestSemaphore.release();
            }
        });
    }

    /**
     * Submits data with exponential backoff retry for transient errors.
     */
    private void submitWithRetry(String type, JsonObject data) {
        int retries = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (retries <= MAX_RETRIES) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("type", type);
                body.add("data", data);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("Content-Type", "application/json")
                        .header("X-Api-Key", apiKey)
                        .timeout(REQUEST_TIMEOUT)
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    return; // Success
                }

                // Handle different error codes
                if (statusCode == 401 || statusCode == 403) {
                    // Authentication errors - don't retry
                    System.err.println("[HytaleTrack] Authentication failed for " + type + ": " + statusCode
                            + ". Check your API key.");
                    return;
                }

                if (statusCode == 429) {
                    // Rate limited - back off longer
                    String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                    long waitMs = parseRetryAfter(retryAfter, backoffMs * 2);
                    System.err.println("[HytaleTrack] Rate limited, waiting " + waitMs + "ms before retry");
                    sleepInterruptibly(waitMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    retries++;
                    continue;
                }

                if (statusCode >= 500 && statusCode < 600) {
                    // Server errors - retry with backoff
                    if (retries < MAX_RETRIES) {
                        System.err.println("[HytaleTrack] Server error " + statusCode + " for " + type
                                + ", retrying in " + backoffMs + "ms (attempt " + (retries + 1) + "/" + MAX_RETRIES + ")");
                        sleepInterruptibly(backoffMs);
                        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                        retries++;
                        continue;
                    }
                }

                // Other client errors (4xx) - don't retry
                String errorMsg = parseErrorMessage(response.body());
                System.err.println("[HytaleTrack] Failed to submit " + type + ": " + statusCode + " - " + errorMsg);
                return;

            } catch (java.net.ConnectException e) {
                if (retries < MAX_RETRIES) {
                    System.err.println("[HytaleTrack] Connection error, retrying in " + backoffMs + "ms");
                    sleepInterruptibly(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    retries++;
                } else {
                    System.err.println("[HytaleTrack] Connection error after " + MAX_RETRIES + " retries");
                    return;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                if (retries < MAX_RETRIES) {
                    System.err.println("[HytaleTrack] Request timeout, retrying in " + backoffMs + "ms");
                    sleepInterruptibly(backoffMs);
                    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                    retries++;
                } else {
                    System.err.println("[HytaleTrack] Request timeout after " + MAX_RETRIES + " retries");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[HytaleTrack] Request interrupted for " + type);
                return;
            } catch (Exception e) {
                System.err.println("[HytaleTrack] Error submitting " + type + ": " + e.getClass().getSimpleName());
                return;
            }
        }
    }

    /**
     * Parses the Retry-After header value.
     */
    private long parseRetryAfter(String retryAfter, long defaultMs) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return defaultMs;
        }
        try {
            // Try parsing as seconds
            int seconds = Integer.parseInt(retryAfter.trim());
            return Math.min(seconds * 1000L, MAX_BACKOFF_MS);
        } catch (NumberFormatException e) {
            return defaultMs;
        }
    }

    /**
     * Sleeps for the specified duration, restoring interrupt status if interrupted.
     */
    private void sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String parseErrorMessage(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("error")) {
                return json.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        // Don't return raw response body as it might contain sensitive data
        return "[see server logs]";
    }
}
