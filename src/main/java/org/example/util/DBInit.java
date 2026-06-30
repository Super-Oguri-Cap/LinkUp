package org.example.util;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库初始化工具类 - 自动执行 resources/db 目录下的 SQL 迁移脚本
 * 按文件名顺序执行，确保表结构正确创建
 */
public class DBInit {

    private static final String DB_SCHEMA_TABLE = "schema_version";
    private static final String MIGRATION_DIR = "db/";

    public static void init() {
        try (Connection conn = DBUtil.getConnection()) {
            ensureSchemaTable(conn);
            List<String> pendingScripts = findPendingScripts(conn);
            for (String script : pendingScripts) {
                executeScript(conn, script);
                recordMigration(conn, script);
                System.out.println("[DBInit] 已执行迁移脚本: " + script);
            }
            if (pendingScripts.isEmpty()) {
                System.out.println("[DBInit] 数据库已是最新版本，无需迁移");
            }
        } catch (SQLException e) {
            System.err.println("[DBInit] 数据库初始化失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void ensureSchemaTable(Connection conn) throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS " + DB_SCHEMA_TABLE + " ("
                + "version VARCHAR(20) NOT NULL PRIMARY KEY, "
                + "applied_at TEXT NOT NULL DEFAULT (datetime('now'))"
                + ")";
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static List<String> findPendingScripts(Connection conn) throws SQLException {
        List<String> pending = new ArrayList<>();
        String sql = "SELECT version FROM " + DB_SCHEMA_TABLE + " ORDER BY version";
        List<String> applied = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                applied.add(rs.getString("version"));
            }
        }

        String[] scripts = {"V0__create_db.sql", "V1__init_user.sql", "V2__init_friendship.sql",
                "V3__init_group.sql", "V4__init_message.sql", "V5__init_friend_request.sql"};
        for (String script : scripts) {
            if (!applied.contains(script)) {
                pending.add(script);
            }
        }
        return pending;
    }

    private static void executeScript(Connection conn, String scriptName) throws SQLException {
        String path = MIGRATION_DIR + scriptName;
        try (InputStream is = DBInit.class.getClassLoader().getResourceAsStream(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            if (is == null) {
                throw new SQLException("未找到迁移脚本: " + path);
            }
            StringBuilder sqlBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.trim().startsWith("--")) {
                    continue;
                }
                sqlBuilder.append(line);
                if (line.trim().endsWith(";")) {
                    String sql = sqlBuilder.toString().trim();
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(sql);
                    }
                    sqlBuilder = new StringBuilder();
                }
            }
        } catch (java.io.IOException e) {
            throw new SQLException("读取迁移脚本失败: " + e.getMessage(), e);
        }
    }

    private static void recordMigration(Connection conn, String scriptName) throws SQLException {
        String sql = "INSERT INTO " + DB_SCHEMA_TABLE + "(version) VALUES (?)";
        try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scriptName);
            ps.executeUpdate();
        }
    }
}