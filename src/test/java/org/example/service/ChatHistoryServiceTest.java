package org.example.service;

import org.example.util.DBUtil;
import org.example.util.DBInit;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChatHistoryService 单元测试（数据库存储版）
 * 测试范围：消息写入、读取、删除、边界条件
 * 所有数据统一存入 SQLite chat_message 表，不再使用文件系统存储
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatHistoryServiceTest {

    private ChatHistoryService service;

    /** 测试用户列表（需在 user 表中预先创建） */
    private static final String[] TEST_USERS = {"alice", "bob", "AI伴侣", "AI小助手"};

    @BeforeAll
    static void setupDatabase() throws Exception {
        // 初始化数据库表结构
        DBInit.init();

        // 创建测试用户（INSERT OR IGNORE 避免重复创建冲突）
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            for (String username : TEST_USERS) {
                stmt.execute("INSERT OR IGNORE INTO `user` (username, password, salt, nickname) "
                        + "VALUES ('" + username + "', 'dummy', 'dummy', '" + username + "')");
            }
        }
    }

    @BeforeEach
    void setUp() {
        service = new ChatHistoryService();
        // 每个测试前清理 chat_message 表，确保测试隔离
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM chat_message");
        } catch (Exception e) {
            System.err.println("[Test] 清理 chat_message 表失败: " + e.getMessage());
        }
    }

    @AfterAll
    static void cleanup() throws Exception {
        // 先清理消息，再清理用户（外键约束要求）
        try (Connection conn = DBUtil.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM chat_message");
            for (String username : TEST_USERS) {
                stmt.execute("DELETE FROM `user` WHERE username = '" + username + "'");
            }
        }
        // 不关闭连接池，其他测试类可能还需要使用
    }

    @Test
    @Order(1)
    @DisplayName("TC-CHS-001: 追加消息 - 单条消息写入")
    void testAppendMessage_SingleMessage() {
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "hello world", "1719780000000");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(1, messages.size());
        assertEquals("alice", messages.get(0)[1]);
        assertEquals("hello world", messages.get(0)[2]);
    }

    @Test
    @Order(2)
    @DisplayName("TC-CHS-002: 追加消息 - 多条消息顺序正确")
    void testAppendMessage_MultipleOrder() {
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "msg1", "1719780001000");
        service.appendMessage("PRIVATE", "alice", "bob",
                "bob", "msg2", "1719780002000");
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "msg3", "1719780003000");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(3, messages.size());
        assertEquals("msg1", messages.get(0)[2]);
        assertEquals("msg2", messages.get(1)[2]);
        assertEquals("msg3", messages.get(2)[2]);
    }

    @Test
    @Order(3)
    @DisplayName("TC-CHS-003: 读取最近 N 条消息 - 限制数量")
    void testLoadRecentMessages_Limit() {
        for (int i = 0; i < 20; i++) {
            service.appendMessage("PRIVATE", "alice", "bob",
                    "alice", "msg" + i, String.valueOf(1719780000000L + i * 1000));
        }

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 5);
        assertEquals(5, messages.size());
        assertEquals("msg15", messages.get(0)[2]);
        assertEquals("msg19", messages.get(4)[2]);
    }

    @Test
    @Order(4)
    @DisplayName("TC-CHS-004: 删除消息 - 存在的消息")
    void testDeleteMessage_Existing() {
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "delete me", "1719780000000");

        boolean result = service.deleteMessage("PRIVATE", "alice", "bob",
                "alice", "delete me", "1719780000000");
        assertTrue(result);

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(0, messages.size());
    }

    @Test
    @Order(5)
    @DisplayName("TC-CHS-005: 删除消息 - 不存在的消息")
    void testDeleteMessage_NonExisting() {
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "keep me", "1719780000000");

        boolean result = service.deleteMessage("PRIVATE", "alice", "bob",
                "alice", "wrong content", "1719780000000");
        assertFalse(result);
    }

    @Test
    @Order(6)
    @DisplayName("TC-CHS-006: 特殊字符 - 换行符正确存储与读取")
    void testSpecialContent_Newline() {
        String multiLine = "line1\nline2\nline3";
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", multiLine, "1719780000000");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(1, messages.size());
        assertEquals(multiLine, messages.get(0)[2],
                "换行符应正确存储和读取");
    }

    @Test
    @Order(7)
    @DisplayName("TC-CHS-007: 特殊字符 - 制表符正确存储与读取")
    void testSpecialContent_Tab() {
        String withTab = "hello\tworld";
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", withTab, "1719780000000");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(1, messages.size());
        assertEquals(withTab, messages.get(0)[2],
                "制表符应正确存储和读取");
    }

    @Test
    @Order(8)
    @DisplayName("TC-CHS-008: 不同聊天类型 - 数据隔离")
    void testChatType_SeparateData() {
        // 群聊消息：receiver_id = 0
        service.appendMessage("GROUP", "alice", "group1",
                "alice", "group msg", "1719780000000");
        // AI伴侣消息：sender=AI伴侣, receiver=currentUser=alice
        service.appendMessage("COMPANION", "alice", "AI伴侣",
                "AI伴侣", "companion msg", "1719780000000");
        // AI小助手消息：sender=AI小助手, receiver=currentUser=alice
        service.appendMessage("AI", "alice", "AI小助手",
                "AI小助手", "ai msg", "1719780000000");

        List<String[]> group = service.loadRecentMessages("GROUP", "alice", "", 10);
        List<String[]> companion = service.loadRecentMessages("COMPANION", "alice", "", 10);
        List<String[]> ai = service.loadRecentMessages("AI", "alice", "", 10);

        assertEquals(1, group.size());
        assertEquals(1, companion.size());
        assertEquals(1, ai.size());
        assertEquals("group msg", group.get(0)[2]);
        assertEquals("companion msg", companion.get(0)[2]);
        assertEquals("ai msg", ai.get(0)[2]);
    }

    @Test
    @Order(9)
    @DisplayName("TC-CHS-009: 私聊双向查询 - 双方消息聚合")
    void testPrivateChat_SymmetricQuery() {
        // alice 发给 bob 和 bob 发给 alice 应能互相查到
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "from alice", "1719780000000");
        service.appendMessage("PRIVATE", "bob", "alice",
                "bob", "from bob", "1719780001000");

        List<String[]> aliceView = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        List<String[]> bobView = service.loadRecentMessages(
                "PRIVATE", "bob", "alice", 10);

        assertEquals(2, aliceView.size());
        assertEquals(2, bobView.size());
    }

    @Test
    @Order(10)
    @DisplayName("TC-CHS-010: 空聊天记录 - 返回空列表")
    void testLoadRecentMessages_Empty() {
        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "nobody", 10);
        assertTrue(messages.isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("TC-CHS-011: 非时间戳时间 - 使用当前时间存储")
    void testFormatTime_NotTimestamp() {
        // 非数字时间戳应回退为当前时间，消息仍能正确写入和读取
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "test", "2024-07-01 12:00:00");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(1, messages.size());
        assertNotNull(messages.get(0)[0], "时间字段不应为空");
        assertEquals("test", messages.get(0)[2]);
    }

    @Test
    @Order(12)
    @DisplayName("TC-CHS-012: 数据库表自动可用")
    void testDatabaseTableAvailable() {
        // 验证 chat_message 表存在且可写入（替代原"自动创建目录"测试）
        service.appendMessage("PRIVATE", "alice", "bob",
                "alice", "first message", "1719780000000");

        List<String[]> messages = service.loadRecentMessages(
                "PRIVATE", "alice", "bob", 10);
        assertEquals(1, messages.size(),
                "写入消息后应能从数据库读取");
    }
}
