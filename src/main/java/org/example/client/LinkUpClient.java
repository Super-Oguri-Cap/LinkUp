package org.example.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.util.MessageProtocol;

import javax.swing.*;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * LinkUp 客户端主程序
 * 使用 Socket 连接服务端，启动独立线程监听服务器消息
 * 主线程负责 UI 交互，确保界面不卡顿
 *
 * 增强功能（P0+P1）：
 * - 心跳保活：每 30 秒发送 PING，10 秒内收不到 PONG 则判定断线
 * - 断线重连：指数退避自动重连（1s→2s→4s→8s→16s，最多 5 次）
 * - 消息送达状态：ACK / DELIVERED / READ 三态追踪
 */
public class LinkUpClient {

    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 8888;

    // ==================== 心跳与重连配置 ====================
    private static final int HEARTBEAT_INTERVAL_SEC = 30;   // 心跳间隔 30 秒
    private static final int MAX_RECONNECT_ATTEMPTS = 5;    // 最大重连次数
    private static final int RECONNECT_BASE_DELAY_MS = 1000; // 重连基础延迟 1 秒

    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    // 心跳线程
    private ScheduledExecutorService heartbeatExecutor;
    private final AtomicInteger missedHeartbeats = new AtomicInteger(0);

    private String currentUser;

    // UI 引用
    private LoginFrame loginFrame;

    // ==================== 回调函数 ====================
    private QuadConsumer<String, String, String, String> messageCallback;     // (sender, content, time, msgId)
    private java.util.function.Consumer<JsonArray> userListCallback;
    private BiConsumer<String, Boolean> userStatusCallback;          // (username, online)
    private BiConsumer<Boolean, String> loginCallback;               // (success, message)
    private BiConsumer<Boolean, String> registerCallback;            // (success, message)
    private BiConsumer<String, String> polishCallback;               // (polishedText, style)
    private BiConsumer<Boolean, String> friendAddCallback;           // (success, friendName)
    private java.util.function.Consumer<JsonArray> friendListCallback;
    private BiConsumer<String, String> friendRequestNotifyCallback;  // (requester, requestId)

    // P0: 消息送达状态回调
    private BiConsumer<String, String> msgStatusCallback;            // (msgId, status: ack/delivered/read)
    // P1: 消息撤回回调
    private BiConsumer<String, String> recallCallback;               // (msgId, sender)
    // P1: 群组列表回调
    private java.util.function.Consumer<JsonArray> groupListCallback;
    // P1: 搜索结果回调
    private BiConsumer<String, JsonArray> searchResultCallback;      // (keyword, results)
    // P1: AI 摘要回调
    private BiConsumer<String, String> chatSummaryCallback;          // (target, summary)
    // P1: @提及通知回调
    private BiConsumer<String, String> mentionCallback;              // (sender, groupName)
    // P0: 未读消息回调
    private java.util.function.Consumer<JsonArray> unreadMessagesCallback;
    private QuadConsumer<String, String, String, String> imageMessageCallback;  // (sender, base64Data, time, msgId)

    /**
     * 程序入口
     */
    public static void main(String[] args) {
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {}
        if (!"Nimbus".equals(UIManager.getLookAndFeel().getName())) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
        }

