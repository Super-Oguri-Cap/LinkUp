-- =============================================
-- LinkUp 即时通讯系统 - 好友请求表（加好友验证）
-- 版本: V5
-- 说明: 用户添加好友时需要对方同意，请求记录存储在此表
--       审批通过后写入 friendship 表建立好友关系
-- =============================================

USE linkup;

CREATE TABLE IF NOT EXISTS `friend_request` (
    -- 请求ID，主键自增
    `id`            BIGINT          NOT NULL AUTO_INCREMENT  COMMENT '请求ID，主键自增',

    -- 请求发起方用户ID
    `from_user_id`  BIGINT          NOT NULL                 COMMENT '请求发起方用户ID',

    -- 请求接收方用户ID
    `to_user_id`    BIGINT          NOT NULL                 COMMENT '请求接收方用户ID',

    -- 状态：0-待处理，1-已同意，2-已拒绝
    `status`        TINYINT         NOT NULL DEFAULT 0       COMMENT '状态：0-待处理，1-已同意，2-已拒绝',

    -- 请求时间
    `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '请求时间',

    -- 更新时间（同意/拒绝时自动更新）
    `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    -- 主键
    PRIMARY KEY (`id`),

    -- 按发起方查询索引
    KEY `idx_from_user` (`from_user_id`),

    -- 按接收方查询索引
    KEY `idx_to_user` (`to_user_id`),

    -- 按接收方+状态联合索引：查询某人有哪些待处理的请求
    KEY `idx_to_user_status` (`to_user_id`, `status`),

    -- 外键：发起方关联 user 表
    CONSTRAINT `fk_friend_request_from` FOREIGN KEY (`from_user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE,

    -- 外键：接收方关联 user 表
    CONSTRAINT `fk_friend_request_to` FOREIGN KEY (`to_user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友请求表（加好友验证）';