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

    private static final String CONFIG_DIR =
            System.getProperty("user.home") + File.separator + ".brainai";
    private static final String DB_URL =
            "jdbc:h2:" + CONFIG_DIR + File.separator + "brainai_db"
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
                + ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS messages ("
                + "  message_id      INT AUTO_INCREMENT PRIMARY KEY,"
                + "  project_id      INT,"
                + "  sender_role     VARCHAR(100),"
                + "  message_content CLOB"
                + ")"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS agents ("
                + "  role_name     VARCHAR(100) PRIMARY KEY,"
                + "  system_prompt CLOB"
                + ")"
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Default agent prompts — inserted once on first run
    // ─────────────────────────────────────────────────────────────────────────

    private static void insertDefaultPrompts(Connection conn) throws SQLException {
        // Check if prompts already exist AND are the latest version (v3+)
        try (ResultSet rs = conn.createStatement()
                .executeQuery("SELECT COUNT(*) FROM agents WHERE role_name = 'PARALLEL_CODER'")) {
            rs.next();
            if (rs.getInt(1) > 0) return; // Already seeded with latest prompts
        }
        // Wipe and re-seed if outdated
        conn.createStatement().execute("DELETE FROM agents");

        String[][] prompts = {
            {
                "ARCHITECT",
                "You are a senior software architect. Analyze the user's request and produce a "
                + "clear, detailed technical plan. Rules:\n"
                + "- Decide the project type: write [TYPE: JAVA] for Java Swing/AWT desktop apps "
                + "  or [TYPE: WEB] for HTML/CSS/JS or Node.js apps.\n"
                + "- List every file that must be created with its EXACT intended absolute path "
                + "  relative to the project root, its purpose, main classes/functions, and key logic.\n"
                + "- Explicitly state the project root directory in your plan.\n"
                + "- Be precise so the coder can implement without ambiguity."
            },
            {
                "CODER",
                "You are an expert Java developer. Write COMPLETE, compilable Java code based on "
                + "the architect's plan. CRITICAL FILE SYSTEM RULES:\n"
                + "- ALWAYS use absolute paths or paths explicitly relative to the stated project root.\n"
                + "- NEVER write files to / or any root directory. All files go under the project root.\n"
                + "- At the top of any file that does file I/O, print System.getProperty('user.dir') "
                + "  to confirm the working directory before any file operations.\n"
                + "- Wrap every file in [FILE: FileName.java]...[ENDFILE] tags. The filename inside "
                + "  the [FILE:] tag is relative to the project root — do NOT use absolute paths in tags.\n"
                + "- Include ALL necessary imports at the top of EVERY file.\n"
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
                + "- Implement ONLY the file(s) specified in the sub-task — do not generate other files.\n"
                + "- ALWAYS use absolute paths relative to the project root for any file I/O inside the code.\n"
                + "- Print System.getProperty('user.dir') at startup in files that do file I/O.\n"
                + "- Wrap output in [FILE: FileName.java]...[ENDFILE] tags.\n"
                + "- Include ALL necessary imports at the top.\n"
                + "- No markdown fences — raw code only.\n"
                + "- Provide the COMPLETE, compilable file content. No stubs."
            },
            {
                "WEB_CODER",
                "You are an expert web developer. Write COMPLETE, production-ready web code based "
                + "on the architect's plan. CRITICAL FILE SYSTEM RULES:\n"
                + "- ALWAYS use paths relative to the project root. Never use absolute OS paths "
                + "  (e.g., /home/user/...) inside HTML/CSS/JS source files.\n"
                + "- For Node.js: all require() calls must use paths relative to the file's location "
                + "  using __dirname (e.g., path.join(__dirname, 'views')).\n"
                + "- Wrap every file in [FILE: filename.ext]...[ENDFILE] tags.\n"
                + "- Always create at least: index.html, style.css, script.js.\n"
                + "- For Node.js: include package.json with a 'start' script.\n"
                + "- Use relative paths for all script/link src attributes in HTML.\n"
                + "- Do NOT use ES module import/export for browser projects.\n"
                + "- CSS must be complete in style.css. JS must be in .js files.\n"
                + "- No placeholders — deliver fully functional code."
            },
            {
                "TESTER",
                "You are a senior code reviewer and QA engineer. Review the provided code for "
                + "bugs, missing imports, logic errors, and compilation issues. CRITICAL RULES:\n"
                + "- Return the COMPLETE fixed code using [FILE: filename]...[ENDFILE] tags for EVERY file.\n"
                + "- NEVER return partial files — always return the full file content.\n"
                + "- Verify all file I/O operations use absolute or properly rooted relative paths. "
                + "  Fix any path that references a root directory or an undefined variable.\n"
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
                if (rs.next()) return rs.getString("system_prompt");
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
                if (rs.next()) return rs.getInt(1);
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
