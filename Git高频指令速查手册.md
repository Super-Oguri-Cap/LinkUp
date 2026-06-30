# Git 高频指令速查手册

> 面向日常开发，覆盖 90% 场景的标准化命令清单。
> 场景前提：当前在 `SQLite` 分支上工作（`git branch` 确认，`*` 号标记当前分支）。

---

## 一、日常提交与推送（最常用）

```bash
# 查看当前状态（红色=未暂存，绿色=已暂存）
git status

# 添加指定文件/文件夹到暂存区
git add src/main/java/com/xxx/UserController.java   # 添加单个文件
git add .                                            # 添加当前目录所有改动
git add frontend/                                    # 添加指定文件夹

# 提交到本地仓库
git commit -m "feat: 完成了用户登录模块"

# 推送到远程
git push -u origin SQLite   # 首次推送（关联远程分支）
git push origin SQLite      # 后续推送
```
git log --oneline origin/SQLite..HEAD   # 查看本地有但远程没有的
git log --oneline HEAD..origin/SQLite   # 查看远程有但本地没有的

### 标准提交流程（三板斧）

```bash
git add .
git commit -m "feat: 改动说明"
git push origin SQLite
```

---

## 二、拉取与合并（同步队友代码）

```bash
# 抓取远程最新代码（不合并，仅查看差异）
git fetch origin

# 合并远程 master 分支到当前分支
git merge origin/master

# 拉取并合并（fetch + merge 一步到位，需已追踪远程同名分支）
git pull origin SQLite

# 解决合并冲突后的操作
git add .
git commit -m "merge: 解决合并冲突"
git push origin SQLite
```

> 执行 `git merge` 时若弹出 vim 编辑器要求写合并信息，输入 `:wq` 回车退出即可。

---

## 三、分支管理（查看、创建、切换、删除）

```bash
# 查看分支
git branch         # 本地分支（当前分支前带 *）
git branch -r      # 远程分支

# 创建并切换到新分支
git checkout -b feature/new-function

# 切换到已有分支
git checkout master

# 重命名当前分支
git branch -m SQLite_final

# 删除本地分支
git branch -d master    # 安全删除（未合并会警告）
git branch -D master    # 强制删除（不检查是否合并）

# 删除远程分支（需权限）
git push origin --delete master
```

---

## 四、撤销与回退（后悔药）

```bash
# 取消暂存（撤销 git add，保留文件修改）
git reset HEAD src/File.java
git restore --staged src/File.java     # 新版写法

# 撤销工作区修改（丢弃未暂存改动，危险！）
git checkout -- src/File.java
git restore src/File.java              # 新版写法

# 撤回最近一次提交（保留改动到暂存区）
git reset --soft HEAD~1

# 撤回最近一次提交（彻底丢掉改动，危险！）
git reset --hard HEAD~1

# 撤销已推送的提交（生成反向提交，最安全）
git revert HEAD
git push origin SQLite
```

> ⚠️ 只要没执行 `git push -f`，几乎都能用 `git reset` 或 `git revert` 救回来。

---

## 五、远程仓库与忽略文件

```bash
# 查看远程仓库地址
git remote -v

# 修改远程仓库地址（HTTPS ↔ SSH 切换）
git remote set-url origin git@github.com:Super-Oguri-Cap/LinkUp.git

# 强制覆盖远程分支（慎用，会覆盖队友代码）
git push -f origin SQLite

# 从 Git 追踪中移除文件（保留本地文件）
git rm --cached -r target/
git rm --cached -r .idea/
git commit -m "chore: 移除误传的编译文件"
git push origin SQLite

# 检查 .gitignore 是否生效
git check-ignore -v src/Test.java
```

---

## 六、日志与查看

```bash
# 简洁提交历史（一行一条）
git log --oneline

# 查看所有分支的提交网络图
git log --graph --oneline --all

# 查看某次提交的具体改动
git show 3686db4

# 查看谁改动了某文件的每一行
git blame src/main/App.java
```

---

## 七、一次性收尾流程

基于 `.idea` 和 `chat_history` 尚未被忽略彻底的情况，直接执行即可完成清理：

```bash
# 1. 确保当前在 SQLite 分支
git checkout SQLite

# 2. 拉取远程 master 并合并
git fetch origin master
git merge origin/master

# 3. 将 .idea 和 chat_history 加入永久忽略
echo ".idea/" >> .gitignore
echo "chat_history/" >> .gitignore

# 4. 提交忽略规则并推送
git add .gitignore
git commit -m "chore: 永久忽略 .idea 和 chat_history"
git push origin SQLite

# 5. 确认最终干净状态（应显示 nothing to commit）
git status
```

---

## 附录：删除 master 分支

```bash
git branch -D master                    # 仅删除本地 master
git push origin --delete master         # 删除远程 master（需先修改默认分支）
```

---

> 📌 **最后提醒**：平时只用 `git add .` → `git commit -m ""` → `git push` 三板斧足够应付 90% 的场景。出错别慌，大多数情况都能救回来。有任何红色报错，把输出复制给 AI 即可排查。
