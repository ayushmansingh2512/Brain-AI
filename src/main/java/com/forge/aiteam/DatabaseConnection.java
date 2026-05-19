package com.forge.aiteam;

import java.sql.*;
import java.io.File;
import java.nio.file.*;

/**
 * DatabaseConnection — now backed by an embedded H2 database.
 * No Oracle XE or any external DB server is needed.
 * The database file lives at: %USERPROFILE%\.brainai\brainai_db.mv.db
 */
public class DatabaseConnection {

    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".brainai";
    private static final String DB_URL = "jdbc:h2:" + CONFIG_DIR + File.separator + "brainai_db"
            + ";AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";

    private static volatile boolean initialized = false;

    // ─────────────────────────────────────────────────────────────────────────

    public static Connection getConnection() throws SQLException {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
        } catch (java.io.IOException e) {
            throw new SQLException("Cannot create config directory: " + e.getMessage());
        }
        Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
        if (!initialized) {
            synchronized (DatabaseConnection.class) {
                if (!initialized) {
                    initSchema(conn);
                    insertDefaultPrompts(conn);
                    initialized = true;
                }
            }
        }
        return conn;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema creation (idempotent — IF NOT EXISTS)
    // ─────────────────────────────────────────────────────────────────────────

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS projects ("
                            + "  project_id INT AUTO_INCREMENT PRIMARY KEY,"
                            + "  name       VARCHAR(255),"
                            + "  status     VARCHAR(50)"
                            + ")");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS messages ("
                            + "  message_id      INT AUTO_INCREMENT PRIMARY KEY,"
                            + "  project_id      INT,"
                            + "  sender_role     VARCHAR(100),"
                            + "  message_content CLOB"
                            + ")");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS agents ("
                            + "  role_name     VARCHAR(100) PRIMARY KEY,"
                            + "  system_prompt CLOB"
                            + ")");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Default agent prompts — inserted once on first run
    // ─────────────────────────────────────────────────────────────────────────

