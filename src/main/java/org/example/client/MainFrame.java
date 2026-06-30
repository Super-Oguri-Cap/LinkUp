package org.example.client;

import org.example.client.panel.AIPanel;
import org.example.client.panel.ContactPanel;
import org.example.client.panel.MessagePanel;
import org.example.service.ChatHistoryService;
import org.example.service.ChatSessionManager;
import org.example.service.ContactManager;
import org.example.util.MessageProtocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * LinkUp 主界面（重构后 — 协调者角色）
 * 职责：组装各模块面板、处理顶层事件协调、管理服务层与面板层的通信
 * 具体 UI 创建委托给 MessagePanel / ContactPanel / AIPanel
 * 业务逻辑委托给 ChatHistoryService / ContactManager / ChatSessionManager
 */
public class MainFrame extends JFrame
        implements ContactManager.ContactChangeListener,
                   ChatSessionManager.SessionChangeListener {

    private final LinkUpClient client;
    private final String currentUser;

    // ==================== 服务层 ====================
    private final ChatHistoryService chatHistoryService;
    private final ContactManager contactManager;
    private final ChatSessionManager sessionManager;

    // ==================== 面板层 ====================
    private MessagePanel messagePanel;
    private ContactPanel contactPanel;
    private AIPanel aiPanel;

    // ==================== 顶层 UI 组件 ====================
    private JTabbedPane tabbedPane;
    private JLabel titleLabel;
    private JLabel statusLabel;

    public MainFrame(LinkUpClient client) {
        this.client = client;
        this.currentUser = client.getCurrentUser();

        // 初始化服务层
        this.chatHistoryService = new ChatHistoryService();
        this.contactManager = new ContactManager(currentUser);
        this.sessionManager = new ChatSessionManager(currentUser);

        // 注册监听器
        this.contactManager.setListener(this);
        this.sessionManager.addListener(this);

        initUI();
        setupCallbacks();
        loadChatHistory();
        // 初始化完成后，从数据库加载真实好友列表（含离线好友）
        requestFriendList();
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        setTitle("LinkUp - " + currentUser);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 680);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(800, 500));

        // 关闭窗口时发送下线通知
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                JsonObject logoutMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_LOGOUT, currentUser, "", "");
                client.sendMessage(MessageProtocol.toWire(logoutMsg));
            }
        });

        // 主面板：BorderLayout
        JPanel mainPanel = new JPanel(new BorderLayout());

        // 标题栏
        mainPanel.add(createTitleBar(), BorderLayout.NORTH);

        // 中央三 Tab 面板
        tabbedPane = new JTabbedPane(JTabbedPane.LEFT);
        tabbedPane.setFont(new Font("微软雅黑", Font.BOLD, 14));
        tabbedPane.setBackground(new Color(240, 240, 245));
        tabbedPane.setForeground(Color.BLACK);

        // 创建三个面板
        messagePanel = new MessagePanel(contactManager, sessionManager, chatHistoryService);
        contactPanel = new ContactPanel(contactManager);
        aiPanel = new AIPanel();

        // 设置面板动作监听器
        setupPanelListeners();

        tabbedPane.addTab("消息", messagePanel);
        tabbedPane.addTab("联系人", contactPanel);
        tabbedPane.addTab("AI 空间", aiPanel);

        // 自定义 Tab 组件 — 强制每个 Tab 上的文字颜色为黑色
        setupTabAppearance();

        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        add(mainPanel);
    }

    /**
     * 设置三个面板的动作监听器，将面板事件连接到 MainFrame 的协调方法
     */
    private void setupPanelListeners() {
        // 消息面板监听器
        messagePanel.setActionListener(new MessagePanel.MessageActionListener() {
            @Override
            public void onSendMessage(String content) {
                sendMessage(content);
            }

            @Override
            public void onSwitchChat(String target) {
                switchToChat(target);
            }

            @Override
            public void onPolish(String text, String style) {
                doPolishFromMessagePanel(text, style);
            }

            @Override
            public void onDeleteMessage(int messageIndex) {
                deleteMessageFromHistory(messageIndex);
            }

            @Override
            public void onRecallMessage(int messageIndex) {
                recallMessage(messageIndex);
            }
        });

        // 联系人面板监听器
        contactPanel.setActionListener(new ContactPanel.ContactActionListener() {
            @Override
            public void onAddFriend(String friendName) {
                addFriend(friendName);
            }

            @Override
            public void onRemoveFriend(String friendName) {
                removeFriend(friendName);
            }

            @Override
            public void onChatWithFriend(String friendName) {
                switchToChat(friendName);
                tabbedPane.setSelectedIndex(0);
            }

            @Override
            public void onSetNickname(String friendName) {
                setNickname(friendName);
            }

            @Override
            public void onClearNickname(String friendName) {
                contactManager.setNickname(friendName, null);
                contactPanel.refreshFriendList();
                JOptionPane.showMessageDialog(MainFrame.this,
                        "已清除 \"" + friendName + "\" 的昵称", "提示", JOptionPane.INFORMATION_MESSAGE);
            }

            @Override
            public void onRefreshFriendList() {
                requestFriendList();
            }

            @Override
            public void onBlockFriend(String friendName) {
                toggleBlockFriend(friendName);
            }
        });

        // AI 面板监听器
        aiPanel.setActionListener(new AIPanel.AIActionListener() {
            @Override
            public void onOpenAICompanion() {
                openAICompanion();
            }

            @Override
            public void onGenerateGroupSummary() {
                generateGroupSummary();
            }

            @Override
            public void onPolish(String text, String style) {
                doPolishFromAIPanel(text, style);
            }

            @Override
            public void onOpenAISettings() {
                new AISettingsDialog(MainFrame.this).setVisible(true);
            }
        });
    }

    /**
     * 设置 Tab 标签外观
     */
    private void setupTabAppearance() {
        Color tabTextColor = new Color(30, 30, 30);
        Color tabSelectedColor = new Color(0, 90, 180);
        String[] tabTitles = {"消息", "联系人", "AI 空间"};
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            JPanel tabPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            tabPanel.setOpaque(false);
            JLabel tabLabel = new JLabel(tabTitles[i]);
            tabLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
            tabLabel.setForeground(tabTextColor);
            tabPanel.add(tabLabel);
            tabbedPane.setTabComponentAt(i, tabPanel);
        }
        tabbedPane.addChangeListener(e -> {
            int selected = tabbedPane.getSelectedIndex();
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                java.awt.Component comp = tabbedPane.getTabComponentAt(i);
                if (comp instanceof JPanel) {
                    java.awt.Component[] children = ((JPanel) comp).getComponents();
                    for (java.awt.Component child : children) {
                        if (child instanceof JLabel) {
                            ((JLabel) child).setForeground(
                                    i == selected ? tabSelectedColor : tabTextColor);
                        }
                    }
                }
            }
        });
    }

    // ==================== 标题栏 ====================

    private JPanel createTitleBar() {
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(new Color(0, 120, 212));
        titleBar.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

        titleLabel = new JLabel("当前用户: " + currentUser + "  |  群聊大厅");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 16));
        titleLabel.setForeground(Color.WHITE);
        titleBar.add(titleLabel, BorderLayout.WEST);

        // 右侧面板：功能按钮 + 状态标签 + 退出登录
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightPanel.setOpaque(false);

        // P1: 搜索按钮
        JButton searchButton = createSmallButton("搜索", new Color(100, 180, 255));
        searchButton.addActionListener(e -> showSearchDialog());
        rightPanel.add(searchButton);

        // P1: 群组按钮
        JButton groupButton = createSmallButton("群组", new Color(100, 180, 255));
        groupButton.addActionListener(e -> showGroupMenu());
        rightPanel.add(groupButton);

        // P1: AI 摘要按钮
        JButton summaryButton = createSmallButton("AI摘要", new Color(255, 152, 0));
        summaryButton.addActionListener(e -> requestAISummary());
        rightPanel.add(summaryButton);

        // P0: 图片按钮
        JButton imageButton = createSmallButton("图片", new Color(76, 175, 80));
        imageButton.addActionListener(e -> sendImageMessage());
        rightPanel.add(imageButton);

        statusLabel = new JLabel("在线");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(200, 255, 200));
        rightPanel.add(statusLabel);

        JButton logoutButton = new JButton("退出登录");
        logoutButton.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        logoutButton.setBackground(new Color(220, 80, 80));
        logoutButton.setForeground(Color.WHITE);
        logoutButton.setFocusPainted(false);
        logoutButton.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        logoutButton.addActionListener(e -> doLogout());
        rightPanel.add(logoutButton);

        titleBar.add(rightPanel, BorderLayout.EAST);

        return titleBar;
    }

    /** 创建小号功能按钮 */
    private JButton createSmallButton(String text, Color bgColor) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btn.setBackground(bgColor);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        return btn;
    }

    /**
     * 退出登录：发送下线通知 → 断开连接 → 关闭主窗口 → 重新显示登录窗口
     */
    private void doLogout() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要退出登录吗？",
                "退出登录", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        // 发送下线通知
        JsonObject logoutMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_LOGOUT, currentUser, "", "");
        client.sendMessage(MessageProtocol.toWire(logoutMsg));

        // 断开连接
        client.disconnect();

        // 关闭主窗口
        dispose();

        // 重新显示登录窗口
        SwingUtilities.invokeLater(() -> client.showLogin());
    }

    // ==================== 消息发送 ====================

    /**
     * 发送消息：根据当前会话类型选择对应的消息协议类型
     */
    private void sendMessage(String content) {
        if (content.isEmpty()) return;

        String type = sessionManager.getMessageProtocolType();
        // 使用 buildMessageWithId 生成唯一 msgId，用于服务端去重和状态追踪
        String msgId = MessageProtocol.generateMsgId();
        JsonObject msg = MessageProtocol.buildMessageWithId(
                type, currentUser, sessionManager.getCurrentTarget(), content, msgId);
        client.sendMessage(MessageProtocol.toWire(msg));

        messagePanel.getAndClearInput();
    }

    // ==================== 会话切换 ====================

    /**
     * 从联系人列表双击或下拉框切换到聊天
     */
    private void switchToChat(String target) {
        if (!sessionManager.switchToChat(target)) {
            return;
        }

        // 同步下拉框选中项
        messagePanel.syncComboSelection(target);

        // 重新加载聊天记录
        messagePanel.initHTMLDocument();
        loadChatHistory();
    }

    // ==================== AI 功能 ====================

    /**
     * 打开 AI 伴侣聊天
     */
    private void openAICompanion() {
        String displayName = contactManager.getDisplayName("AI伴侣");
        sessionManager.setSession("AI伴侣", ChatSessionManager.ChatType.COMPANION);
        titleLabel.setText(sessionManager.getTitleText(displayName));

        messagePanel.initHTMLDocument();
        tabbedPane.setSelectedIndex(0);

        appendMessage("AI伴侣", "你好，我是" + displayName + "。无论你想聊天、倾诉，还是单纯想找人说说话，我都陪着你。",
                    String.valueOf(System.currentTimeMillis()), "");
    }

    /**
     * 生成群聊摘要
     */
    private void generateGroupSummary() {
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_GROUP_SUMMARY, currentUser, "", "");
        client.sendMessage(MessageProtocol.toWire(msg));
        tabbedPane.setSelectedIndex(0);

        appendMessage("AI小助手", "正在生成群聊摘要，请稍候...",
                String.valueOf(System.currentTimeMillis()), "");
    }

    /**
     * 消息面板中的润色功能
     */
    private void doPolishFromMessagePanel(String text, String style) {
        JsonObject polishContent = new JsonObject();
        polishContent.addProperty("text", text);
        polishContent.addProperty("style", style);
        JsonObject polishMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_POLISH, currentUser, "AI小助手",
                polishContent.toString());
        client.sendMessage(MessageProtocol.toWire(polishMsg));

        messagePanel.setInputText("润色中...");
        messagePanel.setInputEnabled(false);
    }

    /**
     * AI 空间面板中的润色功能
     */
    private void doPolishFromAIPanel(String text, String style) {
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先输入要润色的文字", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JsonObject polishContent = new JsonObject();
        polishContent.addProperty("text", text);
        polishContent.addProperty("style", style);
        JsonObject polishMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_POLISH, currentUser, "AI小助手",
                polishContent.toString());
        client.sendMessage(MessageProtocol.toWire(polishMsg));

        aiPanel.setPolishFieldText("润色中...");
        aiPanel.setPolishFieldEnabled(false);
    }

    // ==================== 好友管理 ====================

    /**
     * 添加好友（发送好友请求，需要对方同意）
     */
    private void addFriend(String friendName) {
        // 客户端仅做基础校验，其余由服务端处理
        if (friendName == null || friendName.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入好友用户名", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        friendName = friendName.trim();
        if (friendName.equals(currentUser)) {
            JOptionPane.showMessageDialog(this, "不能添加自己为好友", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 发送好友请求（TYPE_FRIEND_REQUEST），服务端会校验后通知对方
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_FRIEND_REQUEST, currentUser, friendName, "");
        client.sendMessage(MessageProtocol.toWire(msg));
        contactPanel.clearAddFriendField();

        JOptionPane.showMessageDialog(this,
                "好友请求已发送给 " + friendName + "，请等待对方确认",
                "请求已发送", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * 删除好友
     */
    private void removeFriend(String friendName) {
        if (contactManager.isSystemContact(friendName)) {
            JOptionPane.showMessageDialog(this, friendName + "是系统联系人，无法删除", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要删除好友 " + friendName + " 吗？", "确认删除",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            JsonObject msg = MessageProtocol.buildMessage(
                    MessageProtocol.TYPE_REMOVE_FRIEND, currentUser, friendName, "");
            client.sendMessage(MessageProtocol.toWire(msg));
            contactManager.removeFriend(friendName);
        }
    }

    /**
     * 设置联系人昵称
     */
    private void setNickname(String username) {
        String current = contactManager.getRawNickname(username);
        String input = (String) JOptionPane.showInputDialog(this,
                "为 \"" + username + "\" 设置昵称:",
                "设置联系人昵称",
                JOptionPane.QUESTION_MESSAGE, null, null,
                current != null ? current : "");
        if (input != null) {
            contactManager.setNickname(username, input.trim().isEmpty() ? null : input.trim());
            contactPanel.refreshFriendList();
        }
    }

    // ==================== 消息接收与显示 ====================

    /**
     * 接收到新消息，追加到消息展示区并持久化
     * @param msgId 消息ID（用于状态追踪和撤回）
     */
    public void appendMessage(String sender, String content, String timeStr, String msgId) {
        // UI 更新委托给 MessagePanel（传入 msgId 用于状态追踪）
        messagePanel.appendMessage(sender, content, timeStr, currentUser, msgId);

        // 持久化委托给 ChatHistoryService
        chatHistoryService.appendMessage(
                sessionManager.getCurrentType().name(),
                currentUser,
                sessionManager.getCurrentTarget(),
                sender, content, timeStr);
    }

    /**
     * 删除指定索引的消息
     * 1. 从本地文件中删除记录（ChatHistoryService）
     * 2. 向服务端发请求，同步删除 MySQL chat_message 表中的记录
     */
    private void deleteMessageFromHistory(int messageIndex) {
        java.util.List<MessagePanel.MessageMeta> metaList = messagePanel.getMessageMetaList();
        if (messageIndex < 0 || messageIndex >= metaList.size()) return;

        MessagePanel.MessageMeta meta = metaList.get(messageIndex);

        // 1. 删除本地文件记录
        chatHistoryService.deleteMessage(
                meta.chatType, meta.currentUser, meta.target,
                meta.sender, meta.content, meta.timeStr);

        // 2. 向服务端发送删除请求，同步删除 MySQL 中的消息
        JsonObject deleteData = new JsonObject();
        deleteData.addProperty("sender", meta.sender);
        deleteData.addProperty("content", meta.content);
        deleteData.addProperty("time", meta.timeStr);
        deleteData.addProperty("chatType", meta.chatType);

        JsonObject deleteMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_DELETE_MESSAGE, currentUser, "", deleteData.toString());
        client.sendMessage(MessageProtocol.toWire(deleteMsg));
    }

    /**
     * 从本地文件加载最近 50 条聊天记录
     */
    private void loadChatHistory() {
        SwingUtilities.invokeLater(() -> {
            messagePanel.initHTMLDocument();
            try {
                java.util.List<String[]> records = chatHistoryService.loadRecentMessages(
                        sessionManager.getCurrentType().name(),
                        currentUser,
                        sessionManager.getCurrentTarget(),
                        50);
                for (String[] record : records) {
                    messagePanel.appendMessage(record[1], record[2], record[0], currentUser, "");
                }
            } catch (Exception e) {
                System.err.println("[MainFrame] 加载聊天记录失败: " + e.getMessage());
            }
        });
    }

    // ==================== 回调设置 ====================

    private void setupCallbacks() {
        // 消息接收回调
        client.setMessageCallback((sender, content, time, msgId) -> SwingUtilities.invokeLater(() -> {
            appendMessage(sender, content, time, msgId);
        }));

        // 用户列表更新回调
        client.setUserListCallback(this::updateFriendListFromServer);

        // 用户上下线回调
        client.setUserStatusCallback((username, online) -> SwingUtilities.invokeLater(() -> {
            if (online) {
                contactManager.addFriend(username);
            } else {
                contactManager.removeFriend(username);
            }
        }));

        // 润色结果回调
        client.setPolishCallback((polishedText, style) -> SwingUtilities.invokeLater(() -> {
            // 恢复消息面板输入框
            messagePanel.setInputEnabled(true);
            messagePanel.setInputText(polishedText);

            // 恢复 AI 空间中的润色输入框
            aiPanel.setPolishFieldEnabled(true);
            if ("润色中...".equals(aiPanel.getPolishFieldText())) {
                aiPanel.setPolishFieldText(polishedText);
            }
        }));

        // 好友添加结果回调
        // success=true：对方同意了你的好友请求（friendName=对方用户名）
        // success=false：好友请求被拒绝或因其他原因失败（friendName=失败原因）
        client.setFriendAddCallback((success, friendName) -> SwingUtilities.invokeLater(() -> {
            if (success) {
                JOptionPane.showMessageDialog(this,
                        friendName + " 已同意你的好友请求，你们现在是好友了！",
                        "好友请求已通过", JOptionPane.INFORMATION_MESSAGE);
                contactManager.addFriend(friendName);
                // 通知刷新好友列表
                requestFriendList();
            }
            // success=false 时不再弹 error 弹窗，因为内容可能是"XXX 拒绝了你的好友请求"
            // 这类信息提示由服务端通过 TYPE_ERROR 或 TYPE_MESSAGE 发送，
            // 或者通过这里弹出普通提示框
        }));

        // 好友请求通知回调：收到别人发来的好友请求
        client.setFriendRequestNotifyCallback((requester, requestId) -> SwingUtilities.invokeLater(() -> {
            int option = JOptionPane.showConfirmDialog(this,
                    requester + " 请求添加你为好友\n\n是否同意？",
                    "好友请求",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (option == JOptionPane.YES_OPTION) {
                // 同意好友请求
                JsonObject acceptMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_FRIEND_REQUEST_ACCEPT, currentUser, requester, requestId);
                client.sendMessage(MessageProtocol.toWire(acceptMsg));
            } else {
                // 拒绝好友请求
                JsonObject rejectMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_FRIEND_REQUEST_REJECT, currentUser, requester, requestId);
                client.sendMessage(MessageProtocol.toWire(rejectMsg));
            }
        }));

        // 好友列表回调（数据库查询结果，含离线好友）
        client.setFriendListCallback(this::updateFriendListFromDatabase);

        // ==================== P0: 消息状态回调 ====================
        client.setMsgStatusCallback((msgId, status) -> SwingUtilities.invokeLater(() -> {
            messagePanel.updateMessageStatus(msgId, status);
        }));

        // ==================== P0: 离线消息推送回调 ====================
        client.setUnreadMessagesCallback((unreadArray) -> SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < unreadArray.size(); i++) {
                JsonObject msgObj = unreadArray.get(i).getAsJsonObject();
                String sender = msgObj.get("sender").getAsString();
                String content = msgObj.get("content").getAsString();
                String time = msgObj.get("time").getAsString();
                String msgId = msgObj.has("msgId") ? msgObj.get("msgId").getAsString() : "";
                // 切换到该会话并追加消息
                sessionManager.switchToChat(sender);
                messagePanel.syncComboSelection(sender);
                messagePanel.appendMessage(sender, content, time, currentUser, "");
                messagePanel.updateMessageStatus(msgId, "delivered");
            }
            // 切回主会话
            sessionManager.switchToChat("所有人");
            messagePanel.syncComboSelection("所有人");
            JOptionPane.showMessageDialog(this,
                    "您离线期间收到 " + unreadArray.size() + " 条未读消息，已显示在对应聊天中",
                    "离线消息", JOptionPane.INFORMATION_MESSAGE);
        }));

        // ==================== P1: 消息撤回回调 ====================
        client.setRecallCallback((msgId, sender) -> SwingUtilities.invokeLater(() -> {
            messagePanel.handleRecall(msgId, sender);
        }));

        // ==================== P1: 群组列表回调 ====================
        client.setGroupListCallback((groups) -> SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder("您加入的群组：\n");
            for (int i = 0; i < groups.size(); i++) {
                JsonObject g = groups.get(i).getAsJsonObject();
                sb.append("  [").append(g.get("id").getAsInt()).append("] ")
                        .append(g.get("name").getAsString())
                        .append(" (群主: ").append(g.get("ownerName").getAsString()).append(")\n");
            }
            JOptionPane.showMessageDialog(this, sb.toString(), "群组列表", JOptionPane.INFORMATION_MESSAGE);
        }));

        // ==================== P1: @提及回调 ====================
        client.setMentionCallback((sender, groupName) -> SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(this,
                    sender + " 在群聊中@了你",
                    "有人@你", JOptionPane.INFORMATION_MESSAGE);
        }));

        // ==================== P1: 搜索回调 ====================
        client.setSearchResultCallback((keyword, results) -> SwingUtilities.invokeLater(() -> {
            StringBuilder sb = new StringBuilder("搜索 \"" + keyword + "\" 的结果：\n\n");
            for (int i = 0; i < results.size(); i++) {
                JsonObject r = results.get(i).getAsJsonObject();
                sb.append("[").append(r.get("sender").getAsString()).append("] ")
                        .append(r.get("content").getAsString()).append("\n");
                sb.append("  时间: ").append(r.get("time").getAsString()).append("\n\n");
            }
            if (results.size() == 0) {
                sb.append("未找到相关消息");
            }
            JTextArea textArea = new JTextArea(sb.toString());
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(500, 400));
            JOptionPane.showMessageDialog(this, scrollPane, "搜索结果", JOptionPane.INFORMATION_MESSAGE);
        }));

        // ==================== P1: AI 聊天摘要回调 ====================
        client.setChatSummaryCallback((target, summary) -> SwingUtilities.invokeLater(() -> {
            // 切换到对应会话并显示摘要
            sessionManager.switchToChat(target);
            messagePanel.syncComboSelection(target);
            messagePanel.initHTMLDocument();
            messagePanel.appendMessage("AI小助手", "【聊天摘要】" + summary,
                    String.valueOf(System.currentTimeMillis()), currentUser, "");
        }));

        // ==================== P0: 图片消息回调 ====================
        client.setImageMessageCallback((sender, base64Data, time, msgId) -> SwingUtilities.invokeLater(() -> {
            messagePanel.appendImageMessage(sender, base64Data, time, currentUser, msgId);
        }));
    }

    // ==================== P0: 消息撤回 ====================

    /**
     * 撤回消息：向服务端发送撤回请求
     */
    private void recallMessage(int messageIndex) {
        java.util.List<MessagePanel.MessageMeta> metaList = messagePanel.getMessageMetaList();
        if (messageIndex < 0 || messageIndex >= metaList.size()) return;

        MessagePanel.MessageMeta meta = metaList.get(messageIndex);
        if (meta.msgId.isEmpty()) return;

        // 构建撤回请求
        JsonObject recallData = new JsonObject();
        recallData.addProperty("msgId", meta.msgId);
        recallData.addProperty("target", meta.target);
        JsonObject recallMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_RECALL, currentUser, meta.target, recallData.toString());
        client.sendMessage(MessageProtocol.toWire(recallMsg));

        // 删除本地文件中的消息
        chatHistoryService.deleteMessage(
                meta.chatType, meta.currentUser, meta.target,
                meta.sender, meta.content, meta.timeStr);
    }

    // ==================== P1: 消息搜索 ====================

    /**
     * 显示搜索对话框
     */
    private void showSearchDialog() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel label = new JLabel("搜索聊天记录：");
        label.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        panel.add(label, BorderLayout.NORTH);

        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        JTextField keywordField = new JTextField(20);
        keywordField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputPanel.add(keywordField, BorderLayout.CENTER);

        JComboBox<String> targetCombo = new JComboBox<>();
        targetCombo.addItem("全部聊天");
        for (String friend : contactManager.getFriendList()) {
            targetCombo.addItem(friend);
        }
        targetCombo.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        inputPanel.add(targetCombo, BorderLayout.EAST);

        panel.add(inputPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "搜索聊天记录", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String keyword = keywordField.getText().trim();
            if (keyword.isEmpty()) return;

            String target = targetCombo.getSelectedItem().toString();
            JsonObject searchData = new JsonObject();
            searchData.addProperty("keyword", keyword);
            if (!"全部聊天".equals(target)) {
                searchData.addProperty("target", target);
            }
            JsonObject searchMsg = MessageProtocol.buildMessage(
                    MessageProtocol.TYPE_SEARCH_MESSAGES, currentUser, "", searchData.toString());
            client.sendMessage(MessageProtocol.toWire(searchMsg));
        }
    }

    // ==================== P1: 群组管理 ====================

    /**
     * 显示群组管理菜单
     */
    private void showGroupMenu() {
        String[] options = {"查看我的群组", "创建群组", "加入群组", "退出群组", "踢出成员"};
        String choice = (String) JOptionPane.showInputDialog(this,
                "群组管理：", "群组管理",
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == null) return;

        switch (choice) {
            case "查看我的群组":
                JsonObject listMsg = MessageProtocol.buildMessage(
                        MessageProtocol.TYPE_GROUP_LIST_REQ, currentUser, "", "");
                client.sendMessage(MessageProtocol.toWire(listMsg));
                break;
            case "创建群组":
                String groupName = JOptionPane.showInputDialog(this, "请输入群组名称：", "创建群组");
                if (groupName != null && !groupName.trim().isEmpty()) {
                    JsonObject createMsg = MessageProtocol.buildMessage(
                            MessageProtocol.TYPE_CREATE_GROUP, currentUser, "", groupName.trim());
                    client.sendMessage(MessageProtocol.toWire(createMsg));
                }
                break;
            case "加入群组":
                String groupId = JOptionPane.showInputDialog(this, "请输入群组 ID：", "加入群组");
                if (groupId != null && !groupId.trim().isEmpty()) {
                    JsonObject joinMsg = MessageProtocol.buildMessage(
                            MessageProtocol.TYPE_JOIN_GROUP, currentUser, "", groupId.trim());
                    client.sendMessage(MessageProtocol.toWire(joinMsg));
                }
                break;
            case "退出群组":
                String leaveId = JOptionPane.showInputDialog(this, "请输入要退出的群组 ID：", "退出群组");
                if (leaveId != null && !leaveId.trim().isEmpty()) {
                    JsonObject leaveMsg = MessageProtocol.buildMessage(
                            MessageProtocol.TYPE_LEAVE_GROUP, currentUser, "", leaveId.trim());
                    client.sendMessage(MessageProtocol.toWire(leaveMsg));
                }
                break;
            case "踢出成员":
                String kickGroupId = JOptionPane.showInputDialog(this, "请输入群组 ID：", "踢出成员");
                if (kickGroupId != null && !kickGroupId.trim().isEmpty()) {
                    String memberName = JOptionPane.showInputDialog(this, "请输入要踢出的成员用户名：", "踢出成员");
                    if (memberName != null && !memberName.trim().isEmpty()) {
                        JsonObject kickData = new JsonObject();
                        kickData.addProperty("groupId", kickGroupId.trim());
                        kickData.addProperty("memberName", memberName.trim());
                        JsonObject kickMsg = MessageProtocol.buildMessage(
                                MessageProtocol.TYPE_KICK_MEMBER, currentUser, "", kickData.toString());
                        client.sendMessage(MessageProtocol.toWire(kickMsg));
                    }
                }
                break;
        }
    }

    // ==================== P1: AI 聊天摘要 ====================

    /**
     * 请求 AI 生成当前聊天摘要
     */
    private void requestAISummary() {
        String target = sessionManager.getCurrentTarget();
        if ("所有人".equals(target)) {
            // 群聊摘要
            generateGroupSummary();
            return;
        }
        if ("AI伴侣".equals(target) || "AI小助手".equals(target)) {
            JOptionPane.showMessageDialog(this, "AI 聊天无需摘要", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // 私聊摘要
        JsonObject summaryData = new JsonObject();
        summaryData.addProperty("target", target);
        JsonObject summaryMsg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_CHAT_SUMMARY, currentUser, target, summaryData.toString());
        client.sendMessage(MessageProtocol.toWire(summaryMsg));
        JOptionPane.showMessageDialog(this, "正在生成聊天摘要，请稍候...", "AI 摘要", JOptionPane.INFORMATION_MESSAGE);
    }

    // ==================== P0: 图片消息 ====================

    /**
     * 发送图片消息
     */
    private void sendImageMessage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("选择图片");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "图片文件 (jpg, png, gif)", "jpg", "jpeg", "png", "gif"));
        int result = fileChooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        try {
            java.io.File imageFile = fileChooser.getSelectedFile();
            // 读取图片并编码为 Base64
            byte[] imageBytes = java.nio.file.Files.readAllBytes(imageFile.toPath());
            String base64 = java.util.Base64.getEncoder().encodeToString(imageBytes);

            String target = sessionManager.getCurrentTarget();
            if ("所有人".equals(target)) {
                JOptionPane.showMessageDialog(this, "群聊暂不支持图片发送", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if ("AI伴侣".equals(target) || "AI小助手".equals(target)) {
                JOptionPane.showMessageDialog(this, "AI 聊天暂不支持图片", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            JsonObject imageMsg = MessageProtocol.buildMessage(
                    MessageProtocol.TYPE_IMAGE, currentUser, target, base64);
            client.sendMessage(MessageProtocol.toWire(imageMsg));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "图片发送失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== P1: 黑名单 ====================

    /**
     * 拉黑/取消拉黑好友
     */
    private void toggleBlockFriend(String friendName) {
        boolean isBlocked = contactManager.isBlocked(friendName);
        int confirm = JOptionPane.showConfirmDialog(this,
                isBlocked ? "确定要取消拉黑 " + friendName + " 吗？" : "确定要拉黑 " + friendName + " 吗？\n拉黑后将无法收到对方的消息。",
                isBlocked ? "取消拉黑" : "拉黑好友",
                JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        JsonObject blockMsg = MessageProtocol.buildMessage(
                isBlocked ? MessageProtocol.TYPE_UNBLOCK_USER : MessageProtocol.TYPE_BLOCK_USER,
                currentUser, friendName, friendName);
        client.sendMessage(MessageProtocol.toWire(blockMsg));
        contactManager.toggleBlockStatus(friendName);
    }

    /**
     * 从服务端更新好友列表（仅在线用户）
     */
    private void updateFriendListFromServer(JsonArray userList) {
        contactManager.updateFromServer(userList);
    }

    /**
     * 从数据库更新好友列表（含离线好友）
     */
    private void updateFriendListFromDatabase(JsonArray friendList) {
        contactManager.rebuildFromDatabase(friendList);
    }

    /**
     * 向服务端请求刷新好友列表（从数据库查询）
     */
    private void requestFriendList() {
        JsonObject msg = MessageProtocol.buildMessage(
                MessageProtocol.TYPE_REFRESH_FRIENDS, currentUser, "", "");
        client.sendMessage(MessageProtocol.toWire(msg));
    }

    // ==================== ContactManager.ContactChangeListener 实现 ====================

    @Override
    public void onFriendListUpdated(java.util.List<String> friendList, int realFriendCount) {
        SwingUtilities.invokeLater(() -> {
            contactPanel.updateFriendList(friendList);
            contactPanel.updateFriendCount(realFriendCount);
            messagePanel.updateChatTargetCombo(friendList);
        });
    }

    @Override
    public void onNicknameChanged(String username, String nickname) {
        SwingUtilities.invokeLater(() -> contactPanel.refreshFriendList());
    }

    // ==================== ChatSessionManager.SessionChangeListener 实现 ====================

    @Override
    public void onSessionChanged(String target, ChatSessionManager.ChatType type, String titleText) {
        SwingUtilities.invokeLater(() -> {
            String displayName = contactManager.getDisplayName(target);
            titleLabel.setText(sessionManager.getTitleText(displayName));
        });
    }
}
