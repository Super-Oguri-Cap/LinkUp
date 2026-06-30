package org.example.util;

import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数据库初始化集成测试
 * 验证：表结构正确创建、索引存在、外键约束启用、迁移幂等性
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DBInitTest {

    @BeforeAll
    static void setup() {
        DBInit.init();
    }

    @AfterAll
    static void shutdown() {
        DBUtil.shutdown();
    }

    @Test
    @Order(1)
    @DisplayName("TC-DB-001: 用户表存在且字段完整")
    void testUserTable_ExistsWithCorrectColumns() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "user", null)) {
                java.util.Set<String> columns = new java.util.HashSet<>();
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
                assertTrue(columns.contains("id"), "应包含 id 字段");
                assertTrue(columns.contains("username"), "应包含 username 字段");
                assertTrue(columns.contains("password"), "应包含 password 字段");
                assertTrue(columns.contains("salt"), "应包含 salt 字段");
                assertTrue(columns.contains("nickname"), "应包含 nickname 字段");
                assertTrue(columns.contains("created_at"), "应包含 created_at 字段");
                assertTrue(columns.contains("online_status"), "应包含 online_status 字段");
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("TC-DB-002: 好友关系表存在")
    void testFriendshipTable_Exists() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "friendship", null)) {
                assertTrue(rs.next(), "friendship 表应存在");
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-DB-003: 群组表存在")
    void testGroupTables_Exist() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs1 = meta.getTables(null, null, "group_info", null)) {
                assertTrue(rs1.next(), "group_info 表应存在");
            }
            try (ResultSet rs2 = meta.getTables(null, null, "group_member", null)) {
                assertTrue(rs2.next(), "group_member 表应存在");
            }
        }
    }

    @Test
    @Order(4)
    @DisplayName("TC-DB-004: 消息表存在")
    void testMessageTable_Exists() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "chat_message", null)) {
                assertTrue(rs.next(), "chat_message 表应存在");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("TC-DB-005: 好友请求表存在")
    void testFriendRequestTable_Exists() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "friend_request", null)) {
                assertTrue(rs.next(), "friend_request 表应存在");
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("TC-DB-006: 迁移幂等性 - 重复初始化不报错")
    void testDBInit_Idempotent() {
        assertDoesNotThrow(() -> DBInit.init(),
                "重复调用 DBInit.init() 不应报错");
        assertDoesNotThrow(() -> DBInit.init(),
                "第三次调用也不应报错");
    }

    @Test
    @Order(7)
    @DisplayName("TC-DB-007: 外键约束启用")
    void testForeignKeys_Enabled() throws Exception {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "外键约束应启用");
        }
    }

    @Test
    @Order(8)
    @DisplayName("TC-DB-008: WAL 模式启用")
    void testJournalMode_WAL() throws Exception {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA journal_mode")) {
            assertTrue(rs.next());
            String mode = rs.getString(1);
            assertEquals("wal", mode.toLowerCase(), "应启用 WAL 模式");
        }
    }

    @Test
    @Order(9)
    @DisplayName("TC-DB-009: 用户名唯一索引")
    void testUserTable_UniqueUsername() throws Exception {
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            // 清理残留数据
            stmt.execute("DELETE FROM `user` WHERE username = 'unique_test'");
            // 先插入一个用户
            stmt.execute("INSERT INTO `user` (username, password, salt, nickname) " +
                    "VALUES ('unique_test', 'hash', 'salt', 'test')");
            // 再插入相同用户名，应该失败
            assertThrows(Exception.class, () -> {
                try (Statement s2 = conn.createStatement()) {
                    s2.execute("INSERT INTO `user` (username, password, salt, nickname) " +
                            "VALUES ('unique_test', 'hash2', 'salt2', 'test2')");
                }
            }, "重复用户名应违反唯一约束");
            // 清理
            stmt.execute("DELETE FROM `user` WHERE username = 'unique_test'");
        }
    }

    @Test
    @Order(10)
    @DisplayName("TC-DB-010: 数据库连接池可用")
    void testDBUtil_Connection() throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            assertNotNull(conn, "应能获取数据库连接");
            assertFalse(conn.isClosed(), "连接不应已关闭");
        }
    }
}
