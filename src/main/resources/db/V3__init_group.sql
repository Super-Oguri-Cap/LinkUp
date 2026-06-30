PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS `group_info` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `name`          VARCHAR(128)    NOT NULL,
    `avatar`        VARCHAR(255)    DEFAULT NULL,
    `owner_id`      INTEGER         NOT NULL,
    `created_at`    TEXT            NOT NULL DEFAULT (datetime('now')),
    `announcement`  TEXT            DEFAULT NULL,

    FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS `idx_group_owner` ON `group_info` (`owner_id`);

CREATE TABLE IF NOT EXISTS `group_member` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `group_id`      INTEGER         NOT NULL,
    `user_id`       INTEGER         NOT NULL,
    `joined_at`     TEXT            NOT NULL DEFAULT (datetime('now')),
    `role`          INTEGER         NOT NULL DEFAULT 0,
    `group_nickname` VARCHAR(64)    DEFAULT NULL,

    FOREIGN KEY (`group_id`) REFERENCES `group_info` (`id`) ON DELETE CASCADE,
    FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE,
    UNIQUE (`group_id`, `user_id`)
);

CREATE INDEX IF NOT EXISTS `idx_group_member_user` ON `group_member` (`user_id`);