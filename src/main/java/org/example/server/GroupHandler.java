package org.example.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.util.DBUtil;
import org.example.util.MessageProtocol;

import java.sql.*;

/**
 * 群组管理处理器 - 创建/加入/退出/踢人/列表查询
 * 从 ClientHandler 拆分
 */
public class GroupHandler implements MessageHandler {

    @Override
    public void handle(JsonObject msg, HandlerContext ctx) {
        String type = msg.get("type").getAsString();
        switch (type) {
            case MessageProtocol.TYPE_CREATE_GROUP:
                handleCreateGroup(msg, ctx);
                break;
            case MessageProtocol.TYPE_JOIN_GROUP:
                handleJoinGroup(msg, ctx);
                break;
            case MessageProtocol.TYPE_LEAVE_GROUP:
                handleLeaveGroup(msg, ctx);
                break;
            case MessageProtocol.TYPE_KICK_MEMBER:
                handleKickMember(msg, ctx);
                break;
            case MessageProtocol.TYPE_GROUP_LIST_REQ:
                handleGroupListReq(ctx);
                break;
        }
    }

    private void handleCreateGroup(JsonObject msg, HandlerContext ctx) {
        String groupName = msg.get("content").getAsString();
        String username = ctx.getUsername();
        String sql = "INSERT INTO group_info (name, owner_id) SELECT ?, id FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, groupName);
            ps.setString(2, username);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int groupId = keys.getInt(1);
                    ctx.addGroupMember(conn, groupId, username, 2);
                    ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "群组 '" + groupName + "' 创建成功！群ID: " + groupId);
                }
            }
        } catch (SQLException e) {
            ctx.sendError("创建群组失败: " + e.getMessage());
        }
    }

    private void handleJoinGroup(JsonObject msg, HandlerContext ctx) {
        String groupIdStr = msg.get("content").getAsString();
        String username = ctx.getUsername();
        try (Connection conn = DBUtil.getConnection()) {
            int groupId = Integer.parseInt(groupIdStr);
            ctx.addGroupMember(conn, groupId, username, 0);
            ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已加入群组 " + groupId);
        } catch (SQLException e) {
            ctx.sendError("加入群组失败: " + e.getMessage());
        }
    }

    private void handleLeaveGroup(JsonObject msg, HandlerContext ctx) {
        String groupIdStr = msg.get("content").getAsString();
        String username = ctx.getUsername();
        String sql = "DELETE FROM group_member WHERE group_id = ? "
                + "AND user_id = (SELECT id FROM `user` WHERE username = ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(groupIdStr));
            ps.setString(2, username);
            ps.executeUpdate();
            ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已退出群组");
        } catch (SQLException e) {
            ctx.sendError("退出群组失败: " + e.getMessage());
        }
    }

    private void handleKickMember(JsonObject msg, HandlerContext ctx) {
        String contentStr = msg.get("content").getAsString();
        String username = ctx.getUsername();
        JsonObject data = MessageProtocol.fromWire(contentStr);
        if (data == null) {
            ctx.sendError("踢人请求格式错误");
            return;
        }
        String groupId = data.get("groupId").getAsString();
        String memberName = data.get("memberName").getAsString();

        String checkSql = "SELECT role FROM group_member WHERE group_id = ? "
                + "AND user_id = (SELECT id FROM `user` WHERE username = ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setInt(1, Integer.parseInt(groupId));
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt("role") != 2) {
                    ctx.sendError("只有群主才能踢人");
                    return;
                }
            }
            String kickSql = "DELETE FROM group_member WHERE group_id = ? "
                    + "AND user_id = (SELECT id FROM `user` WHERE username = ?)";
            try (PreparedStatement kickPs = conn.prepareStatement(kickSql)) {
                kickPs.setInt(1, Integer.parseInt(groupId));
                kickPs.setString(2, memberName);
                int rows = kickPs.executeUpdate();
                if (rows > 0) {
                    ctx.sendMessage(MessageProtocol.TYPE_MESSAGE, "已踢出成员 " + memberName);
                } else {
                    ctx.sendError("该成员不在群中");
                }
            }
        } catch (SQLException e) {
            ctx.sendError("踢人失败: " + e.getMessage());
        }
    }

    private void handleGroupListReq(HandlerContext ctx) {
        String username = ctx.getUsername();
        String sql = "SELECT gi.id, gi.name, gi.owner_id, u.username AS owner_name "
                + "FROM group_member gm "
                + "JOIN group_info gi ON gm.group_id = gi.id "
                + "JOIN `user` u ON gi.owner_id = u.id "
                + "WHERE gm.user_id = (SELECT id FROM `user` WHERE username = ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                JsonArray groupArray = new JsonArray();
                while (rs.next()) {
                    JsonObject groupObj = new JsonObject();
                    groupObj.addProperty("id", rs.getInt("id"));
                    groupObj.addProperty("name", rs.getString("name"));
                    groupObj.addProperty("ownerName", rs.getString("owner_name"));
                    groupArray.add(groupObj);
                }
                JsonObject response = new JsonObject();
                response.addProperty("type", MessageProtocol.TYPE_GROUP_LIST_RESP);
                response.addProperty("sender", "server");
                response.addProperty("content", groupArray.toString());
                ctx.sendRaw(MessageProtocol.toWire(response));
            }
        } catch (SQLException e) {
            ctx.sendError("查询群组列表失败: " + e.getMessage());
        }
    }
}
