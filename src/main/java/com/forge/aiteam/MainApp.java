package com.forge.aiteam;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.io.*;
import java.nio.file.*;
import java.util.Optional;
import java.util.Properties;

public class MainApp extends Application {

    // Config stored at: %USERPROFILE%\.brainai\config.properties
    public static final String CONFIG_DIR =
            System.getProperty("user.home") + File.separator + ".brainai";
    private static final String CONFIG_FILE =
            CONFIG_DIR + File.separator + "config.properties";

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void start(Stage stage) throws Exception {
        // Ensure API keys are loaded before showing main window
        ensureApiKey(stage);

        FXMLLoader loader = new FXMLLoader(
                getClass().getResource("/com/forge/aiteam/main_scene.fxml"));
        Scene scene = new Scene(loader.load(), 900, 680);

        // Add App Icon
        try {
            stage.getIcons().add(new javafx.scene.image.Image(
                getClass().getResourceAsStream("/com/forge/aiteam/icon.png")));
        } catch (Exception e) {
            System.err.println("Could not load app icon: " + e.getMessage());
        }

        stage.setTitle("Brain AI Assistant");
        stage.setScene(scene);
        stage.show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API-key resolution order:
    //   1. Already set as system property (e.g. re-launch in same JVM)
    //   2. Config file  (~/.brainai/config.properties)
    //   3. Environment variable  GOOGLE_API_KEY
    //   4. Prompt the user via dialog
    // ─────────────────────────────────────────────────────────────────────────

    private void ensureApiKey(Stage ownerStage) {
        // 1. Config file (highest priority — loads pool of keys)
        String saved = readKeyFromConfig();
        if (saved != null && !saved.isEmpty()) {
            APIKeyManager.getInstance().setKeys(saved);
            // Legacy single-key property for backward compat
            System.setProperty("BRAIN_API_KEY", saved.split(",")[0].trim());
            return;
        }

        // 2. Environment variable (single key fallback)
        String env = System.getenv("GOOGLE_API_KEY");
        if (env != null && !env.isEmpty()) {
            APIKeyManager.getInstance().setKeys(env);
            System.setProperty("BRAIN_API_KEY", env);
            return;
        }

        // 3. System property already set (e.g., re-launch in same JVM)
        String prop = System.getProperty("BRAIN_API_KEY", "").trim();
        if (!prop.isEmpty()) {
            APIKeyManager.getInstance().setKeys(prop);
            return;
        }

        // 4. Show first-time setup dialog
        showApiKeyDialog(ownerStage);
    }

    /** Read GOOGLE_API_KEY from the config file, or null if not found. */
    /** Read GOOGLE_API_KEY (may be a comma-separated list) from config, or null if not found. */
    private String readKeyFromConfig() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) return null;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
            String model = p.getProperty("GEMINI_MODEL", "gemini-2.5-flash").trim();
            System.setProperty("BRAIN_MODEL", model);
            return p.getProperty("GOOGLE_API_KEY", "").trim(); // may be CSV
        } catch (IOException e) {
            return null;
        }
    }

    /** Show a polished first-time setup dialog accepting multiple comma-separated keys. */
    private void showApiKeyDialog(Stage ownerStage) {
        Dialog<String> dlg = new Dialog<>();
        dlg.setTitle("Brain AI — First-Time Setup");
        dlg.setHeaderText(
            "Welcome to Brain AI Assistant!\n"
            + "Enter one or more Google Gemini API keys (comma-separated for parallel mode).\n"
            + "Keys are saved locally and never sent anywhere except Google's API."
        );

        ButtonType saveBtn = new ButtonType("Save & Continue", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        VBox root = new VBox(12);
        root.setPadding(new Insets(20));
        root.setMinWidth(480);

        Label label = new Label("Gemini API Key(s) — comma-separated:");
        label.setStyle("-fx-font-weight: bold;");

        TextArea keyField = new TextArea();
        keyField.setPromptText("AIza...key1, AIza...key2");
        keyField.setPrefWidth(460);
        keyField.setPrefRowCount(3);
        keyField.setWrapText(true);

        Hyperlink link = new Hyperlink("Get a free API key at aistudio.google.com");
        link.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop()
                    .browse(new java.net.URI("https://aistudio.google.com/app/apikey"));
            } catch (Exception ignored) {}
        });

        Label note = new Label(
            "Keys saved in: " + CONFIG_FILE + "\n"
            + "Tip: add 3-5 keys for maximum parallel throughput."
        );
        note.setStyle("-fx-font-size: 10px; -fx-text-fill: #555;");
        note.setWrapText(true);

        root.getChildren().addAll(label, keyField, link, note);
        dlg.getDialogPane().setContent(root);

        Button saveButton = (Button) dlg.getDialogPane().lookupButton(saveBtn);
        saveButton.setDisable(true);
        keyField.textProperty().addListener((obs, o, n) ->
                saveButton.setDisable(n == null || n.trim().isEmpty()));

        dlg.setResultConverter(btn -> {
            if (btn == saveBtn) return keyField.getText().trim();
            return null;
        });

        Optional<String> result = dlg.showAndWait();
        result.ifPresent(keysCsv -> {
            if (!keysCsv.isEmpty()) {
                saveKeyToConfig(keysCsv);
                APIKeyManager.getInstance().setKeys(keysCsv);
                System.setProperty("BRAIN_API_KEY", keysCsv.split(",")[0].trim());
            }
        });
    }

    /** Persist the API key CSV (one or many keys) to the config file. */
    private void saveKeyToConfig(String keysCsv) {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            Properties p = new Properties();
            File f = new File(CONFIG_FILE);
            if (f.exists()) {
                try (FileInputStream in = new FileInputStream(f)) { p.load(in); }
            }
            p.setProperty("GOOGLE_API_KEY", keysCsv);
            try (FileOutputStream out = new FileOutputStream(f)) {
                p.store(out, "Brain AI Configuration — do not share this file");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        launch();
    }
}
