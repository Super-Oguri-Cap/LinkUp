package org.example.server;

import com.google.gson.JsonObject;
import org.example.util.MessageProtocol;

import java.io.*;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端处理器 - 每个客户端连接对应一个线程
 * 负责连接管理、消息分发
 *
 * 改进说明：
 * 原实现将所有消息处理逻辑放在一个 1700+ 行的类中，通过庞大的 switch-case 分发。
 * 现重构为策略模式：
 * - 消息处理逻辑拆分到 AuthHandler / ChatHandler / FriendHandler / GroupHandler / SystemHandler
 * - ClientHandler 仅负责连接管理、消息读取和分发
 * - 通过 Map<String, MessageHandler> 注册表实现 O(1) 分发，替代 switch-case
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final LinkUpServer server;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String username; // 登录成功后绑定用户名

    /** 处理器上下文（延迟初始化，因为需要 this 引用） */
    private HandlerContext handlerContext;

    /** 消息处理器注册表：type -> MessageHandler */
    private final Map<String, MessageHandler> handlerMap = new HashMap<>();

    public ClientHandler(Socket socket, LinkUpServer server) {
        this.socket = socket;
        this.server = server;
    }

    /**
     * 初始化处理器上下文和消息分发注册表
     */
    private void initHandlers() {
        this.handlerContext = new HandlerContext(this, server);

        // 注册各功能域的处理器
        AuthHandler authHandler = new AuthHandler();
        ChatHandler chatHandler = new ChatHandler();
        FriendHandler friendHandler = new FriendHandler();
        GroupHandler groupHandler = new GroupHandler();
        SystemHandler systemHandler = new SystemHandler();

        // 认证
        handlerMap.put(MessageProtocol.TYPE_LOGIN, authHandler);
        handlerMap.put(MessageProtocol.TYPE_REGISTER, authHandler);

        // 聊天
        handlerMap.put(MessageProtocol.TYPE_PRIVATE_CHAT, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_GROUP_CHAT, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_AI_CHAT, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_AI_COMPANION, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_GROUP_SUMMARY, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_POLISH, chatHandler);
        handlerMap.put(MessageProtocol.TYPE_IMAGE, chatHandler);

        // 好友管理
        handlerMap.put(MessageProtocol.TYPE_FRIEND_REQUEST, friendHandler);
        handlerMap.put(MessageProtocol.TYPE_FRIEND_REQUEST_ACCEPT, friendHandler);
        handlerMap.put(MessageProtocol.TYPE_FRIEND_REQUEST_REJECT, friendHandler);
        handlerMap.put(MessageProtocol.TYPE_REMOVE_FRIEND, friendHandler);
        handlerMap.put(MessageProtocol.TYPE_REFRESH_FRIENDS, friendHandler);

        // 群组管理
        handlerMap.put(MessageProtocol.TYPE_CREATE_GROUP, groupHandler);
        handlerMap.put(MessageProtocol.TYPE_JOIN_GROUP, groupHandler);
        handlerMap.put(MessageProtocol.TYPE_LEAVE_GROUP, groupHandler);
        handlerMap.put(MessageProtocol.TYPE_KICK_MEMBER, groupHandler);
        handlerMap.put(MessageProtocol.TYPE_GROUP_LIST_REQ, groupHandler);

        // 系统消息
        handlerMap.put(MessageProtocol.TYPE_PING, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_MSG_READ, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_RECALL, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_DELETE_MESSAGE, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_LOGOUT, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_BLOCK_USER, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_UNBLOCK_USER, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_SEARCH_MESSAGES, systemHandler);
        handlerMap.put(MessageProtocol.TYPE_CHAT_SUMMARY, systemHandler);
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));

            // 初始化处理器注册表
            initHandlers();

            String line;
            while ((line = reader.readLine()) != null) {
                // 隐私安全：不打印消息内容，只记录消息类型摘要
                String typeLabel = "";
                try {
                    JsonObject tmp = MessageProtocol.fromWire(line);
                    if (tmp != null && tmp.has("type")) {
                        typeLabel = " [type=" + tmp.get("type").getAsString() + "]";
                    }
                } catch (Exception ignored) {}
                System.out.println("[Handler] 收到来自 " + socket.getInetAddress() + " 的消息" + typeLabel);

                try {
                    JsonObject msg = MessageProtocol.fromWire(line);
                    if (msg == null) {
                        sendError("消息格式错误");
                        continue;
                    }
                    String type = msg.get("type").getAsString();
                    handleMessage(type, msg);
                } catch (Throwable t) {
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
     * 根据消息类型分发到对应的处理器（策略模式替代 switch-case）
     */
    private void handleMessage(String type, JsonObject msg) {
        MessageHandler handler = handlerMap.get(type);
        if (handler != null) {
            handler.handle(msg, handlerContext);
        } else {
            sendError("未知消息类型: " + type);
        }
    }

    // ==================== 连接管理 ====================

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
     * 更新用户离线状态
     */
    private void updateOfflineStatus(String username) {
        String sql = "UPDATE `user` SET online_status = 0 WHERE username = ?";
        try (java.sql.Connection conn = org.example.util.DBUtil.getConnection();
             java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            System.err.println("[ClientHandler] 更新离线状态失败: " + e.getMessage());
        }
    }

    // ==================== 基础方法（供 HandlerContext 调用） ====================

    /**
     * 直接发送原始字符串到当前客户端（线程安全）
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

    /**
     * 发送简单消息给当前客户端
     */
    public void sendMessage(String type, String content) {
        sendRaw(MessageProtocol.buildResponse(type, content));
    }

    public void sendError(String errorMsg) {
        sendRaw(MessageProtocol.buildResponse(MessageProtocol.TYPE_ERROR, errorMsg));
    }

    public String getUsername() {
        return username;
    }

    /**
     * 设置用户名（登录成功后由 AuthHandler 调用）
     */
    public void setUsername(String username) {
        this.username = username;
    }
}
