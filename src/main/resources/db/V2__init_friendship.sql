PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS `friendship` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `user_a_id`     INTEGER         NOT NULL,
    `user_b_id`     INTEGER         NOT NULL,
    `created_at`    TEXT            NOT NULL DEFAULT (datetime('now')),
    `remark`        VARCHAR(64)     DEFAULT NULL,
    `remark_owner`  INTEGER         NOT NULL DEFAULT 0,
    `is_blocked`    INTEGER         NOT NULL DEFAULT 0,
    `blocked_by`    INTEGER         NOT NULL DEFAULT 0,

    FOREIGN KEY (`user_a_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_b_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    UNIQUE (`user_a_id`, `user_b_id`)
);

CREATE INDEX IF NOT EXISTS `idx_friendship_a` ON `friendship` (`user_a_id`);
CREATE INDEX IF NOT EXISTS `idx_friendship_b` ON `friendship` (`user_b_id`);