package com.forge.aiteam;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

/**
 * GeminiService — sends a single prompt to Gemini.
 *
 * Key changes vs. old version:
 *  - Now uses APIKeyManager for key rotation / failover.
 *  - Removed the internal "dot" animation — that's now a CSS spinner in the UI.
 *  - Smart retry: 429 → reports to manager + rotates key; 503/500 → exponential backoff;
 *    401/403 → flags key as bad + rotates.
 */
public class GeminiService {

    private static final int MAX_RETRIES = 8;

    public String askAgent(String systemPrompt, String userTask) {
        APIKeyManager mgr = APIKeyManager.getInstance();

        // Read model from system property (set via Settings dialog), default to 1.5 flash
        String model = System.getProperty("BRAIN_MODEL", "gemini-1.5-flash");

        String lastError = "Unknown error";

        // Fail fast if there are no keys at all — throw so pipeline halts immediately.
        if (mgr.getKeyCount() == 0
                && System.getProperty("BRAIN_API_KEY", "").isBlank()) {
            throw new IllegalStateException(
                "No Gemini API key configured. Open ⚙️ Settings and add your key.");
        }

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            String apiKey;
            try {
                apiKey = mgr.getNextKey();
            } catch (IllegalStateException e) {
                // All keys exhausted / invalid — propagate as fatal
                throw new IllegalStateException(e.getMessage());
            }

            try {
                Client client = Client.builder().apiKey(apiKey).build();
                GenerateContentResponse response =
                        client.models.generateContent(
                                model,
                                systemPrompt + "\n\n" + userTask,
                                null);
                return response.text();

            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "Unknown error";

                // ── 429 / Rate-limit ──────────────────────────────────────────
                if (lastError.contains("429") || lastError.toLowerCase().contains("quota")) {
                    System.out.println("⚠️ 429 on key ..."+tail(apiKey)+". Rotating key.");
                    mgr.reportRateLimit(apiKey, lastError);
                    // Don't sleep here — the manager already computed the wait time
                    // and will park the next getNextKey() call if all are cooling.
                    continue;
                }

                // ── 401/403 / Invalid key ─────────────────────────────────────
                if (lastError.contains("401") || lastError.contains("403")
                        || lastError.toLowerCase().contains("invalid") 
                        || lastError.toLowerCase().contains("unauthorized")) {
                    System.err.println("❌ Invalid key ..."+tail(apiKey)+". Flagging permanently.");
                    mgr.reportInvalidKey(apiKey);
                    continue; // Try next key immediately
                }

                // ── 503/500 / Server overload ──────────────────────────────────
                if (lastError.contains("503") || lastError.contains("500")
                        || lastError.toLowerCase().contains("overloaded")) {
                    System.out.println("⚠️ Server error on key ..."+tail(apiKey)+". Backing off.");
                    mgr.reportServerError(apiKey, attempt);
                    try { Thread.sleep((long) Math.pow(2, Math.min(attempt + 1, 6)) * 1000); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }

                // ── SSL / Network issues ───────────────────────────────────────
                if (lastError.contains("handshake_failure") || lastError.contains("SSL")) {
                    System.err.println("❌ SSL Handshake Failure! Runtime: "
                            + System.getProperty("java.runtime.version"));
                }

                // ── Unknown error: rotate to next key and keep trying ──────────
                // Only give up after ALL keys have been exhausted (MAX_RETRIES).
                System.err.println("⚠️ Unknown error on key ..."+tail(apiKey)
                        + ". Rotating to next key. Error: " + lastError);
                continue;
            }
        }

        return "AI communication failed after " + MAX_RETRIES + " attempts: " + lastError;
    }

    private String tail(String key) {
        if (key == null || key.length() < 6) return "******";
        return key.substring(key.length() - 6);
    }

    // ── Smoke-test main ───────────────────────────────────────────────────────
    public static void main(String[] args) {
        String key = System.getenv("GOOGLE_API_KEY");
        if (key != null) APIKeyManager.getInstance().setKeys(key);
        GeminiService svc = new GeminiService();
        System.out.println(svc.askAgent(
            "You are a pirate. Be brief.",
            "Explain Java in two sentences."));
    }
}
