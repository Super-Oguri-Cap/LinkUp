package org.example.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.example.util.DBUtil;
import org.example.util.MessageProtocol;

import java.io.*;
import java.net.Socket;
import java.sql.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端处理器 - 每个客户端连接对应一个线程
 * 负责处理登录、注册、私聊、群聊、AI聊天等所有消息
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final LinkUpServer server;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String username; // 登录成功后绑定用户名

    /** 好友上限常量 */
    private static final int MAX_FRIENDS = 150;

    /** 会话类型常量 */
    private static final int CHAT_TYPE_PRIVATE = 0;     // 私聊
    private static final int CHAT_TYPE_GROUP = 1;       // 群聊

    /** 消息类型常量 */
    private static final int MSG_TYPE_TEXT = 0;          // 文本消息
    private static final int MSG_TYPE_IMAGE = 1;         // 图片消息

    /** 消息去重缓存：记录最近 30 秒内处理的 msgId，防止重复 */
    private static final Set<String> recentMsgIds = ConcurrentHashMap.newKeySet();
    private static final int MAX_DEDUP_SIZE = 5000;      // 去重缓存上限

    public ClientHandler(Socket socket, LinkUpServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            // 初始化输入输出流
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            String line;
            while ((line = reader.readLine()) != null) {
                // 打印收到的消息（截断防刷屏）
                String logMsg = line.length() > 80 ? line.substring(0, 80) + "..." : line;
                System.out.println("[Handler] 收到来自 " + socket.getInetAddress() + " 的消息: " + logMsg);

                try {
                    // 解析 JSON 消息
                    JsonObject msg = MessageProtocol.fromWire(line);
                    String type = msg.get("type").getAsString();
                    handleMessage(type, msg);
                } catch (Throwable t) {
                    // 捕获所有异常（包括 Error），防止单个消息处理失败导致整个线程崩溃
                    System.err.println("[Handler] 消息处理异常: " + t.getMessage());
                    t.printStackTrace();
                    sendError("消息处理失败: " + t.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[ClientHandler] 客户端连接异常断开: " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    /**
     * 根据消息类型分发处理
     */
    private void handleMessage(String type, JsonObject msg) {
        switch (type) {
            case MessageProtocol.TYPE_LOGIN:
                handleLogin(msg);
                break;
            case MessageProtocol.TYPE_REGISTER:
                handleRegister(msg);
                break;
            case MessageProtocol.TYPE_PRIVATE_CHAT:
                handlePrivateChat(msg);
                break;
            case MessageProtocol.TYPE_GROUP_CHAT:
                handleGroupChat(msg);
                break;
            case MessageProtocol.TYPE_AI_CHAT:
                handleAIChat(msg);
                break;
            case MessageProtocol.TYPE_AI_COMPANION:
                handleAICompanion(msg);
                break;
            case MessageProtocol.TYPE_GROUP_SUMMARY:
                handleGroupSummary(msg);
                break;
            case MessageProtocol.TYPE_POLISH:
                handlePolish(msg);
                break;
            case MessageProtocol.TYPE_FRIEND_REQUEST:
                handleFriendRequest(msg);
                break;
            case MessageProtocol.TYPE_FRIEND_REQUEST_ACCEPT:
                handleFriendRequestAccept(msg);
                break;
            case MessageProtocol.TYPE_FRIEND_REQUEST_REJECT:
                handleFriendRequestReject(msg);
                break;
            case MessageProtocol.TYPE_REMOVE_FRIEND:
                handleRemoveFriend(msg);
                break;
            case MessageProtocol.TYPE_REFRESH_FRIENDS:
                handleRefreshFriends();
                break;
            case MessageProtocol.TYPE_DELETE_MESSAGE:
                handleDeleteMessage(msg);
                break;
            case MessageProtocol.TYPE_LOGOUT:
                handleLogout();
                break;

            // ==================== P0: 心跳保活 ====================
            case MessageProtocol.TYPE_PING:
                // 收到 PING，回复 PONG
                sendRaw("{\"type\":\"PONG\",\"sender\":\"server\",\"content\":\"\"}\n");
                break;

            // ==================== P0: 消息已读回执 ====================
            case MessageProtocol.TYPE_MSG_READ:
                handleMsgRead(msg);
                break;

            // ==================== P1: 消息撤回 ====================
            case MessageProtocol.TYPE_RECALL:
                handleRecall(msg);
                break;

            // ==================== P1: 群组管理 ====================
            case MessageProtocol.TYPE_CREATE_GROUP:
                handleCreateGroup(msg);
                break;
            case MessageProtocol.TYPE_JOIN_GROUP:
                handleJoinGroup(msg);
                break;
            case MessageProtocol.TYPE_LEAVE_GROUP:
                handleLeaveGroup(msg);
                break;
            case MessageProtocol.TYPE_KICK_MEMBER:
                handleKickMember(msg);
                break;
            case MessageProtocol.TYPE_GROUP_LIST_REQ:
                handleGroupListReq();
                break;

            // ==================== P1: 黑名单 ====================
            case MessageProtocol.TYPE_BLOCK_USER:
                handleBlockUser(msg);
                break;
            case MessageProtocol.TYPE_UNBLOCK_USER:
                handleUnblockUser(msg);
                break;

            // ==================== P1: 消息搜索 ====================
            case MessageProtocol.TYPE_SEARCH_MESSAGES:
                handleSearchMessages(msg);
                break;

            // ==================== P1: AI 聊天摘要 ====================
            case MessageProtocol.TYPE_CHAT_SUMMARY:
                handleChatSummary(msg);
                break;

            // ==================== P0: 图片消息 ====================
            case MessageProtocol.TYPE_IMAGE:
                handleImageMessage(msg);
                break;

            default:
                sendError("未知消息类型: " + type);
        }
    }

    // ==================== 登录处理 ====================

    /**
     * 处理登录请求：验证用户名和密码，密码使用 SHA-256 + 盐值校验
     */
    private void handleLogin(JsonObject msg) {
        String loginUsername = msg.get("sender").getAsString();
        String password = msg.get("content").getAsString();
        System.out.println("[登录] 收到登录请求 - 用户名: " + loginUsername);

        String sql = "SELECT password, salt, nickname FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            System.out.println("[登录] 数据库连接成功，正在查询用户: " + loginUsername);

            // 使用 PreparedStatement 防止 SQL 注入
            ps.setString(1, loginUsername);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password");
                    String storedSalt = rs.getString("salt");

                    // 使用 SHA-256 + 盐值验证密码
                    if (org.example.util.PasswordUtil.verify(password, storedHash, storedSalt)) {
                        this.username = loginUsername;
                        server.addClient(username, this);

                        // 更新最后登录时间和在线状态
                        updateLoginStatus(username);

                        // 发送登录成功 + 用户列表
                        sendMessage(MessageProtocol.TYPE_LOGIN_SUCCESS,
                                "登录成功！欢迎 " + rs.getString("nickname"));
                        sendUserList();
                        broadcastUserStatus(username, true);

                        // 登录后推送待处理的好友请求
                        // 当用户离线时别人发来的好友请求需要在此刻通知
                        pushPendingFriendRequests();

                        // P0: 登录后推送离线未读消息
                        pushUnreadMessages(loginUsername);

                        System.out.println("[登录] 用户 " + loginUsername + " 登录成功");
                    } else {
                        sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "密码错误");
                        System.out.println("[登录] 用户 " + loginUsername + " 密码错误");
                    }
                } else {
                    sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "用户不存在");
                    System.out.println("[登录] 用户 " + loginUsername + " 不存在");
                }
            }
        } catch (SQLException e) {
            System.err.println("[登录] 数据库异常: " + e.getMessage());
            sendMessage(MessageProtocol.TYPE_LOGIN_FAIL, "服务器数据库错误: " + e.getMessage());
        }
    }

    // ==================== 注册处理 ====================

    /**
     * 处理注册请求：校验用户名唯一性，SHA-256 + 盐值加密存储
     */
    private void handleRegister(JsonObject msg) {
        String regUsername = msg.get("sender").getAsString();

        // 解析 content 字段中的子 JSON（密码和昵称）
        JsonObject content;
        try {
            String contentStr = msg.get("content").getAsString();
            content = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            System.err.println("[注册] 解析注册内容失败: " + e.getMessage());
            sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "注册数据格式错误");
            return;
        }

        String password = content.get("password").getAsString();
        String nickname = content.get("nickname").getAsString();
        System.out.println("[注册] 收到注册请求 - 用户名: " + regUsername + ", 昵称: " + nickname);

        // 检查用户名唯一性
        String checkSql = "SELECT COUNT(*) FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement checkPs = conn.prepareStatement(checkSql)) {

            checkPs.setString(1, regUsername);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "用户名已存在");
                    System.out.println("[注册] 用户名 " + regUsername + " 已存在");
                    return;
                }
            }

            // 生成盐值并加密密码
            String salt = org.example.util.PasswordUtil.generateSalt();
            String hashedPassword = org.example.util.PasswordUtil.hash(password, salt);

            // 插入新用户
            String insertSql = "INSERT INTO `user` (username, password, salt, nickname) VALUES (?, ?, ?, ?)";
            try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                insertPs.setString(1, regUsername);
                insertPs.setString(2, hashedPassword);
                insertPs.setString(3, salt);
                insertPs.setString(4, nickname);
                insertPs.executeUpdate();
                sendMessage(MessageProtocol.TYPE_REGISTER_SUCCESS, "注册成功！请登录");
                System.out.println("[注册] 新用户注册成功: " + regUsername);
            }
        } catch (SQLException e) {
            System.err.println("[注册] 数据库异常: " + e.getMessage());
            sendMessage(MessageProtocol.TYPE_REGISTER_FAIL, "注册失败: " + e.getMessage());
        }
    }

    // ==================== 私聊处理 ====================

    /**
     * 处理私聊消息：转发给目标用户，同时存储到数据库
     * 增强：msgId 去重 + ACK 回执 + DELIVERED 通知 + 检查黑名单
     */
    private void handlePrivateChat(JsonObject msg) {
        String receiver = msg.get("receiver").getAsString();
        String content = msg.get("content").getAsString();
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";

        // 消息去重检查
        if (!msgId.isEmpty() && !recentMsgIds.add(msgId)) {
            System.out.println("[私聊] 重复消息，跳过: msgId=" + msgId);
            return;
        }
        // 去重缓存容量控制
        if (recentMsgIds.size() > MAX_DEDUP_SIZE) {
            recentMsgIds.clear();
        }

        // 检查发送方是否被接收方拉黑
        if (isBlocked(receiver, username)) {
            sendError("消息发送失败：你已被对方拉黑");
            return;
        }

        // 存储消息到数据库
        saveMessage(username, receiver, CHAT_TYPE_PRIVATE, MSG_TYPE_TEXT, content, timestamp);

        // 发送 ACK 回执给发送方
        if (!msgId.isEmpty()) {
            sendRaw("{\"type\":\"MSG_ACK\",\"sender\":\"server\",\"content\":\"" + msgId + "\"}\n");
        }

        // 先回显给发送者自己（确认消息已发送，带 msgId 用于状态追踪）
        JsonObject echo = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_MESSAGE, username, receiver, content, msgId);
        echo.addProperty("time", String.valueOf(timestamp));
        sendRaw(MessageProtocol.toWire(echo));

        // 转发给目标用户
        ClientHandler target = server.getClient(receiver);
        if (target != null) {
            JsonObject forward = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_MESSAGE, username, receiver, content, msgId);
            forward.addProperty("time", String.valueOf(timestamp));
            target.sendRaw(MessageProtocol.toWire(forward));

            // 发送 DELIVERED 回执给发送方
            if (!msgId.isEmpty()) {
                sendRaw("{\"type\":\"MSG_DELIVERED\",\"sender\":\"server\",\"content\":\"" + msgId + "\"}\n");
            }
        }
    }

    // ==================== 群聊处理 ====================

    /**
     * 处理群聊消息：广播给所有在线用户，同时持久化到数据库
     * 增强：msgId 去重 + @提及通知
     */
    private void handleGroupChat(JsonObject msg) {
        String content = msg.get("content").getAsString();
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";

        // 消息去重检查
        if (!msgId.isEmpty() && !recentMsgIds.add(msgId)) {
            System.out.println("[群聊] 重复消息，跳过: msgId=" + msgId);
            return;
        }
        if (recentMsgIds.size() > MAX_DEDUP_SIZE) {
            recentMsgIds.clear();
        }

        // 存储消息到数据库
        saveGroupMessage(username, content, timestamp);

        JsonObject broadcast = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_MESSAGE, username, "所有人", content, msgId);
        broadcast.addProperty("time", String.valueOf(timestamp));

        // 广播给所有在线客户端
        server.broadcast(MessageProtocol.toWire(broadcast));
        System.out.println("[群聊] " + username + ": " + content);

        // 检测 @提及并通知被提及的用户
        handleMentions(content, username);
    }

    // ==================== AI 聊天处理 ====================

    /**
     * 处理 AI 聊天请求：调用 AI 助手模块获取回复
     */
    private void handleAIChat(JsonObject msg) {
        // 提前提取所有字段，避免异步线程中访问 msg 导致 NPE
        final String content = msg.get("content").getAsString();
        final long timestamp = Long.parseLong(msg.get("time").getAsString());

        // 先回显用户消息
        JsonObject echo = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, username, "AI小助手", content);
        echo.addProperty("time", String.valueOf(timestamp));
        sendRaw(MessageProtocol.toWire(echo));

        // 异步调用 AI API 获取回复（避免阻塞当前线程）
        server.getExecutorService().submit(() -> {
            try {
                String aiReply = AIAssistant.chat(content);
                long replyTime = System.currentTimeMillis();

                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI小助手", username, aiReply);
                reply.addProperty("time", String.valueOf(replyTime));
                sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI小助手", username,
                        "AI 助手暂时不可用，请稍后再试");
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[AI] 调用失败: " + e.getMessage());
            }
        });
    }

    // ==================== AI 情绪陪伴处理 ====================

    /**
     * 处理 AI 情绪陪伴请求：使用温暖 empathetic 的 system prompt
     */
    private void handleAICompanion(JsonObject msg) {
        // 提前提取所有字段，避免异步线程中访问 msg 导致 NPE
        final String content = msg.get("content").getAsString();
        final long timestamp = Long.parseLong(msg.get("time").getAsString());

        // 回显用户消息
        JsonObject echo = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_MESSAGE, username, "AI伴侣", content);
        echo.addProperty("time", String.valueOf(timestamp));
        sendRaw(MessageProtocol.toWire(echo));

        // 异步调用 AI 伴侣
        server.getExecutorService().submit(() -> {
            try {
                // 获取该用户最近的对话历史作为上下文（长期记忆）
                String context = loadRecentChatContext(username, "AI伴侣", 10);
                String aiReply = AIAssistant.chatWithEmotion(content, context);
                long replyTime = System.currentTimeMillis();

                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI伴侣", username, aiReply);
                reply.addProperty("time", String.valueOf(replyTime));
                sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI伴侣", username,
                        "AI 伴侣暂时不可用，请稍后再试");
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[AI伴侣] 调用失败: " + e.getMessage());
            }
        });
    }

    // ==================== 群聊摘要处理 ====================

    /**
     * 处理群聊摘要请求：收集最近群聊消息，调用 AI 生成摘要
     */
    private void handleGroupSummary(JsonObject msg) {
        server.getExecutorService().submit(() -> {
            try {
                // 从数据库加载最近 20 条群聊消息（减少 AI 处理量，避免本地模型超时）
                String groupMessages = loadRecentGroupMessages(20);
                String summary = AIAssistant.summarizeGroup(groupMessages, username);
                long replyTime = System.currentTimeMillis();

                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI小助手", username, summary);
                reply.addProperty("time", String.valueOf(replyTime));
                sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                JsonObject errorReply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_MESSAGE, "AI小助手", username,
                        "群聊摘要生成失败: " + e.getMessage());
                errorReply.addProperty("time", String.valueOf(System.currentTimeMillis()));
                sendRaw(MessageProtocol.toWire(errorReply));
                System.err.println("[群聊摘要] 生成失败: " + e.getMessage());
            }
        });
    }

    // ==================== 对话润色处理 ====================

    /**
     * 处理对话润色请求：调用 AI 对消息进行语气润色
     * content 格式: JSON {"text":"原始消息","style":"高情商"}
     */
    private void handlePolish(JsonObject msg) {
        // 提前提取并解析字段，避免异步线程中访问 msg 导致 NPE
        final String contentStr = msg.get("content").getAsString();
        final JsonObject content;
        final String originalText;
        final String style;
        try {
            content = MessageProtocol.fromWire(contentStr);
            originalText = content.get("text").getAsString();
            style = content.get("style").getAsString();
        } catch (Exception e) {
            sendError("润色请求格式错误: " + e.getMessage());
            return;
        }

        server.getExecutorService().submit(() -> {
            try {
                String polished = AIAssistant.polish(originalText, style);
                long replyTime = System.currentTimeMillis();

                JsonObject reply = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_POLISH_RESULT, "AI小助手", username, polished);
                reply.addProperty("time", String.valueOf(replyTime));
                sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                sendError("润色失败: " + e.getMessage());
                System.err.println("[润色] 失败: " + e.getMessage());
            }
        });
    }

    // ==================== 好友验证管理（加好友需要对方同意）====================

    /**
     * 处理好友请求：检查目标用户存在性、是否已是好友、是否已有待处理的请求
     * 通过后在 friend_request 表插入一条待处理记录，并通知目标用户
     */
    private void handleFriendRequest(JsonObject msg) {
        String friendName = msg.get("receiver").getAsString();
        System.out.println("[好友请求] " + username + " 请求添加好友: " + friendName);

        // 不能添加自己
        if (username.equals(friendName)) {
            sendMessage(MessageProtocol.TYPE_ERROR, "不能添加自己为好友");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            // 步骤1：检查目标用户是否存在
            String existSql = "SELECT id FROM `user` WHERE username = ?";
            int friendId;
            try (PreparedStatement ps = conn.prepareStatement(existSql)) {
                ps.setString(1, friendName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendMessage(MessageProtocol.TYPE_ERROR, "用户 '" + friendName + "' 不存在");
                        return;
                    }
                    friendId = rs.getInt("id");
                }
            }

            // 步骤2：检查是否已经是好友
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
                        sendMessage(MessageProtocol.TYPE_ERROR, "'" + friendName + "' 已经是您的好友");
                        return;
                    }
                }
            }

            // 步骤3：检查发起方好友数量是否已达上限
            String countSql = "SELECT COUNT(*) FROM friendship "
                    + "WHERE user_a_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "OR user_b_id = (SELECT id FROM `user` WHERE username = ?)";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, username);
                ps.setString(2, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) >= MAX_FRIENDS) {
                        sendMessage(MessageProtocol.TYPE_ERROR,
                                "好友数量已达上限（" + MAX_FRIENDS + "人），无法继续添加");
                        return;
                    }
                }
            }

            // 步骤4：检查是否已有待处理的请求（防止重复发送）
            String pendingSql = "SELECT COUNT(*) FROM friend_request "
                    + "WHERE from_user_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND to_user_id = ? AND status = 0";
            try (PreparedStatement ps = conn.prepareStatement(pendingSql)) {
                ps.setString(1, username);
                ps.setInt(2, friendId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        sendMessage(MessageProtocol.TYPE_ERROR, "已向该用户发送过好友请求，请等待对方处理");
                        return;
                    }
                }
            }

            // 步骤5：插入好友请求记录（状态 0-待处理）
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

            // 通知发起方：请求已发送
            sendMessage(MessageProtocol.TYPE_MESSAGE, "好友请求已发送给 " + friendName + "，请等待对方确认");
            System.out.println("[好友请求] " + username + " -> " + friendName + " (请求ID=" + requestId + ")");

            // 通知目标用户（如果在线），弹出好友请求提示
            ClientHandler targetHandler = server.getClient(friendName);
            if (targetHandler != null) {
                // sender 存放发起方用户名，receiver 存放请求ID，content 也存放发起方用户名便于客户端展示
                JsonObject notifyMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_FRIEND_REQUEST_NOTIFY, username, String.valueOf(requestId), username);
                targetHandler.sendRaw(MessageProtocol.toWire(notifyMsg));
            }
        } catch (SQLException e) {
            sendError("发送好友请求失败: " + e.getMessage());
            System.err.println("[好友请求] 发送失败: " + e.getMessage());
        }
    }

    /**
     * 处理同意好友请求：将 friend_request 状态更新为已同意，
     * 插入 friendship 表建立好友关系，通知双方
     */
    private void handleFriendRequestAccept(JsonObject msg) {
        // receiver 字段存放请求发起方的用户名，content 字段存放请求ID
        String requester = msg.get("receiver").getAsString();
        String requestIdStr = msg.get("content").getAsString();
        System.out.println("[好友请求] " + username + " 同意了 " + requester + " 的好友请求 (requestId=" + requestIdStr + ")");

        long requestId;
        try {
            requestId = Long.parseLong(requestIdStr);
        } catch (NumberFormatException e) {
            sendError("请求ID格式错误");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            // 步骤1：检查请求是否存在且为待处理状态
            String checkSql = "SELECT status FROM friend_request WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendError("好友请求不存在");
                        return;
                    }
                    if (rs.getInt("status") != 0) {
                        sendMessage(MessageProtocol.TYPE_ERROR, "该好友请求已处理");
                        return;
                    }
                }
            }

            // 步骤2：更新请求状态为已同意
            String updateSql = "UPDATE friend_request SET status = 1 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, requestId);
                ps.executeUpdate();
            }

            // 步骤3：插入好友关系
            String insertSql = "INSERT INTO friendship (user_a_id, user_b_id) "
                    + "SELECT LEAST(u1.id, u2.id), GREATEST(u1.id, u2.id) "
                    + "FROM `user` u1, `user` u2 "
                    + "WHERE u1.username = ? AND u2.username = ?";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, requester);
                ps.setString(2, username);
                ps.executeUpdate();
            }

            System.out.println("[好友请求] " + requester + " 和 " + username + " 已成为好友");

            // 通知请求发起方（如果在线）
            ClientHandler requesterHandler = server.getClient(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage(MessageProtocol.TYPE_ADD_FRIEND_SUCCESS, username);
            }
            // 通知当前用户（同意方）：好友关系已建立
            sendMessage(MessageProtocol.TYPE_ADD_FRIEND_SUCCESS, requester);
        } catch (SQLException e) {
            sendError("同意好友请求失败: " + e.getMessage());
            System.err.println("[好友请求] 同意失败: " + e.getMessage());
        }
    }

    /**
     * 处理拒绝好友请求：将 friend_request 状态更新为已拒绝，通知发起方
     */
    private void handleFriendRequestReject(JsonObject msg) {
        String requester = msg.get("receiver").getAsString();
        String requestIdStr = msg.get("content").getAsString();
        System.out.println("[好友请求] " + username + " 拒绝了 " + requester + " 的好友请求 (requestId=" + requestIdStr + ")");

        long requestId;
        try {
            requestId = Long.parseLong(requestIdStr);
        } catch (NumberFormatException e) {
            sendError("请求ID格式错误");
            return;
        }

        try (Connection conn = DBUtil.getConnection()) {
            // 步骤1：检查请求是否存在且为待处理状态
            String checkSql = "SELECT status FROM friend_request WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                ps.setLong(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        sendError("好友请求不存在");
                        return;
                    }
                    if (rs.getInt("status") != 0) {
                        sendMessage(MessageProtocol.TYPE_ERROR, "该好友请求已处理");
                        return;
                    }
                }
            }

            // 步骤2：更新请求状态为已拒绝
            String updateSql = "UPDATE friend_request SET status = 2 WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setLong(1, requestId);
                ps.executeUpdate();
            }

            System.out.println("[好友请求] " + username + " 拒绝了 " + requester + " 的好友请求");

            // 通知请求发起方（如果在线）：对方拒绝了你的好友请求
            ClientHandler requesterHandler = server.getClient(requester);
            if (requesterHandler != null) {
                requesterHandler.sendMessage(MessageProtocol.TYPE_ADD_FRIEND_FAIL,
                        username + " 拒绝了你的好友请求");
            }
            // 通知当前用户（拒绝方）：已拒绝
            sendMessage(MessageProtocol.TYPE_MESSAGE, "已拒绝 " + requester + " 的好友请求");
        } catch (SQLException e) {
            sendError("拒绝好友请求失败: " + e.getMessage());
            System.err.println("[好友请求] 拒绝失败: " + e.getMessage());
        }
    }

    /**
     * 处理消息删除请求：按 sender + content + chat_type 匹配，从 MySQL 中删除
     * 不依赖时间戳（客户端传来的 timeStr 可能是格式化时间，无法统一转 long）
     * content 格式：{"sender":"消息发送者","content":"消息原文","time":"时间戳或格式化时间","chatType":"PRIVATE或GROUP"}
     */
    private void handleDeleteMessage(JsonObject msg) {
        // 解析 content 字段中的子 JSON
        String payload = msg.get("content").getAsString();
        JsonObject data;
        try {
            data = MessageProtocol.fromWire(payload);
        } catch (Exception e) {
            System.err.println("[消息删除] 解析请求数据失败: " + e.getMessage());
            return;
        }

        String msgSender = data.get("sender").getAsString();
        String msgContent = data.get("content").getAsString();
        String chatTypeStr = data.get("chatType").getAsString();

        // 确定 chat_type 数值：PRIVATE=0, GROUP=1
        int chatType = "GROUP".equals(chatTypeStr) ? 1 : 0;

        System.out.println("[消息删除] " + username + " 请求删除 " + chatTypeStr + " 消息: "
                + msgSender + ": " + (msgContent.length() > 30 ? msgContent.substring(0, 30) + "…" : msgContent));

        // 按 sender + content + chat_type 匹配删除
        // 不依赖时间戳匹配，因为 timeStr 可能是格式化后的 "HH:mm:ss"（从本地文件加载而来），
        // 也可能是毫秒时间戳（实时消息），无法统一解析。遇到内容相同的消息会全部删除，概率极低可接受。
        String sql = "DELETE FROM chat_message "
                + "WHERE sender_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND content = ? AND chat_type = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, msgSender);
            ps.setString(2, msgContent);
            ps.setInt(3, chatType);
            int rows = ps.executeUpdate();
            System.out.println("[消息删除] 删除了 " + rows + " 条记录 (sender=" + msgSender + ")");
        } catch (SQLException e) {
            System.err.println("[消息删除] 删除失败: " + e.getMessage());
        }
    }

    /**
     * 推送登录后待处理的好友请求
     * 场景：用户A离线时，用户B向A发送了好友请求；
     * 当A登录时，需要查询 friend_request 表中 status=0 的记录并推送通知。
     */
    private void pushPendingFriendRequests() {
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

                    // 逐个推送好友请求通知，客户端会弹出确认框
                    JsonObject notifyMsg = MessageProtocol.buildMessage(
                            MessageProtocol.TYPE_FRIEND_REQUEST_NOTIFY,
                            requesterName, String.valueOf(requestId), requesterName);
                    sendRaw(MessageProtocol.toWire(notifyMsg));

                    System.out.println("[好友请求] 登录推送: " + requesterName + " -> " + username
                            + " (请求ID=" + requestId + ")");
                }
            }
        } catch (SQLException e) {
            System.err.println("[好友请求] 登录推送待处理请求失败: " + e.getMessage());
        }
    }

    /**
     * 处理删除好友请求：删除好友关系并通知对方
     */
    private void handleRemoveFriend(JsonObject msg) {
        String friendName = msg.get("receiver").getAsString();
        System.out.println("[好友] " + username + " 请求删除好友: " + friendName);

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
                sendMessage(MessageProtocol.TYPE_MESSAGE, "已删除好友: " + friendName);

                // 通知对方用户（如果在线）
                ClientHandler friendHandler = server.getClient(friendName);
                if (friendHandler != null) {
                    friendHandler.sendRaw(MessageProtocol.buildResponse(
                            MessageProtocol.TYPE_MESSAGE, username + " 已将你从好友列表中移除"));
                }
            }
        } catch (SQLException e) {
            sendError("删除好友失败: " + e.getMessage());
        }
    }

    /**
     * 处理刷新好友列表请求：从数据库 friendship 表查询当前用户的所有好友
     * 返回好友用户名列表（JSON 数组），客户端据此重建好友列表
     */
    private void handleRefreshFriends() {
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
                com.google.gson.JsonArray friendArray = new com.google.gson.JsonArray();
                while (rs.next()) {
                    friendArray.add(rs.getString("username"));
                }
                // 使用 FRIEND_LIST 类型返回，客户端据此重建好友列表（含离线好友）
                JsonObject response = new JsonObject();
                response.addProperty("type", MessageProtocol.TYPE_FRIEND_LIST);
                response.addProperty("sender", "server");
                response.addProperty("content", friendArray.toString());
                sendRaw(MessageProtocol.toWire(response));
                System.out.println("[好友] 刷新好友列表: " + username + " 共 " + friendArray.size() + " 位好友");
            }
        } catch (SQLException e) {
            sendError("刷新好友列表失败: " + e.getMessage());
            System.err.println("[好友] 刷新好友列表失败: " + e.getMessage());
        }
    }

    // ==================== P0: 离线消息推送 ====================

    /**
     * 登录后推送离线未读消息：查询 chat_message 表中 is_read=0 且 receiver 为当前用户的消息
     */
    private void pushUnreadMessages(String loginUser) {
        String sql = "SELECT cm.id, u.username AS sender_name, cm.content, cm.sent_at, cm.chat_type "
                + "FROM chat_message cm "
                + "JOIN `user` u ON cm.sender_id = u.id "
                + "WHERE cm.receiver_id = (SELECT id FROM `user` WHERE username = ?) "
                + "AND cm.is_read = 0 AND cm.chat_type = ? "
                + "ORDER BY cm.sent_at ASC LIMIT 50";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, loginUser);
            ps.setInt(2, CHAT_TYPE_PRIVATE);
            try (ResultSet rs = ps.executeQuery()) {
                JsonArray unreadArray = new JsonArray();
                while (rs.next()) {
                    JsonObject msgObj = new JsonObject();
                    msgObj.addProperty("msgId", String.valueOf(rs.getLong("id")));
                    msgObj.addProperty("sender", rs.getString("sender_name"));
                    msgObj.addProperty("content", rs.getString("content"));
                    msgObj.addProperty("time", rs.getTimestamp("sent_at").getTime());
                    msgObj.addProperty("chatType", rs.getInt("chat_type"));
                    unreadArray.add(msgObj);
                }
                if (unreadArray.size() > 0) {
                    JsonObject push = new JsonObject();
                    push.addProperty("type", MessageProtocol.TYPE_UNREAD_MESSAGES);
                    push.addProperty("sender", "server");
                    push.addProperty("content", unreadArray.toString());
                    sendRaw(MessageProtocol.toWire(push));
                    System.out.println("[离线消息] 为 " + loginUser + " 推送了 " + unreadArray.size() + " 条未读消息");
                }
            }
        } catch (SQLException e) {
            System.err.println("[离线消息] 查询失败: " + e.getMessage());
        }
    }

    // ==================== P0: 已读回执 ====================

    /**
     * 处理已读回执：将 chat_message 的 is_read 标记为 1，并转发给原始发送方
     */
    private void handleMsgRead(JsonObject msg) {
        String msgId = msg.get("content").getAsString();
        String originalSender = msg.get("receiver").getAsString();

        // 更新数据库中的 is_read 标记
        String sql = "UPDATE chat_message SET is_read = 1 WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(msgId));
            int updated = ps.executeUpdate();
            if (updated > 0) {
                System.out.println("[已读] 消息 " + msgId + " 已标记为已读");
            }
        } catch (SQLException | NumberFormatException e) {
            System.err.println("[已读] 更新失败: " + e.getMessage());
        }

        // 转发已读回执给原始发送方
        ClientHandler sender = server.getClient(originalSender);
        if (sender != null) {
            JsonObject readNotify = new JsonObject();
            readNotify.addProperty("type", MessageProtocol.TYPE_MSG_READ);
            readNotify.addProperty("sender", "server");
            readNotify.addProperty("content", msgId);
            sender.sendRaw(MessageProtocol.toWire(readNotify));
        }
    }

    // ==================== P1: 消息撤回 ====================

    /**
     * 处理消息撤回：按 msgId 从数据库删除，并通知相关方
     * content 格式：{"msgId":"消息ID","target":"接收方用户名"}
     */
    private void handleRecall(JsonObject msg) {
        String contentStr = msg.get("content").getAsString();
        JsonObject data;
        try {
            data = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            sendError("撤回请求格式错误");
            return;
        }

        String msgId = data.get("msgId").getAsString();
        String target = data.get("target").getAsString();

        // 删除数据库中的消息
        String sql = "DELETE FROM chat_message WHERE id = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, Long.parseLong(msgId));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[撤回] 数据库删除失败: " + e.getMessage());
        }

        // 通知目标用户（如果在线）
        ClientHandler targetHandler = server.getClient(target);
        if (targetHandler != null) {
            JsonObject recallNotify = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_RECALL, username, target, msgId, msgId);
            targetHandler.sendRaw(MessageProtocol.toWire(recallNotify));
        }

        // 通知发送方自己（用于 UI 更新）
        JsonObject selfNotify = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_RECALL, "server", username, msgId, msgId);
        sendRaw(MessageProtocol.toWire(selfNotify));
        System.out.println("[撤回] " + username + " 撤回了一条发给 " + target + " 的消息");
    }

    // ==================== P1: @提及检测 ====================

    /**
     * 检测消息内容中的 @提及，通知被提及的用户
     * 格式：@用户名 （空格或标点结尾）
     */
    private void handleMentions(String content, String sender) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("@(\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String mentionedUser = matcher.group(1);
            if (mentionedUser.equals(sender)) continue; // 不通知自己
            ClientHandler mentioned = server.getClient(mentionedUser);
            if (mentioned != null) {
                JsonObject notify = new JsonObject();
                notify.addProperty("type", MessageProtocol.TYPE_MENTION_NOTIFY);
                notify.addProperty("sender", sender);
                notify.addProperty("receiver", "群聊");
                notify.addProperty("content", sender + " 在群聊中@了你");
                mentioned.sendRaw(MessageProtocol.toWire(notify));
                System.out.println("[@提及] " + sender + " @了 " + mentionedUser);
            }
        }
    }

    // ==================== P1: 群组管理 ====================

    /**
     * 创建群组
     */
    private void handleCreateGroup(JsonObject msg) {
        String groupName = msg.get("content").getAsString();
        String sql = "INSERT INTO group_info (name, owner_id) SELECT ?, id FROM `user` WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, groupName);
            ps.setString(2, username);
            ps.executeUpdate();

            // 获取生成的群 ID
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    int groupId = keys.getInt(1);
                    // 将群主加入群成员，role=2 表示群主
                    addGroupMember(conn, groupId, username, 2);
                    sendMessage(MessageProtocol.TYPE_MESSAGE, "群组 '" + groupName + "' 创建成功！群ID: " + groupId);
                    System.out.println("[群组] " + username + " 创建了群组: " + groupName);
                }
            }
        } catch (SQLException e) {
            sendError("创建群组失败: " + e.getMessage());
            System.err.println("[群组] 创建失败: " + e.getMessage());
        }
    }

    /**
     * 加入群组
     */
    private void handleJoinGroup(JsonObject msg) {
        String groupIdStr = msg.get("content").getAsString();
        try (Connection conn = DBUtil.getConnection()) {
            int groupId = Integer.parseInt(groupIdStr);
            addGroupMember(conn, groupId, username, 0); // role=0 普通成员
            sendMessage(MessageProtocol.TYPE_MESSAGE, "已加入群组 " + groupId);
            System.out.println("[群组] " + username + " 加入了群组: " + groupId);
        } catch (SQLException e) {
            sendError("加入群组失败: " + e.getMessage());
        }
    }

    /**
     * 退出群组
     */
    private void handleLeaveGroup(JsonObject msg) {
        String groupIdStr = msg.get("content").getAsString();
        String sql = "DELETE FROM group_member WHERE group_id = ? "
                + "AND user_id = (SELECT id FROM `user` WHERE username = ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, Integer.parseInt(groupIdStr));
            ps.setString(2, username);
            ps.executeUpdate();
            sendMessage(MessageProtocol.TYPE_MESSAGE, "已退出群组");
            System.out.println("[群组] " + username + " 退出了群组: " + groupIdStr);
        } catch (SQLException e) {
            sendError("退出群组失败: " + e.getMessage());
        }
    }

    /**
     * 踢出群成员（仅群主可操作）
     * content 格式：{"groupId":"群ID","memberName":"成员用户名"}
     */
    private void handleKickMember(JsonObject msg) {
        String contentStr = msg.get("content").getAsString();
        JsonObject data;
        try {
            data = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            sendError("踢人请求格式错误");
            return;
        }
        String groupId = data.get("groupId").getAsString();
        String memberName = data.get("memberName").getAsString();

        // 验证操作者是否为群主
        String checkSql = "SELECT role FROM group_member WHERE group_id = ? "
                + "AND user_id = (SELECT id FROM `user` WHERE username = ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(checkSql)) {
            ps.setInt(1, Integer.parseInt(groupId));
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt("role") != 2) {
                    sendError("只有群主才能踢人");
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
                    sendMessage(MessageProtocol.TYPE_MESSAGE, "已踢出成员 " + memberName);
                    System.out.println("[群组] " + username + " 从群 " + groupId + " 踢出了 " + memberName);
                } else {
                    sendError("该成员不在群中");
                }
            }
        } catch (SQLException e) {
            sendError("踢人失败: " + e.getMessage());
        }
    }

    /**
     * 请求群组列表
     */
    private void handleGroupListReq() {
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
                sendRaw(MessageProtocol.toWire(response));
            }
        } catch (SQLException e) {
            sendError("查询群组列表失败: " + e.getMessage());
        }
    }

    /**
     * 向群组添加成员
     */
    private void addGroupMember(Connection conn, int groupId, String memberName, int role) throws SQLException {
        String sql = "INSERT INTO group_member (group_id, user_id, role) "
                + "SELECT ?, id, ? FROM `user` WHERE username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, groupId);
            ps.setInt(2, role);
            ps.setString(3, memberName);
            ps.executeUpdate();
        }
    }

    // ==================== P1: 黑名单管理 ====================

    /**
     * 拉黑用户
     * blocked_by: 1=userA屏蔽, 2=userB屏蔽
     */
    private void handleBlockUser(JsonObject msg) {
        String targetUser = msg.get("content").getAsString();
        // 先查询当前用户在 friendship 中是 user_a 还是 user_b
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
                    sendError("拉黑失败：对方不是你的好友");
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
                    sendMessage(MessageProtocol.TYPE_MESSAGE, "已拉黑 " + targetUser);
                    System.out.println("[黑名单] " + username + " 拉黑了 " + targetUser);
                }
            }
        } catch (SQLException e) {
            sendError("拉黑失败: " + e.getMessage());
        }
    }

    /**
     * 取消拉黑用户
     */
    private void handleUnblockUser(JsonObject msg) {
        String targetUser = msg.get("content").getAsString();
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
            sendMessage(MessageProtocol.TYPE_MESSAGE, "已取消拉黑 " + targetUser);
            System.out.println("[黑名单] " + username + " 取消拉黑 " + targetUser);
        } catch (SQLException e) {
            sendError("取消拉黑失败: " + e.getMessage());
        }
    }

    /**
     * 检查 userA 是否被 userB 拉黑（即 userB 是否拉黑了 userA）
     * blocked_by: 1=user_a 屏蔽, 2=user_b 屏蔽
     */
    private boolean isBlocked(String userNameA, String userNameB) {
        // 查询 userB 是否拉黑了 userA
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
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            // userB is the blocker, userA is the target (the one who might be blocked)
            ps.setString(1, userNameB); // userB's ID position
            ps.setString(2, userNameA); // user_a
            ps.setString(3, userNameB); // user_b
            ps.setString(4, userNameB); // user_a
            ps.setString(5, userNameA); // user_b
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("blocked") == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== P1: 消息搜索 ====================

    /**
     * 搜索聊天消息：全文模糊匹配 content 字段
     * content 格式：{"keyword":"搜索关键词","target":"对方用户名（可选）"}
     */
    private void handleSearchMessages(JsonObject msg) {
        String contentStr = msg.get("content").getAsString();
        JsonObject data;
        try {
            data = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            sendError("搜索请求格式错误");
            return;
        }
        String keyword = data.get("keyword").getAsString();
        String target = data.has("target") ? data.get("target").getAsString() : null;

        String sql;
        if (target != null) {
            // 搜索与特定用户的聊天记录
            sql = "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm "
                    + "JOIN `user` u ON cm.sender_id = u.id "
                    + "WHERE cm.content LIKE ? AND cm.chat_type = ? "
                    + "AND ((cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND cm.receiver_id = (SELECT id FROM `user` WHERE username = ?)) "
                    + "OR (cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "AND cm.receiver_id = (SELECT id FROM `user` WHERE username = ?))) "
                    + "ORDER BY cm.sent_at DESC LIMIT 30";
        } else {
            // 全局搜索
            sql = "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                    + "FROM chat_message cm "
                    + "JOIN `user` u ON cm.sender_id = u.id "
                    + "WHERE cm.content LIKE ? "
                    + "AND (cm.sender_id = (SELECT id FROM `user` WHERE username = ?) "
                    + "OR cm.receiver_id = (SELECT id FROM `user` WHERE username = ?)) "
                    + "ORDER BY cm.sent_at DESC LIMIT 30";
        }

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            if (target != null) {
                ps.setInt(2, CHAT_TYPE_PRIVATE);
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
                    item.addProperty("time", rs.getTimestamp("sent_at").toString());
                    results.add(item);
                }
                JsonObject response = new JsonObject();
                response.addProperty("type", MessageProtocol.TYPE_SEARCH_RESULT);
                response.addProperty("sender", "server");
                response.addProperty("receiver", keyword);
                response.addProperty("content", results.toString());
                sendRaw(MessageProtocol.toWire(response));
                System.out.println("[搜索] " + username + " 搜索 '" + keyword + "' 找到 " + results.size() + " 条");
            }
        } catch (SQLException e) {
            sendError("搜索失败: " + e.getMessage());
        }
    }

    // ==================== P1: AI 聊天摘要 ====================

    /**
     * 进入聊天时自动生成未读消息的 AI 摘要
     * content 格式：{"target":"对方用户名"}
     */
    private void handleChatSummary(JsonObject msg) {
        String contentStr = msg.get("content").getAsString();
        JsonObject data;
        try {
            data = MessageProtocol.fromWire(contentStr);
        } catch (Exception e) {
            return;
        }
        String target = data.get("target").getAsString();

        final String context = loadRecentChatContext(username, target, 20);
        if (context.isEmpty()) return;

        final String targetUser = target;
        server.getExecutorService().submit(() -> {
            try {
                String summary = AIAssistant.summarizeGroup(context, username);
                JsonObject reply = new JsonObject();
                reply.addProperty("type", MessageProtocol.TYPE_CHAT_SUMMARY);
                reply.addProperty("sender", "AI");
                reply.addProperty("receiver", targetUser);
                reply.addProperty("content", summary);
                sendRaw(MessageProtocol.toWire(reply));
            } catch (Exception e) {
                System.err.println("[AI摘要] 生成失败: " + e.getMessage());
            }
        });
    }

    // ==================== P0: 图片消息 ====================

    /**
     * 处理图片消息：Base64 编码传输，存储到数据库，转发给目标
     */
    private void handleImageMessage(JsonObject msg) {
        String receiver = msg.get("receiver").getAsString();
        String content = msg.get("content").getAsString(); // Base64 图片数据
        long timestamp = Long.parseLong(msg.get("time").getAsString());
        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";

        // 消息去重
        if (!msgId.isEmpty() && !recentMsgIds.add(msgId)) {
            System.out.println("[图片] 重复消息，跳过: msgId=" + msgId);
            return;
        }

        // 存储到数据库（图片内容用 Base64 存储，类型标记为 IMAGE）
        saveMessage(username, receiver, CHAT_TYPE_PRIVATE, MSG_TYPE_IMAGE, content, timestamp);

        // 回显给发送者
        JsonObject echo = MessageProtocol.buildMessageWithId(
                MessageProtocol.TYPE_IMAGE, username, receiver, content, msgId);
        echo.addProperty("time", String.valueOf(timestamp));
        sendRaw(MessageProtocol.toWire(echo));

        // 转发给目标用户
        ClientHandler target = server.getClient(receiver);
        if (target != null) {
            JsonObject forward = MessageProtocol.buildMessageWithId(
                    MessageProtocol.TYPE_IMAGE, username, receiver, content, msgId);
            forward.addProperty("time", String.valueOf(timestamp));
            target.sendRaw(MessageProtocol.toWire(forward));
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 加载最近与指定对象的聊天上下文（用于 AI 长期记忆）
     * 使用 ORDER BY ASC + append，避免 insert(0, ...) 的 O(n²) 性能问题
     */
    private String loadRecentChatContext(String user, String target, int limit) {
        StringBuilder sb = new StringBuilder();
        // 子查询先取最近 N 条，再正序排列，避免 insert(0, ...) 的 O(n²) 开销
        String sql = "SELECT sender_name, content FROM ("
                + "SELECT u1.username AS sender_name, cm.content, cm.sent_at "
                + "FROM chat_message cm "
                + "JOIN `user` u1 ON cm.sender_id = u1.id "
                + "JOIN `user` u2 ON cm.receiver_id = u2.id "
                + "WHERE cm.chat_type = 0 AND ("
                + "(u1.username = ? AND u2.username = ?) OR (u1.username = ? AND u2.username = ?)) "
                + "ORDER BY cm.sent_at DESC LIMIT ?"
                + ") AS recent ORDER BY sent_at ASC";
        try (Connection conn = DBUtil.getConnection();
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
            System.err.println("[ClientHandler] 加载聊天上下文失败: " + e.getMessage());
        }
        return sb.toString();
    }

    /**
     * 加载最近群聊消息（用于摘要）
     * 为减轻本地 AI 模型的处理压力，做了三项优化：
     * 1. 减少消息数量（只取最近 20 条）
     * 2. 截断每条消息内容（最长 150 字符，避免单条长消息拖慢推理）
     * 3. 过滤空消息
     * 使用子查询 + ORDER BY ASC，避免 O(n²) 性能问题
     */
    private String loadRecentGroupMessages(int limit) {
        StringBuilder sb = new StringBuilder();
        // 实际取最近 limit 条（当前设为 20），可在调用处调整
        String sql = "SELECT sender_name, content FROM ("
                + "SELECT u.username AS sender_name, cm.content, cm.sent_at "
                + "FROM chat_message cm "
                + "JOIN `user` u ON cm.sender_id = u.id "
                + "WHERE cm.chat_type = 1 "
                + "ORDER BY cm.sent_at DESC LIMIT ?"
                + ") AS recent ORDER BY sent_at ASC";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender_name");
                    String content = rs.getString("content");

                    // 过滤空消息
                    if (content == null || content.trim().isEmpty()) {
                        continue;
                    }

                    // 截断过长的消息：超过 150 字符则截断并加省略号
                    if (content.length() > 150) {
                        content = content.substring(0, 150) + "…";
                    }

                    sb.append(sender).append(": ").append(content).append("\n");
                }
            }
        } catch (SQLException e) {
            System.err.println("[ClientHandler] 加载群聊消息失败: " + e.getMessage());
        }
        return sb.toString();
    }

    private void handleLogout() {
        disconnect();
    }

    private void disconnect() {
        if (username != null) {
            server.removeClient(username);
            broadcastUserStatus(username, false);
            updateOfflineStatus(username);
            System.out.println("[ClientHandler] 用户下线: " + username);
        }
        try { socket.close(); } catch (IOException ignored) {}
    }

    /**
     * 更新用户登录状态为在线
     */
    private void updateLoginStatus(String username) {
        String sql = "UPDATE `user` SET online_status = 1, last_login_at = NOW(3) WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ClientHandler] 更新登录状态失败: " + e.getMessage());
        }
    }

    /**
     * 更新用户登录状态为离线
     */
    private void updateOfflineStatus(String username) {
        String sql = "UPDATE `user` SET online_status = 0 WHERE username = ?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ClientHandler] 更新离线状态失败: " + e.getMessage());
        }
    }

    /**
     * 存储消息到数据库（私聊专用，需要 sender 和 receiver 都在 user 表中）
     */
    private void saveMessage(String sender, String receiver, int chatType, int messageType, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u1.id, u2.id, ?, ?, ?, FROM_UNIXTIME(? / 1000.0) "
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
            System.err.println("[ClientHandler] 消息存储失败: " + e.getMessage());
        }
    }

    /**
     * 存储群聊消息到数据库（receiver_id 暂用 0 占位，后续可关联群表）
     */
    private void saveGroupMessage(String sender, String content, long timestamp) {
        String sql = "INSERT INTO chat_message (sender_id, receiver_id, chat_type, message_type, content, sent_at) "
                + "SELECT u.id, 0, ?, ?, ?, FROM_UNIXTIME(? / 1000.0) "
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
            System.err.println("[ClientHandler] 群聊消息存储失败: " + e.getMessage());
        }
    }

    /**
     * 发送在线用户列表
     */
    private void sendUserList() {
        JsonObject userListJson = new JsonObject();
        userListJson.addProperty("type", MessageProtocol.TYPE_USER_LIST);
        userListJson.addProperty("sender", "server");
        // 组装在线用户列表（JSON 数组字符串）
        String userList = "[" + String.join(",",
                server.getOnlineUsers().stream()
                        .map(u -> "\"" + u + "\"")
                        .toArray(String[]::new)) + "]";
        userListJson.addProperty("content", userList);
        sendRaw(MessageProtocol.toWire(userListJson));
    }

    /**
     * 广播用户上线/下线状态
     */
    private void broadcastUserStatus(String username, boolean online) {
        JsonObject status = new JsonObject();
        status.addProperty("type", online ? MessageProtocol.TYPE_USER_ONLINE : MessageProtocol.TYPE_USER_OFFLINE);
        status.addProperty("sender", "server");
        status.addProperty("content", username);
        server.broadcast(MessageProtocol.toWire(status));
    }

    /**
     * 发送简单消息给当前客户端
     */
    private void sendMessage(String type, String content) {
        sendRaw(MessageProtocol.buildResponse(type, content));
    }

    private void sendError(String errorMsg) {
        sendRaw(MessageProtocol.buildResponse(MessageProtocol.TYPE_ERROR, errorMsg));
    }

    /**
     * 直接发送原始字符串到当前客户端（线程安全）
     * 使用 synchronized 防止多线程并发写入导致消息乱码/丢失
     */
    public synchronized void sendRaw(String raw) {
        if (writer == null) {
            System.err.println("[ClientHandler] writer 为空，无法发送消息");
            return;
        }
        try {
            writer.write(raw);
            writer.flush();
        } catch (IOException e) {
            System.err.println("[ClientHandler] 发送消息失败: " + e.getMessage());
        }
    }

    public String getUsername() {
        return username;
    }
}