package org.example.dao;

import org.example.model.User;
import org.example.util.PasswordUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * UserDAO 单元测试 - 使用 Mockito 模拟数据库连接
 * 覆盖：正常登录、用户名不存在、密码错误三种场景
 */
@ExtendWith(MockitoExtension.class)
class UserDAOTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private UserDAO userDAO;
    private MockedStatic<PasswordUtil> passwordUtilMock;

    // 测试常量
    private static final String TEST_USERNAME = "zhangsan";
    private static final String TEST_PASSWORD = "correctPassword";
    private static final String TEST_WRONG_PASSWORD = "wrongPassword";
    private static final String STORED_SALT = "testSaltBase64==";
    private static final String STORED_HASH = "testHashBase64==";

    @BeforeEach
    void setUp() throws SQLException {
        // 初始化 Mock 对象
        userDAO = new UserDAO(dataSource);

        // 配置 DataSource 每次返回同一个 Mock Connection
        lenient().when(dataSource.getConnection()).thenReturn(connection);

        // 配置 Connection 创建 PreparedStatement
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        // Mock PasswordUtil 静态方法（避免真实 SHA-256 计算）
        passwordUtilMock = mockStatic(PasswordUtil.class);
    }

    @AfterEach
    void tearDown() {
        // 释放静态 Mock，避免影响其他测试
        if (passwordUtilMock != null) {
            passwordUtilMock.close();
        }
    }

    /**
     * 场景一：正常登录
     * 预期：用户名存在，密码正确，返回 User 对象（id=1, username=zhangsan, nickname=张三）
     */
    @Test
    @DisplayName("正常登录 - 用户名和密码均正确，应返回 User 对象")
    void shouldLoginSuccessfully() throws SQLException {
        // Given: 准备 Mock 数据
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true); // 模拟查询到用户
        when(resultSet.getLong("id")).thenReturn(1L);
        when(resultSet.getString("username")).thenReturn(TEST_USERNAME);
        when(resultSet.getString("password")).thenReturn(STORED_HASH);
        when(resultSet.getString("salt")).thenReturn(STORED_SALT);
        when(resultSet.getString("nickname")).thenReturn("张三");

        // Mock PasswordUtil.verify 返回 true（密码正确）
        passwordUtilMock.when(() -> PasswordUtil.verify(TEST_PASSWORD, STORED_HASH, STORED_SALT))
                .thenReturn(true);

        // When: 执行登录
        User result = userDAO.login(TEST_USERNAME, TEST_PASSWORD);

        // Then: 验证结果
        assertNotNull(result, "登录成功应返回非 null 的 User 对象");
        assertEquals(1L, result.getId(), "用户 ID 应为 1");
        assertEquals(TEST_USERNAME, result.getUsername(), "用户名应匹配");
        assertEquals("张三", result.getNickname(), "昵称应为 张三");
        assertEquals(STORED_HASH, result.getPassword(), "密码哈希应匹配");
        assertEquals(STORED_SALT, result.getSalt(), "盐值应匹配");

        // 验证数据库交互
        verify(dataSource).getConnection(); // 确认获取了连接
        verify(preparedStatement).setString(1, TEST_USERNAME); // 确认设置参数
        verify(preparedStatement).executeQuery(); // 确认执行了查询
        verify(resultSet).next(); // 确认检查了结果集
    }

    /**
     * 场景二：用户名不存在
     * 预期：ResultSet 为空，返回 null
     */
    @Test
    @DisplayName("用户名不存在 - 应返回 null")
    void shouldReturnNullWhenUserNotFound() throws SQLException {
        // Given: ResultSet 为空（next() 返回 false）
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // 模拟查询不到用户

        // When: 执行登录
        User result = userDAO.login("nonexistent", TEST_PASSWORD);

        // Then: 验证结果
        assertNull(result, "用户名不存在时应返回 null");

        // 验证未调用 verify 方法（因为根本没查到用户）
        passwordUtilMock.verify(
                () -> PasswordUtil.verify(anyString(), anyString(), anyString()),
                never()
        );
    }

    /**
     * 场景三：密码错误
     * 预期：用户存在，但密码验证失败，返回 null
     */
    @Test
    @DisplayName("密码错误 - 应返回 null")
    void shouldReturnNullWhenPasswordIncorrect() throws SQLException {
        // Given: 用户存在，但密码错误
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("password")).thenReturn(STORED_HASH);
        when(resultSet.getString("salt")).thenReturn(STORED_SALT);

        // Mock PasswordUtil.verify 返回 false（密码错误）
        passwordUtilMock.when(() -> PasswordUtil.verify(TEST_WRONG_PASSWORD, STORED_HASH, STORED_SALT))
                .thenReturn(false);

        // When: 执行登录
        User result = userDAO.login(TEST_USERNAME, TEST_WRONG_PASSWORD);

        // Then: 验证结果
        assertNull(result, "密码错误时应返回 null");

        // 验证调用了密码验证但未获取昵称等字段
        passwordUtilMock.verify(() -> PasswordUtil.verify(TEST_WRONG_PASSWORD, STORED_HASH, STORED_SALT));
        verify(resultSet, never()).getLong("id"); // 密码错误时不会继续读取字段
    }

    /**
     * 场景四（补充）：参数为空
     * 预期：直接返回 null，不触发数据库查询
     */
    @Test
    @DisplayName("用户名为空 - 应返回 null 且不查询数据库")
    void shouldReturnNullWhenUsernameIsNull() throws SQLException {
        // When: 用户名为 null
        User result = userDAO.login(null, TEST_PASSWORD);

        // Then: 返回 null，且不连接数据库
        assertNull(result);
        verify(dataSource, never()).getConnection();
    }

    /**
     * 场景五（补充）：数据库异常
     * 预期：捕获 SQLException，返回 null
     */
    @Test
    @DisplayName("数据库异常 - 应返回 null")
    void shouldReturnNullWhenDatabaseError() throws SQLException {
        // Given: 数据库连接抛出异常
        when(dataSource.getConnection()).thenThrow(new SQLException("连接超时"));

        // When: 执行登录
        User result = userDAO.login(TEST_USERNAME, TEST_PASSWORD);

        // Then: 返回 null
        assertNull(result, "数据库异常时应返回 null");
    }
}