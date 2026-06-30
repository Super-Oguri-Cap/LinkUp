package org.example.server;

import com.google.gson.JsonObject;
import org.example.util.DBUtil;
import org.example.util.MessageProtocol;
import org.example.util.PasswordUtil;

import java.sql.*;

/**
 * 认证消息处理器 - 处理登录和注册
 * 从 ClientHandler 拆分，负责用户身份验证相关逻辑
 */
public class AuthHandler implements MessageHandler {

    @Override
    public void handle(JsonObject msg, HandlerContext ctx) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case MessageProtocol.TYPE_LOGIN:
                handleLogin(msg, ctx);
                break;
            case MessageProtocol.TYPE_REGISTER:
                handleRegister(msg, ctx);
                break;
        }
    }

    /**
     * 处理登录请求：验证用户名和密码，密码使用 SHA-256 + 盐值校验
     */
    private void handleLogin(JsonObject msg, HandlerContext ctx) {
        String loginUsername = msg.get("sender").getAsString();
        String password = msg.get("content").getAsString();
        System.out.println("[登录] 收到登录请求 - 用户名: " + loginUsername);

        String sql = "SELECT id, password, salt, nickname FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, loginUsername);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password");
                    String storedSalt = rs.getString("salt");

                    if (PasswordUtil.verify(password, storedHash, storedSalt)) {
                        ctx.setUsername(loginUsername);
                        ctx.getServer().addClient(loginUsername, ctx.getClientHandler());

                        // 更新最后登录时间和在线状态
                        updateLoginStatus(loginUsername);

                        // 发送登录成功 + 用户列表
                        ctx.sendMessage(MessageProtocol.TYPE_LOGIN_SUCCESS,
                                "登录成功！欢迎 " + rs.getString("nickname"));
                        sendUserList(ctx);
                        broadcastUserStatus(ctx, loginUsername, true);

                        // 登录后推送待处理的好友请求
                        pushPendingFriendRequests(ctx, loginUsername);

                        // P0: 登录后推送离线未读消息
                        pushUnreadMessages(ctx, loginUsername);

                        System.out.println("[登录] 用户 " + loginUsername + " 登录成功");
                    } else {
                        ctx.sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "密码错误");
                        System.out.println("[登录] 用户 " + loginUsername + " 密码错误");
                    }
                } else {
                    ctx.sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "用户不存在");
                    System.out.println("[登录] 用户 " + loginUsername + " 不存在");
                }
            }
        } catch (SQLException e) {
            System.err.println("[登录] 数据库异常: " + e.getMessage());
            ctx.sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "服务器数据库错误: " + e.getMessage());
        }
    }

    /**
     * 处理注册请求：校验用户名唯一性，SHA-256 + 盐值加密存储
     */
    private void handleRegister(JsonObject msg, HandlerContext ctx) {
        String regUsername = msg.get("sender").getAsString();

        JsonObject content;
        try {
            String contentStr = msg.get("content").getAsString();
            content = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            ctx.sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "注册数据格式错误");
            return;
        }

        String password = content.get("password").getAsString();
        String nickname = content.get("nickname").getAsString();
        System.out.println("[注册] 收到注册请求 - 用户名: " + regUsername + ", 昵称: " + nickname);

        String checkSql = "SELECT COUNT(*) FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement checkPs = conn.prepareStatement(checkSql)) {

            checkPs.setString(1, regUsername);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    ctx.sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "用户名已存在");
                    return;
                }
            }

            String salt = PasswordUtil.generateSalt();
            String hash = PasswordUtil.hash(password, salt);

            String insertSql = "INSERT INTO `user` (username, password, salt, nickname) VALUES (?, ?, ?, ?)";
            try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                insertPs.setString(1, regUsername);
                insertPs.setString(2, hash);
                insertPs.setString(3, salt);
                insertPs.setString(4, nickname);
                insertPs.executeUpdate();
            }

            ctx.sendMessage(MessageProtocol.TYPE_REGISTER_SUCCESS, "注册成功，请登录");
            System.out.println("[注册] 用户 " + regUsername + " 注册成功");
        } catch (SQLException e) {
            ctx.sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "注册失败: " + e.getMessage());
            System.err.println("[注册] 失败: " + e.getMessage());
        }
    }

    // ==================== 辅助方法 ====================

    private void updateLoginStatus(String username) {
        String sql = "UPDATE `user` SET online_status = 1, last_login_at = datetime('now') WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AuthHandler] 更新登录状态失败: " + e.getMessage());
        }
    }

    private void sendUserList(HandlerContext ctx) {
        JsonObject userListJson = new JsonObject();
        userListJson.addProperty("type", MessageProtocol.TYPE_USER_LIST);
        userListJson.addProperty("sender", "server");
        String userList = "[" + String.join(",",
                ctx.getServer().getOnlineUsers().stream()
                        .map(u -> "\"" + u + "\"")
                        .toArray(String[]::new)) + "]";
        userListJson.addProperty("content", userList);
        ctx.sendRaw(MessageProtocol.toWire(userListJson));
    }

    private void broadcastUserStatus(HandlerContext ctx, String username, boolean online) {
        JsonObject status = new JsonObject();
        status.addProperty("type", online ? MessageProtocol.TYPE_USER_ONLINE : MessageProtocol.TYPE_USER_OFFLINE);
        status.addProperty("sender", "server");
        status.addProperty("content", username);
        ctx.getServer().broadcast(MessageProtocol.toWire(status));
    }

    private void pushPendingFriendRequests(HandlerContext ctx, String username) {
        String sql = "SELECT fr.id, u.username AS requester_name "
                + "FROM friend_request fr "
                + "JOIN `user` u ON fr.from_user_id = u.id "
                + "WHERE fr.to_user_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND fr.status = 0";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long requestId = rs.getLong("id");
                    String requesterName = rs.getString("requester_name");
                    JsonObject notifyMsg = MessageProtocol.buildMessage(
                            MessageProtocol.TYPE_FRIEND_REQUEST_NOTIFY,
                            requesterName, String.valueOf(requestId), requesterName);
                    ctx.sendRaw(MessageProtocol.toWire(notifyMsg));
                }
            }
        } catch (SQLException e) {
            System.err.println("[AuthHandler] 推送待处理请求失败: " + e.getMessage());
        }
    }

    private void pushUnreadMessages(HandlerContext ctx, String loginUser) {
        String sql = "SELECT cm.id, u.username AS sender_name, cm.content, cm.sent_at, cm.chat_type "
                + "FROM chat_message cm "
                + "JOIN `user` u ON cm.sender_id = u.id "
                + "WHERE cm.receiver_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND cm.is_read = 0 AND cm.chat_type = ? "
                + "ORDER BY cm.sent_at ASC LIMIT 50";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loginUser);
            ps.setInt(2, HandlerContext.CHAT_TYPE_PRIVATE);
            try (ResultSet rs = ps.executeQuery()) {
                com.google.gson.JsonArray unreadArray = new com.google.gson.JsonArray();
                while (rs.next()) {
                    JsonObject msgObj = new JsonObject();
                    msgObj.addProperty("msgId", String.valueOf(rs.getLong("id")));
                    msgObj.addProperty("sender", rs.getString("sender_name"));
                    msgObj.addProperty("content", rs.getString("content"));
                    msgObj.addProperty("time", rs.getString("sent_at"));
                    msgObj.addProperty("chatType", rs.getInt("chat_type"));
                    unreadArray.add(msgObj);
                }
                if (unreadArray.size() > 0) {
                    JsonObject push = new JsonObject();
                    push.addProperty("type", MessageProtocol.TYPE_UNREAD_MESSAGES);
                    push.addProperty("sender", "server");
                    push.addProperty("content", unreadArray.toString());
                    ctx.sendRaw(MessageProtocol.toWire(push));
                    System.out.println("[离线消息] 为 " + loginUser + " 推送了 " + unreadArray.size() + " 条未读消息");
                }
            }
        } catch (SQLException e) {
            System.err.println("[AuthHandler] 查询未读消息失败: " + e.getMessage());
        }
    }
}
