import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

public class QuotaTester {
    public static void main(String[] args) {
        // 🛠️ FIX 1: Force Java to use your Windows System Proxy settings
        System.setProperty("java.net.useSystemProxies", "true");
        
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("❌ ERROR: GOOGLE_API_KEY environment variable not found.");
            return;
        }

        System.out.println("🚀 Starting Advanced Network Diagnosis...");
        
        // 🧪 TEST 1: Low-level DNS Resolution
        try {
            System.out.print("🔍 Attempting to resolve google.com... ");
            java.net.InetAddress.getByName("google.com");
            System.out.println("✅ OK");
            
            System.out.print("🔍 Attempting to resolve generativelanguage.googleapis.com... ");
            java.net.InetAddress.getByName("generativelanguage.googleapis.com");
            System.out.println("✅ OK");
        } catch (Exception e) {
            System.err.println("❌ FAILED!");
            System.err.println("   Reason: " + e.getMessage());
            System.err.println("   💡 HINT: Your DNS or Firewall is blocking this domain for Java.");
        }

        Client client = Client.builder().apiKey(apiKey).build();

        // 🏆 Perform Quota Tests
        testModel(client, "gemini-2.0-flash");
        testModel(client, "gemini-1.5-flash");
    }

    private static void testModel(Client client, String modelName) {
        System.out.println("\n-------------------------------------------");
        System.out.println("🔍 Testing Model: " + modelName + "...");
        
        try {
            GenerateContentResponse response =
                    client.models.generateContent(
                            modelName,
                            "Say 'Hello! I am working.'",
                            null);

            System.out.println("✅ SUCCESS: " + modelName + " responded correctly!");
            System.out.println("   Response: " + response.text().trim());
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : "Unknown Error";
            System.err.println("❌ FAILED: " + modelName + " hit an error.");
            if (error.contains("429") || error.toLowerCase().contains("quota")) {
                System.err.println("   Reason: ❗ QUOTA EXCEEDED (Daily Limit or Rate Limit).");
            } else if (error.contains("404")) {
                System.err.println("   Reason: ❗ MODEL NOT FOUND (Check model string).");
            } else {
                System.err.println("   Reason: " + error);
            }
        }
    }
}
