package org.example.dao;

import org.example.model.User;
import org.example.util.DBUtil;
import org.example.util.DBInit;
import org.example.util.PasswordUtil;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserDAO 集成测试
 * 使用真实 SQLite 数据库测试，验证：
 * 1. 用户登录正确性
 * 2. SQL 注入防护
 * 3. 参数校验边界
 * 4. 数据库异常处理
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserDAOTest {

    private static UserDAO userDAO;
    private static final String TEST_USERNAME = "testuser_dao";
    private static final String TEST_PASSWORD = "TestPass123!";

    @BeforeAll
    static void setupDatabase() throws Exception {
        DBInit.init();
        userDAO = new UserDAO(new javax.sql.DataSource() {
            @Override
            public Connection getConnection() throws java.sql.SQLException {
                return DBUtil.getConnection();
            }
            @Override
            public Connection getConnection(String username, String password) throws java.sql.SQLException {
                return DBUtil.getConnection();
            }
            @Override
            public java.io.PrintWriter getLogWriter() { return null; }
            @Override
            public void setLogWriter(java.io.PrintWriter out) {}
            @Override
            public void setLoginTimeout(int seconds) {}
            @Override
            public int getLoginTimeout() { return 0; }
            @Override
            public java.util.logging.Logger getParentLogger() { return null; }
            @Override
            public <T> T unwrap(Class<T> iface) { return null; }
            @Override
            public boolean isWrapperFor(Class<?> iface) { return false; }
        });

        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            String salt = PasswordUtil.generateSalt();
            String hash = PasswordUtil.hash(TEST_PASSWORD, salt);
            stmt.execute("INSERT INTO `user` (username, password, salt, nickname) " +
                    "VALUES ('" + TEST_USERNAME + "', '" + hash + "', '" + salt + "', '测试用户DAO')");
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM `user` WHERE username = '" + TEST_USERNAME + "'");
        }
        // 注意：不关闭连接池，其他测试类可能还需要使用
    }

    @Test
    @Order(1)
    @DisplayName("TC-DAO-001: 正常登录 - 用户名密码正确")
    void testLogin_Success() {
        User user = userDAO.login(TEST_USERNAME, TEST_PASSWORD);
        assertNotNull(user, "登录成功应返回 User 对象");
        assertEquals(TEST_USERNAME, user.getUsername());
        assertEquals("测试用户DAO", user.getNickname());
        assertTrue(user.getId() > 0, "用户 ID 应大于 0");
    }

    @Test
    @Order(2)
    @DisplayName("TC-DAO-002: 登录失败 - 错误密码")
    void testLogin_WrongPassword() {
        User user = userDAO.login(TEST_USERNAME, "wrongPassword");
        assertNull(user, "错误密码应返回 null");
    }

    @Test
    @Order(3)
    @DisplayName("TC-DAO-003: 登录失败 - 用户名不存在")
    void testLogin_UserNotFound() {
        User user = userDAO.login("nonexistent_user", "anyPassword");
        assertNull(user, "不存在的用户应返回 null");
    }

    @Test
    @Order(4)
    @DisplayName("TC-SEC-DAO-001: SQL 注入防护 - 单引号注入用户名")
    void testLogin_SQLInjection_Username() {
        User user = userDAO.login("' OR '1'='1", "anyPass");
        assertNull(user, "SQL 注入应被防护，返回 null");
    }

    @Test
    @Order(5)
    @DisplayName("TC-SEC-DAO-002: SQL 注入防护 - DROP TABLE 注入")
    void testLogin_SQLInjection_DropTable() {
        User user = userDAO.login("testuser'; DROP TABLE `user`;--", "pass");
        assertNull(user, "DROP TABLE 注入应被防护");

        assertDoesNotThrow(() -> {
            try (Connection conn = DBUtil.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT COUNT(*) FROM `user`");
            }
        }, "user 表不应被删除");
    }

    @Test
    @Order(6)
    @DisplayName("TC-SEC-DAO-003: SQL 注入防护 - UNION SELECT 注入")
    void testLogin_SQLInjection_UnionSelect() {
        User user = userDAO.login(
                "' UNION SELECT 1,'admin','fakehash','fakesalt','Admin'--",
                "pass");
        assertNull(user, "UNION SELECT 注入应被防护");
    }

    @Test
    @Order(7)
    @DisplayName("TC-DAO-004: 登录失败 - 用户名为空")
    void testLogin_EmptyUsername() {
        assertNull(userDAO.login(null, "pass"), "null 用户名应返回 null");
        assertNull(userDAO.login("", "pass"), "空用户名应返回 null");
        assertNull(userDAO.login("   ", "pass"), "空白用户名应返回 null");
    }

    @Test
    @Order(8)
    @DisplayName("TC-DAO-005: 登录失败 - 密码为空")
    void testLogin_EmptyPassword() {
        assertNull(userDAO.login(TEST_USERNAME, null), "null 密码应返回 null");
        assertNull(userDAO.login(TEST_USERNAME, ""), "空密码应返回 null");
        assertNull(userDAO.login(TEST_USERNAME, "   "), "空白密码应返回 null");
    }

    @Test
    @Order(9)
    @DisplayName("TC-DAO-006: 登录失败 - 用户名密码都为空")
    void testLogin_BothEmpty() {
        assertNull(userDAO.login(null, null));
        assertNull(userDAO.login("", ""));
    }

    @Test
    @Order(10)
    @DisplayName("TC-DAO-007: 用户名包含空格 - trim 处理")
    void testLogin_UsernameWithSpaces() {
        User user = userDAO.login("  " + TEST_USERNAME + "  ", TEST_PASSWORD);
        assertNotNull(user, "用户名首尾空格应被 trim");
        assertEquals(TEST_USERNAME, user.getUsername());
    }
}