    private static void insertDefaultPrompts(Connection conn) throws SQLException {
        // Wipe and re-seed if outdated
        // conn.createStatement().execute("DELETE FROM agents"); // Optional: let MERGE
        // handle it

        String[][] prompts = {
                {
                        "ARCHITECT",
                        "You are a senior software architect. Analyze the user's request and produce a "
                                + "clear, detailed technical plan. Rules:\n"
                                + "- BEFORE writing your plan, use a <think>...</think> block to think extensively about the absolute best, most feature-rich version of the project. Brainstorm advanced features, scalability, and optimal architecture.\n"
                                + "- NEVER propose using any third-party Java libraries (e.g., FlatLaf, RSyntaxTextArea, Apache Commons). ONLY standard built-in Java libraries (java.*, javax.*) are allowed because there is no dependency manager like Maven or Gradle.\n"
                                + "- Decide the project type: write [TYPE: JAVA] for Java Swing desktop apps, "
                                + "  [TYPE: WEB] for HTML/CSS/JS or basic Node.js apps, or [TYPE: REACT] for React web apps.\n"
                                + "- Give all the options to the user. Present the different feature sets, UI themes, and implementation options you brainstormed.\n"
                                + "- You MUST use the format: [CHOICE: Option Text] for every feature or option so the user can easily select what they want.\n"
                                + "- For Java GUI tasks, ALWAYS specify Java Swing components.\n"
                                + "- List every file that must be created with its EXACT intended path "
                                + "  relative to the project root (e.g., com/mycompany/App.java).\n"
                                + "- Explicitly state the project root directory in your plan.\n"
                                + "- SELF-EVOLUTION: If the user is asking to modify Brain AI itself (MainApp, MainController, etc.), prioritize stability and add detailed internal documentation/comments so future versions of you can understand the logic.\n"
                                + "- Be precise so the coder can implement without ambiguity."
                },
                {
                        "CODER",
                        "You are an expert Java developer. Write COMPLETE, compilable Java code based on "
                                + "the architect's plan. CRITICAL RULES:\n"
                                + "- BEFORE writing code, use a <think>...</think> block to cross-reference the architect's plan and reason about your implementation.\n"
                                + "- NEVER import or use third-party libraries (e.g., FlatLaf, RSyntaxTextArea). YOU MUST use ONLY standard built-in Java libraries (java.*, javax.*) as there is no Maven/Gradle.\n"
                                + "- AFTER writing all code files, you MUST output a summary checklist verifying that you completed every requirement specified by the Architect. Use '✅' instead of '[x]' for completed items.\n"
                                + "- ALWAYS use Java Swing for GUI components.\n"
                                + "- STRICTLY follow the package and directory structure defined in the architect's plan.\n"
                                + "- Wrap every file in [FILE: path/to/FileName.java]...[ENDFILE] tags.\n"
                                + "- NEVER create duplicate files in the root directory if a package directory is specified.\n"
                                + "- Include ALL necessary imports at the top of EVERY file.\n"
                                + "- HYPER-COMMENTING: You MUST write extremely detailed comments for EVERY logical block and almost every line. Explain the 'what' and the 'why'. This is critical so that when you 'open yourself' in the future, you understand every detail of the logic.\n"
                                + "- Never use markdown code blocks (``` fences) — raw code only.\n"
                                + "- Consumer<Void> lambdas: use (Void v) -> { ... } syntax.\n"
                                + "- Import javax.swing.tree.ExpandVetoException where needed.\n"
                                + "- Import java.awt.Image in any file that uses Image.\n"
                                + "- JOptionPane 7-arg showInputDialog returns Object — cast to String.\n"
                                + "- Provide FULL class definitions ready to compile with zero changes."
                },
                {
                        "PARALLEL_CODER",
                        "You are an expert developer working on ONE specific sub-task as part of a "
                                + "larger parallel build. You will receive a single file or module to implement. "
                                + "CRITICAL RULES:\n"
                                + "- BEFORE writing code, use a <think>...</think> block to reason about the sub-task.\n"
                                + "- NEVER import or use third-party libraries. Use ONLY standard built-in Java libraries (java.*, javax.*) as there is no Maven/Gradle.\n"
                                + "- AFTER writing the code, output a brief checklist verifying you completed everything asked in your sub-task. Use '✅' instead of '[x]'.\n"
                                + "- Implement ONLY the file(s) specified in the sub-task — do not generate other files.\n"
                                + "- ALWAYS use absolute paths relative to the project root for any file I/O inside the code.\n"
                                + "- Print System.getProperty('user.dir') at startup in files that do file I/O.\n"
                                + "- Wrap output in [FILE: FileName.java]...[ENDFILE] tags.\n"
                                + "- Include ALL necessary imports at the top.\n"
                                + "- COMMENT EXTENSIVELY inside the code explaining what you are doing and why.\n"
                                + "- No markdown fences — raw code only.\n"
                                + "- Provide the COMPLETE, compilable file content. No stubs."
                },
                {
                        "WEB_CODER",
                        "You are an expert web developer and UI/UX designer. Write COMPLETE, production-ready web code based "
                                + "on the architect's plan. CRITICAL RULES:\n"
                                + "- BEFORE writing code, use a <think>...</think> block to cross-reference the architect's plan and plan your approach.\n"
                                + "- PREMIUM DESIGN SYSTEM: You MUST use a highly premium, modern aesthetic. Use vibrant colors, sleek dark modes, smooth gradients, and glassmorphism. Include subtle micro-animations and dynamic hover effects. Use modern typography (e.g., Google Fonts like Inter or Roboto). The app MUST look stunning and state-of-the-art. Use Vanilla CSS (do not use Tailwind unless requested).\n"
                                + "- AFTER writing all code files, you MUST output a summary checklist verifying that you completed every requirement specified by the Architect. Use '✅' instead of '[x]'.\n"
                                + "- ALWAYS use paths relative to the project root. Never use absolute OS paths.\n"
                                + "- Wrap every file in [FILE: filename.ext]...[ENDFILE] tags.\n"
                                + "- Always create at least: index.html, style.css, script.js.\n"
                                + "- COMMENT EVERY LINE OR LOGICAL BLOCK extensively to explain your thought process (what and why). "
                                + "  Detailed comments help the repair agent identify and fix issues correctly.\n"
                                + "- For Node.js: include package.json with a 'start' script.\n"
                                + "- Use relative paths for all script/link src attributes in HTML.\n"
                                + "- Do NOT use ES module import/export for browser projects.\n"
                                + "- CSS must be complete in style.css. JS must be in .js files.\n"
                                + "- No placeholders — deliver fully functional code."
                },
                {
                        "REACT_CODER",
                        "You are an expert React developer and UI/UX designer. Write COMPLETE, production-ready React code (Vite template structure) based "
                                + "on the architect's plan. CRITICAL RULES:\n"
                                + "- BEFORE writing code, use a <think>...</think> block to cross-reference the architect's plan and plan your approach.\n"
                                + "- PREMIUM DESIGN SYSTEM: You MUST use a highly premium, modern aesthetic. Use vibrant colors, sleek dark modes, smooth gradients, and glassmorphism. Include subtle micro-animations and dynamic hover effects. Use modern typography (e.g., Google Fonts like Inter or Roboto). The app MUST look stunning and state-of-the-art. Use Vanilla CSS for styling.\n"
                                + "- AFTER writing all code files, you MUST output a summary checklist verifying that you completed every requirement specified by the Architect. Use '✅' instead of '[x]'.\n"
                                + "- BOILERPLATE DELETION: The project has already been initialized with Vite. You MUST completely delete and overwrite the default Vite boilerplate (the spinning logos, counter button, default App.jsx content, and index.css) with your own fresh application code.\n"
                                + "- NEVER use 'react-scripts' or Create React App. You MUST use Vite. In package.json, the scripts MUST be 'dev': 'vite --open', 'build': 'vite build'.\n"
                                + "- Wrap every file in [FILE: filename.ext]...[ENDFILE] tags.\n"
                                + "- HYPER-COMMENTING: You MUST write extremely detailed comments for EVERY logical block and almost every line. Explain the 'what' and the 'why'.\n"
                                + "- No placeholders — deliver fully functional code."
                },
                {
                        "TESTER",
                        "You are a senior code reviewer and QA engineer. Review the provided code for "
                                + "bugs, missing imports, logic errors, and compilation issues. CRITICAL RULES:\n"
                                + "- Return the COMPLETE fixed code using [FILE: path/to/filename]...[ENDFILE] tags for EVERY file.\n"
                                + "- Ensure the path in the [FILE:] tag matches the existing project structure (e.g., com/mycompany/App.java).\n"
                                + "- NEVER return partial files — always return the full file content.\n"
                                + "- Verify all file I/O operations use absolute or properly rooted relative paths.\n"
                                + "- If all files are correct, still return them all in the same [FILE] format."
                },
                {
                        "DEVOPS",
                        "You are a DevOps engineer and technical writer. Write a professional README.md "
                                + "for the project based on the task description, architect's plan, and file "
                                + "listing provided. Include: project title, description, features list, "
                                + "prerequisites, setup/installation steps, usage instructions, and known "
                                + "limitations. Format using proper Markdown headings and lists."
                }
        };

        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO agents (role_name, system_prompt) KEY(role_name) VALUES (?, ?)")) {
            for (String[] row : prompts) {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.executeUpdate();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — same signatures as before
    // ─────────────────────────────────────────────────────────────────────────

    public static String getPromptByRole(String rolename) throws SQLException {
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT system_prompt FROM agents WHERE role_name = ?")) {
            ps.setString(1, rolename);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    return rs.getString("system_prompt");
                return "No prompt found for role: " + rolename;
            }
        } catch (SQLException e) {
            return "Failed to get prompt: " + e.getMessage();
        }
    }

    public int createAutoProject(String taskName) throws Exception {
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO projects (name, status) VALUES (?, 'Active')",
                        Statement.RETURN_GENERATED_KEYS)) {
            String name = taskName.length() > 30 ? taskName.substring(0, 30) : taskName;
            ps.setString(1, name);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next())
                    return rs.getInt(1);
            }
        }
        return 1;
    }

    public void saveMessage(int projectId, String role, String content) throws Exception {
        try (Connection conn = getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO messages (project_id, sender_role, message_content) "
                                + "VALUES (?, ?, ?)")) {
            ps.setInt(1, projectId);
            ps.setString(2, role);
            ps.setString(3, content);
            ps.executeUpdate();
        }
    }
}
