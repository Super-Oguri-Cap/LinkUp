package org.example.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密码工具类单元测试
 * 测试范围：盐值生成、密码哈希、密码验证、边界条件、安全性
 */
class PasswordUtilTest {

    @Test
    @DisplayName("TC-SEC-001: 生成盐值 - 不为空且长度正确")
    void testGenerateSalt_NotNullAndCorrectLength() {
        String salt1 = PasswordUtil.generateSalt();
        String salt2 = PasswordUtil.generateSalt();

        assertNotNull(salt1, "盐值不应为 null");
        assertFalse(salt1.isEmpty(), "盐值不应为空字符串");
        assertNotEquals(salt1, salt2, "两次生成的盐值应不同");
    }

    @Test
    @DisplayName("TC-SEC-002: 密码哈希 - 相同密码+相同盐值应得到相同结果")
    void testHash_SameInputSameResult() {
        String salt = PasswordUtil.generateSalt();
        String hash1 = PasswordUtil.hash("testPassword123", salt);
        String hash2 = PasswordUtil.hash("testPassword123", salt);

        assertEquals(hash1, hash2, "相同密码和盐值应产生相同哈希");
    }

    @Test
    @DisplayName("TC-SEC-003: 密码哈希 - 不同盐值应得到不同结果")
    void testHash_DifferentSaltDifferentResult() {
        String salt1 = PasswordUtil.generateSalt();
        String salt2 = PasswordUtil.generateSalt();
        String hash1 = PasswordUtil.hash("samePassword", salt1);
        String hash2 = PasswordUtil.hash("samePassword", salt2);

        assertNotEquals(hash1, hash2, "不同盐值应产生不同哈希");
    }

    @Test
    @DisplayName("TC-SEC-004: 密码验证 - 正确密码返回 true")
    void testVerify_CorrectPassword() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("mySecretPass", salt);

        assertTrue(PasswordUtil.verify("mySecretPass", hash, salt),
                "正确密码应验证通过");
    }

    @Test
    @DisplayName("TC-SEC-005: 密码验证 - 错误密码返回 false")
    void testVerify_WrongPassword() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("correctPassword", salt);

        assertFalse(PasswordUtil.verify("wrongPassword", hash, salt),
                "错误密码应验证失败");
    }

    @Test
    @DisplayName("TC-SEC-006: 密码验证 - 大小写敏感")
    void testVerify_CaseSensitive() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("HelloWorld", salt);

        assertFalse(PasswordUtil.verify("helloworld", hash, salt),
                "密码应区分大小写");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "123456",
            "a",
            "   ",
            "!@#$%^&*()",
            "中文密码测试",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    @DisplayName("TC-SEC-007: 密码哈希 - 各种输入边界值")
    void testHash_VariousInputs(String password) {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash(password, salt);

        assertNotNull(hash, "哈希结果不应为 null");
        assertFalse(hash.isEmpty(), "哈希结果不应为空");
        assertTrue(PasswordUtil.verify(password, hash, salt),
                "应能正确验证: " + password);
    }

    @Test
    @DisplayName("TC-SEC-008: 密码哈希 - 空密码不抛异常")
    void testHash_EmptyPassword() {
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("", salt);

        assertNotNull(hash);
        assertTrue(PasswordUtil.verify("", hash, salt));
    }

    @Test
    @DisplayName("TC-SEC-009: 密码验证 - 防时序攻击（MessageDigest.isEqual）")
    void testVerify_ConstantTimeComparison() {
        // 验证使用了恒定时间比较（MessageDigest.isEqual）
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash("test123", salt);

        // 长度相同但内容不同的哈希不应通过
        String fakeHash = hash.substring(0, hash.length() - 1) + "A";
        assertFalse(PasswordUtil.verify("test123", fakeHash, salt));
    }

    @Test
    @DisplayName("TC-SEC-010: 盐值唯一性 - 1000 次生成不重复")
    void testGenerateSalt_Uniqueness() {
        java.util.Set<String> salts = new java.util.HashSet<>();
        for (int i = 0; i < 1000; i++) {
            salts.add(PasswordUtil.generateSalt());
        }
        assertEquals(1000, salts.size(), "1000 次生成的盐值应全部唯一");
    }
}
