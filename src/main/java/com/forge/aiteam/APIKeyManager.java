package com.forge.aiteam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * APIKeyManager — Manages a pool of Gemini API keys.
 *
 * Features:
 *  - Round-robin key rotation for load balancing.
 *  - Automatic 60-second cooldown for 429 (Rate Limit) keys.
 *  - Permanent flagging for 401/403 (Invalid/Unauthorized) keys.
 *  - Thread-safe for parallel agent use.
 *
 * Keys are loaded from the config via MainApp / DatabaseConnection.
 * Call setKeys() at startup with all known keys.
 */
public class APIKeyManager {

    // ─── Singleton ────────────────────────────────────────────────────────────
    private static final APIKeyManager INSTANCE = new APIKeyManager();
    public static APIKeyManager getInstance() { return INSTANCE; }

    // ─── State ────────────────────────────────────────────────────────────────
    private final List<String>               keys       = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger              roundRobin = new AtomicInteger(0);

    /** Keys that are on a cooldown: key → time when it becomes available again (ms epoch) */
    private final ConcurrentHashMap<String, Long>    cooldowns  = new ConcurrentHashMap<>();

    /** Keys that are permanently invalid (401/403). */
    private final ConcurrentHashMap<String, Boolean> badKeys    = new ConcurrentHashMap<>();

    private static final long RATE_LIMIT_COOLDOWN_MS = 65_000L; // 65 seconds for 429

    private APIKeyManager() {}

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Load the key pool. Called once at startup.
     * Accepts a comma-separated string of keys (from config.properties).
     */
    public synchronized void setKeys(String keysCsv) {
        keys.clear();
        cooldowns.clear();
        badKeys.clear();
        roundRobin.set(0);
        if (keysCsv == null || keysCsv.isBlank()) return;
        for (String k : keysCsv.split(",")) {
            String trimmed = k.trim();
            if (!trimmed.isEmpty()) keys.add(trimmed);
        }
        System.out.println("🔑 APIKeyManager initialized with " + keys.size() + " key(s).");
    }

    /**
     * Returns the keys list as a comma-separated string (for saving to config).
     */
    public synchronized String getKeysCsv() {
        return String.join(",", keys);
    }

    /**
     * Get the total number of registered keys (including bad/cooling ones).
     */
    public int getKeyCount() { return keys.size(); }

    /**
     * Returns the next available (non-cooling, non-bad) API key via round-robin.
     * Tries every key before giving up.
     * @throws IllegalStateException if no valid key is available.
     */
    public String getNextKey() {
        synchronized (keys) {
            if (keys.isEmpty()) {
                // Fallback to system property (single-key backward compatibility)
                String legacy = System.getProperty("BRAIN_API_KEY", "").trim();
                if (!legacy.isEmpty()) return legacy;
                throw new IllegalStateException(
                    "No Gemini API keys configured. Please add keys in Settings.");
            }

            int total = keys.size();
            long now  = System.currentTimeMillis();

            for (int attempts = 0; attempts < total; attempts++) {
                int idx = roundRobin.getAndIncrement() % total;
                // Guard against negative modulo if int overflows
                if (idx < 0) idx = Math.abs(idx);
                String key = keys.get(idx);

                // Skip permanently bad keys
                if (badKeys.containsKey(key)) continue;

                // Skip keys that are still cooling down
                Long coolUntil = cooldowns.get(key);
                if (coolUntil != null && now < coolUntil) continue;

                // This key is good — return it
                return key;
            }

            // All keys are cooling/bad — find the one whose cooldown expires soonest
            long earliest = Long.MAX_VALUE;
            String bestKey = null;
            for (String k : keys) {
                if (badKeys.containsKey(k)) continue;
                Long cu = cooldowns.getOrDefault(k, 0L);
                if (cu < earliest) {
                    earliest = cu;
                    bestKey  = k;
                }
            }
            if (bestKey != null) {
                long wait = earliest - now;
                System.out.println("⏳ All keys cooling. Sleeping " + (wait / 1000) + "s for the soonest key...");
                try { Thread.sleep(Math.max(wait, 0)); } catch (InterruptedException ignored) {}
                return bestKey;
            }
            throw new IllegalStateException("All API keys are invalid. Please check your key list in Settings.");
        }
    }

    /**
     * Report a 429 (Rate Limit) error for a given key.
     * Parses the retry-in seconds from the error message if present,
     * otherwise applies a default 65-second cooldown.
     */
    public void reportRateLimit(String key, String errorMessage) {
        long coolMs = RATE_LIMIT_COOLDOWN_MS;
        if (errorMessage != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("retry in ([\\d\\.]+)s").matcher(errorMessage);
            if (m.find()) {
                try {
                    coolMs = (long)(Double.parseDouble(m.group(1)) * 1000) + 2_000;
                } catch (NumberFormatException ignored) {}
            }
        }
        cooldowns.put(key, System.currentTimeMillis() + coolMs);
        System.out.println("🔑 Key ..."+tail(key)+" cooling for "+(coolMs/1000)+"s.");
    }

    /**
     * Report a 503/500 (Server Overload) error — apply exponential backoff.
     */
    public void reportServerError(String key, int attempt) {
        long coolMs = (long) Math.pow(2, Math.min(attempt, 6)) * 1000L;
        cooldowns.put(key, System.currentTimeMillis() + coolMs);
        System.out.println("🔑 Key ..."+tail(key)+" server error, cooling "+coolMs+"ms.");
    }

    /**
     * Report a 401/403 (Invalid/Unauthorized) error — permanently disable the key.
     */
    public void reportInvalidKey(String key) {
        badKeys.put(key, Boolean.TRUE);
        System.err.println("❌ Key ..."+tail(key)+" permanently disabled (401/403).");
    }

    /**
     * Returns a status summary of all keys (for the Settings dialog).
     */
    public String getStatusSummary() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        synchronized (keys) {
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i);
                sb.append("Key ").append(i + 1).append(": ...").append(tail(k));
                if (badKeys.containsKey(k)) {
                    sb.append(" [❌ INVALID]");
                } else {
                    Long cu = cooldowns.get(k);
                    if (cu != null && now < cu) {
                        sb.append(" [⏳ cooldown: ").append((cu - now) / 1000).append("s]");
                    } else {
                        sb.append(" [✅ Ready]");
                    }
                }
                if (i < keys.size() - 1) sb.append("\n");
            }
        }
        if (sb.length() == 0) return "No keys configured.";
        return sb.toString();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private String tail(String key) {
        return key.length() > 6 ? key.substring(key.length() - 6) : "******";
    }
}
