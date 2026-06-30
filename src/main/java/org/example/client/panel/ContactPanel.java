package org.example.client.panel;

import org.example.service.ContactManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * 联系人面板
 * 负责联系人 Tab 的 UI 展示和交互，包括好友列表、添加/删除好友、昵称管理
 * 业务逻辑委托给 ContactManager，通过回调接口与 MainFrame 通信
 */
public class ContactPanel extends JPanel {

    /** 好友列表组件 */
    private final JList<String> friendList;
    /** 好友列表数据模型 */
    private final DefaultListModel<String> friendListModel;
    /** 添加好友输入框 */
    private final JTextField addFriendField;
    /** 好友数量统计标签 */
    private final JLabel friendCountLabel;
    /** 联系人管理服务 */
    private final ContactManager contactManager;
    /** 外部动作监听器 */
    private ContactActionListener actionListener;

    /**
     * 联系人面板动作监听接口
     */
    public interface ContactActionListener {
        /** 添加好友 */
        void onAddFriend(String friendName);
        /** 删除好友 */
        void onRemoveFriend(String friendName);
        /** 双击好友开始聊天 */
        void onChatWithFriend(String friendName);
        /** 设置好友昵称 */
        void onSetNickname(String friendName);
        /** 清除好友昵称 */
        void onClearNickname(String friendName);
        /** 刷新好友列表 */
        void onRefreshFriendList();
        /** 拉黑/取消拉黑好友 */
        void onBlockFriend(String friendName);
    }

    public ContactPanel(ContactManager contactManager) {
        this.contactManager = contactManager;
        this.friendListModel = new DefaultListModel<>();
        this.friendList = new JList<>(friendListModel);
        this.addFriendField = new JTextField();
        this.friendCountLabel = new JLabel("我的好友 (0/150)");

        initUI();
    }

    public void setActionListener(ContactActionListener listener) {
        this.actionListener = listener;
    }

    // ==================== UI 初始化 ====================

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBackground(new Color(240, 240, 245));
        setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        // 顶部：添加好友区域 + 好友数量统计
        add(createTopPanel(), BorderLayout.NORTH);

        // 中央：好友列表
        add(createFriendListPanel(), BorderLayout.CENTER);

