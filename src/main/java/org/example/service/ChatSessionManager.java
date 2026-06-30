package org.example.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话状态管理服务
 * 统一管理当前聊天对象、聊天类型和会话切换逻辑
 * 通过回调接口通知 UI 层会话变化
 */
public class ChatSessionManager {

    // ==================== 系统联系人常量 ====================
    // 集中管理所有系统联系人名称，避免硬编码字符串散落各处
    /** 群聊大厅标识 */
    public static final String TARGET_GROUP_HALL = "群聊大厅";
    /** AI 伴侣标识 */
    public static final String TARGET_AI_COMPANION = "AI伴侣";
    /** AI 小助手标识 */
    public static final String TARGET_AI_ASSISTANT = "AI小助手";
    /** 群聊广播时的接收方标识 */
    public static final String TARGET_ALL = "所有人";

    /**
     * 聊天类型枚举
     */
    public enum ChatType {
        GROUP(TARGET_GROUP_HALL),
        PRIVATE("私聊"),
        AI(TARGET_AI_ASSISTANT),
        COMPANION(TARGET_AI_COMPANION);

        private final String displayName;

        ChatType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** 当前登录用户名 */
    private final String currentUser;

    /** 当前聊天对象 */
    private String currentTarget;

    /** 当前聊天类型 */
    private ChatType currentType;

    /** 会话变化监听器列表 */
    private final List<SessionChangeListener> listeners = new ArrayList<>();

    /**
     * 会话变化监听接口
     */
    public interface SessionChangeListener {
        /**
         * 会话发生变化时回调
         *
         * @param target    新的聊天对象
         * @param type      新的聊天类型
         * @param titleText 标题栏文本
         */
        void onSessionChanged(String target, ChatType type, String titleText);
    }

    public ChatSessionManager(String currentUser) {
        this.currentUser = currentUser;
        this.currentTarget = TARGET_GROUP_HALL;
        this.currentType = ChatType.GROUP;
    }

    public void addListener(SessionChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * 切换到指定聊天对象
     *
     * @param target 目标聊天对象
     * @return 是否切换成功（切换到自己时返回 false）
     */
    public boolean switchToChat(String target) {
        if (target.equals(currentUser)) {
            return false;
        }

        this.currentTarget = target;

        if (TARGET_AI_COMPANION.equals(target)) {
            this.currentType = ChatType.COMPANION;
        } else if (TARGET_AI_ASSISTANT.equals(target)) {
            this.currentType = ChatType.AI;
        } else if (TARGET_GROUP_HALL.equals(target)) {
            this.currentType = ChatType.GROUP;
        } else {
            this.currentType = ChatType.PRIVATE;
        }

        notifyListeners();
        return true;
    }

    /**
     * 直接设置会话状态（用于 AI 功能触发时，不通过 switchToChat 的字符串匹配）
     */
    public void setSession(String target, ChatType type) {
        this.currentTarget = target;
        this.currentType = type;
        notifyListeners();
    }

    public String getCurrentTarget() {
        return currentTarget;
    }

    public ChatType getCurrentType() {
        return currentType;
    }

    /**
     * 获取当前聊天类型对应的消息协议类型
     */
    public String getMessageProtocolType() {
        switch (currentType) {
            case AI:
                return "AI_CHAT";
            case COMPANION:
                return "AI_COMPANION";
            case PRIVATE:
                return "PRIVATE_CHAT";
            default:
                return "GROUP_CHAT";
        }
    }

    /**
     * 生成标题栏文本
     *
     * @param displayName 聊天对象的显示名称
     */
    public String getTitleText(String displayName) {
        switch (currentType) {
            case COMPANION:
                return "当前用户: " + currentUser + "  |  AI 伴侣 " + displayName + " (有温度的陪伴者)";
            case AI:
                return "当前用户: " + currentUser + "  |  AI 小助手 " + displayName;
            case GROUP:
                return "当前用户: " + currentUser + "  |  " + TARGET_GROUP_HALL;
            default:
                return "当前用户: " + currentUser + "  |  私聊: " + displayName;
        }
    }

    private void notifyListeners() {
        String titleText = getTitleText(currentTarget);
        for (SessionChangeListener listener : listeners) {
            listener.onSessionChanged(currentTarget, currentType, titleText);
        }
    }
}
