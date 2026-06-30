package org.example.service;

import org.example.util.DBUtil;

import java.sql.*;
import java.util.*;

/**
 * 聊天记录持久化服务（统一数据库存储版）
 * 负责聊天记录的读取、写入和删除，所有数据统一存入 SQLite chat_message 表
 *
 * 改进说明：
 * 原实现使用 chat_history/ 目录的文件系统存储，与数据库存储并行，
 * 存在数据一致性风险（文件删除但数据库未删除，或反之）。
 * 现统一为数据库存储，消除双写不一致问题。
 *
 * 兼容性：保留文件构造函数用于测试隔离，但实际不再写文件
 */
public class ChatHistoryService {

    /** 聊天类型常量：与数据库 chat_message.chat_type 对应 */
    private static final int CHAT_TYPE_PRIVATE = 0;
    private static final int CHAT_TYPE_GROUP = 1;

    /** 消息类型常量 */
    private static final int MSG_TYPE_TEXT = 0;

    /**
     * 默认构造函数
     */
    public ChatHistoryService() {
    }

    /**
     * 指定存储目录的构造函数（保留兼容性，用于测试隔离）
     *
     * @param baseDir 基础目录（当前版本不使用文件存储，仅保留接口兼容）
     */
    public ChatHistoryService(java.nio.file.Path baseDir) {
        // 兼容旧接口，不再使用文件存储
    }

    /**
     * 追加一条消息到数据库
     * 统一存储入口：私聊和群聊都写入 chat_message 表
     *
     * @param chatType    聊天类型：GROUP, PRIVATE, AI, COMPANION
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param sender      消息发送者
     * @param content     消息内容
     * @param timeStr     时间戳字符串（毫秒）
     */
    public void appendMessage(String chatType, String currentUser, String target,
                              String sender, String content, String timeStr) {
        long timestamp;
        try {
            timestamp = Long.parseLong(timeStr);
        } catch (NumberFormatException e) {
            // 非数字时间戳，使用当前时间
            timestamp = System.currentTimeMillis();
        }

        int dbChatType = "GROUP".equals(chatType) ? CHAT_TYPE_GROUP : CHAT_TYPE_PRIVATE;
        if (dbChatType == CHAT_TYPE_GROUP) {
            // 群聊消息：receiver_id = 0（群聊不针对特定接收者），仅通过 sender 查找用户
            saveGroupMessageToDB(sender, content, timestamp);
        } else {
            // 私聊/AI聊天：receiver 为对话另一方
            // 当 sender == target 时（如 AI 回复，target 始终为 AI 名），receiver 应为 currentUser
            String receiver = sender.equals(target) ? currentUser : target;
            saveMessageToDB(sender, receiver, dbChatType, MSG_TYPE_TEXT, content, timestamp);
        }
    }

