-- =============================================
-- LinkUp 即时通讯系统 - 群组相关表
-- 版本: V1.2
-- 说明: group_info（群基本信息）+ group_member（群成员关系）
-- =============================================

USE linkup;

-- ----------------------------
-- 群基本信息表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `group_info` (
    -- 群ID，主键自增
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '群ID，主键自增',

    -- 群名称
    `name`          VARCHAR(128)    NOT NULL                 COMMENT '群名称',

    -- 群头像路径
    `avatar`        VARCHAR(255)    DEFAULT NULL             COMMENT '群头像路径',

    -- 创建者ID，外键关联 user 表
    -- 不使用 ON DELETE CASCADE：创建者注销不应删除群（群可转移给其他管理员）
    `owner_id`      BIGINT          NOT NULL                 COMMENT '创建者/群主ID',

    -- 创建时间
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

    -- 群公告，TEXT 类型支持长文本
    `announcement`  TEXT            DEFAULT NULL             COMMENT '群公告',

    -- 主键
    PRIMARY KEY (`id`),

    -- 创建者索引，查询"某人创建的群列表"
    KEY `idx_owner_id` (`owner_id`),

    -- 外键：创建者关联 user 表
    CONSTRAINT `fk_group_owner` FOREIGN KEY (`owner_id`)
        REFERENCES `user` (`id`) ON DELETE RESTRICT

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LinkUp 群组基本信息表';

-- ----------------------------
-- 群成员关系表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `group_member` (
    -- 成员关系ID，主键自增
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '成员关系ID，主键自增',

    -- 群ID，外键关联 group_info
    `group_id`      BIGINT          NOT NULL                 COMMENT '群ID',

    -- 用户ID，外键关联 user
    `user_id`       BIGINT          NOT NULL                 COMMENT '用户ID',

    -- 加入时间
    `joined_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '加入群时间',

    -- 角色类型：0-普通成员，1-管理员，2-群主
    -- TINYINT 比 ENUM 更灵活，方便后续扩展角色
    `role`          TINYINT         NOT NULL DEFAULT 0       COMMENT '角色：0-普通成员，1-管理员，2-群主',

    -- 群内昵称（不同于全局昵称）
    `group_nickname` VARCHAR(64)    DEFAULT NULL             COMMENT '群内专属昵称',

    -- 主键
    PRIMARY KEY (`id`),

    -- 同一用户在同一群内只能有一条记录
    UNIQUE KEY `uk_group_user` (`group_id`, `user_id`),

    -- 按用户ID查所有加入的群
    KEY `idx_user_id` (`user_id`),

    -- 外键：群ID关联 group_info，级联删除：群解散时自动删除成员记录
    CONSTRAINT `fk_gm_group` FOREIGN KEY (`group_id`)
        REFERENCES `group_info` (`id`) ON DELETE CASCADE,

    -- 外键：用户ID关联 user，级联删除：用户注销时自动退出所有群
    CONSTRAINT `fk_gm_user` FOREIGN KEY (`user_id`)
        REFERENCES `user` (`id`) ON DELETE CASCADE

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LinkUp 群成员关系表';

-- =============================================
-- 外键约束与数据一致性说明
-- =============================================
-- 1. group_info.owner_id → user.id (ON DELETE RESTRICT)
--    群主注销时阻止删除，需先转移群主或解散群，防止群成为孤儿
--
-- 2. group_member.group_id → group_info.id (ON DELETE CASCADE)
--    群解散时自动删除所有成员记录，无需手动清理
--
-- 3. group_member.user_id → user.id (ON DELETE CASCADE)
--    用户注销时自动退出所有群
--
-- 4. group_member.uk_group_user (group_id, user_id) 唯一约束
--    防止同一用户重复加入同一群，DB 层面兜底保证
--
-- 5. 常见查询：
--    - 查某群的所有成员：SELECT * FROM group_member WHERE group_id = ? → 命中 uk_group_user
--    - 查某用户加入的群：  SELECT * FROM group_member WHERE user_id = ?  → 命中 idx_user_id