        SwingUtilities.invokeLater(() -> {
            LinkUpClient client = new LinkUpClient();
            client.showLogin();
        });
    }

    // ==================== TCP 连接管理 ====================

    /**
     * 连接到服务端，启动监听线程和心跳线程
     */
    public boolean connect() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            connected.set(true);
            intentionalDisconnect.set(false);
            missedHeartbeats.set(0);

            // 启动消息监听线程
            Thread listenerThread = new Thread(this::listenForMessages, "ServerListener");
            listenerThread.setDaemon(true);
            listenerThread.start();

            // 启动心跳线程
            startHeartbeat();

            System.out.println("[客户端] 已连接到服务端 " + SERVER_HOST + ":" + SERVER_PORT);
            return true;
        } catch (IOException e) {
            connected.set(false);
            System.err.println("[客户端] 连接失败: " + e.getMessage());
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(null,
                            "无法连接到服务器: " + e.getMessage(),
                            "连接失败", JOptionPane.ERROR_MESSAGE));
            return false;
        }
    }

    /**
     * 启动心跳保活：每 HEARTBEAT_INTERVAL_SEC 秒发送 PING
     * 如果连续 HEARTBEAT_TIMEOUT_SEC 秒未收到 PONG，触发重连
     */
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Heartbeat");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (!connected.get() || intentionalDisconnect.get()) {
                return;
            }
            // 上一轮 PING 没有得到 PONG，累加丢失计数
            int missed = missedHeartbeats.incrementAndGet();
            if (missed > 1) {
                // 连续两轮没收到 PONG，判定断线
                System.err.println("[心跳] 连续 " + missed + " 轮未收到 PONG，判定断线，触发重连...");
                onConnectionLost();
                return;
            }
            // 发送 PING
            try {
                JsonObject ping = new JsonObject();
                ping.addProperty("type", MessageProtocol.TYPE_PING);
                ping.addProperty("sender", currentUser != null ? currentUser : "");
                writer.write(MessageProtocol.toWire(ping));
                writer.flush();
            } catch (IOException e) {
                System.err.println("[心跳] PING 发送失败: " + e.getMessage());
                onConnectionLost();
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdownNow();
        }
    }

    /**
     * 连接丢失时的处理：标记断线 → 触发重连
     */
    private void onConnectionLost() {
        if (!connected.compareAndSet(true, false)) {
            return; // 已经处理过了
        }
        stopHeartbeat();
        closeSocket();

        SwingUtilities.invokeLater(() -> {
            if (!intentionalDisconnect.get()) {
                attemptReconnect();
            }
        });
    }

    /**
     * 指数退避自动重连（1s→2s→4s→8s→16s，最多 5 次）
     */
    private void attemptReconnect() {
        new Thread(() -> {
            for (int attempt = 1; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
                if (intentionalDisconnect.get()) return;

                int delayMs = RECONNECT_BASE_DELAY_MS * (1 << (attempt - 1)); // 1s, 2s, 4s, 8s, 16s
                System.out.println("[重连] 第 " + attempt + "/" + MAX_RECONNECT_ATTEMPTS
                        + " 次尝试，等待 " + (delayMs / 1000) + " 秒...");

                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    return;
                }

                if (intentionalDisconnect.get()) return;

                if (connect()) {
                    System.out.println("[重连] 重连成功！");
                    // 重连成功后重新登录
                    if (currentUser != null) {
                        JsonObject reLogin = MessageProtocol.buildMessage(
                                MessageProtocol.TYPE_LOGIN, currentUser, "", currentUser);
                        sendMessage(MessageProtocol.toWire(reLogin));
                    }
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(null,
                                    "已重新连接到服务器",
                                    "重连成功", JOptionPane.INFORMATION_MESSAGE));
                    return;
                }
            }

            // 全部重试失败
            System.err.println("[重连] 全部 " + MAX_RECONNECT_ATTEMPTS + " 次重连失败");
            SwingUtilities.invokeLater(() -> {
                int choice = JOptionPane.showConfirmDialog(null,
                        "服务器连接丢失，重连失败。\n是否重新连接？",
                        "连接断开", JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) {
                    attemptReconnect();
                }
            });
        }, "Reconnect").start();
    }

    /**
     * 监听服务端消息（运行在独立线程）
     */
    private void listenForMessages() {
        try {
            String line;
            while (connected.get() && (line = reader.readLine()) != null) {
                final String msg = line;
                System.out.println("[客户端] 收到消息: "
                        + msg.substring(0, Math.min(msg.length(), 80)));
                SwingUtilities.invokeLater(() -> handleServerMessage(msg));
            }
        } catch (IOException e) {
            if (connected.get() && !intentionalDisconnect.get()) {
                System.err.println("[客户端] 连接断开: " + e.getMessage());
                onConnectionLost();
            }
        }
    }

    /**
     * 处理服务端消息（在 UI 线程执行）
     */
    private void handleServerMessage(String rawMsg) {
        try {
            JsonObject msg = JsonParser.parseString(rawMsg).getAsJsonObject();
            String type = msg.get("type").getAsString();

            switch (type) {
                case MessageProtocol.TYPE_PONG:
                    // 收到心跳响应，重置丢失计数
                    missedHeartbeats.set(0);
                    break;

                case MessageProtocol.TYPE_LOGIN_SUCCESS:
                    handleLoginSuccess(msg);
                    break;
                case MessageProtocol.TYPE_LOGIN_FAIL:
                    if (loginCallback != null)
                        loginCallback.accept(false, msg.get("content").getAsString());
                    break;
                case MessageProtocol.TYPE_REGISTER_SUCCESS:
                    if (registerCallback != null)
                        registerCallback.accept(true, msg.get("content").getAsString());
                    break;
                case MessageProtocol.TYPE_REGISTER_FAIL:
                    if (registerCallback != null)
                        registerCallback.accept(false, msg.get("content").getAsString());
                    break;
                case MessageProtocol.TYPE_USER_LIST:
                    handleUserList(msg);
                    break;
                case MessageProtocol.TYPE_FRIEND_LIST:
                    handleFriendList(msg);
                    break;
                case MessageProtocol.TYPE_MESSAGE:
                    handleMessage(msg);
                    break;
                case MessageProtocol.TYPE_USER_ONLINE:
                    handleUserStatus(msg.get("content").getAsString(), true);
                    break;
                case MessageProtocol.TYPE_USER_OFFLINE:
                    handleUserStatus(msg.get("content").getAsString(), false);
                    break;
                case MessageProtocol.TYPE_ERROR:
                    JOptionPane.showMessageDialog(null, msg.get("content").getAsString(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                    break;
                case MessageProtocol.TYPE_POLISH_RESULT:
                    if (polishCallback != null)
                        polishCallback.accept(msg.get("content").getAsString(), "");
                    break;
                case MessageProtocol.TYPE_ADD_FRIEND_SUCCESS:
                    if (friendAddCallback != null)
                        friendAddCallback.accept(true, msg.get("content").getAsString());
                    break;
                case MessageProtocol.TYPE_ADD_FRIEND_FAIL:
                    if (friendAddCallback != null)
                        friendAddCallback.accept(false, msg.get("content").getAsString());
                    break;
                case MessageProtocol.TYPE_FRIEND_REQUEST_NOTIFY:
                    if (friendRequestNotifyCallback != null) {
                        String requester = msg.get("sender").getAsString();
                        String requestId = msg.get("receiver").getAsString();
                        friendRequestNotifyCallback.accept(requester, requestId);
                    }
                    break;

                // ==================== P0: 消息送达状态 ====================
                case MessageProtocol.TYPE_MSG_ACK:
                case MessageProtocol.TYPE_MSG_DELIVERED:
                case MessageProtocol.TYPE_MSG_READ:
                    if (msgStatusCallback != null && msg.has("content")) {
                        String status = type.equals(MessageProtocol.TYPE_MSG_ACK) ? "ack"
                                : type.equals(MessageProtocol.TYPE_MSG_DELIVERED) ? "delivered" : "read";
                        msgStatusCallback.accept(msg.get("content").getAsString(), status);
                    }
                    break;

                // ==================== P1: 消息撤回 ====================
                case MessageProtocol.TYPE_RECALL:
                    if (recallCallback != null) {
                        recallCallback.accept(msg.get("content").getAsString(),
                                msg.get("sender").getAsString());
                    }
                    break;

                // ==================== P1: 群组列表 ====================
                case MessageProtocol.TYPE_GROUP_LIST_RESP:
                    if (groupListCallback != null && msg.has("content")) {
                        JsonArray groups = JsonParser.parseString(
                                msg.get("content").getAsString()).getAsJsonArray();
                        groupListCallback.accept(groups);
                    }
                    break;

                // ==================== P1: 搜索结果 ====================
                case MessageProtocol.TYPE_SEARCH_RESULT:
                    if (searchResultCallback != null) {
                        JsonArray results = JsonParser.parseString(
                                msg.get("content").getAsString()).getAsJsonArray();
                        searchResultCallback.accept(msg.get("receiver").getAsString(), results);
                    }
                    break;

                // ==================== P1: @提及通知 ====================
                case MessageProtocol.TYPE_MENTION_NOTIFY:
                    if (mentionCallback != null) {
                        mentionCallback.accept(msg.get("sender").getAsString(),
                                msg.get("receiver").getAsString());
                    }
                    break;

                // ==================== P0: 离线/未读消息推送 ====================
                case MessageProtocol.TYPE_UNREAD_MESSAGES:
                    if (unreadMessagesCallback != null && msg.has("content")) {
                        JsonArray unread = JsonParser.parseString(
                                msg.get("content").getAsString()).getAsJsonArray();
                        unreadMessagesCallback.accept(unread);
                    }
                    break;

                // ==================== P1: AI 聊天摘要 ====================
                case MessageProtocol.TYPE_CHAT_SUMMARY:
                    if (chatSummaryCallback != null) {
                        chatSummaryCallback.accept(msg.get("receiver").getAsString(),
                                msg.get("content").getAsString());
                    }
                    break;
                case MessageProtocol.TYPE_IMAGE:
                    if (imageMessageCallback != null) {
                        String sender = msg.get("sender").getAsString();
                        String content = msg.get("content").getAsString();
                        String time = msg.has("time") ? msg.get("time").getAsString()
                                : String.valueOf(System.currentTimeMillis());
                        String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";
                        imageMessageCallback.accept(sender, content, time, msgId);
                    }
                    break;
                default:
                    System.out.println("[客户端] 未知消息类型: " + type);
            }
        } catch (Exception e) {
            System.err.println("[客户端] 消息处理异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== 消息处理器 ====================

    private void handleLoginSuccess(JsonObject msg) {
        if (loginCallback != null) loginCallback.accept(true, msg.get("content").getAsString());
    }

    private void handleUserList(JsonObject msg) {
        if (userListCallback != null) {
            JsonArray userList = JsonParser.parseString(msg.get("content").getAsString()).getAsJsonArray();
            userListCallback.accept(userList);
        }
    }

    private void handleFriendList(JsonObject msg) {
        if (friendListCallback != null) {
            JsonArray friendList = JsonParser.parseString(msg.get("content").getAsString()).getAsJsonArray();
            friendListCallback.accept(friendList);
        }
    }

    private void handleMessage(JsonObject msg) {
        if (messageCallback != null) {
            String sender = msg.get("sender").getAsString();
            String content = msg.get("content").getAsString();
            String time = msg.has("time") ? msg.get("time").getAsString()
                    : String.valueOf(System.currentTimeMillis());
            String msgId = msg.has("msgId") ? msg.get("msgId").getAsString() : "";
            messageCallback.accept(sender, content, time, msgId);

            // 收到消息后，发送已读回执
            if (!msgId.isEmpty() && currentUser != null) {
                JsonObject readAck = MessageProtocol.buildMessageWithId(
                        MessageProtocol.TYPE_MSG_READ, currentUser,
                        msg.get("sender").getAsString(), msgId, msgId);
                sendMessage(MessageProtocol.toWire(readAck));
            }
        }
    }

    private void handleUserStatus(String username, boolean online) {
        if (userStatusCallback != null) {
            userStatusCallback.accept(username, online);
        }
    }

    // ==================== 发送消息 ====================

    /**
     * 发送消息到服务端（带异常处理和断线检测）
     */
    public void sendMessage(String message) {
        if (!connected.get() || writer == null) {
            System.err.println("[客户端] 未连接到服务器，无法发送消息");
            return;
        }
        try {
            writer.write(message);
            writer.flush();
        } catch (IOException e) {
            System.err.println("[客户端] 发送消息失败: " + e.getMessage());
            onConnectionLost();
        }
    }

    /**
     * 构建并发送业务消息（自动带 msgId）
     */
    public void sendChatMessage(String type, String sender, String receiver, String content) {
        JsonObject msg = MessageProtocol.buildMessage(type, sender, receiver, content);
        sendMessage(MessageProtocol.toWire(msg));
    }

    /**
     * 断开与服务端的连接，清理资源
     */
    public void disconnect() {
        intentionalDisconnect.set(true);
        connected.set(false);
        stopHeartbeat();
        closeSocket();
        System.out.println("[客户端] 已断开连接");
    }

    private void closeSocket() {
        try {
            if (writer != null) { writer.close(); }
            if (reader != null) { reader.close(); }
            if (socket != null && !socket.isClosed()) { socket.close(); }
        } catch (IOException e) {
            System.err.println("[客户端] 关闭连接异常: " + e.getMessage());
        }
    }

    // ==================== UI 显示 ====================

    public void showLogin() {
        if (!connect()) {
            System.exit(1);
        }
        loginFrame = new LoginFrame(this);
        loginFrame.setVisible(true);
        loginCallback = loginFrame::handleLoginResult;
    }

    // ==================== Getter / Setter ====================

    public String getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(String currentUser) {
        this.currentUser = currentUser;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void setMessageCallback(QuadConsumer<String, String, String, String> callback) {
        this.messageCallback = callback;
    }

    public void setUserListCallback(java.util.function.Consumer<JsonArray> callback) {
        this.userListCallback = callback;
    }

    public void setUserStatusCallback(BiConsumer<String, Boolean> callback) {
        this.userStatusCallback = callback;
    }

    public void setRegisterCallback(BiConsumer<Boolean, String> callback) {
        this.registerCallback = callback;
    }

    public void setPolishCallback(BiConsumer<String, String> callback) {
        this.polishCallback = callback;
    }

    public void setFriendAddCallback(BiConsumer<Boolean, String> callback) {
        this.friendAddCallback = callback;
    }

    public void setFriendListCallback(java.util.function.Consumer<JsonArray> callback) {
        this.friendListCallback = callback;
    }

    public void setFriendRequestNotifyCallback(BiConsumer<String, String> callback) {
        this.friendRequestNotifyCallback = callback;
    }

    // P0: 消息送达状态回调
    public void setMsgStatusCallback(BiConsumer<String, String> callback) {
        this.msgStatusCallback = callback;
    }

    // P1: 消息撤回回调
    public void setRecallCallback(BiConsumer<String, String> callback) {
        this.recallCallback = callback;
    }

    // P1: 群组列表回调
    public void setGroupListCallback(java.util.function.Consumer<JsonArray> callback) {
        this.groupListCallback = callback;
    }

    // P1: 搜索结果回调
    public void setSearchResultCallback(BiConsumer<String, JsonArray> callback) {
        this.searchResultCallback = callback;
    }

    // P1: AI 摘要回调
    public void setChatSummaryCallback(BiConsumer<String, String> callback) {
        this.chatSummaryCallback = callback;
    }

    // P1: @提及通知回调
    public void setMentionCallback(BiConsumer<String, String> callback) {
        this.mentionCallback = callback;
    }

    // P0: 未读消息回调
    public void setUnreadMessagesCallback(java.util.function.Consumer<JsonArray> callback) {
        this.unreadMessagesCallback = callback;
    }
    public void setImageMessageCallback(QuadConsumer<String, String, String, String> callback) {
        this.imageMessageCallback = callback;
    }

    // ==================== 函数式接口 ====================

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    @FunctionalInterface
    public interface QuadConsumer<A, B, C, D> {
        void accept(A a, B b, C c, D d);
    }
}