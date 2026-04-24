package com.forge.aiteam;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.animation.RotateTransition;
import javafx.util.Duration;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MainController {

    // ── FXML bindings ────────────────────────────────────────────────────────
    @FXML private TextArea   outputArea;
    @FXML private TextField  userInputField;
    @FXML private Button     helloButton;
    @FXML private Button     openBrowserButton;
    @FXML private Button     settingsButton;
    @FXML private Arc        spinnerArc;          // CSS spinner node
    @FXML private Label      consultLabel;        // "Agent Paused" overlay text

    private RotateTransition spinnerAnim;

    private static final String TYPE_JAVA = "JAVA";
    private static final String TYPE_WEB  = "WEB";

    /** All AI-generated projects land here. */
    private static final String PROJECTS_ROOT =
            System.getProperty("user.home") + File.separator + "BrainAI Projects";

    // Tracks the last successfully built web project path so "Open in Browser" works
    private volatile String lastWebProjectPath = null;

    // ── Loop‑detection state ─────────────────────────────────────────────────
    /** Stores hash(agentRole + outputHash) → consecutive-identical count. */
    private final Map<String, Integer> loopCounter = new HashMap<>();
    private static final int LOOP_THRESHOLD = 3;

    // ── Pause / consult state ────────────────────────────────────────────────
    /**
     * When the loop detector fires, the pipeline thread blocks on
     * waitForConsult.take() until the user provides guidance.
     */
    private final SynchronousQueue<String> waitForConsult = new SynchronousQueue<>();
    private volatile boolean inConsultMode = false;

    // ── Smart-scroll state ───────────────────────────────────────────────────
    /** True ↔ user has scrolled up and wants to read; auto-scroll is suspended. */
    private volatile boolean userScrolledUp = false;

    // ─────────────────────────────────────────────────────────────────────────
    // JavaFX FXML initialize
    // ─────────────────────────────────────────────────────────────────────────
    @FXML
    public void initialize() {
        setupSpinner();
        setupSmartScroll();
        if (openBrowserButton != null) openBrowserButton.setDisable(true);
        if (consultLabel != null)     consultLabel.setVisible(false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spinner: rotate an Arc node in FXML continuously while AI thinks
    // ─────────────────────────────────────────────────────────────────────────
    private void setupSpinner() {
        if (spinnerArc == null) return;
        spinnerArc.setVisible(false);
        spinnerAnim = new RotateTransition(Duration.millis(900), spinnerArc);
        spinnerAnim.setByAngle(360);
        spinnerAnim.setCycleCount(RotateTransition.INDEFINITE);
        spinnerAnim.setInterpolator(javafx.animation.Interpolator.LINEAR);
    }

    private void showSpinner(boolean show) {
        Platform.runLater(() -> {
            if (spinnerArc == null) return;
            spinnerArc.setVisible(show);
            if (show) spinnerAnim.play();
            else      spinnerAnim.stop();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Smart Scroll: only auto-scroll when user is already at the bottom
    // ─────────────────────────────────────────────────────────────────────────
    private void setupSmartScroll() {
        if (outputArea == null) return;
        // Detect when outputArea gets its ScrollBar (after layout)
        outputArea.skinProperty().addListener((obs, o, n) -> {
            if (n == null) return;
            ScrollBar vBar = (ScrollBar) outputArea.lookup(".scroll-bar:vertical");
            if (vBar == null) return;
            vBar.valueProperty().addListener((obsV, oldV, newV) -> {
                // If user dragged the bar away from 1.0, they've scrolled up
                double max = vBar.getMax();
                userScrolledUp = (newV.doubleValue() < max - 0.01);
            });
        });
    }

    /** Appends text and auto-scrolls only if user hasn't scrolled up. */
    private void updateUI(String message) {
        Platform.runLater(() -> {
            outputArea.appendText(message + "\n");
            if (!userScrolledUp) {
                outputArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Loop detection
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Call after each agent response.
     * Returns true if the pipeline should pause for user consultation.
     */
    private boolean checkLoop(String agentRole, String responseText) {
        String key = agentRole + "::" + Integer.toHexString(responseText.hashCode());
        int count  = loopCounter.merge(key, 1, Integer::sum);
        if (count >= LOOP_THRESHOLD) {
            loopCounter.clear(); // Reset so resume is clean
            return true;
        }
        return false;
    }

    /**
     * Enter "Paused/Consult" mode:
     * 1. Update UI with a clear summary.
     * 2. Repurpose the main input field for guidance.
     * 3. Block the pipeline thread until the user sends a message.
     *
     * @return the user's guidance text (never null/empty)
     */
    private String enterConsultMode(String agentRole, String lastError) throws InterruptedException {
        inConsultMode = true;
        Platform.runLater(() -> {
            if (consultLabel != null) {
                consultLabel.setText("⚠️ Agent Paused – Type guidance below and press Send to resume");
                consultLabel.setVisible(true);
            }
            if (helloButton != null) helloButton.setDisable(false); // repurpose Send button
        });

        updateUI("\n\n🛑 ══════════════════════════════════════════════════════");
        updateUI("   LOOP DETECTED: Agent [" + agentRole + "] produced the same result "
                 + LOOP_THRESHOLD + " times in a row.");
        updateUI("   Last error / pattern:\n   " + lastError);
        updateUI("   The pipeline is PAUSED. Your execution context is preserved.");
        updateUI("   👉 Type your guidance or a manual fix in the input field and press Send.");
        updateUI("══════════════════════════════════════════════════════════\n");

        showSpinner(false);
        // Block this pipeline thread until the user sends input
        String guidance = waitForConsult.take();
        inConsultMode = false;
        showSpinner(true);
        Platform.runLater(() -> {
            if (consultLabel != null) consultLabel.setVisible(false);
        });
        updateUI("▶️  Resuming pipeline with guidance: " + guidance + "\n");
        return guidance;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resilient AI caller — retries forever on 429/503/500; pauses on loops.
    // ─────────────────────────────────────────────────────────────────────────
    private String askAgentResilient(GeminiService gemini, String agentRole,
                                     String prompt, String context)
            throws InterruptedException {
        int attempt = 0;

        while (true) {
            attempt++;
            showSpinner(true);

            String result;
            try {
                result = gemini.askAgent(prompt, context);
            } catch (IllegalStateException e) {
                // Fatal: no API key configured or all keys permanently invalid
                showSpinner(false);
                updateUI("\n\uD83D\uDD34 FATAL: " + e.getMessage());
                updateUI("   → Open ⚙️ Settings and paste a valid Gemini API key, then try again.");
                // Propagate as a RuntimeException so the pipeline catches it and stops
                throw new RuntimeException("NO_API_KEY: " + e.getMessage(), e);
            }

            showSpinner(false);

            if (result == null || result.isBlank()) {
                updateUI("⚠️ [" + agentRole + "] Empty response — retrying...");
                continue;
            }

            // ── Fatal API-key error returned as a string (backward compat) ──────
            boolean isNoKey = result.contains("No Gemini API key configured")
                           || result.contains("No Gemini API keys configured")
                           || result.contains("All API keys are invalid");
            if (isNoKey) {
                updateUI("\n\uD83D\uDD34 FATAL: API key missing or all keys are invalid.");
                updateUI("   → Open ⚙️ Settings and paste your Gemini API key, then try again.");
                throw new RuntimeException("NO_API_KEY: " + result);
            }

            if (!result.startsWith("AI communication failed")) {
                // Check for loop BEFORE returning
                if (checkLoop(agentRole, result)) {
                    String guidance = enterConsultMode(agentRole, result);
                    context = context + "\n\nUSER GUIDANCE: " + guidance;
                    loopCounter.clear();
                    continue;
                }
                return result;
            }

            boolean isRateLimit   = result.contains("429") || result.toLowerCase().contains("quota");
            boolean isServerError = result.contains("503") || result.contains("500")
                                 || result.contains("overloaded");

            if (isRateLimit || isServerError) {
                long waitMs = isRateLimit ? 65_000L
                        : (long) Math.pow(2, Math.min(attempt, 6)) * 1000L;
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("retry in ([\\d\\.]+)s").matcher(result);
                if (m.find()) {
                    try { waitMs = (long)(Double.parseDouble(m.group(1)) * 1000) + 2000; }
                    catch (NumberFormatException ignored) {}
                }
                final long fw = waitMs;
                updateUI("⏳ [Rate Limit] Attempt " + attempt + " failed. Waiting "
                        + (fw / 1000) + "s before retrying...");
                Thread.sleep(waitMs);
            } else {
                // Genuine non-retryable error (e.g. bad request, SSL issue)
                updateUI("\u274C [Non-retryable AI Error]: " + result);
                throw new RuntimeException("NON_RETRYABLE: " + result);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parallel coding: dispatch multiple sub-tasks simultaneously
    // ─────────────────────────────────────────────────────────────────────────
    /**
     * Splits the coder context by [SUBTASK] tags from the Architect (if present)
     * and runs them simultaneously using a thread pool matching the key count.
     *
     * Falls back to single-threaded if no sub-task tags are found.
     */
    private String runParallelCoding(GeminiService gemini, String coderPrompt,
                                     String fullContext) throws Exception {
        // Split by [SUBTASK n] markers the Architect may emit
        String[] parts = fullContext.split("(?=\\[SUBTASK\\s*\\d+\\])");
        if (parts.length <= 1) {
            // No sub-tasks — plain sequential call
            return askAgentResilient(gemini, "PARALLEL_CODER", coderPrompt, fullContext);
        }

        int poolSize = Math.min(parts.length, Math.max(APIKeyManager.getInstance().getKeyCount(), 2));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<String>> futures = new ArrayList<>();

        updateUI("🔀 [Parallel Coder] Dispatching " + parts.length + " sub-tasks on "
                + poolSize + " threads...");

        for (String subtaskCtx : parts) {
            if (subtaskCtx.trim().isEmpty()) continue;
            final String ctx = subtaskCtx;
            futures.add(pool.submit(() -> {
                GeminiService localGemini = new GeminiService(); // Each thread gets its own service
                return askAgentResilient(localGemini, "PARALLEL_CODER", coderPrompt, ctx);
            }));
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);

        StringBuilder combined = new StringBuilder();
        for (Future<String> f : futures) {
            try { combined.append(f.get()).append("\n"); }
            catch (ExecutionException e) {
                updateUI("⚠️ [Parallel sub-task failed]: " + e.getCause().getMessage());
            }
        }
        return combined.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Project-type detection
    // ─────────────────────────────────────────────────────────────────────────
    private String detectProjectType(String architectPlan) {
        Matcher m = Pattern.compile("\\[TYPE:\\s*(\\w+)\\]",
                Pattern.CASE_INSENSITIVE).matcher(architectPlan);
        if (m.find() && m.group(1).toUpperCase().equals(TYPE_WEB)) return TYPE_WEB;
        return TYPE_JAVA;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI button handlers
    // ─────────────────────────────────────────────────────────────────────────
    @FXML
    protected void onHelpButtonClick() {
        Alert alert = new Alert(AlertType.INFORMATION);
        alert.setTitle("How to Use Brain AI");
        alert.setHeaderText("Welcome to the AI Assembly Line!");
        alert.setContentText(
                "Supports Java desktop apps AND web projects.\n\n"
                + "Java example: 'Write a Swing prime calculator app'\n"
                + "Web example:  'Build a to-do list as a web page with local storage'\n"
                + "Node example: 'Create a Node.js REST API with Express for a book list'\n\n"
                + "The Architect auto-detects the type. The correct Coder,\n"
                + "Tester, and DevOps agents then run automatically.\n\n"
                + "After a web build, click '🌐' to preview it.\n\n"
                + "💡 TIP: Add multiple API keys in Settings for faster parallel processing!");
        alert.showAndWait();
    }

    @FXML
    protected void onClearButtonClick() {
        outputArea.clear();
        lastWebProjectPath = null;
        loopCounter.clear();
        userScrolledUp = false;
        updateUI("🚀 Engine cleared and ready...\n");
        if (openBrowserButton != null) openBrowserButton.setDisable(true);
    }

    /** Available Gemini models shown in the Settings dropdown. */
    private static final String[][] GEMINI_MODELS = {
        {"gemini-2.5-flash", "Gemini 2.5 Flash  (fast, free tier) ⚡"},
        {"gemini-2.5-pro",   "Gemini 2.5 Pro    (powerful, best quality) 🧠"},
        {"gemini-2.0-flash", "Gemini 2.0 Flash  (previous gen, stable) 🔧"},
    };

    @FXML
    protected void onSettingsButtonClick() {
        Dialog<javafx.util.Pair<String, String>> dlg = new Dialog<>();
        dlg.setTitle("Brain AI — Settings");
        dlg.setHeaderText("Configure API Keys and Model.\nAdd multiple keys separated by commas for parallel processing.");

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(12);
        box.setPadding(new javafx.geometry.Insets(20));
        box.setMinWidth(500);

        // ── API Keys ─────────────────────────────────────────────────────────
        Label keyLbl = new Label("Gemini API Keys (comma-separated for multiple keys):");
        keyLbl.setStyle("-fx-font-weight: bold;");
        // Show current keys from manager, or fall back to system property
        String currentKeys = APIKeyManager.getInstance().getKeyCount() > 0
                ? APIKeyManager.getInstance().getKeysCsv()
                : System.getProperty("BRAIN_API_KEY", "");
        TextArea tf = new TextArea(currentKeys);
        tf.setPromptText("AIza...key1, AIza...key2, AIza...key3");
        tf.setPrefWidth(480);
        tf.setPrefRowCount(3);
        tf.setWrapText(true);

        Hyperlink link = new Hyperlink("Get a free key at aistudio.google.com");
        link.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop()
                    .browse(new java.net.URI("https://aistudio.google.com/app/apikey"));
            } catch (Exception ignored) {}
        });

        // ── Key Status ────────────────────────────────────────────────────────
        Label statusLbl = new Label("Key Pool Status:");
        statusLbl.setStyle("-fx-font-weight: bold;");
        Label statusVal = new Label(APIKeyManager.getInstance().getStatusSummary());
        statusVal.setStyle("-fx-font-size: 11px; -fx-text-fill: #333;");
        statusVal.setWrapText(true);

        // ── Model selector ────────────────────────────────────────────────────
        Label modelLbl = new Label("Gemini Model:");
        modelLbl.setStyle("-fx-font-weight: bold;");
        javafx.scene.control.ComboBox<String> modelBox = new javafx.scene.control.ComboBox<>();
        String currentModel = System.getProperty("BRAIN_MODEL", "gemini-2.5-flash");
        int selectedIdx = 0;
        for (int i = 0; i < GEMINI_MODELS.length; i++) {
            modelBox.getItems().add(GEMINI_MODELS[i][1]);
            if (GEMINI_MODELS[i][0].equals(currentModel)) selectedIdx = i;
        }
        modelBox.getSelectionModel().select(selectedIdx);
        modelBox.setPrefWidth(480);

        Label modelNote = new Label("Flash is recommended for most tasks. Pro gives higher quality but may be slower.");
        modelNote.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        modelNote.setWrapText(true);

        box.getChildren().addAll(keyLbl, tf, link,
                new javafx.scene.control.Separator(),
                statusLbl, statusVal,
                new javafx.scene.control.Separator(),
                modelLbl, modelBox, modelNote);
        dlg.getDialogPane().setContent(box);

        dlg.setResultConverter(b -> {
            if (b != saveBtn) return null;
            int idx = modelBox.getSelectionModel().getSelectedIndex();
            String modelId = (idx >= 0 && idx < GEMINI_MODELS.length)
                    ? GEMINI_MODELS[idx][0] : "gemini-2.5-flash";
            return new javafx.util.Pair<>(tf.getText().trim(), modelId);
        });

        dlg.showAndWait().ifPresent(pair -> {
            String keysCsv = pair.getKey();
            String model   = pair.getValue();

            // Update key pool
            APIKeyManager.getInstance().setKeys(keysCsv);

            // Also keep first key in legacy property (for backward compat)
            String firstKey = keysCsv.split(",")[0].trim();
            if (!firstKey.isEmpty()) System.setProperty("BRAIN_API_KEY", firstKey);

            System.setProperty("BRAIN_MODEL", model);

            // Persist to config file
            try {
                String configDir = System.getProperty("user.home") + File.separator + ".brainai";
                Files.createDirectories(Paths.get(configDir));
                java.util.Properties props = new java.util.Properties();
                java.io.File cf = new java.io.File(configDir + File.separator + "config.properties");
                if (cf.exists()) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(cf)) { props.load(in); }
                }
                props.setProperty("GOOGLE_API_KEY", keysCsv); // store all keys CSV
                props.setProperty("GEMINI_MODEL",   model);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(cf)) {
                    props.store(out, "Brain AI Configuration");
                }
                updateUI("✅ Settings saved. Keys: " + APIKeyManager.getInstance().getKeyCount()
                        + " | Model: " + model);
            } catch (Exception e) {
                updateUI("⚠️ Settings applied but could not save to file: " + e.getMessage());
            }
        });
    }

    @FXML
    protected void onOpenBrowserButtonClick() {
        if (lastWebProjectPath == null) {
            updateUI("⚠️ No web project built yet.");
            return;
        }
        try {
            File indexFile = new File(lastWebProjectPath, "index.html");
            if (!indexFile.exists()) {
                updateUI("⚠️ index.html not found at: " + lastWebProjectPath);
                return;
            }
            Desktop.getDesktop().browse(indexFile.toURI());
            updateUI("🌐 Opened in browser: " + indexFile.getAbsolutePath());
        } catch (Exception e) {
            updateUI("⚠️ Could not open browser: " + e.getMessage());
        }
    }

    @FXML
    protected void onHelloButtonClick() {
        // ── "Send" while in consult mode → resume the paused pipeline ──────
        if (inConsultMode) {
            String guidance = userInputField.getText().trim();
            if (guidance.isEmpty()) {
                updateUI("⚠️ Please type your guidance first, then press Send.");
                return;
            }
            userInputField.clear();
            Platform.runLater(() -> {
                if (helloButton != null) helloButton.setDisable(true);
            });
            try { waitForConsult.put(guidance); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return;
        }

        // ── Normal "send task" flow ──────────────────────────────────────────
        String userTask = userInputField.getText();
        if (userTask == null || userTask.trim().isEmpty()) {
            updateUI("⚠️ Please type a task first.");
            return;
        }

        outputArea.setText("🚀 Starting AI Assembly Line...\n\n");
        userScrolledUp = false;
        loopCounter.clear();
        userInputField.clear();
        if (helloButton != null)       helloButton.setDisable(true);
        if (openBrowserButton != null) openBrowserButton.setDisable(true);

        Task<Void> pipelineTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                try {
                    DatabaseConnection db     = new DatabaseConnection();
                    GeminiService      gemini = new GeminiService();

                    int projectId = db.createAutoProject(userTask);
                    updateUI("🆔 Project ID: " + projectId);

                    // ── 1. ARCHITECT ──────────────────────────────────────────
                    updateUI("\n🏗️ [Architect] Analyzing task...");
                    String architectPrompt = DatabaseConnection.getPromptByRole("ARCHITECT");
                    String architectPlan   = askAgentResilient(gemini, "ARCHITECT",
                                                              architectPrompt, userTask);
                    updateUI("--- ARCHITECT'S PLAN ---\n" + architectPlan + "\n");
                    db.saveMessage(projectId, "ARCHITECT", architectPlan);

                    String projectType = detectProjectType(architectPlan);
                    updateUI("🔖 Project type: " + projectType);

                    // ── 2. CODER (parallel if sub-tasks present) ──────────────
                    String coderRole    = projectType.equals(TYPE_WEB) ? "WEB_CODER" : "CODER";
                    String coderContext = projectType.equals(TYPE_WEB)
                            ? buildWebCoderContext(userTask, architectPlan)
                            : buildJavaCoderContext(userTask, architectPlan);

                    updateUI("💻 [Coder/" + projectType + "] Writing code"
                            + (APIKeyManager.getInstance().getKeyCount() > 1 ? " (parallel mode)..." : "..."));
                    String coderPrompt = DatabaseConnection.getPromptByRole(coderRole);
                    String coderCode;
                    if (APIKeyManager.getInstance().getKeyCount() > 1) {
                        coderCode = runParallelCoding(gemini, coderPrompt, coderContext);
                    } else {
                        coderCode = askAgentResilient(gemini, coderRole, coderPrompt, coderContext);
                    }

                    if (coderCode.startsWith("AI communication failed")) {
                        updateUI("\n❌ CRITICAL ERROR (non-retryable): " + coderCode);
                        return null;
                    }
                    updateUI("--- CODER'S OUTPUT ---\n" + coderCode + "\n");
                    db.saveMessage(projectId, "CODER", coderCode);

                    // Folder & extraction
                    String folderName  = "Task_" + System.currentTimeMillis();
                    String projectPath = PROJECTS_ROOT + File.separator + folderName;
                    Files.createDirectories(Paths.get(projectPath));
                    updateUI("📁 Project Folder: " + projectPath);
                    extractAndCreateFiles(coderCode, folderName);

                    // ── 3. TESTER ─────────────────────────────────────────────
                    updateUI("\n🔍 [Tester] Reviewing code...");
                    String testerPrompt  = DatabaseConnection.getPromptByRole("TESTER");
                    String testerContext = "PROJECT TYPE: " + projectType + "\n\n" + coderCode;
                    String testerReview  = askAgentResilient(gemini, "TESTER",
                                                             testerPrompt, testerContext);
                    updateUI("--- TESTER'S REVIEW ---\n" + testerReview);
                    db.saveMessage(projectId, "TESTER", testerReview);

                    if (testerReview.contains("[FILE:")) {
                        extractAndCreateFiles(testerReview, folderName);
                    } else {
                        updateUI("⚠️ [Tester] No file blocks — keeping Coder's output.");
                    }

                    // ── 4. DEVOPS ─────────────────────────────────────────────
                    String finalCode = testerReview.contains("[FILE:") ? testerReview : coderCode;
                    if (projectType.equals(TYPE_WEB)) {
                        runWebPipeline(folderName, projectPath, finalCode, db, gemini, projectId);
                        lastWebProjectPath = projectPath;
                        Platform.runLater(() -> {
                            if (openBrowserButton != null) openBrowserButton.setDisable(false);
                        });
                    } else {
                        runJavaPipeline(folderName, projectPath, finalCode, db, gemini, projectId);
                    }

                    // ── 5. README ─────────────────────────────────────────────
                    generateReadme(gemini, db, projectId, projectPath, projectType,
                                   userTask, architectPlan);

                    updateUI("\n✨ ENTIRE PIPELINE COMPLETE!");

                } catch (Exception e) {
                    updateUI("\n❌ Pipeline failed: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    showSpinner(false);
                    Platform.runLater(() -> {
                        if (helloButton != null) helloButton.setDisable(false);
                    });
                }
                return null;
            }
        };

        Thread t = new Thread(pipelineTask);
        t.setDaemon(true);
        t.start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // README generation
    // ─────────────────────────────────────────────────────────────────────────
    private void generateReadme(GeminiService gemini, DatabaseConnection db, int projectId,
                                String projectPath, String projectType,
                                String userTask, String architectPlan)
            throws Exception {
        updateUI("\n📝 [DevOps] Generating README...");
        String devopsPrompt  = DatabaseConnection.getPromptByRole("DEVOPS");
        String readmeContext =
            "PROJECT TYPE: " + projectType + "\n"
            + "TASK: " + userTask + "\n\n"
            + "ARCHITECT PLAN:\n" + architectPlan + "\n\n"
            + "CURRENT FILES IN PROJECT:\n" + listProjectFiles(projectPath);

        String readme = askAgentResilient(gemini, "DEVOPS", devopsPrompt, readmeContext);

        Matcher fileMatcher = Pattern.compile(
                "\\[FILE:\\s*README\\.md\\](.*?)\\[ENDFILE\\]",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(readme);
        String readmeContent = fileMatcher.find() ? fileMatcher.group(1).trim() : readme;

        Path readmePath = Paths.get(projectPath, "README.md");
        Files.writeString(readmePath, readmeContent);
        db.saveMessage(projectId, "DEVOPS", readmeContent);
        updateUI("📄 README.md written to: " + readmePath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Context builders
    // ─────────────────────────────────────────────────────────────────────────
    private String buildJavaCoderContext(String userTask, String architectPlan) {
        return "Task: " + userTask + "\nPlan:\n" + architectPlan
            + "\nIMPORTANT: Use [FILE: Name.java] and [ENDFILE] tags. DO NOT use markdown."
            + "\n- Include ALL necessary imports at the top of EVERY file."
            + "\n- Use ABSOLUTE paths for any file I/O. Call System.getProperty(\"user.dir\") first."
            + "\n- Consumer<Void> lambdas: use (Void v) -> { ... } not () -> { ... }."
            + "\n- Import javax.swing.tree.ExpandVetoException where needed."
            + "\n- Import java.awt.Image in any file using Image."
            + "\n- JOptionPane 7-arg showInputDialog returns Object — cast to String."
            + "\n- Provide FULL class definitions ready to compile.";
    }

    private String buildWebCoderContext(String userTask, String architectPlan) {
        return "Task: " + userTask + "\nPlan:\n" + architectPlan
            + "\nIMPORTANT: Use [FILE: Name.ext] and [ENDFILE] tags. DO NOT use markdown."
            + "\n- Always create at least: index.html, style.css, script.js (or app.js for Node)."
            + "\n- For Node.js: include package.json with a 'start' script."
            + "\n- Use __dirname for all file paths in Node.js code."
            + "\n- Use relative paths for all script/link src attributes in HTML."
            + "\n- Do NOT use ES module import/export for browser projects."
            + "\n- CSS must be complete in style.css. JS must be in .js files."
            + "\n- Provide production-ready code, not placeholders.";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Web DevOps pipeline
    // ─────────────────────────────────────────────────────────────────────────
    private void runWebPipeline(String folderName, String projectPath, String code,
                                DatabaseConnection db, GeminiService gemini, int projectId)
            throws Exception {

        int     maxAttempts = 10;
        int     attempt     = 0;
        boolean success     = false;

        while (attempt < maxAttempts && !success) {
            attempt++;
            final int fa = attempt;
            try {
                updateUI("\n🌐 [Web DevOps] Validation attempt " + fa + "...");
                runWebValidation(projectPath);
                success = true;
                updateUI("✅ Web project validated on attempt " + attempt + ".");
                updateUI("📂 Project ready at: " + projectPath);
            } catch (Exception err) {
                if (attempt >= maxAttempts) {
                    updateUI("\n❌ Max web repair attempts reached.");
                    throw err;
                }
                updateUI("\n🔧 [Web Repair] Issue found. Asking AI to fix...");

                String projectCtx   = getProjectFilesContext(projectPath);
                String repairContext =
                    "Issue found:\n" + err.getMessage()
                    + "\n\nCURRENT FILES:\n" + projectCtx
                    + "\n\nFix the issues and return COMPLETE corrected files "
                    + "in [FILE: Name.ext]...[ENDFILE] blocks.";

                String repairCode = askAgentResilient(gemini, "TESTER",
                        DatabaseConnection.getPromptByRole("TESTER"), repairContext);

                if (!repairCode.contains("[FILE:")) {
                    updateUI("⚠️ [Web Repair Guard]: No file blocks. Retrying...");
                    continue;
                }
                db.saveMessage(projectId, "WEB_REPAIR_" + attempt, repairCode);
                if (extractAndCreateFiles(repairCode, folderName)) {
                    updateUI("\n🛠️ [Web Repair Applied]: Retrying validation...");
                }
            }
        }
    }

    private void runWebValidation(String projectPath) throws Exception {
        updateUI("\n🌐 [Web DevOps] Validating web project...");

        Path indexHtml = null;
        for (String candidate : new String[]{"index.html", "public/index.html"}) {
            Path p = Paths.get(projectPath, candidate);
            if (Files.exists(p)) { indexHtml = p; break; }
        }

        boolean isNodeSSR = Files.exists(Paths.get(projectPath, "server.js"))
                && Files.isDirectory(Paths.get(projectPath, "views"))
                && Files.walk(Paths.get(projectPath, "views"))
                         .anyMatch(p -> {
                             String n = p.toString().toLowerCase();
                             return n.endsWith(".ejs") || n.endsWith(".pug")
                                 || n.endsWith(".hbs") || n.endsWith(".html");
                         });

        if (indexHtml == null && !isNodeSSR) {
            throw new Exception("index.html not found in root or public/, and no server-side "
                    + "views/ templates detected in: " + projectPath);
        }

        if (indexHtml != null) {
            updateUI("✅ index.html found: " + indexHtml);
            String  html       = Files.readString(indexHtml);
            Path    publicDir  = Paths.get(projectPath, "public");
            Path    htmlParent = indexHtml.getParent();
            Pattern refPattern = Pattern.compile(
                    "(?:src|href)=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
            Matcher refMatcher = refPattern.matcher(html);
            StringBuilder missing = new StringBuilder();

            while (refMatcher.find()) {
                String ref = refMatcher.group(1).trim();
                if (ref.startsWith("http") || ref.startsWith("//")
                        || ref.startsWith("#")  || ref.startsWith("data:")) continue;
                String cleanRef = ref.contains("?") ? ref.substring(0, ref.indexOf('?')) : ref;
                if (cleanRef.contains("#")) cleanRef = cleanRef.substring(0, cleanRef.indexOf('#'));
                if (cleanRef.isEmpty()) continue;

                boolean found;
                if (cleanRef.startsWith("/")) {
                    String rel = cleanRef.substring(1);
                    found = Files.exists(publicDir.resolve(rel))
                         || Files.exists(Paths.get(projectPath, rel));
                } else {
                    found = Files.exists(htmlParent.resolve(cleanRef))
                         || Files.exists(Paths.get(projectPath, cleanRef));
                }
                if (!found) missing.append("  - Missing: ").append(ref).append("\n");
            }

            if (missing.length() > 0) {
                throw new Exception("Broken asset references in " + indexHtml.getFileName()
                        + ":\n" + missing);
            }
            updateUI("✅ All asset references resolved.");
        } else {
            updateUI("✅ Server-side rendered Node.js project (EJS/Pug/HBS) — no static index.html required.");
        }

        Path packageJson = Paths.get(projectPath, "package.json");
        if (Files.exists(packageJson)) {
            updateUI("📦 Node.js project detected. Running npm install...");
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                runCmd(projectPath, "cmd", "/c", "npm", "install");
            } else {
                runCmd(projectPath, "npm", "install");
            }
            updateUI("✅ npm install complete.");
            updateUI("▶️  To run: cd " + projectPath + " && npm start");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java DevOps pipeline
    // ─────────────────────────────────────────────────────────────────────────
    private void runJavaPipeline(String folderName, String projectPath, String code,
                                 DatabaseConnection db, GeminiService gemini, int projectId)
            throws Exception {

        if (code == null || code.isEmpty()) {
            updateUI("\n⚠️ No code to build."); return;
        }

        String  currentCode = code;
        String  mainClass   = findMainClass(currentCode);
        int     maxAttempts = 10;
        int     attempt     = 0;
        boolean success     = false;

        while (attempt < maxAttempts && !success) {
            attempt++;
            final int fa = attempt;
            try {
                if (mainClass == null) {
                    updateUI("\n🔎 [Repair] No main class. Asking AI...");
                    String fix = askAgentResilient(gemini, "CODER",
                            "No 'public static void main' found. Add an entry point and return "
                            + "COMPLETE [FILE: Name.java]...[ENDFILE] blocks.",
                            currentCode);
                    if (fix.contains("[FILE:")) {
                        extractAndCreateFiles(fix, folderName);
                        mainClass   = findMainClass(fix);
                        currentCode = fix;
                    }
                    if (mainClass == null) {
                        updateUI("⚠️ Still no main class. Aborting."); break;
                    }
                }

                updateUI("\n⚙️ [DevOps] Build attempt " + fa + "...");
                runDevOpsPipeline(folderName, mainClass);
                success = true;
                updateUI("✅ Build complete on attempt " + attempt + ".");

            } catch (Exception buildError) {
                if (attempt >= maxAttempts) {
                    updateUI("\n❌ Max repair attempts reached. Build failed.");
                    throw buildError;
                }
                updateUI("\n🔧 [Repair] Build failed. Analyzing error...");

                String projectCtx   = getProjectFilesContext(projectPath);
                String repairContext =
                    "ERROR LOGS:\n" + buildError.getMessage() + "\n\n"
                    + "CURRENT FILES:\n" + projectCtx + "\n\n"
                    + "FIX RULES:\n"
                    + "- Add ALL missing imports\n"
                    + "- Use ABSOLUTE paths for file I/O\n"
                    + "- Consumer<Void> lambdas: (Void v) -> { ... }\n"
                    + "- Import javax.swing.tree.ExpandVetoException\n"
                    + "- Import java.awt.Image where used\n"
                    + "- Cast JOptionPane 7-arg result to String\n"
                    + "- Return COMPLETE [FILE: Name.java]...[ENDFILE] blocks only. No markdown.";

                // Check for a loop before calling the AI
                if (checkLoop("REPAIR", buildError.getMessage())) {
                    String guidance = enterConsultMode("REPAIR", buildError.getMessage());
                    repairContext += "\n\nUSER GUIDANCE: " + guidance;
                    loopCounter.clear();
                }

                String repairCode = askAgentResilient(gemini, "TESTER",
                        DatabaseConnection.getPromptByRole("TESTER"), repairContext);

                if (repairCode.startsWith("AI communication failed")
                        && !repairCode.contains("429")
                        && !repairCode.contains("quota")) {
                    updateUI("⚠️ Non-retryable AI error. Aborting."); break;
                }
                if (!repairCode.contains("[FILE:")) {
                    updateUI("⚠️ [Repair Guard]: No file blocks. Retrying with existing files...");
                    continue;
                }
                db.saveMessage(projectId, "REPAIR_" + attempt, repairCode);
                if (extractAndCreateFiles(repairCode, folderName)) {
                    updateUI("\n🛠️ [Repair Applied]: Retrying build...");
                    currentCode = repairCode;
                    String nm = findMainClass(repairCode);
                    if (nm != null) mainClass = nm;
                }
            }
        }
    }

    private void runDevOpsPipeline(String folderName, String mainClassName) throws Exception {
        String basePath = PROJECTS_ROOT + File.separator + folderName;
        updateUI("\n⚙️ [DevOps] Starting Java Build...");

        Path projectDir = Paths.get(basePath);
        StringBuilder sources = new StringBuilder();
        Files.walk(projectDir)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(p -> sources.append("\"")
                    .append(p.toAbsolutePath().toString().replace("\\", "/"))
                    .append("\"\n"));
        Files.writeString(projectDir.resolve("sources.txt"), sources.toString());

        updateUI("🔨 Step 1: Compiling...");
        runCmd(basePath, "javac", "@sources.txt");
        updateUI("✅ Compilation successful.");

        Files.createDirectories(projectDir.resolve("libs"));
        updateUI("🔨 Step 2: Creating JAR...");
        runCmd(basePath, "jar", "cfe", "libs/App.jar", mainClassName, ".");
        updateUI("📦 JAR created: libs/App.jar");

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        updateUI("🔨 Step 3: Generating native app...");

        List<String> jpkgArgs = new ArrayList<>(Arrays.asList(
            "jpackage",
            "--type", "app-image", "--name", "AI_App",
            "--input", "libs",     "--main-jar", "App.jar",
            "--main-class", mainClassName,
            "--add-modules", "java.desktop,java.base,java.logging"
        ));
        if (isWindows) jpkgArgs.add("--win-console");

        runCmd(basePath, jpkgArgs.toArray(new String[0]));
        updateUI("🚀 Native application generated.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────
    private boolean extractAndCreateFiles(String aiOutput, String projectName) throws Exception {
        String  basePath = PROJECTS_ROOT + File.separator + projectName;
        Pattern pattern  = Pattern.compile(
                "\\[FILE:\\s*(.*?)\\](.*?)\\[ENDFILE\\]", Pattern.DOTALL);
        Matcher matcher  = pattern.matcher(aiOutput);
        boolean found    = false;

        while (matcher.find()) {
            found = true;
            String filePath    = matcher.group(1).trim()
                    .replace("src/main/java/", "").replace("src/", "");
            String fileContent = matcher.group(2).trim();
            Path   fullPath    = Paths.get(basePath, filePath);
            Files.createDirectories(fullPath.getParent());
            Files.writeString(fullPath, fileContent);
            updateUI("📄 Created: " + fullPath);
        }
        if (!found) updateUI("⚠️ No [FILE] tags detected! Code was not saved.");
        return found;
    }

    private String findMainClass(String aiOutput) {
        if (aiOutput == null) return null;
        Pattern fp = Pattern.compile(
                "\\[FILE:\\s*(.*?)\\](.*?)\\[ENDFILE\\]", Pattern.DOTALL);
        Matcher m  = fp.matcher(aiOutput);
        while (m.find()) {
            String fileName = m.group(1).trim();
            String content  = m.group(2);
            if (!content.contains("public static void main")) continue;
            Matcher mPkg = Pattern.compile("package\\s+([\\w\\.]+);").matcher(content);
            String  pkg  = mPkg.find() ? mPkg.group(1).trim() + "." : "";
            Matcher mCls = Pattern.compile("public\\s+class\\s+([\\w\\d_]+)").matcher(content);
            String  found = mCls.find()
                    ? pkg + mCls.group(1).trim()
                    : pkg + fileName.replace(".java", "");
            updateUI("🔎 Entry point: " + found);
            return found;
        }
        return null;
    }

    private String listProjectFiles(String projectPath) {
        StringBuilder sb = new StringBuilder();
        try {
            Files.walk(Paths.get(projectPath))
                .filter(Files::isRegularFile)
                .forEach(p -> sb.append("  - ").append(
                        Paths.get(projectPath).relativize(p)).append("\n"));
        } catch (java.io.IOException ignored) {}
        return sb.toString();
    }

    private String getProjectFilesContext(String projectPath) {
        StringBuilder ctx = new StringBuilder();
        try {
            Files.walk(Paths.get(projectPath))
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.toString().toLowerCase();
                    return n.endsWith(".java") || n.endsWith(".html")
                        || n.endsWith(".css")  || n.endsWith(".js")
                        || n.endsWith(".json");
                })
                .forEach(p -> {
                    try {
                        ctx.append("[FILE: ").append(p.getFileName()).append("]\n")
                           .append(Files.readString(p)).append("\n[ENDFILE]\n\n");
                    } catch (java.io.IOException ignored) {}
                });
        } catch (java.io.IOException ignored) {}
        return ctx.toString();
    }

    private String runCmd(String directory, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                String fl = line;
                Platform.runLater(() -> outputArea.appendText("   > " + fl + "\n"));
            }
        }
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new Exception("Command failed (exit " + exitCode + ")\n" + output);
        }
        return output.toString();
    }
}