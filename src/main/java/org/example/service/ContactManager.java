package org.example.service;

import com.google.gson.JsonArray;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 联系人管理服务
 * 负责好友列表维护、昵称映射管理、好友数量统计
 * 通过回调接口通知 UI 层数据变化，不直接操作 Swing 组件
 */
public class ContactManager {

    /** 昵称配置文件路径 */
    private static final Path NICKNAME_FILE = Paths.get(
            System.getProperty("user.home"), ".linkup", "nicknames.properties");

    /** 好友数量上限 */
    private static final int MAX_FRIENDS = 150;

    /** 系统内置联系人（不可删除） */
    private static final String[] SYSTEM_CONTACTS = {"AI伴侣", "AI小助手"};

    /** 当前登录用户名 */
    private final String currentUser;

    /** 昵称映射：用户名 -> 昵称 */
    private final Map<String, String> nicknames = new HashMap<>();

    /** 好友集合（保持插入顺序） */
    private final Set<String> friends = new LinkedHashSet<>();
    /** 被拉黑的用户集合（P1） */
    private final Set<String> blockedUsers = new HashSet<>();

    /** UI 层回调监听器 */
    private ContactChangeListener listener;

    /**
     * 联系人变化监听接口
     */
    public interface ContactChangeListener {
        /** 好友列表发生变化 */
        void onFriendListUpdated(List<String> friendList, int realFriendCount);
        /** 昵称发生变化 */
        void onNicknameChanged(String username, String nickname);
    }

    /**
     * 添加好友操作的结果枚举
     */
    public enum AddFriendResult {
        OK,                 // 可以添加
        EMPTY_NAME,         // 用户名为空
        SELF_NOT_ALLOWED,   // 不能添加自己
        ALREADY_EXISTS,     // 已是好友
        LIMIT_EXCEEDED      // 超过 150 人上限
    }

    public ContactManager(String currentUser) {
        this.currentUser = currentUser;
        loadNicknames();
        // 初始化系统联系人
        for (String contact : SYSTEM_CONTACTS) {
            friends.add(contact);
        }
    }

    public void setListener(ContactChangeListener listener) {
        this.listener = listener;
    }

    // ==================== 好友管理 ====================

    /**
     * 检查是否可以添加指定好友
     *
     * @param friendName 好友用户名
     * @return 检查结果
     */
    public AddFriendResult canAddFriend(String friendName) {
        if (friendName.isEmpty()) {
            return AddFriendResult.EMPTY_NAME;
        }
        if (friendName.equals(currentUser)) {
            return AddFriendResult.SELF_NOT_ALLOWED;
        }
        if (friends.contains(friendName)) {
            return AddFriendResult.ALREADY_EXISTS;
        }
        if (getRealFriendCount() >= MAX_FRIENDS) {
            return AddFriendResult.LIMIT_EXCEEDED;
        }
        return AddFriendResult.OK;
    }

    /**
     * 添加好友到列表
     */
    public void addFriend(String friendName) {
        friends.add(friendName);
        notifyListener();
    }

    /**
     * 从列表移除好友（系统联系人不可删除）
     */
    public void removeFriend(String friendName) {
        if (isSystemContact(friendName)) {
            return;
        }
        friends.remove(friendName);
        notifyListener();
    }

    /**
     * 根据服务端返回的用户列表更新好友列表（仅在线用户）
     */
    public void updateFromServer(JsonArray userList) {
        friends.clear();
        // 先添加系统联系人
        for (String contact : SYSTEM_CONTACTS) {
            friends.add(contact);
        }
        // 添加在线用户
        for (int i = 0; i < userList.size(); i++) {
            String username = userList.get(i).getAsString();
            if (!username.equals(currentUser)) {
                friends.add(username);
            }
        }
        notifyListener();
    }

    /**
     * 根据数据库好友列表重建好友列表（含离线好友）
     * 保留系统联系人，用数据库查询结果替换用户好友
     */
    public void rebuildFromDatabase(JsonArray friendList) {
        // 保留系统联系人
        Set<String> systemContacts = new LinkedHashSet<>();
        for (String contact : SYSTEM_CONTACTS) {
            systemContacts.add(contact);
        }
        friends.clear();
        friends.addAll(systemContacts);
        // 添加数据库中的好友
        for (int i = 0; i < friendList.size(); i++) {
            String username = friendList.get(i).getAsString();
            if (!username.equals(currentUser)) {
                friends.add(username);
            }
        }
        notifyListener();
    }

    /**
     * 判断是否为系统内置联系人
     */
    public boolean isSystemContact(String username) {
        for (String contact : SYSTEM_CONTACTS) {
            if (contact.equals(username)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 昵称管理 ====================

    /**
     * 获取用户显示名称（昵称优先，无昵称则返回用户名）
     */
    public String getDisplayName(String username) {
        return nicknames.getOrDefault(username, username);
    }

    /**
     * 设置联系人昵称
     *
     * @param username 用户名
     * @param nickname 昵称（为 null 或空字符串时清除昵称）
     */
    public void setNickname(String username, String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            nicknames.remove(username);
        } else {
            nicknames.put(username, nickname.trim());
        }
        saveNicknames();
        if (listener != null) {
            listener.onNicknameChanged(username, nicknames.get(username));
        }
    }

    /**
     * 获取原始昵称映射（用于右键菜单判断是否已设置昵称）
     */
    public String getRawNickname(String username) {
        return nicknames.get(username);
    }

    // ==================== 查询方法 ====================

    public List<String> getFriendList() {
        return new ArrayList<>(friends);
    }

    public int getRealFriendCount() {
        return friends.size() - SYSTEM_CONTACTS.length;
    }

    public int getMaxFriends() {
        return MAX_FRIENDS;
    }

    /**
     * 检查用户是否被拉黑
     */
    public boolean isBlocked(String username) {
        return blockedUsers.contains(username);
    }

    /**
     * 切换拉黑状态
     */
    public void toggleBlockStatus(String username) {
        if (blockedUsers.contains(username)) {
            blockedUsers.remove(username);
        } else {
            blockedUsers.add(username);
        }
    }

    // ==================== 内部方法 ====================

    private void notifyListener() {
        if (listener != null) {
            listener.onFriendListUpdated(getFriendList(), getRealFriendCount());
        }
    }

    /**
     * 从本地文件加载昵称映射
     */
    private void loadNicknames() {
        try {
            if (Files.exists(NICKNAME_FILE)) {
                Properties props = new Properties();
                try (InputStreamReader reader = new InputStreamReader(
                        Files.newInputStream(NICKNAME_FILE), StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                for (String key : props.stringPropertyNames()) {
                    nicknames.put(key, props.getProperty(key));
                }
            }
        } catch (IOException e) {
            System.err.println("[ContactManager] 加载昵称文件失败: " + e.getMessage());
        }
    }

    /**
     * 将昵称映射保存到本地文件
     */
    private void saveNicknames() {
        try {
            Files.createDirectories(NICKNAME_FILE.getParent());
            Properties props = new Properties();
            props.putAll(nicknames);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(NICKNAME_FILE), StandardCharsets.UTF_8)) {
                props.store(writer, "LinkUp Nicknames");
            }
        } catch (IOException e) {
            System.err.println("[ContactManager] 保存昵称文件失败: " + e.getMessage());
        }
    }
}
