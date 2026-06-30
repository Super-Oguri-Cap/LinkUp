package org.example.client.panel;

import org.example.service.ChatHistoryService;
import org.example.service.ChatSessionManager;
import org.example.service.ContactManager;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 消息面板
 * 负责消息 Tab 的 UI 展示和交互，包括消息展示区、输入区、聊天对象选择栏
 * 消息持久化委托给 ChatHistoryService，会话状态委托给 ChatSessionManager
 */
public class MessagePanel extends JPanel {

    /** 时间格式化 */
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss");

    /** 消息展示区 */
    private final JTextPane messageArea;
    /** 消息输入框 */
    private final JTextField inputField;
    /** 聊天对象选择下拉框 */
    private final JComboBox<String> chatTargetCombo;
    /** 润色按钮 */
    private final JButton polishButton;

    /** 缓存 HTML 文档的 content 元素，避免每次 appendMessage 都 O(n) 查找 */
    private Element contentElement;

    /** 防止下拉框和 switchToChat 互相触发死循环 */
    private boolean isUpdatingChatTarget = false;

    /** 联系人管理服务（用于获取显示名称） */
    private final ContactManager contactManager;
    /** 会话状态管理服务 */
    private final ChatSessionManager sessionManager;
    /** 存储每条消息的元数据，用于右键删除时定位 */
    private final List<MessageMeta> messageMetaList = new ArrayList<>();

    /**
     * 消息元数据 — 记录每条消息在文件中的原始行，用于删除定位
     */
    public static class MessageMeta {
        public final String sender;
        public final String content;
        public final String timeStr;
        public final String chatType;
        public final String currentUser;
        public final String target;
        public String msgId = "";   // 消息ID（用于状态追踪和撤回）
        public String status = "";  // 消息状态：ack/delivered/read

        public MessageMeta(String sender, String content, String timeStr,
                    String chatType, String currentUser, String target) {
            this.sender = sender;
            this.content = content;
            this.timeStr = timeStr;
            this.chatType = chatType;
            this.currentUser = currentUser;
            this.target = target;
        }
    }

    /** 外部动作监听器 */
    private MessageActionListener actionListener;

    /**
     * 消息面板动作监听接口
     */
    public interface MessageActionListener {
        /** 发送消息 */
        void onSendMessage(String content);
        /** 切换聊天对象 */
        void onSwitchChat(String target);
        /** 润色消息 */
        void onPolish(String text, String style);
        /** 删除消息（由 MainFrame 协调 ChatHistoryService 删除） */
        void onDeleteMessage(int messageIndex);
        /** 撤回消息 */
        void onRecallMessage(int messageIndex);
    }

    public MessagePanel(ContactManager contactManager, ChatSessionManager sessionManager,
                        ChatHistoryService chatHistoryService) {
        this.contactManager = contactManager;
        this.sessionManager = sessionManager;
        this.messageArea = new JTextPane();
        this.inputField = new JTextField();
        this.chatTargetCombo = new JComboBox<>();
        this.polishButton = new JButton("润色");

        initUI();
    }

    public void setActionListener(MessageActionListener listener) {
        this.actionListener = listener;
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        setLayout(new BorderLayout());

        // 顶部：聊天对象选择栏
        add(createChatTargetBar(), BorderLayout.NORTH);

        // 消息展示区
        add(createMessageArea(), BorderLayout.CENTER);

        // 底部输入区
        add(createInputArea(), BorderLayout.SOUTH);
    }

    /**
     * 顶部聊天对象选择栏 — 下拉框选择群聊/私聊对象
     */
    private JPanel createChatTargetBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(new Color(0, 100, 180));
        bar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        JLabel chatLabel = new JLabel("聊天对象:");
        chatLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chatLabel.setForeground(Color.WHITE);
        bar.add(chatLabel, BorderLayout.WEST);

