package org.example.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码工具类 - 使用 SHA-256 + 盐值加密存储密码
 * 注意：生产环境建议使用 BCrypt，此处为教学演示使用 SHA-256
 */
public class PasswordUtil {

    private static final String ALGORITHM = "SHA-256";
    private static final int SALT_LENGTH = 16; // 盐值长度（字节）

    /**
     * 生成随机盐值
     *
     * @return Base64 编码的盐值字符串
     */
    public static String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * 使用 SHA-256 + 盐值对密码进行哈希
     *
     * @param password 明文密码
     * @param salt     Base64 编码的盐值
     * @return Base64 编码的哈希结果
     */
    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            // 将盐值与密码拼接后计算哈希，防止彩虹表攻击
            md.update(Base64.getDecoder().decode(salt));
            byte[] hashedBytes = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 验证密码是否正确
     *
     * @param inputPassword 用户输入的明文密码
     * @param storedHash    数据库中存储的哈希值
     * @param storedSalt    数据库中存储的盐值
     * @return true-密码正确，false-密码错误
     */
    public static boolean verify(String inputPassword, String storedHash, String storedSalt) {
        String computedHash = hash(inputPassword, storedSalt);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}