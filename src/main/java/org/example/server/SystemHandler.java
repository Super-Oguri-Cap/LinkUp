package org.example.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.util.DBUtil;
import org.example.util.MessageProtocol;

import java.sql.*;

/**
 * 系统消息处理器 - 心跳、登出、已读回执、撤回、删除消息、黑名单、搜索、AI摘要
 * 从 ClientHandler 拆分
 */
public class SystemHandler implements MessageHandler {

    @Override
    public void handle(JsonObject msg, HandlerContext ctx) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case MessageProtocol.TYPE_PING:
                ctx.sendRaw("{\"type\":\"PONG\",\"sender\":\"server\",\"content\":\"\"}\n");
                break;
            case MessageProtocol.TYPE_MSG_READ:
                handleMsgRead(msg, ctx);
                break;
            case MessageProtocol.TYPE_RECALL:
                handleRecall(msg, ctx);
                break;
            case MessageProtocol.TYPE_DELETE_MESSAGE:
                handleDeleteMessage(msg, ctx);
                break;
            case MessageProtocol.TYPE_LOGOUT:
                // 登出由 ClientHandler.disconnect() 处理
                break;
            case MessageProtocol.TYPE_BLOCK_USER:
                handleBlockUser(msg, ctx);
                break;
            case MessageProtocol.TYPE_UNBLOCK_USER:
                handleUnblockUser(msg, ctx);
                break;
            case MessageProtocol.TYPE_SEARCH_MESSAGES:
                handleSearchMessages(msg, ctx);
                break;
            case MessageProtocol.TYPE_CHAT_SUMMARY:
                handleChatSummary(msg, ctx);
                break;
        }
    }

    private void handleMsgRead(JsonObject msg, HandlerContext ctx) {
        String msgId = msg.get("content").getAsString();
        String originalSender = msg.get("receiver").getAsString();

        String sql = "UPDATE chat_message SET is_read = 1 WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(msgId));
            ps.executeUpdate();
        } catch (SQLException | NumberFormatException e) {
            System.err.println("[已读] 更新失败: " + e.getMessage());
        }

        ClientHandler sender = ctx.getServer().getClient(originalSender);
        if (sender != null) {
            JsonObject readNotify = new JsonObject();
            readNotify.addProperty("type", MessageProtocol.TYPE_MSG_READ);
            readNotify.addProperty("sender", "server");
            readNotify.addProperty("content", msgId);
            sender.sendRaw(MessageProtocol.toWire(readNotify));
        }
    }

    private void handleRecall(JsonObject msg, HandlerContext ctx) {
        String contentStr = msg.get("content").getAsString();
        String username = ctx.getUsername();
        JsonObject data = MessageProtocol.fromWire(contentStr);
        if (data == null) {
            ctx.sendError("撤回请求格式错误");
            return;
        }
        String msgId = data.get("msgId").getAsString();
        String target = data.get("target").getAsString();

        String sql = "DELETE FROM chat_message WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(msgId));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[撤回] 数据库删除失败: " + e.getMessage());
        }

        ClientHandler targetHandler = ctx.getServer().getClient(target);
        if (targetHandler != null) {
            JsonObject recallNotify = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_RECALL, username, target, msgId, msgId);
            targetHandler.sendRaw(MessageProtocol.toWire(recallNotify));
        }
        JsonObject selfNotify = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_RECALL, "server", username, msgId, msgId);
        ctx.sendRaw(MessageProtocol.toWire(selfNotify));
    }

    private void handleDeleteMessage(JsonObject msg, HandlerContext ctx) {
        String payload = msg.get("content").getAsString();
        String username = ctx.getUsername();
        JsonObject data = MessageProtocol.fromWire(payload);
        if (data == null) return;

        String msgSender = data.get("sender").getAsString();
        String msgContent = data.get("content").getAsString();
        String chatTypeStr = data.get("chatType").getAsString();
        String msgIdStr = data.has("msgId") ? data.get("msgId").getAsString() : "";
        int chatType = "GROUP".equals(chatTypeStr) ? 1 : 0;

        // 优先使用 msgId 精确定位删除（避免 content 相同导致误删多条）
        String sql;
        if (msgIdStr != null && !msgIdStr.isEmpty()) {
            sql = "DELETE FROM chat_message WHERE id = ?";
        } else {
            // 兼容旧版本：使用 sender + content + chat_type（可能误删多条）
            sql = "DELETE FROM chat_message "
                    + "WHERE sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND content = ? AND chat_type = ?";
        }
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (msgIdStr != null && !msgIdStr.isEmpty()) {
                ps.setLong(1, Long.parseLong(msgIdStr));
            } else {
                ps.setString(1, msgSender);
                ps.setString(2, msgContent);
                ps.setInt(3, chatType);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[消息删除] 删除失败: " + e.getMessage());
        }
    }

    private void handleBlockUser(JsonObject msg, HandlerContext ctx) {
        String targetUser = msg.get("content").getAsString();
        String username = ctx.getUsername();
        String checkSql = "SELECT "
                + "CASE WHEN (SELECT id FROM `user` WHERE username = ?) = f.user_a_id THEN 1 ELSE 2 END AS side "
                + "FROM friendship f "
                + "WHERE (f.user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND f.user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                + "OR (f.user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND f.user_b_id = (SELECT id FROM `user` WHERE username = ?))";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setString(3, targetUser);
            ps.setString(4, targetUser);
            ps.setString(5, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    ctx.sendError("拉黑失败：对方不是你的好友");
                    return;
                }
                int side = rs.getInt("side");
                String updateSql = "UPDATE friendship SET is_blocked = 1, blocked_by = ? "
                        + "WHERE (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                        + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                        + "OR (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                        + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?))";
                try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                    ups.setInt(1, side);
                    ups.setString(2, username);
                    ups.setString(3, targetUser);
                    ups.setString(4, targetUser);
                    ups.setString(5, username);
                    ups.executeUpdate();
                    ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已拉黑 " + targetUser);
                }
            }
        } catch (SQLException e) {
            ctx.sendError("拉黑失败: " + e.getMessage());
        }
    }

    private void handleUnblockUser(JsonObject msg, HandlerContext ctx) {
        String targetUser = msg.get("content").getAsString();
        String username = ctx.getUsername();
        String sql = "UPDATE friendship SET is_blocked = 0, blocked_by = 0 "
                + "WHERE (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                + "OR (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?))";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, targetUser);
            ps.setString(3, targetUser);
            ps.setString(4, username);
            ps.executeUpdate();
            ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已取消拉黑 " + targetUser);
        } catch (SQLException e) {
            ctx.sendError("取消拉黑失败: " + e.getMessage());
        }
    }

    private void handleSearchMessages(JsonObject msg, HandlerContext ctx) {
        String contentStr = msg.get("content").getAsString();
        String username = ctx.getUsername();
        JsonObject data = MessageProtocol.fromWire(contentStr);
        if (data == null) {
            ctx.sendError("搜索请求格式错误");
            return;
        }
        String keyword = data.get("keyword").getAsString();
        String target = data.has("target") ? data.get("target").getAsString() : null;

        String sql;
        if (target != null) {
            sql = "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm JOIN `user` u ON cm.sender_id = u.id "
                    + "WHERE cm.content LIKE ? AND cm.chat_type = ? "
                    + "AND ((cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND cm.receiver_id = (SELECT id FROM `user` WHERE username = ?)) "
                    + "OR (cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND cm.receiver_id = (SELECT id FROM `user` WHERE username = ?))) "
                    + "ORDER BY cm.sent_at DESC LIMIT 30";
        } else {
            sql = "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm JOIN `user` u ON cm.sender_id = u.id "
                    + "WHERE cm.content LIKE ? "
                    + "AND (cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "OR cm.receiver_id = (SELECT id FROM `user` WHERE username = ?)) "
                    + "ORDER BY cm.sent_at DESC LIMIT 30";
        }

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            if (target != null) {
                ps.setInt(2, HandlerContext.CHAT_TYPE_PRIVATE);
                ps.setString(3, username);
                ps.setString(4, target);
                ps.setString(5, target);
                ps.setString(6, username);
            } else {
                ps.setString(2, username);
                ps.setString(3, username);
            }
            try (ResultSet rs = ps.executeQuery()) {
                JsonArray results = new JsonArray();
                while (rs.next()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("sender", rs.getString("sender_name"));
                    item.addProperty("content", rs.getString("content"));
                    item.addProperty("time", rs.getString("sent_at"));
                    results.add(item);
                }
                JsonObject response = new JsonObject();
                response.addProperty("type", MessageProtocol.TYPE_SEARCH_RESULT);
                response.addProperty("sender", "server");
                response.addProperty("receiver", keyword);
                response.addProperty("content", results.toString());
                ctx.sendRaw(MessageProtocol.toWire(response));
            }
        } catch (SQLException e) {
            ctx.sendError("搜索失败: " + e.getMessage());
        }
    }

    private void handleChatSummary(JsonObject msg, HandlerContext ctx) {
        String contentStr = msg.get("content").getAsString();
        final String username = ctx.getUsername();
        JsonObject data = MessageProtocol.fromWire(contentStr);
        if (data == null) return;
        final String target = data.get("target").getAsString();

        final String context = ctx.loadRecentChatContext(username, target, 20);
        if (context.isEmpty()) return;

        ctx.getServer().getExecutorService().submit(() -> {
            try {
                // 使用私聊摘要而非群聊摘要
                String summary = AIAssistant.summarizeChat(context, target, username);
                JsonObject reply = new JsonObject();
                reply.addProperty("type", MessageProtocol.TYPE_CHAT_SUMMARY);
                reply.addProperty("sender", "AI");
                reply.addProperty("receiver", target);
                reply.addProperty("content", summary);
                ctx.sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                System.err.println("[AI摘要] 生成失败: " + e.getMessage());
            }
        });
    }
}
