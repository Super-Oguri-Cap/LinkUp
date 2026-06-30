-- =============================================
-- LinkUp 即时通讯系统 - 用户表初始化脚本
-- 版本: V1.0
-- 数据库: MySQL 8.0+
-- 说明: 存储注册用户的基本信息
-- =============================================

USE linkup;

CREATE TABLE IF NOT EXISTS `user` (
    -- 用户ID，主键，自增
    -- 使用 BIGINT 以支持海量用户（IM 场景用户量可能很大）
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '用户ID，主键自增',

    -- 用户名，全局唯一，用于登录
    -- VARCHAR(64) 足够容纳常见用户名，UNIQUE 约束保证唯一性
    `username`      VARCHAR(64)     NOT NULL                 COMMENT '用户名，全局唯一，用于登录',

    -- 密码，SHA-256 + 盐值加密后存储
    -- 哈希值 Base64 编码后约 44 字符，VARCHAR(128) 预留升级空间
    `password`      VARCHAR(128)    NOT NULL                 COMMENT '密码，SHA-256+盐值加密存储，不可逆',

    -- 盐值，每次注册随机生成，与密码配合使用
    -- Base64 编码后约 24 字符
    `salt`          VARCHAR(32)     NOT NULL                 COMMENT '密码盐值，Base64编码',

    -- 昵称，展示用，允许重复
    -- 默认取用户名，用户可后续修改
    `nickname`      VARCHAR(64)     NOT NULL                 COMMENT '昵称，展示名称，可重复',

    -- 头像路径，存储相对路径或 URL
    -- 可为 NULL，表示使用系统默认头像
    `avatar`        VARCHAR(255)    DEFAULT NULL             COMMENT '头像路径，NULL 时使用默认头像',

    -- 邮箱，用于找回密码、通知等
    -- VARCHAR(128) 覆盖常见邮箱长度
    `email`         VARCHAR(128)    DEFAULT NULL             COMMENT '邮箱地址',

    -- 注册时间，默认当前时间
    -- 使用 DATETIME(3) 保留毫秒精度，便于精确排序
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '注册时间',

    -- 最后登录时间，登录时更新
    `last_login_at` DATETIME(3)     DEFAULT NULL             COMMENT '最后登录时间',

    -- 在线状态：0-离线，1-在线
    -- TINYINT 节省空间，DEFAULT 0 表示默认离线
    `online_status` TINYINT         NOT NULL DEFAULT 0       COMMENT '在线状态：0-离线，1-在线',

    -- 主键
    PRIMARY KEY (`id`),

    -- 用户名唯一索引，登录查询的核心索引
    UNIQUE KEY `uk_username` (`username`),

    -- 邮箱普通索引，支持按邮箱查找用户
    KEY `idx_email` (`email`),

    -- 在线状态 + 最后登录时间联合索引，优化"在线用户列表"查询
    KEY `idx_online_last_login` (`online_status`, `last_login_at`)

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LinkUp 用户基本信息表';