    /**
     * 加载最近 N 条聊天记录
     * 从数据库查询，替代原文件读取
     *
     * @param chatType    聊天类型
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param limit       加载条数上限
     * @return 消息记录列表，每条记录为 [时间, 发送者, 内容]
     */
    public List<String[]> loadRecentMessages(String chatType, String currentUser,
                                              String target, int limit) {
        List<String[]> result = new ArrayList<>();

        if ("GROUP".equals(chatType)) {
            // 群聊：查 chat_type=1 的消息
            String sql = "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm "
                    + "JOIN `user` u ON cm.sender_id = u.id "
                    + "WHERE cm.chat_type = ? "
                    + "ORDER BY cm.sent_at DESC LIMIT ?";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, CHAT_TYPE_GROUP);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String time = rs.getString("sent_at");
                        String sender = rs.getString("sender_name");
                        String msgContent = rs.getString("content");
                        result.add(new String[]{time, sender, msgContent});
                    }
                }
            } catch (SQLException e) {
                System.err.println("[ChatHistoryService] 读取群聊记录失败: " + e.getMessage());
            }
        } else if ("COMPANION".equals(chatType) || "AI".equals(chatType)) {
            // AI 聊天：查与 AI 的消息（sender 或 receiver 为 AI 标识）
            String aiTarget = "COMPANION".equals(chatType) ? "AI伴侣" : "AI小助手";
            String sql = "SELECT sender_name, content, sent_at FROM ("
                    + "SELECT u1.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm "
                    + "JOIN `user` u1 ON cm.sender_id = u1.id "
                    + "JOIN `user` u2 ON cm.receiver_id = u2.id "
                    + "WHERE cm.chat_type = ? AND ("
                    + "(u1.username = ? AND u2.username = ?) OR (u1.username = ? AND u2.username = ?)) "
                    + "ORDER BY cm.sent_at DESC LIMIT ?"
                    + ") AS recent ORDER BY sent_at ASC";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, CHAT_TYPE_PRIVATE);
                ps.setString(2, currentUser);
                ps.setString(3, aiTarget);
                ps.setString(4, aiTarget);
                ps.setString(5, currentUser);
                ps.setInt(6, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new String[]{
                                rs.getString("sent_at"),
                                rs.getString("sender_name"),
                                rs.getString("content")
                        });
                    }
                }
            } catch (SQLException e) {
                System.err.println("[ChatHistoryService] 读取AI聊天记录失败: " + e.getMessage());
            }
        } else {
            // 私聊：查两个用户之间的消息
            String sql = "SELECT sender_name, content, sent_at FROM ("
                    + "SELECT u1.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm "
                    + "JOIN `user` u1 ON cm.sender_id = u1.id "
                    + "JOIN `user` u2 ON cm.receiver_id = u2.id "
                    + "WHERE cm.chat_type = ? AND ("
                    + "(u1.username = ? AND u2.username = ?) OR (u1.username = ? AND u2.username = ?)) "
                    + "ORDER BY cm.sent_at DESC LIMIT ?"
                    + ") AS recent ORDER BY sent_at ASC";
            try (Connection conn = DBUtil.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, CHAT_TYPE_PRIVATE);
                ps.setString(2, currentUser);
                ps.setString(3, target);
                ps.setString(4, target);
                ps.setString(5, currentUser);
                ps.setInt(6, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(new String[]{
                                rs.getString("sent_at"),
                                rs.getString("sender_name"),
                                rs.getString("content")
                        });
                    }
                }
            } catch (SQLException e) {
                System.err.println("[ChatHistoryService] 读取私聊记录失败: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * 删除指定的一条聊天记录
     * 从数据库删除，替代原文件删除
     *
     * @param chatType    聊天类型
     * @param currentUser 当前用户名
     * @param target      聊天对象
     * @param sender      消息发送者
     * @param content     消息内容
     * @param timeStr     时间戳字符串（毫秒）
     * @return 是否成功删除
     */
    public boolean deleteMessage(String chatType, String currentUser, String target,
                                  String sender, String content, String timeStr) {
        int dbChatType = "GROUP".equals(chatType) ? CHAT_TYPE_GROUP : CHAT_TYPE_PRIVATE;

        // 按 sender + content + chat_type 匹配删除
        // 不依赖时间戳匹配（timeStr 可能是格式化时间或毫秒时间戳，无法统一解析）
        String sql = "DELETE FROM chat_message "
                + "WHERE sender_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND content = ? AND chat_type = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sender);
            ps.setString(2, content);
            ps.setInt(3, dbChatType);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                System.out.println("[ChatHistoryService] 已删除 " + rows + " 条消息记录");
            }
            return rows > 0;
        } catch (SQLException e) {
            System.err.println("[ChatHistoryService] 删除消息失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 存储群聊消息到数据库（receiver_id = 0，与 HandlerContext.saveGroupMessage 保持一致）
     */
    private void saveGroupMessageToDB(String sender, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u.id, 0, ?, ?, ?, datetime(? / 1000, 'unixepoch') "
                + "FROM `user` u WHERE u.username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, CHAT_TYPE_GROUP);
            ps.setInt(2, MSG_TYPE_TEXT);
            ps.setString(3, content);
            ps.setLong(4, timestamp);
            ps.setString(5, sender);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ChatHistoryService] 群聊消息存储失败: " + e.getMessage());
        }
    }

    /**
     * 存储消息到数据库
     */
    private void saveMessageToDB(String sender, String receiver, int chatType,
                                  int messageType, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u1.id, u2.id, ?, ?, ?, datetime(? / 1000, 'unixepoch') "
                + "FROM `user` u1, `user` u2 WHERE u1.username = ? AND u2.username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, chatType);
            ps.setInt(2, messageType);
            ps.setString(3, content);
            ps.setLong(4, timestamp);
            ps.setString(5, sender);
            ps.setString(6, receiver);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ChatHistoryService] 消息存储失败: " + e.getMessage());
        }
    }
}
