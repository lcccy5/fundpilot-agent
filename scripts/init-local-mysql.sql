-- 创建本地开发库和测试库，字符集为 utf8mb4。
-- 库已经存在时语句成功且不改动已有库的字符集。账号没有建库权限、服务未启动，或服务器不接受该排序规则时整段失败。
CREATE DATABASE IF NOT EXISTS jijing_agent
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS jijing_agent_test
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;
