package com.example;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;

public class GenerateTextFromTextInput {
  public static void main(String[] args) {
    // The client gets the API key from the environment variable `GEMINI_API_KEY` or `GOOGLE_API_KEY`.
    Client client = new Client();

    try {
        System.out.println("🚀 Testing Gemini 3 Flash Preview...");
        GenerateContentResponse response =
            client.models.generateContent(
                "gemini-3-flash-preview",
                "Explain how AI works in a few words",
                null);

        System.out.println("✅ Response: " + response.text());
    } catch (Exception e) {
        System.err.println("❌ FAILED: " + e.getMessage());
    }
  }
}
