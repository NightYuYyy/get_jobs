# 本地 BOSS 回复模块

自己的 fork：https://github.com/NightYuYyy/get_jobs
原项目 remote：upstream → https://github.com/loks666/get_jobs.git
功能分支：codex/boss-replies

## 运行方式与边界

Get Jobs 是 Java / Spring Boot 后台，配置与状态落在本地 SQLite；后台管理一个持久化 Chrome，
通过唯一的 Playwright 专用线程执行页面操作。投递整段占用该线程，因此第一版采用**投递优先**，
投递运行时不扫描、不生成新的回复、不发送消息。未恢复 ai-job 的后台、支付、用户体系或模型池。

BOSS 页面新增「回复助手」标签页：
- 默认关闭，重启后保持关闭；开启先建立现有会话基线，不补发历史消息。
- 复用 AI 配置和「个人介绍」，不复用仅适用于首次打招呼的五参数提示词。
- 默认只生成草稿，需在管理页人工确认发送；开启前提示聊天数据发送到所配置模型及费用。
- 可选常见问候自动发送，仅限「你好、您好、在吗、还在找工作吗、还在看机会吗、有兴趣了解一下吗」。
  技能问题等其他消息仍生成草稿；薪资、面试、到岗、联系方式、承诺等交给本人。
- 本人新发送的消息、浏览器中的输入草稿会暂停该会话，可在管理页恢复。
- 以会话 ID + 消息 ID 去重；发送前检查最新消息和目标会话，结果未知时绝不自动重试。
- 每天最多 20 次发送（包括结果未知）；模型生成单线程，避免无限排队。
- 不发送附件、不交换联系方式、不接受邀约、不读取浏览器 Cookie 或其他账号资料。
- 回复接口只允许 loopback 与本机管理页 Origin，聊天内容不写入仓库。
- 页面字段、发送者或消息 ID 不可确认时停止，不刷新绕过平台检查。

读取脚本的选择器及 Vue 字段来自当时主动服务的 BOSS chat-new v5550 资产。
**ponytail:** 第一版仅扫描当前 DOM 加载的最多 100 个普通 HR 会话，不滚动全量历史列表；
BOSS 页面升级或会话未加载时可能无法监听。需要全量覆盖时再增加经真实页面验证的分页适配。
不得把离线测试通过当作当前 BOSS 线上回复已验证。

本地表 boss_reply 保存草稿、发送状态与最多 20 条上下文；boss_reply_pause 保存人工接管状态。
服务启动只新增表、不改原投递配置。模型失败不会自动重试或自动发送原始响应。
发送期间断线/服务重启标为 UNKNOWN，需先在 BOSS 核对，不可直接补发。

## 自检与构建

    node scripts/check-boss-reply-reader.mjs
    .\gradlew.bat checkBossReplies -x buildFrontend
    cd front
    corepack pnpm install --frozen-lockfile --ignore-scripts
    node node_modules/typescript/bin/tsc --noEmit
    node node_modules/eslint/bin/eslint.js app/boss/ReplyContent.tsx
    cd ..
    .\gradlew.bat bootJar

自检使用内存 SQLite、假浏览器和假模型；没有真实账号、网络或模型费用。
构建与测试在隔离目录中完成，不覆盖正在运行的 JAR、数据库或 browser-data。
升级需等待投递结束、确认 idle、保留旧 JAR 与数据库备份后再由用户发起；
代码保存或远程推送不意味着运行进程已升级。

## 上游同步

本机已有配置文件修改时不要自动 pull，不能强行覆盖。
在已提交、干净的功能分支中执行：

    .\scripts\sync-upstream.ps1

脚本只 fetch 原项目 main，预演无冲突后 merge；已有自定义功能被保留。
冲突时工作区不变，不自动 reset/stash、不推送、不部署、不重启。
同步后重新自检和构建，再 git push origin HEAD。不设置无人审核的定时覆盖。
本项目继续遵守原仓库的 PolyForm Noncommercial License，不改变授权条款。
