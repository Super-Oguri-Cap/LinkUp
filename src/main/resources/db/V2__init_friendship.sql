-- =============================================
-- LinkUp 即时通讯系统 - 好友关系表
-- 版本: V1.1
-- 说明: 双向好友关系管理，确保 user_a_id < user_b_id 避免重复
-- =============================================

USE linkup;

CREATE TABLE IF NOT EXISTS `friendship` (
    -- 关系ID，主键自增
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '关系ID，主键自增',

    -- 用户A ID（约定 user_a_id < user_b_id，保证唯一性）
    -- 外键关联 user 表，级联删除：用户注销时自动删除好友关系
    `user_a_id`     BIGINT          NOT NULL                 COMMENT '用户A ID，始终小于 user_b_id',

    -- 用户B ID
    `user_b_id`     BIGINT          NOT NULL                 COMMENT '用户B ID',

    -- 添加时间
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '添加好友时间',

    -- 备注名，可为空，用户可给对方设置备注
    `remark`        VARCHAR(64)     DEFAULT NULL             COMMENT '备注名，用户A对用户B或用户B对用户A的备注',

    -- 备注从属方：标识备注是谁设置的，0-无备注，1-用户A设置，2-用户B设置
    `remark_owner`  TINYINT         NOT NULL DEFAULT 0       COMMENT '备注设置者：0-无备注，1-用户A，2-用户B',

    -- 是否屏蔽消息：0-未屏蔽，1-已屏蔽
    `is_blocked`    TINYINT         NOT NULL DEFAULT 0       COMMENT '是否屏蔽消息：0-未屏蔽，1-已屏蔽',

    -- 屏蔽操作发起方：0-未屏蔽，1-用户A屏蔽，2-用户B屏蔽
    `blocked_by`    TINYINT         NOT NULL DEFAULT 0       COMMENT '屏蔽发起方：0-未屏蔽，1-用户A，2-用户B',

    -- 主键
    PRIMARY KEY (`id`),

    -- 核心约束：同一对好友只能有一条记录
    -- 插入时应用层保证 user_a_id < user_b_id
    UNIQUE KEY `uk_user_pair` (`user_a_id`, `user_b_id`),

    -- 外键：用户A关联 user 表
    CONSTRAINT `fk_friendship_user_a` FOREIGN KEY (`user_a_id`)
        REFERENCES `user` (`id`) ON DELETE CASCADE,

    -- 外键：用户B关联 user 表
    CONSTRAINT `fk_friendship_user_b` FOREIGN KEY (`user_b_id`)
        REFERENCES `user` (`id`) ON DELETE CASCADE

) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LinkUp 好友关系表';

-- =============================================
-- 索引优化说明
-- =============================================
-- 1. uk_user_pair (user_a_id, user_b_id) 联合唯一索引：
--    - 保证同一对好友只有一条记录
--    - 同时也是查询"某人全部好友"的覆盖索引
--    - 查询用户X的所有好友：
--      SELECT * FROM friendship WHERE user_a_id = X OR user_b_id = X;
--      该查询会使用 user_a_id 索引扫描 + user_b_id 索引扫描（UNION）
--
-- 2. 判断两人是否好友：
--    SELECT 1 FROM friendship
--    WHERE (user_a_id = ? AND user_b_id = ?)
--    命中 uk_user_pair 唯一索引，O(1) 查询
--
-- 3. 为什么不用两条记录（A-B, B-A）表示双向好友？
--    - 一条记录 + 排序约束（a < b）减少 50% 存储
--    - 避免双写带来的数据不一致风险
--    - 查询时 OR 条件可被 MySQL 优化为 index_merge union