PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS `user` (
    `id`            INTEGER         NOT NULL PRIMARY KEY AUTOINCREMENT,
    `username`      VARCHAR(64)     NOT NULL,
    `password`      VARCHAR(128)    NOT NULL,
    `salt`          VARCHAR(32)     NOT NULL,
    `nickname`      VARCHAR(64)     NOT NULL,
    `avatar`        VARCHAR(255)    DEFAULT NULL,
    `email`         VARCHAR(128)    DEFAULT NULL,
    `created_at`    TEXT            NOT NULL DEFAULT (datetime('now')),
    `last_login_at` TEXT            DEFAULT NULL,
    `online_status` INTEGER         NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS `uk_username` ON `user` (`username`);
CREATE INDEX IF NOT EXISTS `idx_email` ON `user` (`email`);
CREATE INDEX IF NOT EXISTS `idx_online_last_login` ON `user` (`online_status`, `last_login_at`);