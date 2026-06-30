package org.example.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.util.DBUtil;
import org.example.util.MessageProtocol;

import java.sql.*;

/**
 * 好友管理处理器 - 处理好友请求、同意、拒绝、删除、刷新列表
 * 从 ClientHandler 拆分
 */
public class FriendHandler implements MessageHandler {

    @Override
    public void handle(JsonObject msg, HandlerContext ctx) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case MessageProtocol.TYPE_FRIEND_REQUEST:
                handleFriendRequest(msg, ctx);
                break;
            case MessageProtocol.TYPE_FRIEND_REQUEST_ACCEPT:
                handleFriendRequestAccept(msg, ctx);
                break;
            case MessageProtocol.TYPE_FRIEND_REQUEST_REJECT:
                handleFriendRequestReject(msg, ctx);
                break;
            case MessageProtocol.TYPE_REMOVE_FRIEND:
                handleRemoveFriend(msg, ctx);
                break;
            case MessageProtocol.TYPE_REFRESH_FRIENDS:
                handleRefreshFriends(ctx);
                break;
        }
    }

    private void handleFriendRequest(JsonObject msg, HandlerContext ctx) {
        String friendName = msg.get("receiver").getAsString();
        String username = ctx.getUsername();

        if (username.equals(friendName)) {
            ctx.sendError("不能添加自己为好友");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            String existSql = "SELECT id FROM `user` WHERE username = ?";
            int friendId;
            try (PreparedStatement ps = conn.prepareStatement(existSql)) {
                ps.setString(1, friendName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ctx.sendError("用户 '" + friendName + "' 不存在");
                        return;
                    }
                    friendId = rs.getInt("id");
                }
            }

            String checkSql = "SELECT COUNT(*) FROM friendship "
                    + "WHERE (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                    + "OR (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?))";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setString(1, username);
                ps.setString(2, friendName);
                ps.setString(3, friendName);
                ps.setString(4, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        ctx.sendError("'" + friendName + "' 已经是您的好友");
                        return;
                    }
                }
            }

            String countSql = "SELECT COUNT(*) FROM friendship "
                    + "WHERE user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "OR user_b_id = (SELECT id FROM `user` WHERE username = ?)";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, username);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= HandlerContext.MAX_FRIENDS) {
                        ctx.sendError("好友数量已达上限（" + HandlerContext.MAX_FRIENDS + "人），无法继续添加");
                        return;
                    }
                }
            }

            String pendingSql = "SELECT COUNT(*) FROM friend_request "
                    + "WHERE from_user_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND to_user_id = ? AND status = 0";
            try (PreparedStatement ps = conn.prepareStatement(pendingSql)) {
                ps.setString(1, username);
                ps.setInt(2, friendId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        ctx.sendError("已向该用户发送过好友请求，请等待对方处理");
                        return;
                    }
                }
            }

            String insertSql = "INSERT INTO friend_request (from_user_id, to_user_id, status) "
                    + "SELECT u1.id, u2.id, 0 FROM `user` u1, `user` u2 "
                    + "WHERE u1.username = ? AND u2.username = ?";
            long requestId;
            try (PreparedStatement ps = conn.prepareStatement(insertSql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, username);
                ps.setString(2, friendName);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    requestId = rs.next() ? rs.getLong(1) : 0;
                }
            }

            ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "好友请求已发送给 " + friendName + "，请等待对方确认");

            ClientHandler targetHandler = ctx.getServer().getClient(friendName);
            if (targetHandler != null) {
                JsonObject notifyMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_FRIEND_REQUEST_NOTIFY, username, String.valueOf(requestId), username);
                targetHandler.sendRaw(MessageProtocol.toWire(notifyMsg));
            }
        } catch (SQLException e) {
            ctx.sendError("发送好友请求失败: " + e.getMessage());
        }
    }

    private void handleFriendRequestAccept(JsonObject msg, HandlerContext ctx) {
        String requester = msg.get("receiver").getAsString();
        String requestIdStr = msg.get("content").getAsString();
        String username = ctx.getUsername();

        long requestId;
        try {
            requestId = Long.parseLong(requestIdStr);
        } catch (NumberFormatException e) {
            ctx.sendError("请求ID格式错误");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            String checkSql = "SELECT status FROM friend_request WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ctx.sendError("好友请求不存在");
                        return;
                    }
                    if (rs.getInt("status") != 0) {
                        ctx.sendError("该好友请求已处理");
                        return;
                    }
                }
            }

            String updateSql = "UPDATE friend_request SET status = 1 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, requestId);
                ps.executeUpdate();
            }

            String insertSql = "INSERT INTO friendship (user_a_id, user_b_id) "
                    + "SELECT LEAST(u1.id, u2.id), GREATEST(u1.id, u2.id) "
                    + "FROM `user` u1, `user` u2 "
                    + "WHERE u1.username = ? AND u2.username = ?";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, requester);
                ps.setString(2, username);
                ps.executeUpdate();
            }

            ClientHandler requesterHandler = ctx.getServer().getClient(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage(MessageProtocol.TYPE_ADD_FRIEND_SUCCESS, username);
            }
            ctx.sendMessage(MessageProtocol.TYPE_ADD_FRIEND_SUCCESS, requester);
        } catch (SQLException e) {
            ctx.sendError("同意好友请求失败: " + e.getMessage());
        }
    }

    private void handleFriendRequestReject(JsonObject msg, HandlerContext ctx) {
        String requester = msg.get("receiver").getAsString();
        String requestIdStr = msg.get("content").getAsString();
        String username = ctx.getUsername();

        long requestId;
        try {
            requestId = Long.parseLong(requestIdStr);
        } catch (NumberFormatException e) {
            ctx.sendError("请求ID格式错误");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            String checkSql = "SELECT status FROM friend_request WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ctx.sendError("好友请求不存在");
                        return;
                    }
                    if (rs.getInt("status") != 0) {
                        ctx.sendError("该好友请求已处理");
                        return;
                    }
                }
            }

            String updateSql = "UPDATE friend_request SET status = 2 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, requestId);
                ps.executeUpdate();
            }

            ClientHandler requesterHandler = ctx.getServer().getClient(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage(MessageProtocol.TYPE_ADD_FRIEND_FAIL,
                        username + " 拒绝了你的好友请求");
            }
            ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已拒绝 " + requester + " 的好友请求");
        } catch (SQLException e) {
            ctx.sendError("拒绝好友请求失败: " + e.getMessage());
        }
    }

    private void handleRemoveFriend(JsonObject msg, HandlerContext ctx) {
        String friendName = msg.get("receiver").getAsString();
        String username = ctx.getUsername();

        String deleteSql = "DELETE FROM friendship WHERE "
                + "(user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                + "OR (user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND user_b_id = (SELECT id FROM `user` WHERE username = ?))";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(deleteSql)) {
            ps.setString(1, username);
            ps.setString(2, friendName);
            ps.setString(3, friendName);
            ps.setString(4, username);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已删除好友: " + friendName);
                ClientHandler friendHandler = ctx.getServer().getClient(friendName);
                if (friendHandler != null) {
                    friendHandler.sendRaw(MessageProtocol.buildResponse(
                            MessageProtocol.TYPE_MESSAGE, username + " 已将你从好友列表中移除"));
                }
            }
        } catch (SQLException e) {
            ctx.sendError("删除好友失败: " + e.getMessage());
        }
    }

    private void handleRefreshFriends(HandlerContext ctx) {
        String username = ctx.getUsername();
        String sql = "SELECT u.username FROM `user` u "
                + "JOIN friendship f ON (u.id = f.user_a_id OR u.id = f.user_b_id) "
                + "WHERE (f.user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                + "OR f.user_b_id = (SELECT id FROM `user` WHERE username = ?)) "
                + "AND u.username != ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                JsonArray friendArray = new JsonArray();
                while (rs.next()) {
                    friendArray.add(rs.getString("username"));
                }
                JsonObject response = new JsonObject();
                response.addProperty("type", MessageProtocol.TYPE_FRIEND_LIST);
                response.addProperty("sender", "server");
                response.addProperty("content", friendArray.toString());
                ctx.sendRaw(MessageProtocol.toWire(response));
            }
        } catch (SQLException e) {
            ctx.sendError("刷新好友列表失败: " + e.getMessage());
        }
    }
}
