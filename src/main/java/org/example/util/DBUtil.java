package org.example.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接工具类 - 基于 HikariCP 连接池 + SQLite
 * SQLite 为单文件数据库，无需安装服务，数据存储在 data/linkup.db
 * 使用懒加载模式，避免静态初始化失败导致 JVM 崩溃
 */
public class DBUtil {

    private static final String DB_PATH = "data/linkup.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    private static volatile HikariDataSource dataSource;
    private static final Object lock = new Object();

    /**
     * 确保数据目录存在
     */
    private static void ensureDataDir() {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
            System.out.println("[DBUtil] 创建数据目录: " + dataDir.getAbsolutePath());
        }
    }

    /**
     * 懒加载初始化连接池（首次调用时初始化）
     */
    private static HikariDataSource getDataSource() throws SQLException {
        if (dataSource == null) {
            synchronized (lock) {
                if (dataSource == null) {
                    try {
                        ensureDataDir();
                        HikariConfig config = new HikariConfig();
                        config.setJdbcUrl(DB_URL);
                        config.setDriverClassName("org.sqlite.JDBC");

                        // SQLite 不需要用户名密码，连接池配置需调整
                        config.setMaximumPoolSize(1);           // SQLite 单文件，仅需 1 个连接
                        config.setMinimumIdle(1);
                        config.setConnectionTimeout(3000);
                        config.setIdleTimeout(600000);
                        config.setMaxLifetime(0);               // SQLite 连接无需回收
                        config.setLeakDetectionThreshold(0);    // 关闭泄漏检测

                        dataSource = new HikariDataSource(config);
                        System.out.println("[DBUtil] SQLite 数据库连接池初始化成功: " + DB_PATH);
                    } catch (Exception e) {
                        System.err.println("[DBUtil] SQLite 数据库连接池初始化失败: " + e.getMessage());
                        throw new SQLException("数据库连接失败: " + e.getMessage(), e);
                    }
                }
            }
        }
        return dataSource;
    }

    /**
     * 从连接池获取一个数据库连接
     *
     * @return 数据库连接
     * @throws SQLException 获取连接失败时抛出
     */
    public static Connection getConnection() throws SQLException {
        return getDataSource().getConnection();
    }

    /**
     * 关闭连接池（应用关闭时调用）
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("[DBUtil] 数据库连接池已关闭");
        }
    }
}