package org.example.util;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 消息协议工具类单元测试
 * 测试范围：消息构建、序列化/反序列化、消息ID生成、边界条件
 */
class MessageProtocolTest {

    @Test
    @DisplayName("TC-PROTO-001: 构建消息 - 字段完整正确")
    void testBuildMessage_AllFieldsPresent() {
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_PRIVATE_CHAT, "alice", "bob", "hello");

        assertEquals(MessageProtocol.TYPE_PRIVATE_CHAT, msg.get("type").getAsString());
        assertEquals("alice", msg.get("sender").getAsString());
        assertEquals("bob", msg.get("receiver").getAsString());
        assertEquals("hello", msg.get("content").getAsString());
        assertNotNull(msg.get("msgId"), "应包含 msgId 字段");
        assertNotNull(msg.get("time"), "应包含 time 字段");
        assertFalse(msg.get("msgId").getAsString().isEmpty(), "msgId 不应为空");
    }

    @Test
    @DisplayName("TC-PROTO-002: 构建消息 - msgId 自动生成且唯一")
    void testBuildMessage_MsgIdUnique() {
        JsonObject msg1 = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, "a", "b", "1");
        JsonObject msg2 = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, "a", "b", "1");

        assertNotEquals(msg1.get("msgId").getAsString(), msg2.get("msgId").getAsString(),
                "每次构建消息 msgId 应唯一");
    }

    @Test
    @DisplayName("TC-PROTO-003: 带指定 msgId 构建消息")
    void testBuildMessageWithId_SpecifiedMsgId() {
        String customId = "abc123xyz";
        JsonObject msg = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_MSG_ACK, "server", "alice", "ok", customId);

        assertEquals(customId, msg.get("msgId").getAsString());
        assertEquals(MessageProtocol.TYPE_MSG_ACK, msg.get("type").getAsString());
    }

    @Test
    @DisplayName("TC-PROTO-004: 序列化/反序列化 - toWire & fromWire 一致")
    void testToWireAndFromWire_Consistent() {
        JsonObject original = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_GROUP_CHAT, "user1", "group1", "test message");

        String wire = MessageProtocol.toWire(original);
        assertTrue(wire.endsWith("\n"), "toWire 结果应以换行符结尾");

        JsonObject parsed = MessageProtocol.fromWire(wire);
        assertEquals(original.get("type").getAsString(), parsed.get("type").getAsString());
        assertEquals(original.get("sender").getAsString(), parsed.get("sender").getAsString());
        assertEquals(original.get("content").getAsString(), parsed.get("content").getAsString());
        assertEquals(original.get("msgId").getAsString(), parsed.get("msgId").getAsString());
    }

    @Test
    @DisplayName("TC-PROTO-005: 快速响应消息构建")
    void testBuildResponse_QuickResponse() {
        String resp = MessageProtocol.buildResponse(
                MessageProtocol.TYPE_LOGIN_FAIL, "用户名或密码错误");

        JsonObject parsed = MessageProtocol.fromWire(resp);
        assertEquals(MessageProtocol.TYPE_LOGIN_FAIL, parsed.get("type").getAsString());
        assertEquals("server", parsed.get("sender").getAsString());
        assertEquals("用户名或密码错误", parsed.get("content").getAsString());
    }

    @Test
    @DisplayName("TC-PROTO-006: 消息 ID 生成 - 长度为 16")
    void testGenerateMsgId_Length16() {
        String msgId = MessageProtocol.generateMsgId();
        assertEquals(16, msgId.length(), "msgId 长度应为 16 字符");
    }

    @Test
    @DisplayName("TC-PROTO-007: 消息 ID 生成 - 不含横杠")
    void testGenerateMsgId_NoHyphen() {
        String msgId = MessageProtocol.generateMsgId();
        assertFalse(msgId.contains("-"), "msgId 不应包含横杠");
    }

    @Test
    @DisplayName("TC-PROTO-008: 空内容消息构建")
    void testBuildMessage_EmptyContent() {
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, "a", "b", "");
        assertEquals("", msg.get("content").getAsString());
    }

    @Test
    @DisplayName("TC-PROTO-009: 特殊字符内容消息")
    void testBuildMessage_SpecialChars() {
        String special = "测试\n换行\t制表符\"引号{json}";
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, "a", "b", special);

        String wire = MessageProtocol.toWire(msg);
        JsonObject parsed = MessageProtocol.fromWire(wire);
        assertEquals(special, parsed.get("content").getAsString(),
                "特殊字符序列化后应保持一致");
    }

    @Test
    @DisplayName("TC-PROTO-010: 长消息内容（10KB）")
    void testBuildMessage_LongContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append('x');
        }
        String longContent = sb.toString();

        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, "a", "b", longContent);
        String wire = MessageProtocol.toWire(msg);
        JsonObject parsed = MessageProtocol.fromWire(wire);

        assertEquals(longContent, parsed.get("content").getAsString(),
                "长消息内容应完整传递");
    }

    @Test
    @DisplayName("TC-PROTO-011: 所有消息类型常量定义")
    void testMessageTypeConstants_Defined() {
        // P0 核心类型
        assertNotNull(MessageProtocol.TYPE_PING);
        assertNotNull(MessageProtocol.TYPE_PONG);
        assertNotNull(MessageProtocol.TYPE_MSG_ACK);
        assertNotNull(MessageProtocol.TYPE_MSG_DELIVERED);
        assertNotNull(MessageProtocol.TYPE_MSG_READ);
        assertNotNull(MessageProtocol.TYPE_UNREAD_MESSAGES);
        assertNotNull(MessageProtocol.TYPE_RECALL);
        assertNotNull(MessageProtocol.TYPE_IMAGE);

        // P1 功能类型
        assertNotNull(MessageProtocol.TYPE_CREATE_GROUP);
        assertNotNull(MessageProtocol.TYPE_JOIN_GROUP);
        assertNotNull(MessageProtocol.TYPE_LEAVE_GROUP);
        assertNotNull(MessageProtocol.TYPE_KICK_MEMBER);
        assertNotNull(MessageProtocol.TYPE_MENTION_NOTIFY);
        assertNotNull(MessageProtocol.TYPE_BLOCK_USER);
        assertNotNull(MessageProtocol.TYPE_SEARCH_MESSAGES);
        assertNotNull(MessageProtocol.TYPE_CHAT_SUMMARY);
    }

    @Test
    @DisplayName("TC-PROTO-012: 异常 JSON 解析不崩溃")
    void testFromWire_InvalidJson() {
        // 非 JSON 字符串解析
        JsonObject result = MessageProtocol.fromWire("not a json");
        assertNull(result, "无效 JSON 应返回 null");
    }
}
