package org.example.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据库连接工具类 - 基于 HikariCP 连接池
 * 支持高并发场景下的高效数据库连接管理
 * 使用懒加载模式，避免静态初始化失败导致 JVM 崩溃
 *
 * 使用前请修改 DB_URL、DB_USER、DB_PASSWORD 为实际配置
 */
public class DBUtil {

    // ========== 数据库连接配置（请根据实际环境修改）==========
    private static final String DB_URL = "jdbc:mysql://localhost:3306/linkup?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=UTF-8";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "1464483789";

    private static volatile HikariDataSource dataSource;
    private static final Object lock = new Object();

    /**
     * 懒加载初始化连接池（首次调用时初始化，避免静态初始化失败导致 JVM 崩溃）
     */
    private static HikariDataSource getDataSource() throws SQLException {
        if (dataSource == null) {
            synchronized (lock) {
                if (dataSource == null) {
                    try {
                        HikariConfig config = new HikariConfig();
                        config.setJdbcUrl(DB_URL);
                        config.setUsername(DB_USER);
                        config.setPassword(DB_PASSWORD);
                        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

                        // 连接池配置
                        config.setMaximumPoolSize(20);          // 最大连接数（IM 场景需要较多连接）
                        config.setMinimumIdle(5);               // 最小空闲连接数
                        config.setConnectionTimeout(3000);      // 获取连接超时 3 秒
                        config.setIdleTimeout(600000);          // 空闲连接超时 10 分钟
                        config.setMaxLifetime(1800000);         // 连接最大存活时间 30 分钟
                        config.setLeakDetectionThreshold(10000);// 连接泄漏检测 10 秒

                        dataSource = new HikariDataSource(config);
                        System.out.println("[DBUtil] 数据库连接池初始化成功");
                    } catch (Exception e) {
                        System.err.println("[DBUtil] 数据库连接池初始化失败: " + e.getMessage());
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