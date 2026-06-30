package org.example.dao;

import org.example.model.User;
import org.example.util.PasswordUtil;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 用户数据访问层 - 负责用户的增删改查操作
 * 通过构造器注入 DataSource，便于单元测试时 Mock 数据库连接
 */
public class UserDAO {

    private final DataSource dataSource;

    /**
     * 构造器注入 DataSource（支持 HikariCP 连接池或 Mock 数据源）
     *
     * @param dataSource 数据源
     */
    public UserDAO(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * 用户登录方法
     * 使用 PreparedStatement 防止 SQL 注入，SHA-256 + 盐值校验密码
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 登录成功返回 User 对象，失败返回 null
     */
    public User login(String username, String password) {
        // 参数校验：用户名和密码不能为空
        if (username == null || username.trim().isEmpty()
                || password == null || password.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT id, username, password, salt, nickname FROM `user` WHERE username = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // 使用 PreparedStatement 防止 SQL 注入
            ps.setString(1, username.trim());

            try (ResultSet rs = ps.executeQuery()) {
                // 用户名不存在 -> 返回 null
                if (!rs.next()) {
                    return null;
                }

                String storedHash = rs.getString("password");
                String storedSalt = rs.getString("salt");

                // 使用 SHA-256 + 盐值验证密码
                // 密码错误 -> 返回 null
                if (!PasswordUtil.verify(password, storedHash, storedSalt)) {
                    return null;
                }

                // 密码正确 -> 组装 User 对象返回
                User user = new User();
                user.setId(rs.getLong("id"));
                user.setUsername(rs.getString("username"));
                user.setPassword(storedHash);
                user.setSalt(storedSalt);
                user.setNickname(rs.getString("nickname"));
                return user;
            }
        } catch (SQLException e) {
            // 数据库异常 -> 记录日志并返回 null
            System.err.println("[UserDAO] 登录查询异常: " + e.getMessage());
            return null;
        }
    }
}