/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  javafx.animation.Interpolator
 *  javafx.animation.RotateTransition
 *  javafx.application.Platform
 *  javafx.concurrent.Task
 *  javafx.fxml.FXML
 *  javafx.geometry.Insets
 *  javafx.scene.Node
 *  javafx.scene.control.Alert
 *  javafx.scene.control.Alert$AlertType
 *  javafx.scene.control.Button
 *  javafx.scene.control.ButtonBar$ButtonData
 *  javafx.scene.control.ButtonType
 *  javafx.scene.control.CheckBox
 *  javafx.scene.control.ComboBox
 *  javafx.scene.control.Dialog
 *  javafx.scene.control.Hyperlink
 *  javafx.scene.control.Label
 *  javafx.scene.control.ScrollBar
 *  javafx.scene.control.Separator
 *  javafx.scene.control.TextArea
 *  javafx.scene.control.TextField
 *  javafx.scene.control.TextInputDialog
 *  javafx.scene.layout.VBox
 *  javafx.scene.shape.Arc
 *  javafx.stage.DirectoryChooser
 *  javafx.util.Duration
 *  javafx.util.Pair
 */
package com.forge.aiteam;

import com.forge.aiteam.APIKeyManager;
import com.forge.aiteam.DatabaseConnection;
import com.forge.aiteam.GeminiService;
import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;
import javafx.util.Pair;

public class MainController {
    @FXML
    private TextArea outputArea;
    @FXML
    private TextField userInputField;
    @FXML
    private Button helloButton;
    @FXML
    private Button openBrowserButton;
    @FXML
    private Button settingsButton;
    @FXML
    private Arc spinnerArc;
    @FXML
    private Label consultLabel;
    @FXML
    private VBox consultOptionsBox;
    @FXML
    private VBox choicesBox;
    @FXML
    private TextField otherInputField;
    private RotateTransition spinnerAnim;
    private static final String TYPE_JAVA = "JAVA";
    private static final String TYPE_WEB = "WEB";
    private static final String TYPE_REACT = "REACT";
    private static final String PROJECTS_ROOT = System.getProperty("user.home") + File.separator + "BrainAI Projects";
    private volatile String lastWebProjectPath = null;
    private final Map<String, Integer> loopCounter = new HashMap<String, Integer>();
    private static final int LOOP_THRESHOLD = 3;
    private final BlockingQueue<String> waitForConsult = new LinkedBlockingQueue<String>(1);
    private volatile boolean inConsultMode = false;
    private volatile boolean userScrolledUp = false;
    private static final String[][] GEMINI_MODELS = new String[][]{{"gemini-3-flash-preview", "Gemini 3 Flash Preview (Latest & Fastest) \u26a1"}, {"gemini-2.0-flash", "Gemini 2.0 Flash (Advanced) \ud83e\udde0"}, {"gemini-1.5-flash", "Gemini 1.5 Flash (Stable/Fast) \ud83d\udd27"}, {"gemini-1.5-pro", "Gemini 1.5 Pro (Highest Quality) \ud83d\udc8e"}};

    @FXML
    public void initialize() {
        this.setupSpinner();
        this.setupSmartScroll();
        if (this.openBrowserButton != null) {
            this.openBrowserButton.setDisable(true);
        }
        if (this.consultLabel != null) {
            this.consultLabel.setVisible(false);
        }
    }

    private void setupSpinner() {
        if (this.spinnerArc == null) {
            return;
        }
        this.spinnerArc.setVisible(false);
        this.spinnerAnim = new RotateTransition(Duration.millis((double)900.0), (Node)this.spinnerArc);
        this.spinnerAnim.setByAngle(360.0);
        this.spinnerAnim.setCycleCount(-1);
        this.spinnerAnim.setInterpolator(Interpolator.LINEAR);
    }

    private void showSpinner(boolean bl) {
        Platform.runLater(() -> {
            if (this.spinnerArc == null) {
                return;
            }
            this.spinnerArc.setVisible(bl);
            if (bl) {
                this.spinnerAnim.play();
            } else {
                this.spinnerAnim.stop();
            }
        });
    }

    private void setupSmartScroll() {
        if (this.outputArea == null) {
            return;
        }
        this.outputArea.skinProperty().addListener((observableValue2, skin, skin2) -> {
            if (skin2 == null) {
                return;
            }
            ScrollBar scrollBar = (ScrollBar)this.outputArea.lookup(".scroll-bar:vertical");
            if (scrollBar == null) {
                return;
            }
            scrollBar.valueProperty().addListener((observableValue, number, number2) -> {
                double d = scrollBar.getMax();
                this.userScrolledUp = number2.doubleValue() < d - 0.01;
            });
        });
    }

