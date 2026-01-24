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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // Circuit breaker configuration
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final long CIRCUIT_OPEN_DURATION_MS = 60000;

    // Allowed API hosts whitelist
    private static final Set<String> ALLOWED_HOSTS = Set.of(
        "hytaletrack.com",
        "api.hytaletrack.com",
        "www.hytaletrack.com"
    );

    private String apiKey;
    private String apiUrl;
    private int statusIntervalSeconds;
    private volatile HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private Semaphore requestSemaphore;
    private final ConcurrentHashMap<UUID, PlayerInfo> onlinePlayers = new ConcurrentHashMap<>();
    // Secondary index for O(1) lookup by player name during disconnect
    // Note: If multiple players have the same name, the last one to join wins (rare edge case)
    private final ConcurrentHashMap<String, UUID> playerNameToUuid = new ConcurrentHashMap<>();

    // Circuit breaker state
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong circuitOpenedAt = new AtomicLong(0);
    // Prevents multiple threads from entering half-open state simultaneously (thundering herd)
    private final AtomicBoolean halfOpenInProgress = new AtomicBoolean(false);

    // Issue #50: Circuit breaker for virtual thread creation failures
    private static final int THREAD_CREATION_FAILURE_THRESHOLD = 3;
    private static final long THREAD_CIRCUIT_OPEN_DURATION_MS = 30000; // 30 seconds
    private final AtomicInteger threadCreationFailures = new AtomicInteger(0);
    private final AtomicLong threadCircuitOpenedAt = new AtomicLong(0);

    // Shutdown flag to prevent new requests during shutdown
    private volatile boolean shuttingDown = false;

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

        // Validate API URL (HTTPS + whitelisted host)
        if (!isValidApiUrl(apiUrl)) {
            System.err.println("[HytaleTrack] ERROR: API URL must be a valid HTTPS URL with an allowed host: " + sanitizeUrl(apiUrl));
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
    protected void shutdown() {
        // Set shutdown flag to prevent new requests
        shuttingDown = true;

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

        // Wait briefly for pending requests to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        httpClient = null;
        onlinePlayers.clear();
        playerNameToUuid.clear();
        System.out.println("[HytaleTrack] Plugin disabled");
    }

    /**
     * Validates that the URL is a proper HTTPS URL with an allowed host.
     * Only allows requests to hytaletrack.com and its subdomains.
     */
    private boolean isValidApiUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            // Check against whitelist or allow subdomains of hytaletrack.com
            return ALLOWED_HOSTS.contains(host.toLowerCase()) ||
                   host.toLowerCase().endsWith(".hytaletrack.com");
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

        // Issue #13 fix: Track the player atomically using synchronized block
        // to combine operations on both maps atomically, preventing race condition
        // where another thread could see inconsistent state between the two maps
        PlayerInfo newInfo = new PlayerInfo(playerUuid, playerName);
        boolean isNewPlayer;
        synchronized (onlinePlayers) {
            PlayerInfo existing = onlinePlayers.putIfAbsent(playerUuid, newInfo);
            isNewPlayer = (existing == null);
            if (isNewPlayer) {
                // Update secondary index for O(1) name lookup during disconnect
                playerNameToUuid.put(playerName, playerUuid);
            }
        }
        if (isNewPlayer) {

            // Submit player join event asynchronously
            JsonObject data = new JsonObject();
            data.addProperty("playerUuid", playerUuid.toString());
            data.addProperty("playerName", playerName);
            submitAsync("player_join", data);

            System.out.println("[HytaleTrack] Player joined: " + playerName + " (" + onlinePlayers.size() + " online)");
        }
    }

    /**
     * Event handler for player disconnect events.
     * Note: PlayerDisconnectEvent provides PlayerRef, not Player entity.
     */
    private void onPlayerDisconnect(PlayerDisconnectEvent event) {
        String playerName = event.getPlayerRef().getUsername();
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        // Look up player by name since disconnect event doesn't provide UUID directly
        onPlayerLeave(playerName);
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
     * Uses O(1) lookup via secondary index instead of O(n) stream search.
     */
    public void onPlayerLeave(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return;
        }
        // O(1) lookup using secondary index
        UUID uuid = playerNameToUuid.get(playerName);
        if (uuid != null) {
            handlePlayerLeave(uuid);
        }
    }

    /**
     * Internal method to handle player leave logic.
     * Issue #13 fix: Use synchronized block to atomically update both maps.
     */
    private void handlePlayerLeave(UUID playerUuid) {
        PlayerInfo info;
        synchronized (onlinePlayers) {
            info = onlinePlayers.remove(playerUuid);
            if (info != null) {
                // Clean up secondary index - only remove if it still points to this UUID
                // (handles rare case where another player with same name joined after)
                playerNameToUuid.remove(info.name, playerUuid);
            }
        }
        if (info != null) {
            JsonObject data = new JsonObject();
            data.addProperty("playerUuid", info.uuid.toString());
            data.addProperty("playerName", info.name);
            submitAsync("player_leave", data);

            System.out.println("[HytaleTrack] Player left: " + info.name + " (" + onlinePlayers.size() + " online)");
        }
    }

    private void submitStatus() {
        if (apiKey == null) return;

        // Derive count from list size to avoid race conditions
        int playerCount = onlinePlayers.size();

        JsonObject data = new JsonObject();
        data.addProperty("playerCount", playerCount);
        data.addProperty("isOnline", true);

        // Also include player list snapshot
        List<Map<String, String>> playerList = onlinePlayers.values().stream()
                .map(info -> Map.of("uuid", info.uuid.toString(), "name", info.name))
                .toList();
        data.add("players", GSON.toJsonTree(playerList));

        submitAsync("status", data);
    }

    private void submitAsync(String type, JsonObject data) {
        if (shuttingDown || httpClient == null || apiKey == null) return;

        // Check circuit breaker before attempting request
        if (isCircuitOpen()) {
            System.err.println("[HytaleTrack] Circuit breaker open, skipping " + type);
            return;
        }

        // Issue #50 fix: Check thread creation circuit breaker
        if (isThreadCircuitOpen()) {
            System.err.println("[HytaleTrack] Thread creation circuit breaker open, skipping " + type);
            return;
        }

        // Try to acquire a permit, skip if too many concurrent requests
        if (!requestSemaphore.tryAcquire()) {
            System.err.println("[HytaleTrack] Too many concurrent requests, skipping " + type);
            return;
        }

        try {
            // Run in a bounded virtual thread
            Thread.startVirtualThread(() -> {
                try {
                    submitWithRetry(type, data);
                } finally {
                    requestSemaphore.release();
                }
            });
            // Issue #50 fix: Reset thread creation failure counter on success
            threadCreationFailures.set(0);
            threadCircuitOpenedAt.set(0);
        } catch (Exception e) {
            // If thread creation fails, release the semaphore
            requestSemaphore.release();
            // Issue #50 fix: Track thread creation failures and open circuit if threshold exceeded
            int failures = threadCreationFailures.incrementAndGet();
            if (failures >= THREAD_CREATION_FAILURE_THRESHOLD) {
                long now = System.currentTimeMillis();
                if (threadCircuitOpenedAt.compareAndSet(0, now)) {
                    System.err.println("[HytaleTrack] Thread creation circuit breaker opened after " + failures
                            + " failures. Will retry in " + (THREAD_CIRCUIT_OPEN_DURATION_MS / 1000) + " seconds.");
                }
            }
            System.err.println("[HytaleTrack] Failed to start virtual thread for " + type + ": " + e.getClass().getSimpleName());
        }
    }

    /**
     * Issue #50 fix: Checks if the thread creation circuit breaker is open.
     */
    private boolean isThreadCircuitOpen() {
        long openedAt = threadCircuitOpenedAt.get();
        if (openedAt == 0) {
            return false; // Circuit has never been opened
        }
        long elapsed = System.currentTimeMillis() - openedAt;
        if (elapsed >= THREAD_CIRCUIT_OPEN_DURATION_MS) {
            // Circuit has been open long enough, allow retry
            // Reset the circuit breaker
            threadCircuitOpenedAt.set(0);
            threadCreationFailures.set(0);
            return false;
        }
        return true; // Circuit is still open
    }

    // Issue #48 fix: Object used for synchronizing circuit breaker state transitions
    private final Object circuitBreakerLock = new Object();

    /**
     * Checks if the circuit breaker is currently open.
     * The circuit is open when consecutive failures exceed the threshold
     * and the open duration has not yet elapsed.
     *
     * Issue #48 fix: Uses synchronized block for atomic state transitions to prevent
     * race conditions when multiple threads try to enter half-open state simultaneously.
     */
    private boolean isCircuitOpen() {
        synchronized (circuitBreakerLock) {
            long openedAt = circuitOpenedAt.get();
            if (openedAt == 0) {
                return false; // Circuit has never been opened
            }
            long elapsed = System.currentTimeMillis() - openedAt;
            if (elapsed >= CIRCUIT_OPEN_DURATION_MS) {
                // Circuit open duration has elapsed, attempt to enter half-open state
                // Only one thread should be allowed to make a test request (prevent thundering herd)
                if (!halfOpenInProgress.get()) {
                    halfOpenInProgress.set(true);
                    // This thread won the race and will make the test request
                    return false;
                }
                // Another thread is already testing, keep circuit open for this thread
                return true;
            }
            return true; // Circuit is still open
        }
    }

    /**
     * Records a successful request, resetting the circuit breaker.
     * Also resets the half-open flag to allow normal operation.
     * Issue #48 fix: Uses synchronized block for atomic state transitions.
     */
    private void recordSuccess() {
        synchronized (circuitBreakerLock) {
            consecutiveFailures.set(0);
            circuitOpenedAt.set(0);
            halfOpenInProgress.set(false);
        }
    }

    /**
     * Records a failed request, potentially opening the circuit breaker.
     * Issue #48 fix: Uses synchronized block for atomic state transitions
     * to prevent race conditions when multiple threads fail simultaneously.
     */
    private void recordFailure() {
        synchronized (circuitBreakerLock) {
            // Reset half-open flag since the test request failed
            halfOpenInProgress.set(false);

            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                long previousOpenTime = circuitOpenedAt.get();
                long now = System.currentTimeMillis();
                // Only update and log when circuit first opens or after duration has elapsed
                if (previousOpenTime == 0) {
                    circuitOpenedAt.set(now);
                    System.err.println("[HytaleTrack] Circuit breaker opened after " + failures
                            + " consecutive failures. Will retry in " + (CIRCUIT_OPEN_DURATION_MS / 1000) + " seconds.");
                } else if ((now - previousOpenTime) >= CIRCUIT_OPEN_DURATION_MS) {
                    // Half-open test failed, re-open the circuit
                    circuitOpenedAt.set(now);
                    System.err.println("[HytaleTrack] Circuit breaker re-opened after " + failures
                            + " consecutive failures. Will retry in " + (CIRCUIT_OPEN_DURATION_MS / 1000) + " seconds.");
                }
            }
        }
    }

    /**
     * Submits data with exponential backoff retry for transient errors.
     * Issue #49 fix: Checks shuttingDown flag at start of each retry iteration.
     */
    private void submitWithRetry(String type, JsonObject data) {
        HttpClient client = this.httpClient;
        if (client == null) return; // Plugin shutting down

        int retries = 0;
        long backoffMs = INITIAL_BACKOFF_MS;

        while (retries <= MAX_RETRIES) {
            // Issue #49 fix: Check shutdown flag at start of each retry iteration
            // to avoid unnecessary retries during plugin shutdown
            if (shuttingDown) {
                System.out.println("[HytaleTrack] Shutdown in progress, aborting " + type + " request");
                return;
            }

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

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    recordSuccess();
                    return; // Success
                }

                // Handle different error codes
                if (statusCode == 401 || statusCode == 403) {
                    // Authentication errors - don't retry, don't affect circuit breaker
                    // Note: Don't log response body as it may echo sensitive data
                    System.err.println("[HytaleTrack] Authentication failed for " + type
                            + " (HTTP " + statusCode + "). Please verify your API key is correct.");
                    return;
                }

                if (statusCode == 429) {
                    // Rate limited - back off longer
                    if (retries < MAX_RETRIES) {
                        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                        long waitMs = parseRetryAfter(retryAfter, backoffMs * 2);
                        System.err.println("[HytaleTrack] Rate limited, waiting " + waitMs + "ms before retry");
                        sleepInterruptibly(waitMs);
                        backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                        retries++;
                        continue;
                    }
                    // Exhausted retries for rate limiting
                    recordFailure();
                    System.err.println("[HytaleTrack] Rate limited for " + type + " after " + MAX_RETRIES + " retries");
                    return;
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
                    // Exhausted retries for server error
                    recordFailure();
                    System.err.println("[HytaleTrack] Server error " + statusCode + " for " + type + " after " + MAX_RETRIES + " retries");
                    return;
                }

                // Other client errors (4xx) - don't retry, don't affect circuit breaker
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
                    recordFailure();
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
                    recordFailure();
                    System.err.println("[HytaleTrack] Request timeout after " + MAX_RETRIES + " retries");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[HytaleTrack] Request interrupted for " + type);
                return;
            } catch (Exception e) {
                recordFailure();
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

    /**
     * Parses and sanitizes error messages from API responses.
     * This method ensures sensitive data (like API keys) is never logged,
     * even if the server echoes it back in error messages.
     */
    private String parseErrorMessage(String responseBody) {
        try {
            JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
            if (json != null && json.has("error")) {
                String errorMsg = json.get("error").getAsString();
                // Sanitize the error message to prevent API key exposure
                // API keys could be echoed back by server in error messages
                return sanitizeErrorMessage(errorMsg);
            }
        } catch (Exception ignored) {
        }
        // Don't return raw response body as it might contain sensitive data
        return "[see server logs]";
    }

    /**
     * Sanitizes error messages to remove any potential sensitive data.
     * This prevents API keys or other credentials from being logged if
     * the server happens to echo them back in error responses.
     */
    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "[empty error]";
        }
        // Remove any potential API key patterns (htk_...)
        String sanitized = message.replaceAll("htk_[A-Za-z0-9_-]+", "[REDACTED_KEY]");
        // Remove any URLs that might contain sensitive query parameters
        sanitized = sanitized.replaceAll("https?://[^\\s]+", "[REDACTED_URL]");
        // Truncate long messages that might contain full request/response bodies
        if (sanitized.length() > 200) {
            sanitized = sanitized.substring(0, 200) + "...[truncated]";
        }
        return sanitized;
    }
}