        chatTargetCombo.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chatTargetCombo.setBackground(Color.WHITE);
        chatTargetCombo.setForeground(Color.BLACK);
        // 自定义渲染器：确保文字颜色始终可见
        chatTargetCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setForeground(isSelected ? Color.WHITE : Color.BLACK);
                label.setFont(new Font("微软雅黑", Font.PLAIN, 13));
                return label;
            }
        });
        chatTargetCombo.addActionListener(e -> {
            if (isUpdatingChatTarget) return;
            if (chatTargetCombo.getSelectedItem() != null && actionListener != null) {
                String selected = chatTargetCombo.getSelectedItem().toString();
                if (!selected.equals(sessionManager.getCurrentTarget())) {
                    actionListener.onSwitchChat(selected);
                }
            }
        });
        bar.add(chatTargetCombo, BorderLayout.CENTER);

        return bar;
    }

    /**
     * 消息展示区 — JTextPane + HTML 渲染 + 右键删除菜单
     */
    private JScrollPane createMessageArea() {
        messageArea.setEditable(false);
        messageArea.setContentType("text/html");
        messageArea.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        messageArea.setBackground(new Color(248, 248, 252));
        messageArea.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        HTMLEditorKit kit = new HTMLEditorKit();
        messageArea.setEditorKit(kit);
        initHTMLDocument();

        // 右键弹出删除菜单
        messageArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showDeleteMenu(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showDeleteMenu(e);
            }
        });

        JScrollPane scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    /**
     * 显示右键删除菜单 — 根据点击位置定位到对应的消息
     */
    private void showDeleteMenu(MouseEvent e) {
        int viewPos = messageArea.viewToModel2D(e.getPoint());
        if (viewPos < 0) return;

        // 根据点击位置找到对应的消息索引
        int msgIndex = findMessageIndexAtPosition(viewPos);
        if (msgIndex < 0) return;

        JPopupMenu popup = new JPopupMenu();

        // 撤回按钮（仅自己发送的消息，且2分钟内）
        JMenuItem recallItem = new JMenuItem("撤回此消息");
        recallItem.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        recallItem.addActionListener(ev -> {
            if (msgIndex >= 0 && msgIndex < messageMetaList.size()) {
                MessageMeta meta = messageMetaList.get(msgIndex);
                if (meta.sender.equals(meta.currentUser) && !meta.msgId.isEmpty()) {
                    int confirm = JOptionPane.showConfirmDialog(this,
                            "确定要撤回这条消息吗？",
                            "确认撤回", JOptionPane.YES_NO_OPTION);
                    if (confirm == JOptionPane.YES_OPTION && actionListener != null) {
                        actionListener.onRecallMessage(msgIndex);
                    }
                } else {
                    JOptionPane.showMessageDialog(this, "只能撤回自己发送的消息", "提示", JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
        popup.add(recallItem);

        JMenuItem deleteItem = new JMenuItem("删除此消息");
        deleteItem.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        deleteItem.addActionListener(ev -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "确定要删除这条消息吗？\n此操作不可撤销。",
                    "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                deleteMessage(msgIndex);
            }
        });
        popup.add(deleteItem);
        popup.show(messageArea, e.getX(), e.getY());
    }

    /**
     * 根据文档位置找到对应的消息索引
     * 遍历 messageMetaList，通过 HTML 内容长度估算每条消息的文档偏移
     */
    private int findMessageIndexAtPosition(int pos) {
        HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
        Element contentEl = (contentElement != null) ? contentElement : doc.getElement("content");
        int childCount = contentEl.getElementCount();

        // 遍历 content 的子元素，找到点击位置对应的子元素索引
        for (int i = 0; i < childCount; i++) {
            Element child = contentEl.getElement(i);
            int start = child.getStartOffset();
            int end = child.getEndOffset();
            if (pos >= start && pos <= end) {
                // 子元素索引对应 messageMetaList 索引
                if (i < messageMetaList.size()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 删除指定索引的消息（UI 层面移除 + 通知 MainFrame 删除文件记录）
     */
    private void deleteMessage(int msgIndex) {
        if (msgIndex < 0 || msgIndex >= messageMetaList.size()) return;

        try {
            HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
            Element contentEl = (contentElement != null) ? contentElement : doc.getElement("content");

            if (msgIndex < contentEl.getElementCount()) {
                Element child = contentEl.getElement(msgIndex);
                // 移除该 HTML 元素
                doc.remove(child.getStartOffset(), child.getEndOffset() - child.getStartOffset());
            }

            // ★ 先通知 MainFrame 从文件中删除（此时 messageMetaList 尚未变更，msgIndex 定位准确）
            if (actionListener != null) {
                actionListener.onDeleteMessage(msgIndex);
            }

            // 再从元数据列表中移除（必须在回调之后，否则 MainFrame 读取的元数据会错位）
            messageMetaList.remove(msgIndex);
        } catch (Exception ex) {
            System.err.println("[MessagePanel] 删除消息失败: " + ex.getMessage());
        }
    }

    /**
     * 初始化 HTML 文档结构（含 AI 伴侣、摘要等特殊样式）
     */
    public void initHTMLDocument() {
        messageMetaList.clear();  // 清空元数据列表
        StringBuilder html = new StringBuilder();
        html.append("<html><head><style>");
        html.append("body { font-family: '微软雅黑', sans-serif; font-size: 14px; margin: 10px; }");
        html.append(".msg-container { margin-bottom: 8px; overflow: hidden; }");
        html.append(".msg-self { background-color: #C8E6C9; border-radius: 8px; padding: 8px 12px; margin-left: 80px; }");
        html.append(".msg-other { background-color: #FFFFFF; border-radius: 8px; padding: 8px 12px; margin-right: 80px; }");
        html.append(".msg-ai { background-color: #E3F2FD; border-radius: 8px; padding: 8px 12px; margin-right: 80px; }");
        html.append(".msg-companion { background-color: #FFF3E0; border-radius: 8px; padding: 8px 12px; margin-right: 80px; border-left: 3px solid #FF9800; }");
        html.append(".msg-summary { background-color: #E8F5E9; border-radius: 8px; padding: 8px 12px; margin-right: 80px; border-left: 3px solid #4CAF50; }");
        html.append(".msg-sender { font-weight: bold; color: #333; font-size: 12px; }");
        html.append(".msg-time { color: #999; font-size: 11px; float: right; }");
        html.append(".msg-content { color: #333; margin-top: 4px; }");
        html.append(".msg-status { color: #999; font-size: 10px; font-style: italic; }");
        html.append(".msg-recalled { background-color: #F5F5F5; color: #999; font-style: italic; border-radius: 8px; padding: 8px 12px; margin-right: 80px; }");
        html.append(".msg-image { max-width: 200px; max-height: 200px; border-radius: 8px; }");
        html.append(".msg-mention { background-color: #FFF9C4; padding: 2px 4px; border-radius: 3px; }");
        html.append("</style></head><body><div id='content'></div></body></html>");
        messageArea.setText(html.toString());
        // 缓存 content 元素，避免每次 appendMessage 都做 O(n) 的 DOM 查找
        contentElement = ((HTMLDocument) messageArea.getDocument()).getElement("content");
    }

    /**
     * 底部输入区
     */
    private JPanel createInputArea() {
        JPanel inputPanel = new JPanel(new BorderLayout(5, 0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        inputPanel.setBackground(new Color(245, 245, 245));

        inputField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        inputField.setForeground(Color.BLACK);
        inputField.setPreferredSize(new Dimension(0, 36));
        inputField.addActionListener(e -> {
            if (actionListener != null) {
                actionListener.onSendMessage(inputField.getText().trim());
            }
        });

        JButton sendButton = new JButton("发送");
        sendButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        sendButton.setBackground(new Color(0, 120, 212));
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        sendButton.setPreferredSize(new Dimension(70, 36));
        sendButton.addActionListener(e -> {
            if (actionListener != null) {
                actionListener.onSendMessage(inputField.getText().trim());
            }
        });

        polishButton.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        polishButton.setBackground(new Color(255, 152, 0));
        polishButton.setForeground(Color.WHITE);
        polishButton.setFocusPainted(false);
        polishButton.setPreferredSize(new Dimension(60, 36));
        polishButton.setToolTipText("高情商/专业/委婉 语气润色");
        polishButton.addActionListener(e -> showPolishMenu());

        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        rightButtons.setOpaque(false);
        rightButtons.add(polishButton);
        rightButtons.add(sendButton);

        inputPanel.add(inputField, BorderLayout.CENTER);
        inputPanel.add(rightButtons, BorderLayout.EAST);

        return inputPanel;
    }

    /**
     * 显示润色风格选择菜单
     */
    private void showPolishMenu() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先输入要润色的文字", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] options = {"高情商", "专业", "委婉"};
        String style = (String) JOptionPane.showInputDialog(this,
                "选择润色风格:", "对话润色",
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);

        if (style != null && actionListener != null) {
            actionListener.onPolish(text, style);
        }
    }

    // ==================== 消息显示 ====================

    /**
     * 接收到新消息，追加到消息展示区
     *
     * @param sender   发送者
     * @param content  消息内容
     * @param timeStr  时间戳字符串
     * @param currentUser 当前用户名（用于判断是否为自己发送的消息）
     */
    public void appendMessage(String sender, String content, String timeStr, String currentUser, String msgId) {
        SwingUtilities.invokeLater(() -> {
            try {
                String formattedTime;
                try {
                    long ts = Long.parseLong(timeStr);
                    formattedTime = SDF.format(new Date(ts));
                } catch (NumberFormatException e) {
                    formattedTime = timeStr;
                }

                HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                Element targetElement = (contentElement != null) ? contentElement : doc.getElement("content");

                String cssClass;
                String displayName;

                if ("AI伴侣".equals(sender)) {
                    cssClass = "msg-companion";
                    displayName = contactManager.getDisplayName("AI伴侣");
                } else if ("AI小助手".equals(sender)) {
                    cssClass = content.contains("【群聊摘要】") ? "msg-summary" : "msg-ai";
                    displayName = contactManager.getDisplayName("AI小助手");
                } else if (sender.equals(currentUser)) {
                    cssClass = "msg-self";
                    displayName = "我";
                } else {
                    cssClass = "msg-other";
                    displayName = contactManager.getDisplayName(sender);
                }

                // 构建带 msgId 的 HTML，用于状态更新定位
                String msgHtml = "<div class='msg-container' data-msgid='" + msgId + "'>"
                        + "<div class='" + cssClass + "'>"
                        + "<span class='msg-sender'>" + escapeHtml(displayName) + "</span>"
                        + "<span class='msg-time'>" + formattedTime + "</span>"
                        + "<div class='msg-content'>" + escapeHtml(content) + "</div>"
                        + "<span class='msg-status'></span>"
                        + "</div></div>";

                doc.insertBeforeEnd(targetElement, msgHtml);
                messageArea.setCaretPosition(messageArea.getDocument().getLength());

                // 存储消息元数据（含 msgId），用于右键删除/撤回和状态更新
                MessageMeta meta = new MessageMeta(
                        sender, content, timeStr,
                        sessionManager.getCurrentType().name(),
                        currentUser,
                        sessionManager.getCurrentTarget());
                meta.msgId = msgId;
                messageMetaList.add(meta);
            } catch (Exception e) {
                System.err.println("[MessagePanel] 追加消息失败: " + e.getMessage());
            }
        });
    }

    // ==================== 公共方法 ====================

    /**
     * 获取输入框内容并清空
     */
    public String getAndClearInput() {
        String content = inputField.getText().trim();
        inputField.setText("");
        inputField.requestFocus();
        return content;
    }

    /**
     * 设置输入框内容
     */
    public void setInputText(String text) {
        inputField.setText(text);
        inputField.requestFocus();
    }

    /**
     * 设置输入框启用状态
     */
    public void setInputEnabled(boolean enabled) {
        inputField.setEnabled(enabled);
    }

    /**
     * 获取消息元数据列表（供 MainFrame 在删除时读取）
     */
    public List<MessageMeta> getMessageMetaList() {
        return messageMetaList;
    }

    /**
     * 更新聊天对象下拉框
     */
    public void updateChatTargetCombo(java.util.List<String> friends) {
        String previousSelection = (String) chatTargetCombo.getSelectedItem();
        chatTargetCombo.removeAllItems();
        chatTargetCombo.addItem("群聊大厅");
        chatTargetCombo.addItem("AI伴侣");
        chatTargetCombo.addItem("AI小助手");
        for (String name : friends) {
            if (!"AI伴侣".equals(name) && !"AI小助手".equals(name)) {
                chatTargetCombo.addItem(name);
            }
        }
        // 恢复之前的选中项
        if (previousSelection != null) {
            for (int i = 0; i < chatTargetCombo.getItemCount(); i++) {
                if (chatTargetCombo.getItemAt(i).equals(previousSelection)) {
                    chatTargetCombo.setSelectedIndex(i);
                    return;
                }
            }
        }
        chatTargetCombo.setSelectedItem("群聊大厅");
    }

    /**
     * 同步下拉框选中项到指定目标
     */
    public void syncComboSelection(String target) {
        isUpdatingChatTarget = true;
        for (int i = 0; i < chatTargetCombo.getItemCount(); i++) {
            if (chatTargetCombo.getItemAt(i).equals(target)) {
                chatTargetCombo.setSelectedIndex(i);
                break;
            }
        }
        isUpdatingChatTarget = false;
    }

    // ==================== P0: 消息状态更新 ====================

    /**
     * 更新消息状态图标（ack → delivered → read）
     * @param msgId 消息ID
     * @param status 状态：ack/delivered/read
     */
    public void updateMessageStatus(String msgId, String status) {
        SwingUtilities.invokeLater(() -> {
            // 找到对应的消息元数据并更新状态
            for (MessageMeta meta : messageMetaList) {
                if (meta.msgId.equals(msgId)) {
                    meta.status = status;
                    break;
                }
            }
            // 通过 HTML 文档中的 data-msgid 属性更新状态标签
            HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
            Element contentEl = (contentElement != null) ? contentElement : doc.getElement("content");
            if (contentEl == null) return;

            int childCount = contentEl.getElementCount();
            for (int i = 0; i < childCount && i < messageMetaList.size(); i++) {
                MessageMeta meta = messageMetaList.get(i);
                if (meta.msgId.equals(msgId)) {
                    Element child = contentEl.getElement(i);
                    String oldHtml = getElementHtml(doc, child);
                    String statusText = status.equals("read") ? "已读" : status.equals("delivered") ? "已送达" : "已发送";
                    String newHtml = oldHtml.replace("msg-status\">", "msg-status\">" + statusText + " ");
                    if (!newHtml.equals(oldHtml)) {
                        try {
                            // 替换此元素
                            doc.setOuterHTML(child, newHtml);
                        } catch (Exception ex) {
                            System.err.println("[状态] 更新HTML失败: " + ex.getMessage());
                        }
                    }
                    return;
                }
            }
        });
    }

    // ==================== P1: 消息撤回 ====================

    /**
     * 处理消息撤回：将对应消息的 HTML 替换为"已被撤回"
     */
    public void handleRecall(String msgId, String sender) {
        SwingUtilities.invokeLater(() -> {
            HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
            Element contentEl = (contentElement != null) ? contentElement : doc.getElement("content");
            if (contentEl == null) return;

            int childCount = contentEl.getElementCount();
            for (int i = 0; i < childCount && i < messageMetaList.size(); i++) {
                MessageMeta meta = messageMetaList.get(i);
                if (meta.msgId.equals(msgId)) {
                    Element child = contentEl.getElement(i);
                    String recalledHtml = "<div class='msg-container'>"
                            + "<div class='msg-recalled'>" + escapeHtml(sender) + " 撤回了一条消息</div></div>";
                    try {
                        doc.setOuterHTML(child, recalledHtml);
                    } catch (Exception ex) {
                        System.err.println("[撤回] 更新HTML失败: " + ex.getMessage());
                    }
                    return;
                }
            }
        });
    }

    // ==================== P0: 图片消息 ====================

    /**
     * 追加图片消息（Base64 格式）
     */
    public void appendImageMessage(String sender, String base64Data, String timeStr,
                                    String currentUser, String msgId) {
        SwingUtilities.invokeLater(() -> {
            try {
                String formattedTime;
                try {
                    long ts = Long.parseLong(timeStr);
                    formattedTime = SDF.format(new Date(ts));
                } catch (NumberFormatException e) {
                    formattedTime = timeStr;
                }

                HTMLDocument doc = (HTMLDocument) messageArea.getDocument();
                Element targetElement = (contentElement != null) ? contentElement : doc.getElement("content");

                String cssClass = sender.equals(currentUser) ? "msg-self" : "msg-other";
                String displayName = sender.equals(currentUser) ? "我" : contactManager.getDisplayName(sender);

                String imgTag = "<img src='data:image/png;base64," + base64Data
                        + "' class='msg-image' />";
                String msgHtml = "<div class='msg-container' data-msgid='" + msgId + "'>"
                        + "<div class='" + cssClass + "'>"
                        + "<span class='msg-sender'>" + escapeHtml(displayName) + "</span>"
                        + "<span class='msg-time'>" + formattedTime + "</span>"
                        + "<div class='msg-content'>" + imgTag + "</div>"
                        + "<span class='msg-status'></span>"
                        + "</div></div>";

                doc.insertBeforeEnd(targetElement, msgHtml);
                messageArea.setCaretPosition(messageArea.getDocument().getLength());

                MessageMeta meta = new MessageMeta(sender, "[图片]", timeStr,
                        sessionManager.getCurrentType().name(), currentUser, sessionManager.getCurrentTarget());
                meta.msgId = msgId;
                meta.status = "ack";
                messageMetaList.add(meta);
            } catch (Exception e) {
                System.err.println("[图片] 追加图片消息失败: " + e.getMessage());
            }
        });
    }

    /**
     * 获取元素对应的 HTML 字符串
     */
    private String getElementHtml(HTMLDocument doc, Element element) {
        try {
            return doc.getText(element.getStartOffset(),
                    element.getEndOffset() - element.getStartOffset());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * HTML 转义，防止 XSS
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br>");
    }
}
