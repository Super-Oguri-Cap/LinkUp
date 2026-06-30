package org.example.service;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContactManager 业务逻辑单元测试
 * 测试范围：好友管理、黑名单、昵称管理、边界条件
 */
class ContactManagerTest {

    private ContactManager manager;
    private static final String CURRENT_USER = "testuser";

    @BeforeEach
    void setUp() {
        manager = new ContactManager(CURRENT_USER);
    }

    // ==================== 好友添加测试 ====================

    @Test
    @DisplayName("TC-CM-001: 添加好友 - 正常添加")
    void testAddFriend_Success() {
        ContactManager.AddFriendResult result = manager.canAddFriend("friend1");
        assertEquals(ContactManager.AddFriendResult.OK, result);

        manager.addFriend("friend1");
        assertTrue(manager.getFriendList().contains("friend1"));
    }

    @Test
    @DisplayName("TC-CM-002: 添加好友 - 用户名为空")
    void testCanAddFriend_EmptyName() {
        assertEquals(ContactManager.AddFriendResult.EMPTY_NAME, manager.canAddFriend(""));
    }

    @Test
    @DisplayName("TC-CM-003: 添加好友 - 不能添加自己")
    void testCanAddFriend_Self() {
        assertEquals(ContactManager.AddFriendResult.SELF_NOT_ALLOWED,
                manager.canAddFriend(CURRENT_USER));
    }

    @Test
    @DisplayName("TC-CM-004: 添加好友 - 已是好友")
    void testCanAddFriend_AlreadyExists() {
        manager.addFriend("friend1");
        assertEquals(ContactManager.AddFriendResult.ALREADY_EXISTS,
                manager.canAddFriend("friend1"));
    }

    @Test
    @DisplayName("TC-CM-005: 添加好友 - 系统联系人不能重复添加")
    void testCanAddFriend_SystemContact() {
        // AI伴侣和AI小助手是系统联系人
        assertEquals(ContactManager.AddFriendResult.ALREADY_EXISTS,
                manager.canAddFriend("AI伴侣"));
    }

    // ==================== 好友删除测试 ====================

    @Test
    @DisplayName("TC-CM-006: 删除好友 - 正常删除")
    void testRemoveFriend_Success() {
        manager.addFriend("friend1");
        int before = manager.getRealFriendCount();
        manager.removeFriend("friend1");
        assertEquals(before - 1, manager.getRealFriendCount());
        assertFalse(manager.getFriendList().contains("friend1"));
    }

    @Test
    @DisplayName("TC-CM-007: 删除好友 - 系统联系人不可删除")
    void testRemoveFriend_SystemContact_NotAllowed() {
        int before = manager.getFriendList().size();
        manager.removeFriend("AI伴侣");
        assertEquals(before, manager.getFriendList().size(),
                "系统联系人不应被删除");
        assertTrue(manager.getFriendList().contains("AI伴侣"));
    }

    // ==================== 好友数量测试 ====================

    @Test
    @DisplayName("TC-CM-008: 真实好友数 - 排除系统联系人")
    void testGetRealFriendCount_ExcludesSystem() {
        int baseCount = manager.getRealFriendCount();
        manager.addFriend("friend1");
        manager.addFriend("friend2");
        assertEquals(baseCount + 2, manager.getRealFriendCount());
    }

    @Test
    @DisplayName("TC-CM-009: 好友上限 - 超过 150 人限制")
    void testCanAddFriend_LimitExceeded() {
        // 添加到接近上限
        for (int i = 0; i < manager.getMaxFriends(); i++) {
            manager.addFriend("friend_" + i);
        }
        assertEquals(ContactManager.AddFriendResult.LIMIT_EXCEEDED,
                manager.canAddFriend("exceed_friend"));
    }

    // ==================== 黑名单测试 ====================

    @Test
    @DisplayName("TC-CM-010: 黑名单 - 拉黑用户")
    void testBlockUser_Add() {
        assertFalse(manager.isBlocked("user1"));
        manager.toggleBlockStatus("user1");
        assertTrue(manager.isBlocked("user1"));
    }

