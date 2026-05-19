package com.forge.aiteam;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.TextInputDialog;
import javafx.stage.DirectoryChooser;
import javafx.scene.layout.*;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.animation.RotateTransition;
import javafx.util.Duration;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.event.EventHandler;
import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MainController: The central nervous system of Brain AI.
 * 
 * 
 * WHY: This class manages the multi-agent pipeline (Architect -> Coder ->
 * 
 * Tester -> DevOps).
 * HOW: It uses JavaFX for the UI, Gemini for the AI, and local system commands
 * for builds.
 * WHAT: It handles user input, routes it to the AI, extracts generated files,
 * and triggers local development pipelines (Vite, Javac, etc.).
 */
     
public class MainController {
     
    // FXML bindings
    // These link the Java code to the UI elements defined in main_scene.fxml.
    @FXML
    private TextArea outputArea; // The main log area.
    @FXML
    private TextField userInputField; // Where you type your instructions.
    @FXML
    private Button helloButton; // The "Send to Brain" button.
    @FXML
     
    private Button openBrowserButton; // The "    " button for web previews.
    @FXML
    private Button settingsButton; // Opens the API key/model settings.
    @FXML
    private Arc spinnerArc; // The rotating circle shown during AI thought.
    @FXML
    private Label consultLabel; // Overlay shown when the AI is paused for your input.

    //        Consult-mode MCQ components       when the AI needs you to choose between multiple implementation options.
    @FXML
    private VBox consultOptionsBox; // The container for MCQ options.
    @FXML
    private VBox choicesBox; // Where the CheckBoxes are dynamically added.
    @FXML
    private TextField otherInputField; // Field for custom guidance during consult.

    private RotateTransition spinnerAnim; // Manages the rotation animation of the spinner.

            
    // Project Type Constants: Used to route logic to specific agent prompts.
    private static final String TYPE_JAVA = "JAVA"; // Standard Java Swing apps.
    private static final String TYPE_WEB = "WEB"; // Static HTML or Node.js apps.
    private static final String TYPE_REACT = "REACT"; // Modern React apps using Vite.

    /** All AI-generated projects are saved in this directory. */
    private static final String PROJECTS_ROOT = System.getProperty("user.home") + File.separator + "BrainAI Projects";

    // Stores the path of the last web project built so the "Open Browser" button
    // knows where to look.
    private volatile String lastWebProjectPath = null;

    //        Loop   detection state  
                                                                                                                                                             
    /**
            
     * WHY: Prevents the AI from repeating the same error or output infinitely.
     * HOW: Stores a hash of the response. If it repeats too many times, we pause
     * and ask the user.
     */
            
    private final Map<String, Integer> loopCounter = new HashMap<>();
    private static final int LOOP_THRESHOLD = 3; // Maximum allowed repetitions.

    //        Pause / consult state                                                                                                                                                 
    /**
     * When the loop detector fires, the pipeline thread blocks on
     * waitForConsult.take() until the user provides guidance.
     */
    private final java.util.concurrent.BlockingQueue<String> waitForConsult = new java.util.concurrent.LinkedBlockingQueue<>(1);
    private volatile boolean inConsultMode = false;
            

                
                
                                              // 
    //        Smart-scroll state                                                                                                                                                          
    /** True if user has scrolled up and wants to read; auto-scroll is suspended. */
    private boolean userScrolledUp = false;
    //                                                                                                                                                                                                                            
    // JavaFX FXML initialize
    //                                                                                                                                                                                                                            
    @FXML
    public void initialize() {
        setupSpinner();
        setupSmartScroll();
        setupDragAndDrop();
        if (openBrowserButton != null) openBrowserButton.setDisable(true);
        if (consultLabel != null)     consultLabel.setVisible(false);
    }

    /** Enables dragging a folder onto the input field or output area to load it. */
    private void setupDragAndDrop() {
        if (userInputField == null || outputArea == null) return;

        EventHandler<DragEvent> onDragOver = event -> {
            if (event.getGestureSource() != userInputField && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        };

        EventHandler<DragEvent> onDragDropped = event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                File file = db.getFiles().get(0);
                if (file.isDirectory()) {
                    userInputField.setText("[LOAD: " + file.getAbsolutePath() + "] fix the issues in this project");
                    userInputField.requestFocus();
                    userInputField.positionCaret(userInputField.getText().length());
                    updateUI("     [Drag&Drop] Folder loaded: " + file.getName());
                } else {
                    userInputField.setText("Fix the issues in this file: " + file.getAbsolutePath());
                    updateUI("     [Drag&Drop] File detected: " + file.getName());
                }
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        };

        userInputField.setOnDragOver(onDragOver);
        userInputField.setOnDragDropped(onDragDropped);
        outputArea.setOnDragOver(onDragOver);
        outputArea.setOnDragDropped(onDragDropped);
    }

    //                                                                                              
                                                                                                                                       
