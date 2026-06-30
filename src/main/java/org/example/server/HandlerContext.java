package org.example.server;

import com.google.gson.JsonObject;
import org.example.util.MessageProtocol;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处理器上下文 - 为各 Handler 提供对 ClientHandler 核心能力的访问
 * 避免将 ClientHandler 整体暴露给 Handler，减少耦合
 */
public class HandlerContext {

    private final ClientHandler handler;
    private final LinkUpServer server;

    /** 消息去重缓存：记录最近处理的 msgId，防止重复 */
    private static final Set<String> recentMsgIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_DEDUP_SIZE = 5000;

    /** 好友上限常量 */
    public static final int MAX_FRIENDS = 150;

    /** 会话类型常量 */
    public static final int CHAT_TYPE_PRIVATE = 0;
    public static final int CHAT_TYPE_GROUP = 1;

    /** 消息类型常量 */
    public static final int MSG_TYPE_TEXT = 0;
    public static final int MSG_TYPE_IMAGE = 1;

    public HandlerContext(ClientHandler handler, LinkUpServer server) {
        this.handler = handler;
        this.server = server;
    }

    // ==================== 核心访问方法 ====================

    public String getUsername() {
        return handler.getUsername();
    }

    public void setUsername(String username) {
        handler.setUsername(username);
    }

    public LinkUpServer getServer() {
        return server;
    }

    public ClientHandler getClientHandler() {
        return handler;
    }

    public void sendRaw(String raw) {
        handler.sendRaw(raw);
    }

    public void sendMessage(String type, String content) {
        sendRaw(MessageProtocol.buildResponse(type, content));
    }

    public void sendError(String errorMsg) {
        sendRaw(MessageProtocol.buildResponse(MessageProtocol.TYPE_ERROR, errorMsg));
    }

    // ==================== 消息去重 ====================

    /**
     * 检查消息是否重复（基于 msgId）
     * @return true 表示消息不重复（首次出现），false 表示重复
     */
    public boolean checkAndAddMsgId(String msgId) {
        if (msgId == null || msgId.isEmpty()) {
            return true; // 无 msgId 的消息不做去重
        }
        if (!recentMsgIds.add(msgId)) {
            return false; // 重复
        }
        if (recentMsgIds.size() > MAX_DEDUP_SIZE) {
            recentMsgIds.clear();
        }
        return true;
    }

    // ==================== 数据库操作辅助 ====================

    /**
     * 存储私聊消息到数据库
     */
    public void saveMessage(String sender, String receiver, int chatType, int messageType, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u1.id, u2.id, ?, ?, ?, datetime(? / 1000, 'unixepoch') "
                + "FROM `user` u1, `user` u2 WHERE u1.username = ? AND u2.username = ?";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, chatType);
            ps.setInt(2, messageType);
            ps.setString(3, content);
            ps.setLong(4, timestamp);
            ps.setString(5, sender);
            ps.setString(6, receiver);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[HandlerContext] 消息存储失败: " + e.getMessage());
        }
    }

    /**
     * 存储群聊消息到数据库
     */
    public void saveGroupMessage(String sender, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u.id, 0, ?, ?, ?, datetime(? / 1000, 'unixepoch') "
                + "FROM `user` u WHERE u.username = ?";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, CHAT_TYPE_GROUP);
            ps.setInt(2, MSG_TYPE_TEXT);
            ps.setString(3, content);
            ps.setLong(4, timestamp);
            ps.setString(5, sender);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[HandlerContext] 群聊消息存储失败: " + e.getMessage());
        }
    }

    /**
     * 检查 userA 是否被 userB 拉黑
     */
    public boolean isBlocked(String userNameA, String userNameB) {
        String sql = "SELECT "
                + "CASE WHEN (SELECT id FROM `user` WHERE username = ?) = f.user_a_id THEN "
                + "  (f.is_blocked = 1 AND f.blocked_by = 2) "
                + "ELSE "
                + "  (f.is_blocked = 1 AND f.blocked_by = 1) "
                + "END AS blocked "
                + "FROM friendship f "
                + "WHERE (f.user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND f.user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                + "OR (f.user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND f.user_b_id = (SELECT id FROM `user` WHERE username = ?))";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userNameB);
            ps.setString(2, userNameA);
            ps.setString(3, userNameB);
            ps.setString(4, userNameB);
            ps.setString(5, userNameA);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("blocked") == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 加载最近与指定对象的聊天上下文（用于 AI 长期记忆）
     */
    public String loadRecentChatContext(String user, String target, int limit) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT sender_name, content FROM ("
                + "SELECT u1.username AS sender_name, cm.content, cm.sent_at "
                + "FROM chat_message cm "
                + "JOIN `user` u1 ON cm.sender_id = u1.id "
                + "JOIN `user` u2 ON cm.receiver_id = u2.id "
                + "WHERE cm.chat_type = 0 AND ("
                + "(u1.username = ? AND u2.username = ?) OR (u1.username = ? AND u2.username = ?)) "
                + "ORDER BY cm.sent_at DESC LIMIT ?"
                + ") AS recent ORDER BY sent_at ASC";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user);
            ps.setString(2, target);
            ps.setString(3, target);
            ps.setString(4, user);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append(rs.getString("sender_name"))
                      .append(": ")
                      .append(rs.getString("content"))
                      .append("\n");
                }
            }
        } catch (SQLException e) {
            System.err.println("[HandlerContext] 加载聊天上下文失败: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 加载最近群聊消息（用于摘要）
     */
    public String loadRecentGroupMessages(int limit) {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT sender_name, content FROM ("
                + "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                + "FROM chat_message cm "
                + "JOIN `user` u ON cm.sender_id = u.id "
                + "WHERE cm.chat_type = 1 "
                + "ORDER BY cm.sent_at DESC LIMIT ?"
                + ") AS recent ORDER BY sent_at ASC";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender_name");
                    String content = rs.getString("content");
                    if (content == null || content.trim().isEmpty()) {
                        continue;
                    }
                    if (content.length() > 150) {
                        content = content.substring(0, 150) + "…";
                    }
                    sb.append(sender).append(": ").append(content).append("\n");
                }
            }
        } catch (SQLException e) {
            System.err.println("[HandlerContext] 加载群聊消息失败: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 检查用户名是否存在
     */
    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM `user` WHERE username = ? LIMIT 1";
        try (Connection conn = org.example.util.DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("[HandlerContext] 检查用户存在性失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 向群组添加成员
     */
    public void addGroupMember(Connection conn, int groupId, String memberName, int role) throws SQLException {
        String sql = "INSERT INTO group_member (group_id, user_id, role) "
                + "SELECT ?, id, ? FROM `user` WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setInt(2, role);
            ps.setString(3, memberName);
            ps.executeUpdate();
        }
    }
}