        // 底部：删除好友按钮
        add(createBottomPanel(), BorderLayout.SOUTH);
    }

    /**
     * 创建顶部面板：添加好友输入区 + 好友数量统计
     */
    private JPanel createTopPanel() {
        JPanel topPanel = new JPanel(new BorderLayout(5, 5));
        topPanel.setOpaque(false);

        // 添加好友区域
        JPanel addPanel = new JPanel(new BorderLayout(5, 0));
        addPanel.setOpaque(false);
        addPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        JLabel addLabel = new JLabel("添加好友:");
        addLabel.setFont(new Font("微软雅黑", Font.BOLD, 13));
        addLabel.setForeground(new Color(50, 50, 50));
        addPanel.add(addLabel, BorderLayout.WEST);

        addFriendField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        addFriendField.setForeground(Color.BLACK);
        addPanel.add(addFriendField, BorderLayout.CENTER);

        JButton addFriendButton = new JButton("添加");
        addFriendButton.setFont(new Font("微软雅黑", Font.BOLD, 12));
        addFriendButton.setBackground(new Color(0, 150, 0));
        addFriendButton.setForeground(Color.WHITE);
        addFriendButton.setFocusPainted(false);
        addFriendButton.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        addFriendButton.addActionListener(e -> {
            if (actionListener != null) {
                actionListener.onAddFriend(addFriendField.getText().trim());
            }
        });
        addPanel.add(addFriendButton, BorderLayout.EAST);

        topPanel.add(addPanel, BorderLayout.CENTER);

        // 好友数量统计条
        JPanel countPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        countPanel.setBackground(new Color(220, 230, 245));
        countPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 210, 230)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));

        friendCountLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        friendCountLabel.setForeground(new Color(60, 60, 60));
        countPanel.add(friendCountLabel);

        topPanel.add(countPanel, BorderLayout.SOUTH);

        return topPanel;
    }

    /**
     * 创建好友列表面板
     */
    private JPanel createFriendListPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);

        friendList.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        friendList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        friendList.setCellRenderer(new FriendListCellRenderer());
        friendList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && actionListener != null) {
                    String selected = friendList.getSelectedValue();
                    if (selected != null) {
                        actionListener.onChatWithFriend(selected);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int index = friendList.locationToIndex(e.getPoint());
                    friendList.setSelectedIndex(index);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int index = friendList.locationToIndex(e.getPoint());
                    if (index >= 0) {
                        friendList.setSelectedIndex(index);
                        showContextMenu(e.getComponent(), e.getX(), e.getY());
                    }
                }
            }
        });

        JScrollPane friendScroll = new JScrollPane(friendList);
        friendScroll.setBorder(BorderFactory.createLineBorder(new Color(220, 220, 220)));
        panel.add(friendScroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * 创建底部面板：刷新好友按钮 + 删除好友按钮
     */
    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        bottomPanel.setOpaque(false);

        JButton refreshButton = new JButton("刷新好友列表");
        refreshButton.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        refreshButton.setBackground(new Color(0, 120, 212));
        refreshButton.setForeground(Color.WHITE);
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> {
            if (actionListener != null) {
                actionListener.onRefreshFriendList();
            }
        });
        bottomPanel.add(refreshButton);

        JButton removeFriendButton = new JButton("删除选中好友");
        removeFriendButton.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        removeFriendButton.setBackground(new Color(200, 50, 50));
        removeFriendButton.setForeground(Color.WHITE);
        removeFriendButton.setFocusPainted(false);
        removeFriendButton.addActionListener(e -> {
            if (actionListener != null) {
                String selected = friendList.getSelectedValue();
                if (selected != null) {
                    actionListener.onRemoveFriend(selected);
                }
            }
        });
        bottomPanel.add(removeFriendButton);

        return bottomPanel;
    }

    /**
     * 显示联系人右键菜单：设置昵称 / 清除昵称
     */
    private void showContextMenu(Component invoker, int x, int y) {
        String selected = friendList.getSelectedValue();
        if (selected == null) return;

        JPopupMenu menu = new JPopupMenu();
        menu.setFont(new Font("微软雅黑", Font.PLAIN, 13));

        String currentNickname = contactManager.getRawNickname(selected);
        if (currentNickname != null) {
            JMenuItem showNickname = new JMenuItem("昵称: " + currentNickname);
            showNickname.setEnabled(false);
            menu.add(showNickname);

            JMenuItem changeNickname = new JMenuItem("修改昵称");
            changeNickname.addActionListener(e -> {
                if (actionListener != null) actionListener.onSetNickname(selected);
            });
            menu.add(changeNickname);

            JMenuItem clearNickname = new JMenuItem("清除昵称");
            clearNickname.addActionListener(e -> {
                if (actionListener != null) actionListener.onClearNickname(selected);
            });
            menu.add(clearNickname);
        } else {
            JMenuItem setNicknameItem = new JMenuItem("设置昵称");
            setNicknameItem.addActionListener(e -> {
                if (actionListener != null) actionListener.onSetNickname(selected);
            });
            menu.add(setNicknameItem);
        }

        // P1: 拉黑/取消拉黑
        menu.addSeparator();
        boolean isBlocked = contactManager.isBlocked(selected);
        JMenuItem blockItem = new JMenuItem(isBlocked ? "取消拉黑" : "拉黑好友");
        blockItem.addActionListener(e -> {
            if (actionListener != null) actionListener.onBlockFriend(selected);
        });
        menu.add(blockItem);

        menu.show(invoker, x, y);
    }

    // ==================== 公共方法 ====================

    /**
     * 更新好友列表显示
     */
    public void updateFriendList(java.util.List<String> friends) {
        friendListModel.clear();
        for (String friend : friends) {
            friendListModel.addElement(friend);
        }
    }

    /**
     * 更新好友数量统计
     */
    public void updateFriendCount(int count) {
        friendCountLabel.setText("我的好友 (" + count + "/150)");
    }

    /**
     * 刷新好友列表渲染（昵称变化时调用）
     */
    public void refreshFriendList() {
        friendList.repaint();
    }

    /**
     * 清空添加好友输入框
     */
    public void clearAddFriendField() {
        addFriendField.setText("");
    }

    /**
     * 获取选中的好友
     */
    public String getSelectedFriend() {
        return friendList.getSelectedValue();
    }

    // ==================== 内部类 ====================

    /**
     * 好友列表渲染器：显示昵称（如有），AI 联系人带特殊标识
     */
    private class FriendListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                       int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            String name = value.toString();
            String displayName = contactManager.getDisplayName(name);

            // AI 联系人显示特殊标识
            if ("AI伴侣".equals(name)) {
                label.setText(displayName + "  · 有温度的陪伴者");
                label.setForeground(isSelected ? Color.WHITE : new Color(230, 126, 34));
            } else if ("AI小助手".equals(name)) {
                label.setText(displayName + "  · 智能助手");
                label.setForeground(isSelected ? Color.WHITE : new Color(0, 120, 212));
            } else {
                // 有昵称时显示 "昵称 (用户名)" 格式
                if (!displayName.equals(name)) {
                    label.setText(displayName + " (" + name + ")");
                }
                label.setForeground(isSelected ? Color.WHITE : Color.BLACK);
            }
            label.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            return label;
        }
    }
}