    // Spinner: rotate an Arc node
    // FXML continuously while AI thinks
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
            else spinnerAnim.stop();
        });
    }

    //                                                                                                                                                                                                                            
    // Smart Scroll: only auto-scroll when user is already at the bottom
    //                                                                                                                                                                                                                            
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

    //                                                                                                                                                                                                                            
    // Loop detection
                                
    //                                                                                                                                                                                                                            
    /**
     * Call after each agent response.
     * Returns true if the pipeline should pause for user consultation.
     */
    private boolean checkLoop(String agentRole, String responseText) {
        String key = agentRole + "::" + Integer.toHexString(responseText.hashCode());
        int count  = loopCounter.merge(key, 1, Integer::sum);
        if (count >= LOOP_THRESHOLD) {
            updateUI("\n                                                                                                                                                                       ");
            updateUI("   LOOP DETECTED: Agent [" + agentRole + "] produced the same result " + count + " times in a row.");
            updateUI("   Last error / pattern:\n" + (responseText.length() > 500 ? responseText.substring(0, 500) + "..." : responseText));
            updateUI("   --------------------------------------------------------------------------------\n");
            loopCounter.clear(); 
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
                
     * @return the user's guidance text (n
                ver null/empty)
     */
                
    private String enterConsultMode(String agentRole, String lastError) throws InterruptedException {
        inConsultMode = true;
        waitForConsult.clear(); // Ensure queue is empty before starting a new pause
        Platform.runLater(() -> {
                
            if (consultLabel != null) {
                consultLabel.setText("       Agent Paused     Type guidance below and press Send to resume");
                consultLabel.setVisible(true);
            }
            if (helloButton != null) helloButton.setDisable(false); // repurpose Send button
        });

        //        Header Message                                                                                                                                                       
        updateUI("\n\n                                                                                                                                                                       ");
        if (agentRole.contains("REVIEW") || agentRole.contains("CONSULT")) {
            updateUI("   AGENT PAUSED: " + agentRole);
        } else {
        // 
            updateUI("   LOOP DETECTED: Agent [" + agentRole + "] produced the same result "
                     + LOOP_THRESHOLD + " times in a row.");
        }
        updateUI("   Last output / pattern:\n   " + (lastError.length() > 500 ? lastError.substring(0, 500) + "..." : lastError));
        updateUI("   The pipeline is PAUSED. Your execution context is preserved.");
        
        //        MCQ Detection                                                                                                                                                             
        List<String> choices = new ArrayList<>();
        Matcher m = Pattern.compile("\\[CHOICE:\\s*([^\\]]+)\\]", Pattern.CASE_INSENSITIVE).matcher(lastError);
        while (m.find()) {
            choices.add(m.group(1).trim());
        }

                    
        if (!choices.isEmpty()) {
                    
            Platform.runLater(() -> {
                    
                     choicesBox.getChildren().clear(); 
                for (String choice : choices) {
                    CheckBox cb = new CheckBox(choice);
                    choicesBox.getChildren().add(cb);
                }
                consultOptionsBox.setVisible(true);
                consultOptionsBox.setManaged(true);
                userInputField.setVisible(false);
                userInputField.setManaged(false);
                otherInputField.clear();
                
            });
            updateUI("        Select one or more options below and press Send.");
        } else {
            updateUI("        Type your guidance or a manual fix in the input field and press Send.");
        }
        updateUI("                                                                                                                                                                              \n");

        showSpinner(false);
        // Block this pipeline thread until the user sends input
        String guidance = waitForConsult.take();
        inConsultMode = false;
        showSpinner(true);
        Platform.runLater(() -> {
            if (consultLabel != null) consultLabel.setVisible(false);
            consultOptionsBox.setVisible(false);
            consultOptionsBox.setManaged(false);
            userInputField.setVisible(true);
            userInputField.setManaged(true);
            
            
        });
        updateUI("        Resuming pipeline with guidance: " + guidance + "\n");
        return guidance;
    }

    //                                                                                                                                                                                                                            
    // Resilient AI caller     retries forever on 429/503/500; pauses on loops.
    //                                                                                                                                                                                                                            
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
                updateUI("       Open        Settings and paste a valid Gemini API key, then try again.");
                // Propagate as a RuntimeException so the pipeline catches it and stops
                throw new RuntimeException("NO_API_KEY: " + e.getMessage(), e);
            }

            showSpinner(false);

            if (result == null || result.isBlank()) {
                updateUI("       [" + agentRole + "] Empty response      retrying...");
                continue;
            }

                    
            // Fatal API-key error returned as a string (backward compat)
            boolean isNoKey = result.contains("No Gemini API key configured")
                           || result.contains("No Gemini API keys configured")
                           || result.contains("API Key");
            if (isNoKey) {
                updateUI("\n🔴 FATAL: API key missing or all keys are invalid.");
                updateUI("       Open        Settings and paste your Gemini API key, then try again.");
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
            
                long waitMs = isRateLimit ? 30000L : (long) Math.pow(2, Math.min(attempt, 6)) * 1000L;
                java.util.regex.Matcher m = java.util.regex.Pattern
                            
                        .compile("retry in ([\\d\\.]+)s").matcher(result);
                if (m.find()) {
                    try { waitMs = (long)(Double.parseDouble(m.group(1)) * 1000) + 2000; }
                    catch (NumberFormatException ignored) {}
                }
                final long fw = waitMs;
                updateUI("    [Rate Limit] Attempt " + attempt + " failed. Waiting "
                        + (fw / 1000) + "s before retrying...");
                Thread.sleep(waitMs);
            } else {
                // Genuine non-retryable error (e.g. bad request, SSL issue)
                            
                updateUI("\u274C [Non-retryable AI Error]: " + result);
                            
                throw new RuntimeException("NON_RETRYABLE: " + result);
            }
        }
    }
                            

                        
    //                                                                                                                                                                                                                            
    // Parallel coding: dispatch multiple sub-tasks simultaneously
    //                                                                                                                                                                                                                            
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
            // No sub-tasks     plain sequential call
            return askAgentResilient(gemini, "PARALLEL_CODER", coderPrompt, fullContext);
        }

        int poolSize = Math.min(parts.length, Math.max(APIKeyManager.getInstance().getKeyCount(), 2));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<Future<String>> futures = new ArrayList<>();

            
        updateUI("     [Parallel Coder] Dispatching " + parts.length + " sub-tasks on "
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
                updateUI("       [Parallel sub-task failed]: " + e.getCause().getMessage());
            }
        }
        return combined.toString();
    }

    //                                                                                                                                                                                                                            
    // Project-type detection
    //                                                                                                                                                                                                                            
    private String detectProjectType(String architectPlan) {
        Matcher m = Pattern.compile("\\[TYPE:\\s*(\\w+)\\]",
                Pattern.CASE_INSENSITIVE).matcher(architectPlan);
        if (m.find()) {
            String type = m.group(1).toUpperCase();
            if (type.equals(TYPE_WEB)) return TYPE_WEB;
            if (type.equals(TYPE_REACT)) return TYPE_REACT;
        }
        return TYPE_JAVA;
    }

    //                                                                                                                                                                                                                            
    // UI button handlers
    //                                                                                                                                                                                                                            
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
                + "After a web build, click '    ' to preview it.\n\n"
                + "     TIP: Add multiple API keys in Settings for faster parallel processing!");
        alert.showAndWait();
    }

    @FXML
    protected void onLoadProjectButtonClick() {
        javafx.stage.DirectoryChooser directoryChooser = new javafx.stage.DirectoryChooser();
        directoryChooser.setTitle("Select Brain AI Project Folder");
        
        File defaultDirectory = new File(PROJECTS_ROOT);
        if (defaultDirectory.exists()) {
            directoryChooser.setInitialDirectory(defaultDirectory);
        }

        File selectedDirectory = directoryChooser.showDialog(userInputField.getScene().getWindow());
        
        if (selectedDirectory != null) {
            userInputField.setText("[LOAD: " + selectedDirectory.getAbsolutePath() + "] ");
            userInputField.requestFocus();
            userInputField.positionCaret(userInputField.getText().length());
        }
    }

    @FXML
    protected void onClearButtonClick() {
        outputArea.clear();
        lastWebProjectPath = null;
        loopCounter.clear();
        userScrolledUp = false;
        updateUI("     Engine cleared and ready...\n");
        if (openBrowserButton != null) openBrowserButton.setDisable(true);
    }

    /** Available Gemini models shown in the Settings dropdown. */
    private static final String[][] GEMINI_MODELS = {
        {"gemini-3-flash-preview", "Gemini 3 Flash Preview (Latest & Fastest)    "},
        {"gemini-2.0-flash",       "Gemini 2.0 Flash (Advanced)     "},
        {"gemini-1.5-flash",       "Gemini 1.5 Flash (Stable/Fast)     "},
        {"gemini-1.5-pro",         "Gemini 1.5 Pro (Highest Quality)     "},
    };

    @FXML
    protected void onSettingsButtonClick() {
        Dialog<javafx.util.Pair<String, String>> dlg = new Dialog<>();
        dlg.setTitle("Brain AI     Settings");
        dlg.setHeaderText("Configure API Keys and Model.\nAdd multiple keys separated by commas for parallel processing.");

        ButtonType saveBtn = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dlg.getDialogPane().getButtonTypes().addAll(saveBtn, ButtonType.CANCEL);

        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(12);
        box.setPadding(new javafx.geometry.Insets(20));
        box.setMinWidth(500);

        //        API Keys                                                                                                                                                                            
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

        //        Key Status                                                                                                                                                                         
        Label statusLbl = new Label("Key Pool Status:");
        statusLbl.setStyle("-fx-font-weight: bold;");
        Label statusVal = new Label(APIKeyManager.getInstance().getStatusSummary());
        statusVal.setStyle("-fx-font-size: 11px; -fx-text-fill: #333;");
        statusVal.setWrapText(true);

        //        Model selector                                                                                                                                                             
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
                updateUI("    Settings saved. Keys: " + APIKeyManager.getInstance().getKeyCount()
                        + " | Model: " + model);
            } catch (Exception e) {
                updateUI("       Settings applied but could not save to file: " + e.getMessage());
            }
        });
    }

    @FXML
    protected void onOpenBrowserButtonClick() {
        if (lastWebProjectPath == null) {
            updateUI("       No web project built yet.");
            return;
        }
        try {
            File indexFile = new File(lastWebProjectPath, "index.html");
            if (!indexFile.exists()) {
                updateUI("       index.html not found at: " + lastWebProjectPath);
                return;
            }
            Desktop.getDesktop().browse(indexFile.toURI());
            updateUI("     Opened in browser: " + indexFile.getAbsolutePath());
        } catch (Exception e) {
            updateUI("       Could not open browser: " + e.getMessage());
        }
    }

    @FXML
    protected void onHelloButtonClick() {
        // --- Consult Mode Logic ---
        if (inConsultMode) {
            StringBuilder guidance = new StringBuilder();
            
            // 1. Collect MCQ choices (from checkboxes)
            if (choicesBox != null && !choicesBox.getChildren().isEmpty()) {
                for (javafx.scene.Node node : choicesBox.getChildren()) {
                    if (node instanceof CheckBox) {
                        CheckBox cb = (CheckBox) node;
                        if (cb.isSelected()) {
                            if (guidance.length() > 0) guidance.append(", ");
                            guidance.append(cb.getText());
                        }
                    }
                }
            }

            // 2. Add "Other" input field
            String other = otherInputField.getText().trim();
            if (!other.isEmpty()) {
                if (guidance.length() > 0) guidance.append(" | Guidance: ");
                guidance.append(other);
            }

            // 3. Fallback: check main input field
            String mainText = userInputField.getText().trim();
            if (!mainText.isEmpty() && !mainText.equals(other)) {
                if (guidance.length() > 0) guidance.append(" | Input: ");
                guidance.append(mainText);
            }

            String finalGuidance = guidance.toString().trim();
            if (finalGuidance.isEmpty()) {
                updateUI("       Please select an option or type your guidance first.");
                return;
            }
            
            userInputField.clear();
            otherInputField.clear();
            updateUI("       Sending guidance to Brain...");

            Platform.runLater(() -> {
                if (helloButton != null) helloButton.setDisable(true);
            });

            try {
                waitForConsult.clear();
                boolean sent = waitForConsult.offer(finalGuidance, 5, java.util.concurrent.TimeUnit.SECONDS);
                if (!sent) {
                    updateUI("       Brain is busy or not responding (timeout). Please try again.");
                    Platform.runLater(() -> { if (helloButton != null) helloButton.setDisable(false); });
                }
            } catch (InterruptedException ignored) {}
            return;
        }

        // --- Start New Pipeline ---
        final String rawInput = userInputField.getText().trim();
        if (rawInput.isEmpty()) return;
        userInputField.clear();

        // Parse LOAD and NAME commands
        String tempUserTask = rawInput;
        String tempLoadedPath = null;
        String tempUserChosenName = null;

        Matcher loadMatcher = Pattern.compile("\\[LOAD:\\s*(.*?)\\]", Pattern.CASE_INSENSITIVE).matcher(rawInput);
        if (loadMatcher.find()) {
            tempLoadedPath = loadMatcher.group(1).trim();
            tempUserTask = rawInput.replace(loadMatcher.group(0), "").trim();
        }
        Matcher nameMatcher = Pattern.compile("\\[NAME:\\s*(.*?)\\]", Pattern.CASE_INSENSITIVE).matcher(rawInput);
        if (nameMatcher.find()) {
            tempUserChosenName = nameMatcher.group(1).trim();
            tempUserTask = tempUserTask.replace(nameMatcher.group(0), "").trim();
        }

        // If no name was provided and we aren't loading an existing project, ask the user
        if (tempUserChosenName == null && tempLoadedPath == null) {
            TextInputDialog dialog = new TextInputDialog("MyNewProject");
            dialog.setTitle("New Project");
            dialog.setHeaderText("Name your project folder");
            dialog.setContentText("Please enter a name for the project directory:");
            
            java.util.Optional<String> result = dialog.showAndWait();
            if (result.isPresent() && !result.get().trim().isEmpty()) {
                tempUserChosenName = result.get().trim();
            }
        }

        final String finalUserTask = tempUserTask;
        final String finalLoadedPath = tempLoadedPath;
        final String finalUserChosenName = tempUserChosenName;

        helloButton.setDisable(true);
        showSpinner(true);

        Task<Void> pipelineTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                DatabaseConnection db = new DatabaseConnection();
                GeminiService gemini = new GeminiService();
                int projectId = db.createAutoProject(finalUserTask);
                String contextInjection = "";
                String projectPathStr = null;
                String folderNameStr = null;

                try {
                    if (finalLoadedPath != null) {
                        Path loadedPath = Paths.get(finalLoadedPath);
                        if (Files.exists(loadedPath) && Files.isDirectory(loadedPath)) {
                            projectPathStr = loadedPath.toAbsolutePath().normalize().toString();
                            folderNameStr = loadedPath.getFileName().toString();
                            updateUI("     [Load Project] Read existing files from: " + projectPathStr);
                            
                            boolean isSelfUpgrade = folderNameStr.toLowerCase().contains("brain") || Files.exists(Paths.get(projectPathStr, "BRAIN_AI_CONTEXT.md"));
                            if (isSelfUpgrade) {
                                updateUI("     [Self-Evolution] Brain AI workspace detected!");
                                String contextFile = "";
                                Path ctxPath = Paths.get(projectPathStr, "BRAIN_AI_CONTEXT.md");
                                if (Files.exists(ctxPath)) {
                                     contextFile = Files.readString(ctxPath);
                                }
                                contextInjection = "\n\n*** SELF-EVOLUTION MODE ***\n"
                                    + "You are modifying YOUR OWN SOURCE CODE.\n"
                                    + contextFile + "\n"
                                    + "You have full permission to modify the UI (main_scene.fxml, style.css) and all Java classes EXCEPT DatabaseConnection.java.\n"
                                    + "EXISTING FILES:\n" + getProjectFilesContext(projectPathStr);
                            } else {
                                String existingFiles = getProjectFilesContext(projectPathStr);
                                contextInjection = "\n\nEXISTING PROJECT FILES:\n" + existingFiles + "\n"
                                    + "IMPORTANT: Please modify these existing files to fulfill the task.";
                            }
                        } else {
                            updateUI("       [Load Project] Invalid directory: " + finalLoadedPath + " - starting fresh.");
                        }
                    }

                    if (projectPathStr == null) {
                        if (finalUserChosenName != null) {
                            folderNameStr = finalUserChosenName;
                        } else {
                            folderNameStr = "Task_" + System.currentTimeMillis();
                        }
                        projectPathStr = PROJECTS_ROOT + File.separator + folderNameStr;
                    }

                    final String currentProjectPath = projectPathStr;
                    final String currentFolderName = folderNameStr;

                    // 1. ARCHITECT
                    updateUI("\n        [Architect] Analyzing task...");
                    String architectPrompt = DatabaseConnection.getPromptByRole("ARCHITECT");
                    String architectTask = finalUserTask + contextInjection;
                    String architectPlan = askAgentResilient(gemini, "ARCHITECT", architectPrompt, architectTask);
                    updateUI("--- ARCHITECT'S PLAN ---\n" + architectPlan + "\n");
                    db.saveMessage(projectId, "ARCHITECT", architectPlan);

                    // 1b. CONSULTATION
                    updateUI("\n    [Consultation] Please review the Architect's plan above.");
                    updateUI("   Choose an option, provide feedback, or type 'Proceed' to start coding.");
                    String userGuidance = enterConsultMode("ARCHITECT_REVIEW", architectPlan);
                    updateUI("    [Consultation] Guidance received: " + userGuidance);

                    // 1c. REFINEMENT
                    if (!userGuidance.equalsIgnoreCase("continue") && !userGuidance.equalsIgnoreCase("proceed")) {
                        updateUI("\n     [Architect] Refining plan based on your guidance...");
                        String refinePrompt = architectPrompt + "\n\n"
                            + "The user has provided guidance/choices for your initial plan.\n"
                            + "Incorporate this feedback and produce a FINAL, COMPLETE technical plan.\n"
                            + "The coder will follow this final plan exactly. No more questions.";
                        String refineContext = "Original Task: " + finalUserTask + "\n\n"
                            + "Initial Plan:\n" + architectPlan + "\n\n"
                            + "User Guidance:\n" + userGuidance;
                        architectPlan = askAgentResilient(gemini, "ARCHITECT", refinePrompt, refineContext);
                        updateUI("--- REFINED ARCHITECT'S PLAN ---\n" + architectPlan + "\n");
                        db.saveMessage(projectId, "ARCHITECT_REFINED", architectPlan);
                    } else {
                        updateUI("       [Consultation] Proceeding with original plan.");
                    }

                    String projectType = detectProjectType(architectPlan);
                    updateUI("     Project type: " + projectType);

                    // 1.5 VITE PRE-INITIALIZATION
                    if (projectType.equals(TYPE_REACT) && finalLoadedPath == null) {
                        Files.createDirectories(Paths.get(currentProjectPath));
                        updateUI("    [Vite] Pre-initializing React project in: " + currentProjectPath);
                        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                        try {
                            if (isWindows) {
                                runCmd(currentProjectPath, "cmd", "/c", "npm", "create", "vite@latest", ".", "--yes", "--", "--template", "react");
                            } else {
                                runCmd(currentProjectPath, "npm", "create", "vite@latest", ".", "--yes", "--", "--template", "react");
                            }
                            updateUI("    [Vite] Boilerplate generated successfully.");
                            String viteFiles = getProjectFilesContext(currentProjectPath);
                            contextInjection += "\n\nEXISTING VITE FILES (MODIFY THESE):\n" + viteFiles + "\n"
                                + "IMPORTANT: Please modify the existing Vite boilerplate files (like src/App.jsx, src/index.css, package.json). DO NOT write react-scripts.";
                        } catch (Exception e) {
                            updateUI("       [Vite] Failed to pre-initialize: " + e.getMessage());
                        }
                    }

                    // 2. CODER
                    String coderRole = projectType.equals(TYPE_REACT) ? "REACT_CODER"
                                     : (projectType.equals(TYPE_WEB) ? "WEB_CODER" : "CODER");
                    String coderContext;
                    if (projectType.equals(TYPE_REACT)) {
                        coderContext = buildReactCoderContext(finalUserTask, architectPlan) + contextInjection;
                    } else if (projectType.equals(TYPE_WEB)) {
                        coderContext = buildWebCoderContext(finalUserTask, architectPlan) + contextInjection;
                    } else {
                        coderContext = buildJavaCoderContext(finalUserTask, architectPlan) + contextInjection;
                    }

                    updateUI("     [" + coderRole + "] Writing code"
                            + (APIKeyManager.getInstance().getKeyCount() > 1 ? " (parallel mode)..." : "..."));
                    String coderPrompt = DatabaseConnection.getPromptByRole(coderRole);
                    String coderCode;
                    if (APIKeyManager.getInstance().getKeyCount() > 1) {
                        coderCode = runParallelCoding(gemini, coderPrompt, coderContext);
                    } else {
                        coderCode = askAgentResilient(gemini, coderRole, coderPrompt, coderContext);
                    }

                    if (coderCode.startsWith("AI communication failed")) {
                        updateUI("\n    CRITICAL ERROR (non-retryable): " + coderCode);
                        return null;
                    }
                    updateUI("--- CODER'S OUTPUT ---\n" + coderCode + "\n");
                    db.saveMessage(projectId, "CODER", coderCode);

                    if (finalLoadedPath == null) {
                        Files.createDirectories(Paths.get(currentProjectPath));
                    }
                    updateUI("     Project Folder: " + currentProjectPath);
                    extractAndCreateFiles(coderCode, currentProjectPath, projectType);

                    // 3. TESTER
                    updateUI("\n     [Tester] Reviewing code...");
                    String testerPrompt = DatabaseConnection.getPromptByRole("TESTER");
                    String testerContext = "PROJECT TYPE: " + projectType + "\n\n" + coderCode;
                    String testerReview = askAgentResilient(gemini, "TESTER", testerPrompt, testerContext);
                    updateUI("--- TESTER'S REVIEW ---\n" + testerReview);
                    db.saveMessage(projectId, "TESTER", testerReview);

                    if (testerReview.contains("[FILE:")) {
                        extractAndCreateFiles(testerReview, currentProjectPath, projectType);
                    } else {
                        updateUI("       [Tester] No file blocks     keeping Coder's output.");
                    }

                    // 4. DEVOPS
                    String finalCode = testerReview.contains("[FILE:") ? testerReview : coderCode;
                    if (projectType.equals(TYPE_WEB) || projectType.equals(TYPE_REACT)) {
                        runWebPipeline(currentProjectPath, currentProjectPath, finalCode, db, gemini, projectId);
                        lastWebProjectPath = currentProjectPath;
                        Platform.runLater(() -> {
                            if (openBrowserButton != null) openBrowserButton.setDisable(false);
                        });
                        autoStartWebServer(currentProjectPath, projectType);
                    } else {
                        runJavaPipeline(currentProjectPath, currentProjectPath, finalCode, db, gemini, projectId);
                    }

                    // 5. README
                    generateReadme(gemini, db, projectId, currentProjectPath, projectType, finalUserTask, architectPlan);
                    updateUI("\n    ENTIRE PIPELINE COMPLETE!");

                } catch (Exception e) {
                    updateUI("\n    Pipeline failed: " + e.getMessage());
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

    //                                                                                                                                                                                                                            
    // README generation
    //                                                                                                                                                                                                                            
    private void generateReadme(GeminiService gemini, DatabaseConnection db, int projectId,
                                String projectPath, String projectType,
                                String userTask, String architectPlan)
            throws Exception {
        updateUI("\n     [DevOps] Generating README...");
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
        updateUI("     README.md written to: " + readmePath);
    }

    //                                                                                                                                                                                                                            
    // Context builders
    //                                                                                                                                                                                                                            
    private String buildJavaCoderContext(String userTask, String architectPlan) {
        return "Task: " + userTask + "\nPlan:\n" + architectPlan
            + "\nIMPORTANT: Use [FILE: path/to/Name.java] and [ENDFILE] tags. DO NOT use markdown."
            + "\n- ALWAYS use Java Swing for GUI components."
            + "\n- COMMENT EVERY LINE or logical block to explain the logic clearly."
            + "\n- Include ALL necessary imports at the top of EVERY file."
            + "\n- Use ABSOLUTE paths for any file I/O. Call System.getProperty(\"user.dir\") first."
            + "\n- Consumer<Void> lambdas: use (Void v) -> { ... } not () -> { ... }."
            + "\n- Import javax.swing.tree.ExpandVetoException where needed."
            + "\n- Import java.awt.Image in any file using Image."
            + "\n- JOptionPane 7-arg showInputDialog returns Object     cast to String."
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

    private String buildReactCoderContext(String userTask, String architectPlan) {
        return "Task: " + userTask + "\nPlan:\n" + architectPlan
            + "\nIMPORTANT: Use [FILE: Name.ext] and [ENDFILE] tags. DO NOT use markdown."
            + "\n- You MUST create all Vite boilerplate files: package.json, vite.config.js, index.html, src/main.jsx, src/App.jsx, and CSS."
            + "\n- In package.json, ensure scripts include 'dev': 'vite --open'."
            + "\n- Provide production-ready React code.";
    }

    //                                                                                                                                                                                                                            
    // Web DevOps pipeline
    //                                                                                                                                                                                                                            
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
                updateUI("\n     [Web DevOps] Validation attempt " + fa + "...");
                runWebValidation(projectPath);
                success = true;
                updateUI("    Web project validated on attempt " + attempt + ".");
                updateUI("     Project ready at: " + projectPath);
            } catch (Exception err) {
                if (attempt >= maxAttempts) {
                    updateUI("\n    Max web repair attempts reached.");
                    throw err;
                }
                updateUI("\n     [Web Repair] Issue found. Asking AI to fix...");

                String projectCtx   = getProjectFilesContext(projectPath);
                String repairContext =
                    "Issue found:\n" + err.getMessage()
                    + "\n\nCURRENT FILES:\n" + projectCtx
                    + "\n\nFix the issues and return COMPLETE corrected files "
                    + "in [FILE: Name.ext]...[ENDFILE] blocks.";

                String repairCode = askAgentResilient(gemini, "TESTER",
                        DatabaseConnection.getPromptByRole("TESTER"), repairContext);

                if (!repairCode.contains("[FILE:")) {
                    updateUI("       [Web Repair Guard]: No file blocks. Retrying...");
                    continue;
                }
                db.saveMessage(projectId, "WEB_REPAIR_" + attempt, repairCode);
                if (extractAndCreateFiles(repairCode, projectPath, TYPE_WEB)) {
                    updateUI("\n        [Web Repair Applied]: Retrying validation...");
                }
            }
        }
    }

    private void runWebValidation(String projectPath) throws Exception {
        updateUI("\n     [Web DevOps] Validating web project...");

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
            updateUI("    index.html found: " + indexHtml);
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
            updateUI("    All asset references resolved.");
        } else {
            updateUI("    Server-side rendered Node.js project (EJS/Pug/HBS)     no static index.html required.");
        }

        Path packageJson = Paths.get(projectPath, "package.json");
        if (Files.exists(packageJson)) {
            updateUI("     Node.js project detected. Running npm install...");
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                runCmd(projectPath, "cmd", "/c", "npm", "install");
            } else {
                runCmd(projectPath, "npm", "install");
            }
            updateUI("    npm install complete.");
            updateUI("        To run: cd " + projectPath + " && npm run dev (or npm start)");
        }
    }

    private void autoStartWebServer(String projectPath, String projectType) {
        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            Path packageJson = Paths.get(projectPath, "package.json");
            
            if (Files.exists(packageJson)) {
                updateUI("     Auto-starting Node/React server...");
                
                String pkgContent = Files.readString(packageJson);
                String scriptToRun = pkgContent.contains("\"dev\":") ? "npm run dev" : "npm start";

                if (isWindows) {
                    new ProcessBuilder("cmd", "/c", "start", "cmd", "/k", scriptToRun)
                        .directory(new File(projectPath))
                        .start();
                } else {
                    new ProcessBuilder("sh", "-c", scriptToRun + " &")
                        .directory(new File(projectPath))
                        .start();
                }
            } else if (projectType.equals(TYPE_WEB)) {
                // Static HTML site
                File indexFile = new File(projectPath, "index.html");
                if (indexFile.exists()) {
                    updateUI("     Opening static index.html in browser...");
                    Desktop.getDesktop().browse(indexFile.toURI());
                }
            }
        } catch (Exception e) {
            updateUI("       Could not auto-start web server: " + e.getMessage());
        }
    }

    //                                                                                                                                                                                                                            
    // Java DevOps pipeline
    //                                                                                                                                                                                                                            
    private void runJavaPipeline(String folderName, String projectPath, String code,
                                 DatabaseConnection db, GeminiService gemini, int projectId)
            throws Exception {

        if (code == null || code.isEmpty()) {
            updateUI("\n       No code to build."); return;
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
                    updateUI("\n     [Repair] No main class. Asking AI...");
                    String fix = askAgentResilient(gemini, "CODER",
                            "No 'public static void main' found. Add an entry point and return "
                            + "COMPLETE [FILE: Name.java]...[ENDFILE] blocks.",
                            currentCode);
                    if (fix.contains("[FILE:")) {
                        extractAndCreateFiles(fix, projectPath, TYPE_JAVA);
                        mainClass   = findMainClass(fix);
                        currentCode = fix;
                    }
                    if (mainClass == null) {
                        updateUI("       Still no main class. Aborting."); break;
                    }
                }

                updateUI("\n       [DevOps] Build attempt " + fa + "...");
                runDevOpsPipeline(projectPath, mainClass);
                success = true;
                updateUI("    Build complete on attempt " + attempt + ".");

            } catch (Exception buildError) {
                if (attempt >= maxAttempts) {
                    updateUI("\n    Max repair attempts reached. Build failed.");
                    throw buildError;
                }
                updateUI("\n     [Repair] Build failed. Analyzing error...");

                String projectCtx   = getProjectFilesContext(projectPath);
                String repairContext =
                    "ERROR LOGS:\n" + buildError.getMessage() + "\n\n"
                    + "CURRENT FILES (Full Structure):\n" + projectCtx + "\n\n"
                    + "FIX RULES:\n"
                    + "- If the error is 'package does not exist' for a third-party library, YOU MUST REMOVE the import and rewrite the code using standard built-in Java libraries (e.g., standard javax.swing). We do NOT use Maven/Gradle.\n"
                    + "- Maintain EXACTLY the same directory and package structure as shown in CURRENT FILES.\n"
                    + "- Use [FILE: path/to/FileName.java] tags with the relative path from the project root.\n"
                    + "- Always use Java Swing for GUI fixes.\n"
                    + "- EXPLAIN YOUR FIXES using detailed comments inside the code.\n"
                    + "- Add ALL missing imports\n"
                    + "- Use ABSOLUTE paths for file I/O\n"
                    + "- Consumer<Void> lambdas: (Void v) -> { ... }\n"
                    + "- Import javax.swing.tree.ExpandVetoException\n"
                    + "- Import java.awt.Image where used\n"
                    + "- Cast JOptionPane 7-arg result to String\n"
                    + "- Return COMPLETE [FILE: path/to/Name.java]...[ENDFILE] blocks only. No markdown code fences.";

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
                    updateUI("       Non-retryable AI error. Aborting."); break;
                }
                if (!repairCode.contains("[FILE:")) {
                    updateUI("       [Repair Guard]: No file blocks. Retrying with existing files...");
                    continue;
                }
                db.saveMessage(projectId, "REPAIR_" + attempt, repairCode);
                if (extractAndCreateFiles(repairCode, projectPath, TYPE_JAVA)) {
                    updateUI("\n        [Repair Applied]: Retrying build...");
                    currentCode = repairCode;
                    String nm = findMainClass(repairCode);
                    if (nm != null) mainClass = nm;
                }
            }
        }
    }

    private void runDevOpsPipeline(String absoluteProjectPath, String mainClassName) throws Exception {
        String basePath = absoluteProjectPath;
        updateUI("\n       [DevOps] Starting Java Build...");

        Path projectDir = Paths.get(basePath);
        StringBuilder sources = new StringBuilder();
        Files.walk(projectDir)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(p -> sources.append("\"")
                    .append(p.toAbsolutePath().toString().replace("\\", "/"))
                    .append("\"\n"));
        Files.writeString(projectDir.resolve("sources.txt"), sources.toString());

        updateUI("     Step 1: Compiling...");
        
        //      [Self-Evolution] Check if we are building Brain AI itself
        if ("com.forge.aiteam.MainApp".equals(mainClassName) || absoluteProjectPath.contains("Brain - Copy")) {
            updateUI("     [Self-Evolution] Brain AI detected! Running custom JavaFX build...");
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWin) {
                runCmd(basePath, "cmd", "/c", "javac", "--module-path", "javafx-sdk\\lib", 
                       "--add-modules", "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing", 
                       "-cp", "lib\\*;javafx-sdk\\lib\\*", "-d", "build\\classes", 
                       "src\\main\\java\\com\\forge\\aiteam\\*.java");
                
                updateUI("    [Self-Evolution] Compilation successful.");
                
                // Create restart.bat
                String bat = "@echo off\n"
                    + "echo Starting Brain AI...\n"
                    + "java --module-path \"javafx-sdk\\lib\" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing -cp \"build\\classes;lib\\*\" com.forge.aiteam.MainApp\n"
                    + "pause";
                Files.writeString(Paths.get(basePath, "restart.bat"), bat);
                
                // Log to CHANGELOG_BRAIN.md
                try {
                    Path changelog = Paths.get(basePath, "CHANGELOG_BRAIN.md");
                    String date = java.time.LocalDate.now().toString();
                    String logEntry = "\n## [Self-Evolution Update] — " + date + "\n### Changed\n- Autonomous update applied via Brain AI UI.\n### Agent responsible\n- CoderAgent via self-improvement loop\n";
                    Files.writeString(changelog, logEntry, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    updateUI("     [Self-Evolution] Logged update to CHANGELOG_BRAIN.md");
                } catch (Exception ignored) {}

                updateUI("     [Self-Evolution] Created restart.bat. Auto-launching the new version!");
                try {
                    new ProcessBuilder("cmd", "/c", "start", "restart.bat")
                        .directory(new File(basePath))
                        .start();
                } catch (Exception launchEx) {
                    updateUI("     [Self-Evolution] Could not auto-launch. Please run restart.bat manually.");
                }
                return; // Done
            }
        }

        runCmd(basePath, "javac", "@sources.txt");
        updateUI("    Compilation successful.");

        Files.createDirectories(projectDir.resolve("libs"));
        updateUI("     Step 2: Creating JAR...");
        runCmd(basePath, "jar", "cfe", "libs/App.jar", mainClassName, ".");
        updateUI("     JAR created: libs/App.jar");

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        updateUI("     Step 3: Generating native app...");

        List<String> jpkgArgs = new ArrayList<>(Arrays.asList(
            "jpackage",
            "--type", "app-image", "--name", "AI_App",
            "--input", "libs",     "--main-jar", "App.jar",
            "--main-class", mainClassName,
            "--add-modules", "java.desktop,java.base,java.logging"
        ));
        if (isWindows) jpkgArgs.add("--win-console");

        runCmd(basePath, jpkgArgs.toArray(new String[0]));
        updateUI("     Native .exe application generated inside AI_App folder.");
    }

    //                                                                                                                                                                                                                            
    // Utilities
    //                                                                                                                                                                                                                            
    private boolean extractAndCreateFiles(String aiOutput, String targetDirectoryPath, String projectType) throws Exception {
        Path   basePath    = Paths.get(targetDirectoryPath).toAbsolutePath().normalize();
        
        Pattern pattern = Pattern.compile("\\[FILE:\\s*(.*?)\\](.*?)\\[ENDFILE\\]", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(aiOutput);
        boolean found   = false;

        while (matcher.find()) {
            found = true;
            String originalPath = matcher.group(1).trim();
            String fileContent  = matcher.group(2).trim();

            // 1. Robust Markdown Cleaning: strip backticks and language identifiers
            fileContent = fileContent.replaceAll("(?i)^```[a-z]*\\n", "")
                                     .replaceAll("(?i)^```[a-z]*", "")
                                     .replaceAll("(?i)\\n```$", "")
                                     .replaceAll("(?i)```$", "");

            // 2. Normalize path and strip common prefixes added by confused agents
            String filePath = originalPath.replace("\\", "/");
            if (TYPE_JAVA.equals(projectType)) {
                filePath = filePath.replace("src/main/java/", "")
                                   .replace("src/", "");
            }

            // 3. Package-Aware Path Correction for Java files
            if (filePath.endsWith(".java")) {
                Matcher pkgMatcher = Pattern.compile("^package\\s+([\\w\\.]+);", Pattern.MULTILINE).matcher(fileContent);
                if (pkgMatcher.find()) {
                    String pkg     = pkgMatcher.group(1).trim();
                    String pkgPath = pkg.replace(".", "/");
                    String fileName = Paths.get(filePath).getFileName().toString();
                    
                    // Force the file into the correct package-derived directory
                    if (!filePath.startsWith(pkgPath)) {
                        String oldPath = filePath;
                        filePath = pkgPath + "/" + fileName;
                        updateUI("     [Auto-Correct] Fixed directory for " + fileName + " (mapped package '" + pkg + "' to '" + pkgPath + "')");
                    }
                }
            }

            Path fullPath = basePath.resolve(filePath).toAbsolutePath().normalize();
            
            // 4. Security Guard: Prevent writing outside project boundaries (Path Traversal)
            if (!fullPath.startsWith(basePath)) {
                updateUI("     [Security] Blocked attempt to write outside project root: " + originalPath);
                continue;
            }

            // Hard check against DatabaseConnection.java
            if (filePath.endsWith("DatabaseConnection.java")) {
                updateUI("     [Security Guard] Blocked modification of DatabaseConnection.java as per constraints.");
                continue;
            }

            // 5. Human-in-the-Loop Deletion Guard
            boolean isProtected = filePath.endsWith(".java") || filePath.startsWith("src/") || filePath.startsWith("lib/") || filePath.equals("BRAIN_AI_CONTEXT.md");
            if (Files.exists(fullPath) && isProtected) {
                java.util.concurrent.CompletableFuture<Boolean> approval = new java.util.concurrent.CompletableFuture<>();
                final String displayPath = filePath;
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("⚠️ DELETION REQUESTED — HUMAN APPROVAL REQUIRED");
                    alert.setHeaderText("You are about to modify/overwrite:\n  → " + displayPath);
                    alert.setContentText("Reason given by agent: Agent is applying an update.\n\nAre you absolutely sure? This cannot be undone.");
                    
                    ButtonType btnYes = new ButtonType("YES, DELETE/OVERWRITE IT", ButtonBar.ButtonData.OK_DONE);
                    ButtonType btnNo = new ButtonType("NO, KEEP IT", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(btnYes, btnNo);
                    
                    java.util.Optional<ButtonType> res = alert.showAndWait();
                    approval.complete(res.isPresent() && res.get() == btnYes);
                });
                try {
                    if (!approval.get()) {
                        updateUI("     [Guard] Modification of " + filePath + " aborted by user.");
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
            }

            Files.createDirectories(fullPath.getParent());
            Files.writeString(fullPath, fileContent);
            updateUI("     Created/Updated: " + fullPath);
        }
        if (!found) updateUI("       No [FILE] tags detected in the AI output.");
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
            updateUI("     Entry point: " + found);
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
                    return n.endsWith(".java") || n.endsWith(".html") || n.endsWith(".css") 
                        || n.endsWith(".js")   || n.endsWith(".json") || n.endsWith(".jsx") 
                        || n.endsWith(".xml")  || n.endsWith(".fxml") || n.endsWith(".bat")
                        || n.endsWith(".md")   || n.endsWith(".properties") || n.endsWith(".sh")
                        || n.endsWith(".txt");
                })
                .forEach(p -> {
                    try {
                        String relPath = Paths.get(projectPath).relativize(p).toString().replace("\\", "/");
                        ctx.append("[FILE: ").append(relPath).append("]\n")
                           .append(Files.readString(p)).append("\n[ENDFILE]\n\n");
                    } catch (java.io.IOException ignored) {}
                });
        } catch (java.io.IOException ignored) {}
        return ctx.toString();
    }

    private String runCmd(String directory, String... command) throws Exception {
        updateUI("     [System] Running: " + String.join(" ", command));
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
