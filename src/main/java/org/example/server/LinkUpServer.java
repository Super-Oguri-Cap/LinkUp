package org.example.server;

import org.example.util.DBUtil;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * LinkUp 服务端主程序
 * 使用 ServerSocket 监听客户端连接，每个客户端分配一个 ClientHandler 线程
 * 支持消息广播、在线用户管理、线程池管理
 */
public class LinkUpServer {

    private static final int PORT = 8888;                       // 默认监听端口
    private static final int THREAD_POOL_SIZE = 50;             // 线程池大小
    private static final int MAX_QUEUE_SIZE = 200;              // 等待队列最大长度

    private ServerSocket serverSocket;
    private boolean running = false;

    // 在线用户映射：用户名 -> ClientHandler
    private final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    // 线程池：管理 ClientHandler 线程和 AI 调用等异步任务
    private final ThreadPoolExecutor executorService = new ThreadPoolExecutor(
            THREAD_POOL_SIZE,                                   // 核心线程数
            THREAD_POOL_SIZE,                                   // 最大线程数
            60L, TimeUnit.SECONDS,                              // 空闲线程存活时间
            new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),          // 等待队列
            new ThreadPoolExecutor.CallerRunsPolicy()           // 拒绝策略：由调用线程执行
    );

    /**
     * 启动服务器
     */
    public void start() {
        try {
            System.out.println("[服务端] 正在初始化数据库...");
            org.example.util.DBInit.init();

            serverSocket = new ServerSocket(PORT);
            running = true;
            System.out.println("============================================");
            System.out.println("  LinkUp 服务端已启动");
            System.out.println("  监听端口: " + PORT);
            System.out.println("  线程池大小: " + THREAD_POOL_SIZE);
            System.out.println("============================================");

            // 主循环：接受客户端连接
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String clientAddress = clientSocket.getInetAddress().getHostAddress();
                    System.out.println("[服务端] 新客户端连接: " + clientAddress);

                    // 创建 ClientHandler 并提交到线程池
                    ClientHandler handler = new ClientHandler(clientSocket, this);
                    executorService.execute(handler);
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[服务端] 接受连接失败: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[服务端] 启动失败: " + e.getMessage());
        } finally {
            shutdown();
        }
    }

    /**
     * 关闭服务器
     */
    public void shutdown() {
        running = false;
        System.out.println("[服务端] 正在关闭...");

        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 关闭所有客户端连接（先拍快照，避免迭代过程中 clients 被并发修改）
        List<ClientHandler> snapshot = new ArrayList<>(clients.values());
        for (ClientHandler handler : snapshot) {
            handler.sendRaw("{\"type\":\"SERVER_SHUTDOWN\",\"sender\":\"server\",\"content\":\"服务器已关闭\"}\n");
        }
        clients.clear();

        // 关闭 ServerSocket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}

        // 关闭数据库连接池
        DBUtil.shutdown();

        System.out.println("[服务端] 已关闭");
    }

    // ==================== 在线用户管理 ====================

    /**
     * 添加在线客户端
     */
    public void addClient(String username, ClientHandler handler) {
        clients.put(username, handler);
        System.out.println("[服务端] 用户上线: " + username + " (当前在线: " + clients.size() + ")");
    }

    /**
     * 移除在线客户端
     */
    public void removeClient(String username) {
        clients.remove(username);
        System.out.println("[服务端] 用户下线: " + username + " (当前在线: " + clients.size() + ")");
    }

    /**
     * 获取指定用户的 ClientHandler
     */
    public ClientHandler getClient(String username) {
        return clients.get(username);
    }

    /**
     * 获取所有在线用户名集合
     */
    public Set<String> getOnlineUsers() {
        return new HashSet<>(clients.keySet());
    }

    /**
     * 广播消息给所有在线客户端
     */
    public void broadcast(String message) {
        for (ClientHandler handler : clients.values()) {
            handler.sendRaw(message);
        }
    }

    /**
     * 获取线程池（供 AI 异步调用等任务使用）
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    // ==================== 入口 ====================

    public static void main(String[] args) {
        LinkUpServer server = new LinkUpServer();

        // 注册 JVM 关闭钩子，确保优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        server.start();
    }
}