    private void updateUI(String string) {
        Platform.runLater(() -> {
            this.outputArea.appendText(string + "\n");
            if (!this.userScrolledUp) {
                this.outputArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    private boolean checkLoop(String string, String string2) {
        String string3 = string + "::" + Integer.toHexString(string2.hashCode());
        int n = this.loopCounter.merge(string3, 1, Integer::sum);
        if (n >= 3) {
            this.updateUI("\n\ud83d\uded1 \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
            this.updateUI("   LOOP DETECTED: Agent [" + string + "] produced the same result " + n + " times in a row.");
            this.updateUI("   Last error / pattern:\n" + (String)(string2.length() > 500 ? string2.substring(0, 500) + "..." : string2));
            this.updateUI("   \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n");
            this.loopCounter.clear();
            return true;
        }
        return false;
    }

    private String enterConsultMode(String string, String string2) throws InterruptedException {
        this.inConsultMode = true;
        this.waitForConsult.clear();
        Platform.runLater(() -> {
            if (this.consultLabel != null) {
                this.consultLabel.setText("\u26a0\ufe0f Agent Paused \u2013 Type guidance below and press Send to resume");
                this.consultLabel.setVisible(true);
            }
            if (this.helloButton != null) {
                this.helloButton.setDisable(false);
            }
        });
        this.updateUI("\n\n\ud83d\uded1 \u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        if (string.contains("REVIEW") || string.contains("CONSULT")) {
            this.updateUI("   AGENT PAUSED: " + string);
        } else {
            this.updateUI("   LOOP DETECTED: Agent [" + string + "] produced the same result 3 times in a row.");
        }
        this.updateUI("   Last output / pattern:\n   " + (String)(string2.length() > 500 ? string2.substring(0, 500) + "..." : string2));
        this.updateUI("   The pipeline is PAUSED. Your execution context is preserved.");
        ArrayList<String> arrayList = new ArrayList<String>();
        Matcher matcher = Pattern.compile("\\[CHOICE:\\s*([^\\]]+)\\]", 2).matcher(string2);
        while (matcher.find()) {
            arrayList.add(matcher.group(1).trim());
        }
        if (!arrayList.isEmpty()) {
            Platform.runLater(() -> {
                this.choicesBox.getChildren().clear();
                for (String string : arrayList) {
                    CheckBox checkBox = new CheckBox(string);
                    this.choicesBox.getChildren().add((Object)checkBox);
                }
                this.consultOptionsBox.setVisible(true);
                this.consultOptionsBox.setManaged(true);
                this.userInputField.setVisible(false);
                this.userInputField.setManaged(false);
                this.otherInputField.clear();
            });
            this.updateUI("   \ud83d\udc49 Select one or more options below and press Send.");
        } else {
            this.updateUI("   \ud83d\udc49 Type your guidance or a manual fix in the input field and press Send.");
        }
        this.updateUI("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\n");
        this.showSpinner(false);
        String string3 = this.waitForConsult.take();
        this.inConsultMode = false;
        this.showSpinner(true);
        Platform.runLater(() -> {
            if (this.consultLabel != null) {
                this.consultLabel.setVisible(false);
            }
            this.consultOptionsBox.setVisible(false);
            this.consultOptionsBox.setManaged(false);
            this.userInputField.setVisible(true);
            this.userInputField.setManaged(true);
        });
        this.updateUI("\u25b6\ufe0f  Resuming pipeline with guidance: " + string3 + "\n");
        return string3;
    }

    private String askAgentResilient(GeminiService geminiService, String string, String string2, String object) throws InterruptedException {
        String string3;
        int n = 0;
        while (true) {
            boolean bl;
            boolean bl2;
            ++n;
            this.showSpinner(true);
            try {
                string3 = geminiService.askAgent(string2, (String)object);
            }
            catch (IllegalStateException illegalStateException) {
                this.showSpinner(false);
                this.updateUI("\n\ud83d\udd34 FATAL: " + illegalStateException.getMessage());
                this.updateUI("   \u2192 Open \u2699\ufe0f Settings and paste a valid Gemini API key, then try again.");
                throw new RuntimeException("NO_API_KEY: " + illegalStateException.getMessage(), illegalStateException);
            }
            this.showSpinner(false);
            if (string3 == null || string3.isBlank()) {
                this.updateUI("\u26a0\ufe0f [" + string + "] Empty response \u2014 retrying...");
                continue;
            }
            boolean bl3 = bl2 = string3.contains("No Gemini API key configured") || string3.contains("No Gemini API keys configured") || string3.contains("All API keys are invalid");
            if (bl2) {
                this.updateUI("\n\ud83d\udd34 FATAL: API key missing or all keys are invalid.");
                this.updateUI("   \u2192 Open \u2699\ufe0f Settings and paste your Gemini API key, then try again.");
                throw new RuntimeException("NO_API_KEY: " + string3);
            }
            if (!string3.startsWith("AI communication failed")) {
                if (this.checkLoop(string, string3)) {
                    String string4 = this.enterConsultMode(string, string3);
                    object = (String)object + "\n\nUSER GUIDANCE: " + string4;
                    this.loopCounter.clear();
                    continue;
                }
                return string3;
            }
            boolean bl4 = string3.contains("429") || string3.toLowerCase().contains("quota");
            boolean bl5 = bl = string3.contains("503") || string3.contains("500") || string3.contains("overloaded");
            if (!bl4 && !bl) break;
            long l = bl4 ? 65000L : (long)Math.pow(2.0, Math.min(n, 6)) * 1000L;
            Matcher matcher = Pattern.compile("retry in ([\\d\\.]+)s").matcher(string3);
            if (matcher.find()) {
                try {
                    l = (long)(Double.parseDouble(matcher.group(1)) * 1000.0) + 2000L;
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
            }
            long l2 = l;
            this.updateUI("\u23f3 [Rate Limit] Attempt " + n + " failed. Waiting " + l2 / 1000L + "s before retrying...");
            Thread.sleep(l);
        }
        this.updateUI("\u274c [Non-retryable AI Error]: " + string3);
        throw new RuntimeException("NON_RETRYABLE: " + string3);
    }

    /*
     * WARNING - void declaration
     */
    private String runParallelCoding(GeminiService geminiService, String string, String string2) throws Exception {
        void future;
        String[] stringArray = string2.split("(?=\\[SUBTASK\\s*\\d+\\])");
        if (stringArray.length <= 1) {
            return this.askAgentResilient(geminiService, "PARALLEL_CODER", string, string2);
        }
        int n = Math.min(stringArray.length, Math.max(APIKeyManager.getInstance().getKeyCount(), 2));
        ExecutorService executorService = Executors.newFixedThreadPool(n);
        ArrayList<Future<String>> arrayList = new ArrayList<Future<String>>();
        this.updateUI("\ud83d\udd00 [Parallel Coder] Dispatching " + stringArray.length + " sub-tasks on " + n + " threads...");
        Object stringBuilder = stringArray;
        int n2 = ((String[])stringBuilder).length;
        boolean bl = false;
        while (future < n2) {
            String executionException = stringBuilder[future];
            if (!executionException.trim().isEmpty()) {
                String string3 = executionException;
                arrayList.add(executorService.submit(() -> {
                    GeminiService geminiService = new GeminiService();
                    return this.askAgentResilient(geminiService, "PARALLEL_CODER", string, string4);
                }));
            }
            ++future;
        }
        executorService.shutdown();
        executorService.awaitTermination(10L, TimeUnit.MINUTES);
        stringBuilder = new StringBuilder();
        for (Future future2 : arrayList) {
            try {
                ((StringBuilder)stringBuilder).append((String)future2.get()).append("\n");
            }
            catch (ExecutionException executionException) {
                this.updateUI("\u26a0\ufe0f [Parallel sub-task failed]: " + executionException.getCause().getMessage());
            }
        }
        return ((StringBuilder)stringBuilder).toString();
    }

    private String detectProjectType(String string) {
        Matcher matcher = Pattern.compile("\\[TYPE:\\s*(\\w+)\\]", 2).matcher(string);
        if (matcher.find()) {
            String string2 = matcher.group(1).toUpperCase();
            if (string2.equals(TYPE_WEB)) {
                return TYPE_WEB;
            }
            if (string2.equals(TYPE_REACT)) {
                return TYPE_REACT;
            }
        }
        return TYPE_JAVA;
    }

    @FXML
    protected void onHelpButtonClick() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("How to Use Brain AI");
        alert.setHeaderText("Welcome to the AI Assembly Line!");
        alert.setContentText("Supports Java desktop apps AND web projects.\n\nJava example: 'Write a Swing prime calculator app'\nWeb example:  'Build a to-do list as a web page with local storage'\nNode example: 'Create a Node.js REST API with Express for a book list'\n\nThe Architect auto-detects the type. The correct Coder,\nTester, and DevOps agents then run automatically.\n\nAfter a web build, click '\ud83c\udf10' to preview it.\n\n\ud83d\udca1 TIP: Add multiple API keys in Settings for faster parallel processing!");
        alert.showAndWait();
    }

    @FXML
    protected void onLoadProjectButtonClick() {
        File file;
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select Brain AI Project Folder");
        File file2 = new File(PROJECTS_ROOT);
        if (file2.exists()) {
            directoryChooser.setInitialDirectory(file2);
        }
        if ((file = directoryChooser.showDialog(this.userInputField.getScene().getWindow())) != null) {
            this.userInputField.setText("[LOAD: " + file.getAbsolutePath() + "] ");
            this.userInputField.requestFocus();
            this.userInputField.positionCaret(this.userInputField.getText().length());
        }
    }

    @FXML
    protected void onClearButtonClick() {
        this.outputArea.clear();
        this.lastWebProjectPath = null;
        this.loopCounter.clear();
        this.userScrolledUp = false;
        this.updateUI("\ud83d\ude80 Engine cleared and ready...\n");
        if (this.openBrowserButton != null) {
            this.openBrowserButton.setDisable(true);
        }
    }

    @FXML
    protected void onSettingsButtonClick() {
        Dialog dialog = new Dialog();
        dialog.setTitle("Brain AI \u2014 Settings");
        dialog.setHeaderText("Configure API Keys and Model.\nAdd multiple keys separated by commas for parallel processing.");
        ButtonType buttonType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll((Object[])new ButtonType[]{buttonType, ButtonType.CANCEL});
        VBox vBox = new VBox(12.0);
        vBox.setPadding(new Insets(20.0));
        vBox.setMinWidth(500.0);
        Label label = new Label("Gemini API Keys (comma-separated for multiple keys):");
        label.setStyle("-fx-font-weight: bold;");
        String string = APIKeyManager.getInstance().getKeyCount() > 0 ? APIKeyManager.getInstance().getKeysCsv() : System.getProperty("BRAIN_API_KEY", "");
        TextArea textArea = new TextArea(string);
        textArea.setPromptText("AIza...key1, AIza...key2, AIza...key3");
        textArea.setPrefWidth(480.0);
        textArea.setPrefRowCount(3);
        textArea.setWrapText(true);
        Hyperlink hyperlink = new Hyperlink("Get a free key at aistudio.google.com");
        hyperlink.setOnAction(actionEvent -> {
            try {
                Desktop.getDesktop().browse(new URI("https://aistudio.google.com/app/apikey"));
            }
            catch (Exception exception) {
                // empty catch block
            }
        });
        Label label2 = new Label("Key Pool Status:");
        label2.setStyle("-fx-font-weight: bold;");
        Label label3 = new Label(APIKeyManager.getInstance().getStatusSummary());
        label3.setStyle("-fx-font-size: 11px; -fx-text-fill: #333;");
        label3.setWrapText(true);
        Label label4 = new Label("Gemini Model:");
        label4.setStyle("-fx-font-weight: bold;");
        ComboBox comboBox = new ComboBox();
        String string2 = System.getProperty("BRAIN_MODEL", "gemini-2.5-flash");
        int n = 0;
        for (int i = 0; i < GEMINI_MODELS.length; ++i) {
            comboBox.getItems().add((Object)GEMINI_MODELS[i][1]);
            if (!GEMINI_MODELS[i][0].equals(string2)) continue;
            n = i;
        }
        comboBox.getSelectionModel().select(n);
        comboBox.setPrefWidth(480.0);
        Label label5 = new Label("Flash is recommended for most tasks. Pro gives higher quality but may be slower.");
        label5.setStyle("-fx-font-size: 10px; -fx-text-fill: #888;");
        label5.setWrapText(true);
        vBox.getChildren().addAll((Object[])new Node[]{label, textArea, hyperlink, new Separator(), label2, label3, new Separator(), label4, comboBox, label5});
        dialog.getDialogPane().setContent((Node)vBox);
        dialog.setResultConverter(buttonType2 -> {
            if (buttonType2 != buttonType) {
                return null;
            }
            int n = comboBox.getSelectionModel().getSelectedIndex();
            String string = n >= 0 && n < GEMINI_MODELS.length ? GEMINI_MODELS[n][0] : "gemini-2.5-flash";
            return new Pair((Object)textArea.getText().trim(), (Object)string);
        });
        dialog.showAndWait().ifPresent(pair -> {
            String string = (String)pair.getKey();
            String string2 = (String)pair.getValue();
            APIKeyManager.getInstance().setKeys(string);
            String string3 = string.split(",")[0].trim();
            if (!string3.isEmpty()) {
                System.setProperty("BRAIN_API_KEY", string3);
            }
            System.setProperty("BRAIN_MODEL", string2);
            try {
                Closeable closeable;
                String string4 = System.getProperty("user.home") + File.separator + ".brainai";
                Files.createDirectories(Paths.get(string4, new String[0]), new FileAttribute[0]);
                Properties properties = new Properties();
                File file = new File(string4 + File.separator + "config.properties");
                if (file.exists()) {
                    closeable = new FileInputStream(file);
                    try {
                        properties.load((InputStream)closeable);
                    }
                    finally {
                        ((FileInputStream)closeable).close();
                    }
                }
                properties.setProperty("GOOGLE_API_KEY", string);
                properties.setProperty("GEMINI_MODEL", string2);
                closeable = new FileOutputStream(file);
                try {
                    properties.store((OutputStream)closeable, "Brain AI Configuration");
                }
                finally {
                    ((FileOutputStream)closeable).close();
                }
                this.updateUI("\u2705 Settings saved. Keys: " + APIKeyManager.getInstance().getKeyCount() + " | Model: " + string2);
            }
            catch (Exception exception) {
                this.updateUI("\u26a0\ufe0f Settings applied but could not save to file: " + exception.getMessage());
            }
        });
    }

    @FXML
    protected void onOpenBrowserButtonClick() {
        if (this.lastWebProjectPath == null) {
            this.updateUI("\u26a0\ufe0f No web project built yet.");
            return;
        }
        try {
            File file = new File(this.lastWebProjectPath, "index.html");
            if (!file.exists()) {
                this.updateUI("\u26a0\ufe0f index.html not found at: " + this.lastWebProjectPath);
                return;
            }
            Desktop.getDesktop().browse(file.toURI());
            this.updateUI("\ud83c\udf10 Opened in browser: " + file.getAbsolutePath());
        }
        catch (Exception exception) {
            this.updateUI("\u26a0\ufe0f Could not open browser: " + exception.getMessage());
        }
    }

    @FXML
    protected void onHelloButtonClick() {
        Optional<Object> optional;
        TextInputDialog textInputDialog;
        if (this.inConsultMode) {
            String string;
            String string2;
            Object object;
            StringBuilder stringBuilder = new StringBuilder();
            if (this.choicesBox != null && !this.choicesBox.getChildren().isEmpty()) {
                object = this.choicesBox.getChildren().iterator();
                while (object.hasNext()) {
                    string2 = (Node)object.next();
                    if (!(string2 instanceof CheckBox) || !(string = (CheckBox)string2).isSelected()) continue;
                    if (stringBuilder.length() > 0) {
                        stringBuilder.append(", ");
                    }
                    stringBuilder.append(string.getText());
                }
            }
            if (!((String)(object = this.otherInputField.getText().trim())).isEmpty()) {
                if (stringBuilder.length() > 0) {
                    stringBuilder.append(" | Guidance: ");
                }
                stringBuilder.append((String)object);
            }
            if (!(string2 = this.userInputField.getText().trim()).isEmpty() && !string2.equals(object)) {
                if (stringBuilder.length() > 0) {
                    stringBuilder.append(" | Input: ");
                }
                stringBuilder.append(string2);
            }
            if ((string = stringBuilder.toString().trim()).isEmpty()) {
                this.updateUI("\u26a0\ufe0f Please select an option or type your guidance first.");
                return;
            }
            this.userInputField.clear();
            this.otherInputField.clear();
            this.updateUI("\u25b6\ufe0f Sending guidance to Brain...");
            Platform.runLater(() -> {
                if (this.helloButton != null) {
                    this.helloButton.setDisable(true);
                }
            });
            try {
                this.waitForConsult.clear();
                boolean bl = this.waitForConsult.offer(string, 5L, TimeUnit.SECONDS);
                if (!bl) {
                    this.updateUI("\u26a0\ufe0f Brain is busy or not responding (timeout). Please try again.");
                    Platform.runLater(() -> {
                        if (this.helloButton != null) {
                            this.helloButton.setDisable(false);
                        }
                    });
                }
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        String string = this.userInputField.getText();
        if (string == null || string.trim().isEmpty()) {
            this.updateUI("\u26a0\ufe0f Please type a task first.");
            return;
        }
        String string3 = null;
        Matcher matcher = Pattern.compile("\\[LOAD:\\s*(.*?)\\]", 2).matcher(string);
        if (matcher.find()) {
            string3 = matcher.group(1).trim();
            string = matcher.replaceFirst("").trim();
            this.updateUI("\ud83d\udcc1 [Load Project] Attempting to resume project at: " + string3);
        }
        final String string4 = string;
        final String string5 = string3;
        Object object = null;
        if (string3 == null) {
            textInputDialog = new TextInputDialog("");
            textInputDialog.setTitle("Project Name");
            textInputDialog.setHeaderText("What do you want to name your project and website?");
            textInputDialog.setContentText("Name (leave blank to auto-generate):");
            optional = textInputDialog.showAndWait();
            if (optional.isPresent() && !((String)optional.get()).trim().isEmpty()) {
                object = ((String)optional.get()).trim().replaceAll("[^a-zA-Z0-9_\\-]", "-");
            }
        }
        textInputDialog = object;
        this.outputArea.setText("\ud83d\ude80 Starting AI Assembly Line...\n\n");
        this.userScrolledUp = false;
        this.loopCounter.clear();
        this.userInputField.clear();
        if (this.helloButton != null) {
            this.helloButton.setDisable(true);
        }
        if (this.openBrowserButton != null) {
            this.openBrowserButton.setDisable(true);
        }
        optional = new Task<Void>((String)textInputDialog){
            final /* synthetic */ String val$finalUserChosenName;
            {
                this.val$finalUserChosenName = string3;
            }

            /*
             * WARNING - Removed try catching itself - possible behaviour change.
             */
            protected Void call() throws Exception {
                try {
                    String string;
                    Object object;
                    Object object2;
                    Object object3;
                    String string2;
                    Path path;
                    DatabaseConnection databaseConnection = new DatabaseConnection();
                    GeminiService geminiService = new GeminiService();
                    int n = databaseConnection.createAutoProject(string4);
                    MainController.this.updateUI("\ud83c\udd94 Project ID: " + n);
                    Object object4 = "";
                    Object object5 = null;
                    Object object6 = null;
                    if (string5 != null) {
                        path = Paths.get(string5, new String[0]);
                        if (Files.exists(path, new LinkOption[0]) && Files.isDirectory(path, new LinkOption[0])) {
                            object5 = path.toAbsolutePath().normalize().toString();
                            object6 = path.getFileName().toString();
                            MainController.this.updateUI("\ud83d\udcc2 [Load Project] Read existing files from: " + (String)object5);
                            string2 = MainController.this.getProjectFilesContext((String)object5);
                            object4 = "\n\nEXISTING PROJECT FILES:\n" + string2 + "\nIMPORTANT: Please modify these existing files to fulfill the task.";
                        } else {
                            MainController.this.updateUI("\u26a0\ufe0f [Load Project] Invalid directory: " + string5 + " - starting fresh.");
                        }
                    }
                    if (object5 == null) {
                        object6 = this.val$finalUserChosenName != null ? this.val$finalUserChosenName : "Task_" + System.currentTimeMillis();
                        object5 = PROJECTS_ROOT + File.separator + (String)object6;
                    }
                    path = object5;
                    string2 = object6;
                    MainController.this.updateUI("\n\ud83c\udfd7\ufe0f [Architect] Analyzing task...");
                    String string3 = DatabaseConnection.getPromptByRole("ARCHITECT");
                    String string42 = string4 + (String)object4;
                    String string52 = MainController.this.askAgentResilient(geminiService, "ARCHITECT", string3, string42);
                    MainController.this.updateUI("--- ARCHITECT'S PLAN ---\n" + string52 + "\n");
                    databaseConnection.saveMessage(n, "ARCHITECT", string52);
                    MainController.this.updateUI("\n\u23f3 [Consultation] Please review the Architect's plan above.");
                    MainController.this.updateUI("   Choose an option, provide feedback, or type 'Proceed' to start coding.");
                    String string6 = MainController.this.enterConsultMode("ARCHITECT_REVIEW", string52);
                    MainController.this.updateUI("\u2705 [Consultation] Guidance received: " + string6);
                    if (!string6.equalsIgnoreCase("continue") && !string6.equalsIgnoreCase("proceed")) {
                        MainController.this.updateUI("\n\ud83e\udd14 [Architect] Refining plan based on your guidance...");
                        object3 = string3 + "\n\nThe user has provided guidance/choices for your initial plan.\nIncorporate this feedback and produce a FINAL, COMPLETE technical plan.\nThe coder will follow this final plan exactly. No more questions.";
                        object2 = "Original Task: " + string4 + "\n\nInitial Plan:\n" + string52 + "\n\nUser Guidance:\n" + string6;
                        string52 = MainController.this.askAgentResilient(geminiService, "ARCHITECT", (String)object3, (String)object2);
                        MainController.this.updateUI("--- REFINED ARCHITECT'S PLAN ---\n" + string52 + "\n");
                        databaseConnection.saveMessage(n, "ARCHITECT_REFINED", string52);
                    } else {
                        MainController.this.updateUI("\u27a1\ufe0f [Consultation] Proceeding with original plan.");
                    }
                    object3 = MainController.this.detectProjectType(string52);
                    MainController.this.updateUI("\ud83d\udd16 Project type: " + (String)object3);
                    if (((String)object3).equals(MainController.TYPE_REACT) && string5 == null) {
                        Files.createDirectories(Paths.get((String)((Object)path), new String[0]), new FileAttribute[0]);
                        MainController.this.updateUI("\u26a1 [Vite] Pre-initializing React project in: " + (String)((Object)path));
                        boolean bl = System.getProperty("os.name").toLowerCase().contains("win");
                        try {
                            if (bl) {
                                MainController.this.runCmd((String)((Object)path), "cmd", "/c", "npm", "create", "vite@latest", ".", "--yes", "--", "--template", "react");
                            } else {
                                MainController.this.runCmd((String)((Object)path), "npm", "create", "vite@latest", ".", "--yes", "--", "--template", "react");
                            }
                            MainController.this.updateUI("\u2705 [Vite] Boilerplate generated successfully.");
                            object = MainController.this.getProjectFilesContext((String)((Object)path));
                            object4 = (String)object4 + "\n\nEXISTING VITE FILES (MODIFY THESE):\n" + (String)object + "\nIMPORTANT: Please modify the existing Vite boilerplate files (like src/App.jsx, src/index.css, package.json). DO NOT write react-scripts.";
                        }
                        catch (Exception exception) {
                            MainController.this.updateUI("\u26a0\ufe0f [Vite] Failed to pre-initialize: " + exception.getMessage());
                        }
                    }
                    Object object7 = ((String)object3).equals(MainController.TYPE_REACT) ? "REACT_CODER" : (object2 = ((String)object3).equals(MainController.TYPE_WEB) ? "WEB_CODER" : "CODER");
                    object = ((String)object3).equals(MainController.TYPE_REACT) ? MainController.this.buildReactCoderContext(string4, string52) + (String)object4 : (((String)object3).equals(MainController.TYPE_WEB) ? MainController.this.buildWebCoderContext(string4, string52) + (String)object4 : MainController.this.buildJavaCoderContext(string4, string52) + (String)object4);
                    MainController.this.updateUI("\ud83d\udcbb [" + (String)object2 + "] Writing code" + (APIKeyManager.getInstance().getKeyCount() > 1 ? " (parallel mode)..." : "..."));
                    String string7 = DatabaseConnection.getPromptByRole((String)object2);
                    String string8 = APIKeyManager.getInstance().getKeyCount() > 1 ? MainController.this.runParallelCoding(geminiService, string7, (String)object) : MainController.this.askAgentResilient(geminiService, (String)object2, string7, (String)object);
                    if (string8.startsWith("AI communication failed")) {
                        MainController.this.updateUI("\n\u274c CRITICAL ERROR (non-retryable): " + string8);
                        Void void_ = null;
                        return void_;
                    }
                    MainController.this.updateUI("--- CODER'S OUTPUT ---\n" + string8 + "\n");
                    databaseConnection.saveMessage(n, "CODER", string8);
                    if (string5 == null) {
                        Files.createDirectories(Paths.get((String)((Object)path), new String[0]), new FileAttribute[0]);
                    }
                    MainController.this.updateUI("\ud83d\udcc1 Project Folder: " + (String)((Object)path));
                    MainController.this.extractAndCreateFiles(string8, (String)((Object)path), (String)object3);
                    MainController.this.updateUI("\n\ud83d\udd0d [Tester] Reviewing code...");
                    String string9 = DatabaseConnection.getPromptByRole("TESTER");
                    String string10 = "PROJECT TYPE: " + (String)object3 + "\n\n" + string8;
                    String string11 = MainController.this.askAgentResilient(geminiService, "TESTER", string9, string10);
                    MainController.this.updateUI("--- TESTER'S REVIEW ---\n" + string11);
                    databaseConnection.saveMessage(n, "TESTER", string11);
                    if (string11.contains("[FILE:")) {
                        MainController.this.extractAndCreateFiles(string11, (String)((Object)path), (String)object3);
                    } else {
                        MainController.this.updateUI("\u26a0\ufe0f [Tester] No file blocks \u2014 keeping Coder's output.");
                    }
                    String string12 = string = string11.contains("[FILE:") ? string11 : string8;
                    if (((String)object3).equals(MainController.TYPE_WEB) || ((String)object3).equals(MainController.TYPE_REACT)) {
                        MainController.this.runWebPipeline((String)((Object)path), (String)((Object)path), string, databaseConnection, geminiService, n);
                        MainController.this.lastWebProjectPath = path;
                        Platform.runLater(() -> {
                            if (MainController.this.openBrowserButton != null) {
                                MainController.this.openBrowserButton.setDisable(false);
                            }
                        });
                        MainController.this.autoStartWebServer((String)((Object)path), (String)object3);
                    } else {
                        MainController.this.runJavaPipeline((String)((Object)path), (String)((Object)path), string, databaseConnection, geminiService, n);
                    }
                    MainController.this.generateReadme(geminiService, databaseConnection, n, (String)((Object)path), (String)object3, string4, string52);
                    MainController.this.updateUI("\n\u2728 ENTIRE PIPELINE COMPLETE!");
                }
                catch (Exception exception) {
                    MainController.this.updateUI("\n\u274c Pipeline failed: " + exception.getMessage());
                    exception.printStackTrace();
                }
                finally {
                    MainController.this.showSpinner(false);
                    Platform.runLater(() -> {
                        if (MainController.this.helloButton != null) {
                            MainController.this.helloButton.setDisable(false);
                        }
                    });
                }
                return null;
            }
        };
        Thread thread = new Thread((Runnable)((Object)optional));
        thread.setDaemon(true);
        thread.start();
    }

    private void generateReadme(GeminiService geminiService, DatabaseConnection databaseConnection, int n, String string, String string2, String string3, String string4) throws Exception {
        this.updateUI("\n\ud83d\udcdd [DevOps] Generating README...");
        String string5 = DatabaseConnection.getPromptByRole("DEVOPS");
        String string6 = "PROJECT TYPE: " + string2 + "\nTASK: " + string3 + "\n\nARCHITECT PLAN:\n" + string4 + "\n\nCURRENT FILES IN PROJECT:\n" + this.listProjectFiles(string);
        String string7 = this.askAgentResilient(geminiService, "DEVOPS", string5, string6);
        Matcher matcher = Pattern.compile("\\[FILE:\\s*README\\.md\\](.*?)\\[ENDFILE\\]", 34).matcher(string7);
        String string8 = matcher.find() ? matcher.group(1).trim() : string7;
        Path path = Paths.get(string, "README.md");
        Files.writeString(path, (CharSequence)string8, new OpenOption[0]);
        databaseConnection.saveMessage(n, "DEVOPS", string8);
        this.updateUI("\ud83d\udcc4 README.md written to: " + String.valueOf(path));
    }

    private String buildJavaCoderContext(String string, String string2) {
        return "Task: " + string + "\nPlan:\n" + string2 + "\nIMPORTANT: Use [FILE: path/to/Name.java] and [ENDFILE] tags. DO NOT use markdown.\n- ALWAYS use Java Swing for GUI components.\n- COMMENT EVERY LINE or logical block to explain the logic clearly.\n- Include ALL necessary imports at the top of EVERY file.\n- Use ABSOLUTE paths for any file I/O. Call System.getProperty(\"user.dir\") first.\n- Consumer<Void> lambdas: use (Void v) -> { ... } not () -> { ... }.\n- Import javax.swing.tree.ExpandVetoException where needed.\n- Import java.awt.Image in any file using Image.\n- JOptionPane 7-arg showInputDialog returns Object \u2014 cast to String.\n- Provide FULL class definitions ready to compile.";
    }

    private String buildWebCoderContext(String string, String string2) {
        return "Task: " + string + "\nPlan:\n" + string2 + "\nIMPORTANT: Use [FILE: Name.ext] and [ENDFILE] tags. DO NOT use markdown.\n- Always create at least: index.html, style.css, script.js (or app.js for Node).\n- For Node.js: include package.json with a 'start' script.\n- Use __dirname for all file paths in Node.js code.\n- Use relative paths for all script/link src attributes in HTML.\n- Do NOT use ES module import/export for browser projects.\n- CSS must be complete in style.css. JS must be in .js files.\n- Provide production-ready code, not placeholders.";
    }

    private String buildReactCoderContext(String string, String string2) {
        return "Task: " + string + "\nPlan:\n" + string2 + "\nIMPORTANT: Use [FILE: Name.ext] and [ENDFILE] tags. DO NOT use markdown.\n- You MUST create all Vite boilerplate files: package.json, vite.config.js, index.html, src/main.jsx, src/App.jsx, and CSS.\n- In package.json, ensure scripts include 'dev': 'vite --open'.\n- Provide production-ready React code.";
    }

    private void runWebPipeline(String string, String string2, String string3, DatabaseConnection databaseConnection, GeminiService geminiService, int n) throws Exception {
        int n2 = 10;
        int n3 = 0;
        boolean bl = false;
        while (n3 < n2 && !bl) {
            int n4 = ++n3;
            try {
                this.updateUI("\n\ud83c\udf10 [Web DevOps] Validation attempt " + n4 + "...");
                this.runWebValidation(string2);
                bl = true;
                this.updateUI("\u2705 Web project validated on attempt " + n3 + ".");
                this.updateUI("\ud83d\udcc2 Project ready at: " + string2);
            }
            catch (Exception exception) {
                if (n3 >= n2) {
                    this.updateUI("\n\u274c Max web repair attempts reached.");
                    throw exception;
                }
                this.updateUI("\n\ud83d\udd27 [Web Repair] Issue found. Asking AI to fix...");
                String string4 = this.getProjectFilesContext(string2);
                String string5 = "Issue found:\n" + exception.getMessage() + "\n\nCURRENT FILES:\n" + string4 + "\n\nFix the issues and return COMPLETE corrected files in [FILE: Name.ext]...[ENDFILE] blocks.";
                String string6 = this.askAgentResilient(geminiService, "TESTER", DatabaseConnection.getPromptByRole("TESTER"), string5);
                if (!string6.contains("[FILE:")) {
                    this.updateUI("\u26a0\ufe0f [Web Repair Guard]: No file blocks. Retrying...");
                    continue;
                }
                databaseConnection.saveMessage(n, "WEB_REPAIR_" + n3, string6);
                if (!this.extractAndCreateFiles(string6, string2, TYPE_WEB)) continue;
                this.updateUI("\n\ud83d\udee0\ufe0f [Web Repair Applied]: Retrying validation...");
            }
        }
    }

    private void runWebValidation(String string) throws Exception {
        boolean bl;
        Object object;
        this.updateUI("\n\ud83c\udf10 [Web DevOps] Validating web project...");
        Path path2 = null;
        for (String object2 : new String[]{"index.html", "public/index.html"}) {
            object = Paths.get(string, object2);
            if (!Files.exists((Path)object, new LinkOption[0])) continue;
            path2 = object;
            break;
        }
        boolean bl2 = bl = Files.exists(Paths.get(string, "server.js"), new LinkOption[0]) && Files.isDirectory(Paths.get(string, "views"), new LinkOption[0]) && Files.walk(Paths.get(string, "views"), new FileVisitOption[0]).anyMatch(path -> {
            String string = path.toString().toLowerCase();
            return string.endsWith(".ejs") || string.endsWith(".pug") || string.endsWith(".hbs") || string.endsWith(".html");
        });
        if (path2 == null && !bl) {
            throw new Exception("index.html not found in root or public/, and no server-side views/ templates detected in: " + string);
        }
        if (path2 != null) {
            this.updateUI("\u2705 index.html found: " + String.valueOf(path2));
            String string2 = Files.readString(path2);
            Path path3 = Paths.get(string, "public");
            Path path4 = path2.getParent();
            object = Pattern.compile("(?:src|href)=[\"']([^\"']+)[\"']", 2);
            Matcher matcher = ((Pattern)object).matcher(string2);
            StringBuilder stringBuilder = new StringBuilder();
            while (matcher.find()) {
                boolean bl3;
                String string3;
                String string4 = matcher.group(1).trim();
                if (string4.startsWith("http") || string4.startsWith("//") || string4.startsWith("#") || string4.startsWith("data:")) continue;
                String string5 = string3 = string4.contains("?") ? string4.substring(0, string4.indexOf(63)) : string4;
                if (string3.contains("#")) {
                    string3 = string3.substring(0, string3.indexOf(35));
                }
                if (string3.isEmpty()) continue;
                if (string3.startsWith("/")) {
                    String string6 = string3.substring(1);
                    bl3 = Files.exists(path3.resolve(string6), new LinkOption[0]) || Files.exists(Paths.get(string, string6), new LinkOption[0]);
                } else {
                    boolean bl4 = bl3 = Files.exists(path4.resolve(string3), new LinkOption[0]) || Files.exists(Paths.get(string, string3), new LinkOption[0]);
                }
                if (bl3) continue;
                stringBuilder.append("  - Missing: ").append(string4).append("\n");
            }
            if (stringBuilder.length() > 0) {
                throw new Exception("Broken asset references in " + String.valueOf(path2.getFileName()) + ":\n" + String.valueOf(stringBuilder));
            }
            this.updateUI("\u2705 All asset references resolved.");
        } else {
            this.updateUI("\u2705 Server-side rendered Node.js project (EJS/Pug/HBS) \u2014 no static index.html required.");
        }
        Path path4 = Paths.get(string, "package.json");
        if (Files.exists(path4, new LinkOption[0])) {
            this.updateUI("\ud83d\udce6 Node.js project detected. Running npm install...");
            boolean bl5 = System.getProperty("os.name").toLowerCase().contains("win");
            if (bl5) {
                this.runCmd(string, "cmd", "/c", "npm", "install");
            } else {
                this.runCmd(string, "npm", "install");
            }
            this.updateUI("\u2705 npm install complete.");
            this.updateUI("\u25b6\ufe0f  To run: cd " + string + " && npm run dev (or npm start)");
        }
    }

    private void autoStartWebServer(String string, String string2) {
        try {
            File file;
            boolean bl = System.getProperty("os.name").toLowerCase().contains("win");
            Path path = Paths.get(string, "package.json");
            if (Files.exists(path, new LinkOption[0])) {
                String string3;
                this.updateUI("\ud83d\ude80 Auto-starting Node/React server...");
                String string4 = Files.readString(path);
                String string5 = string3 = string4.contains("\"dev\":") ? "npm run dev" : "npm start";
                if (bl) {
                    new ProcessBuilder("cmd", "/c", "start", "cmd", "/k", string3).directory(new File(string)).start();
                } else {
                    new ProcessBuilder("sh", "-c", string3 + " &").directory(new File(string)).start();
                }
            } else if (string2.equals(TYPE_WEB) && (file = new File(string, "index.html")).exists()) {
                this.updateUI("\ud83d\ude80 Opening static index.html in browser...");
                Desktop.getDesktop().browse(file.toURI());
            }
        }
        catch (Exception exception) {
            this.updateUI("\u26a0\ufe0f Could not auto-start web server: " + exception.getMessage());
        }
    }

    private void runJavaPipeline(String string, String string2, String string3, DatabaseConnection databaseConnection, GeminiService geminiService, int n) throws Exception {
        if (string3 == null || string3.isEmpty()) {
            this.updateUI("\n\u26a0\ufe0f No code to build.");
            return;
        }
        String string4 = string3;
        String string5 = this.findMainClass(string4);
        int n2 = 10;
        int n3 = 0;
        boolean bl = false;
        while (n3 < n2 && !bl) {
            int n4 = ++n3;
            try {
                if (string5 == null) {
                    this.updateUI("\n\ud83d\udd0e [Repair] No main class. Asking AI...");
                    String string6 = this.askAgentResilient(geminiService, "CODER", "No 'public static void main' found. Add an entry point and return COMPLETE [FILE: Name.java]...[ENDFILE] blocks.", string4);
                    if (string6.contains("[FILE:")) {
                        this.extractAndCreateFiles(string6, string2, TYPE_JAVA);
                        string5 = this.findMainClass(string6);
                        string4 = string6;
                    }
                    if (string5 == null) {
                        this.updateUI("\u26a0\ufe0f Still no main class. Aborting.");
                        break;
                    }
                }
                this.updateUI("\n\u2699\ufe0f [DevOps] Build attempt " + n4 + "...");
                this.runDevOpsPipeline(string2, string5);
                bl = true;
                this.updateUI("\u2705 Build complete on attempt " + n3 + ".");
            }
            catch (Exception exception) {
                String string7;
                if (n3 >= n2) {
                    this.updateUI("\n\u274c Max repair attempts reached. Build failed.");
                    throw exception;
                }
                this.updateUI("\n\ud83d\udd27 [Repair] Build failed. Analyzing error...");
                String string8 = this.getProjectFilesContext(string2);
                String string9 = "ERROR LOGS:\n" + exception.getMessage() + "\n\nCURRENT FILES (Full Structure):\n" + string8 + "\n\nFIX RULES:\n- If the error is 'package does not exist' for a third-party library, YOU MUST REMOVE the import and rewrite the code using standard built-in Java libraries (e.g., standard javax.swing). We do NOT use Maven/Gradle.\n- Maintain EXACTLY the same directory and package structure as shown in CURRENT FILES.\n- Use [FILE: path/to/FileName.java] tags with the relative path from the project root.\n- Always use Java Swing for GUI fixes.\n- EXPLAIN YOUR FIXES using detailed comments inside the code.\n- Add ALL missing imports\n- Use ABSOLUTE paths for file I/O\n- Consumer<Void> lambdas: (Void v) -> { ... }\n- Import javax.swing.tree.ExpandVetoException\n- Import java.awt.Image where used\n- Cast JOptionPane 7-arg result to String\n- Return COMPLETE [FILE: path/to/Name.java]...[ENDFILE] blocks only. No markdown code fences.";
                if (this.checkLoop("REPAIR", exception.getMessage())) {
                    string7 = this.enterConsultMode("REPAIR", exception.getMessage());
                    string9 = string9 + "\n\nUSER GUIDANCE: " + string7;
                    this.loopCounter.clear();
                }
                if ((string7 = this.askAgentResilient(geminiService, "TESTER", DatabaseConnection.getPromptByRole("TESTER"), string9)).startsWith("AI communication failed") && !string7.contains("429") && !string7.contains("quota")) {
                    this.updateUI("\u26a0\ufe0f Non-retryable AI error. Aborting.");
                    break;
                }
                if (!string7.contains("[FILE:")) {
                    this.updateUI("\u26a0\ufe0f [Repair Guard]: No file blocks. Retrying with existing files...");
                    continue;
                }
                databaseConnection.saveMessage(n, "REPAIR_" + n3, string7);
                if (!this.extractAndCreateFiles(string7, string2, TYPE_JAVA)) continue;
                this.updateUI("\n\ud83d\udee0\ufe0f [Repair Applied]: Retrying build...");
                string4 = string7;
                String string10 = this.findMainClass(string7);
                if (string10 == null) continue;
                string5 = string10;
            }
        }
    }

    private void runDevOpsPipeline(String string, String string2) throws Exception {
        boolean bl;
        String string3 = string;
        this.updateUI("\n\u2699\ufe0f [DevOps] Starting Java Build...");
        Path path2 = Paths.get(string3, new String[0]);
        StringBuilder stringBuilder = new StringBuilder();
        Files.walk(path2, new FileVisitOption[0]).filter(path -> path.toString().endsWith(".java")).forEach(path -> stringBuilder.append("\"").append(path.toAbsolutePath().toString().replace("\\", "/")).append("\"\n"));
        Files.writeString(path2.resolve("sources.txt"), (CharSequence)stringBuilder.toString(), new OpenOption[0]);
        this.updateUI("\ud83d\udd28 Step 1: Compiling...");
        if ("com.forge.aiteam.MainApp".equals(string2) || string.contains("Brain - Copy")) {
            this.updateUI("\ud83e\udde0 [Self-Evolution] Brain AI detected! Running custom JavaFX build...");
            bl = System.getProperty("os.name").toLowerCase().contains("win");
            if (bl) {
                this.runCmd(string3, "cmd", "/c", "javac", "--module-path", "javafx-sdk\\lib", "--add-modules", "javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing", "-cp", "lib\\*;javafx-sdk\\lib\\*", "-d", "build\\classes", "src\\main\\java\\com\\forge\\aiteam\\*.java");
                this.updateUI("\u2705 [Self-Evolution] Compilation successful.");
                String string4 = "@echo off\necho Starting Brain AI...\njava --module-path \"javafx-sdk\\lib\" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing -cp \"build\\classes;lib\\*\" com.forge.aiteam.MainApp\npause";
                Files.writeString(Paths.get(string3, "restart.bat"), (CharSequence)string4, new OpenOption[0]);
                this.updateUI("\ud83d\ude80 [Self-Evolution] Created restart.bat. Run this to launch the new version!");
                return;
            }
        }
        this.runCmd(string3, "javac", "@sources.txt");
        this.updateUI("\u2705 Compilation successful.");
        Files.createDirectories(path2.resolve("libs"), new FileAttribute[0]);
        this.updateUI("\ud83d\udd28 Step 2: Creating JAR...");
        this.runCmd(string3, "jar", "cfe", "libs/App.jar", string2, ".");
        this.updateUI("\ud83d\udce6 JAR created: libs/App.jar");
        bl = System.getProperty("os.name").toLowerCase().contains("win");
        this.updateUI("\ud83d\udd28 Step 3: Generating native app...");
        ArrayList<String> arrayList = new ArrayList<String>(Arrays.asList("jpackage", "--type", "app-image", "--name", "AI_App", "--input", "libs", "--main-jar", "App.jar", "--main-class", string2, "--add-modules", "java.desktop,java.base,java.logging"));
        if (bl) {
            arrayList.add("--win-console");
        }
        this.runCmd(string3, arrayList.toArray(new String[0]));
        this.updateUI("\ud83d\ude80 Native .exe application generated inside AI_App folder.");
    }

    private boolean extractAndCreateFiles(String string, String string2, String string3) throws Exception {
        Path path = Paths.get(string2, new String[0]).toAbsolutePath().normalize();
        Pattern pattern = Pattern.compile("\\[FILE:\\s*(.*?)\\](.*?)\\[ENDFILE\\]", 32);
        Matcher matcher = pattern.matcher(string);
        boolean bl = false;
        while (matcher.find()) {
            Object object;
            bl = true;
            String string4 = matcher.group(1).trim();
            String string5 = matcher.group(2).trim();
            string5 = string5.replaceAll("(?i)^```[a-z]*\\n", "").replaceAll("(?i)^```[a-z]*", "").replaceAll("(?i)\\n```$", "").replaceAll("(?i)```$", "");
            Object object2 = string4.replace("\\", "/");
            if (TYPE_JAVA.equals(string3)) {
                object2 = ((String)object2).replace("src/main/java/", "").replace("src/", "");
            }
            if (((String)object2).endsWith(".java") && ((Matcher)(object = Pattern.compile("^package\\s+([\\w\\.]+);", 8).matcher(string5))).find()) {
                String string6 = ((Matcher)object).group(1).trim();
                String string7 = string6.replace(".", "/");
                String string8 = Paths.get((String)object2, new String[0]).getFileName().toString();
                if (!((String)object2).startsWith(string7)) {
                    String string9 = object2;
                    object2 = string7 + "/" + string8;
                    this.updateUI("\ud83d\udd27 [Auto-Correct] Fixed directory for " + string8 + " (mapped package '" + string6 + "' to '" + string7 + "')");
                }
            }
            if (!(object = path.resolve((String)object2).toAbsolutePath().normalize()).startsWith(path)) {
                this.updateUI("\ud83d\uded1 [Security] Blocked attempt to write outside project root: " + string4);
                continue;
            }
            Files.createDirectories(object.getParent(), new FileAttribute[0]);
            Files.writeString((Path)object, (CharSequence)string5, new OpenOption[0]);
            this.updateUI("\ud83d\udcc4 Created: " + String.valueOf(object));
        }
        if (!bl) {
            this.updateUI("\u26a0\ufe0f No [FILE] tags detected in the AI output.");
        }
        return bl;
    }

    private String findMainClass(String string) {
        if (string == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\[FILE:\\s*(.*?)\\](.*?)\\[ENDFILE\\]", 32);
        Matcher matcher = pattern.matcher(string);
        while (matcher.find()) {
            String string2 = matcher.group(1).trim();
            String string3 = matcher.group(2);
            if (!string3.contains("public static void main")) continue;
            Matcher matcher2 = Pattern.compile("package\\s+([\\w\\.]+);").matcher(string3);
            String string4 = matcher2.find() ? matcher2.group(1).trim() + "." : "";
            Matcher matcher3 = Pattern.compile("public\\s+class\\s+([\\w\\d_]+)").matcher(string3);
            String string5 = matcher3.find() ? string4 + matcher3.group(1).trim() : string4 + string2.replace(".java", "");
            this.updateUI("\ud83d\udd0e Entry point: " + string5);
            return string5;
        }
        return null;
    }

    private String listProjectFiles(String string) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            Files.walk(Paths.get(string, new String[0]), new FileVisitOption[0]).filter(path -> Files.isRegularFile(path, new LinkOption[0])).forEach(path -> stringBuilder.append("  - ").append(Paths.get(string, new String[0]).relativize((Path)path)).append("\n"));
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return stringBuilder.toString();
    }

    private String getProjectFilesContext(String string) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            Files.walk(Paths.get(string, new String[0]), new FileVisitOption[0]).filter(path -> Files.isRegularFile(path, new LinkOption[0])).filter(path -> {
                String string = path.toString().toLowerCase();
                return string.endsWith(".java") || string.endsWith(".html") || string.endsWith(".css") || string.endsWith(".js") || string.endsWith(".json") || string.endsWith(".jsx") || string.endsWith(".xml") || string.endsWith(".fxml") || string.endsWith(".bat") || string.endsWith(".md") || string.endsWith(".properties") || string.endsWith(".sh") || string.endsWith(".txt");
            }).forEach(path -> {
                try {
                    String string2 = Paths.get(string, new String[0]).relativize((Path)path).toString().replace("\\", "/");
                    stringBuilder.append("[FILE: ").append(string2).append("]\n").append(Files.readString(path)).append("\n[ENDFILE]\n\n");
                }
                catch (IOException iOException) {
                    // empty catch block
                }
            });
        }
        catch (IOException iOException) {
            // empty catch block
        }
        return stringBuilder.toString();
    }

    private String runCmd(String string, String ... stringArray) throws Exception {
        this.updateUI("\ud83d\udcbb [System] Running: " + String.join((CharSequence)" ", stringArray));
        ProcessBuilder processBuilder = new ProcessBuilder(stringArray);
        processBuilder.directory(new File(string));
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));){
            String string2;
            while ((string2 = bufferedReader.readLine()) != null) {
                stringBuilder.append(string2).append("\n");
                String string3 = string2;
                Platform.runLater(() -> this.outputArea.appendText("   > " + string3 + "\n"));
            }
        }
        int n = process.waitFor();
        if (n != 0) {
            throw new Exception("Command failed (exit " + n + ")\n" + String.valueOf(stringBuilder));
        }
        return stringBuilder.toString();
    }
}