    @Test
    @DisplayName("TC-CM-011: 黑名单 - 取消拉黑")
    void testBlockUser_Remove() {
        manager.toggleBlockStatus("user1");
        assertTrue(manager.isBlocked("user1"));
        manager.toggleBlockStatus("user1");
        assertFalse(manager.isBlocked("user1"));
    }

    @Test
    @DisplayName("TC-CM-012: 黑名单 - 多个用户独立")
    void testBlockUser_MultipleIndependent() {
        manager.toggleBlockStatus("user1");
        manager.toggleBlockStatus("user2");
        assertTrue(manager.isBlocked("user1"));
        assertTrue(manager.isBlocked("user2"));

        manager.toggleBlockStatus("user1");
        assertTrue(manager.isBlocked("user2"));
        assertFalse(manager.isBlocked("user1"));
    }

    // ==================== 昵称管理测试 ====================

    @Test
    @DisplayName("TC-CM-013: 昵称 - 设置后获取显示名")
    void testSetNickname_DisplayName() {
        manager.setNickname("friend1", "好朋友");
        assertEquals("好朋友", manager.getDisplayName("friend1"));
    }

    @Test
    @DisplayName("TC-CM-014: 昵称 - 无昵称时返回用户名")
    void testGetDisplayName_NoNickname() {
        assertEquals("friend1", manager.getDisplayName("friend1"));
    }

    @Test
    @DisplayName("TC-CM-015: 昵称 - 空昵称清除")
    void testSetNickname_EmptyClears() {
        manager.setNickname("friend1", "好朋友");
        assertEquals("好朋友", manager.getDisplayName("friend1"));

        manager.setNickname("friend1", "");
        assertEquals("friend1", manager.getDisplayName("friend1"));
    }

    @Test
    @DisplayName("TC-CM-016: 昵称 - null 昵称清除")
    void testSetNickname_NullClears() {
        manager.setNickname("friend1", "好朋友");
        manager.setNickname("friend1", null);
        assertEquals("friend1", manager.getDisplayName("friend1"));
    }

    // ==================== 列表更新测试 ====================

    @Test
    @DisplayName("TC-CM-017: 从服务端更新列表 - 排除自己")
    void testUpdateFromServer_ExcludesSelf() {
        JsonArray arr = new JsonArray();
        arr.add("user1");
        arr.add(CURRENT_USER);
        arr.add("user2");
        manager.updateFromServer(arr);

        List<String> friends = manager.getFriendList();
        assertTrue(friends.contains("user1"));
        assertTrue(friends.contains("user2"));
        assertFalse(friends.contains(CURRENT_USER), "自己不应出现在好友列表中");
    }

    @Test
    @DisplayName("TC-CM-018: 从数据库重建列表 - 保留系统联系人")
    void testRebuildFromDatabase_PreservesSystem() {
        JsonArray arr = new JsonArray();
        arr.add("dbfriend1");
        arr.add("dbfriend2");
        manager.rebuildFromDatabase(arr);

        assertTrue(manager.getFriendList().contains("AI伴侣"));
        assertTrue(manager.getFriendList().contains("AI小助手"));
        assertTrue(manager.getFriendList().contains("dbfriend1"));
        assertTrue(manager.getFriendList().contains("dbfriend2"));
    }

    // ==================== 系统联系人测试 ====================

    @Test
    @DisplayName("TC-CM-019: 系统联系人判断")
    void testIsSystemContact() {
        assertTrue(manager.isSystemContact("AI伴侣"));
        assertTrue(manager.isSystemContact("AI小助手"));
        assertFalse(manager.isSystemContact("friend1"));
    }

    @Test
    @DisplayName("TC-CM-020: 系统联系人数量正确")
    void testSystemContacts_Count() {
        // 初始状态下真实好友数为 0
        assertEquals(0, manager.getRealFriendCount());
        // 总好友数 = 系统联系人数 + 0
        assertTrue(manager.getFriendList().size() >= 2);
    }
}
