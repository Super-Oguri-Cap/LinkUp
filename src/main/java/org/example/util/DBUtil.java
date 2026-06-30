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

    private static final String DB_PATH = new File(System.getProperty("user.dir"), "data/linkup.db").getAbsolutePath();
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    private static volatile HikariDataSource dataSource;
    private static final Object lock = new Object();

    /**
     * 确保数据目录存在
     */
    private static void ensureDataDir() {
        File dataDir = new File(System.getProperty("user.dir"), "data");
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

                        // SQLite 连接池配置
                        // 注意：SQLite 单文件写入会加锁，maxPoolSize 增大会增加并发写入冲突概率
                        // 但 maxPoolSize=1 时，单个长查询（如 AI 摘要加载大量历史消息）会阻塞所有写操作
                        // 因此设为 5，允许少量并发操作，同时保持 SQLite 写入稳定性
                        config.setMaximumPoolSize(5);
                        config.setMinimumIdle(2);
                        config.setConnectionTimeout(3000);      // 获取连接超时 3 秒
                        config.setIdleTimeout(600000);          // 空闲连接超时 10 分钟
                        config.setMaxLifetime(1800000);         // 连接最大存活时间 30 分钟

                        // 每个新连接初始化时执行的 SQL：启用外键约束 + WAL 模式 + 忙等待超时
                        // WAL 模式：写操作不阻塞读操作，大幅提升并发性能
                        // foreign_keys：启用外键约束（SQLite 默认关闭）
                        // busy_timeout：当数据库被锁时等待时间，避免立即报错
                        config.setConnectionInitSql("PRAGMA foreign_keys = ON; PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;");

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
            dataSource = null;
            System.out.println("[DBUtil] 数据库连接池已关闭");
        }
    }
}