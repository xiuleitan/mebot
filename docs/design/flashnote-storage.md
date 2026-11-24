# 闪念本地数据基础

统计扩展：`10→11` 增加 `flashnote_completions`、`flashnote_completion_archive`，并按已有有效完成时间回填。当前共用主库保留后续业务迁移链；本次不重建或清空数据库。详见 [历史统计](flashnote-ui.md#历史统计)。

附件基础版本 0.19.0 使用 schema 10；`MIGRATION_9_10` 为保留数据的迁移，原有日程、闪念、草稿、凭据及媒体文件均保留。

基础版本：0.16.2。开发步骤一在共用主库 `mebot.db` 中建立闪念事项表、草稿表和 DAO，主库版本由 8 升级到 9。

0.16.3 已增加 [步骤二的本地业务 Repository](flashnote-business.md)，0.18.0 已接入 [页面状态、真实录入及编辑管理流程](flashnote-ui.md)，当时复用 schema 9。正式页面不注入示例数据。

## 数据结构

| 表 | 字段 | 说明 |
| --- | --- | --- |
| `flashnotes` | `id`、`title`、`content`、`status`、`createdAt`、`updatedAt`、`completedAt`、`revision` | 正式事项；标题和完整正文分别保存，状态为 `PENDING` 或 `COMPLETED` |
| `flashnote_drafts` | `key`、`title`、`content`、`editingNoteId`、`baseRevision`、`revision`、`updatedAt` | 独立的新建／编辑草稿；新建草稿的编辑目标为空，编辑草稿关联正式事项及原 revision |

时间均以毫秒时间戳持久化。两个状态列表按 `createdAt ASC, id ASC` 查询，不使用修改或完成时间排序；建立 `(status, createdAt, id)` 联合索引。

草稿的 `editingNoteId` 有外键和唯一索引，每件事项最多关联一份编辑草稿。删除正式事项时级联删除该编辑草稿，不影响其他事项或独立的新建草稿。草稿另外按 `updatedAt` 建索引，供后续恢复选择使用。

DAO 提供事项和草稿的插入、读取、订阅、状态列表查询及基础删除入口。主键或编辑目标重复时使用 `ABORT`，不以 `REPLACE` 覆盖已有事项和草稿。步骤二扩展了版本条件更新和删除，参数校验、创建时间生成及保存事务由 Repository 协调。

初始 schema 9 仅保存文字。0.19.0 增加 `flashnote_attachments`（正式事项与媒体）、`flashnote_draft_attachments`（草稿与媒体）两张关联表，记录顺序和显示名称，外键关联共享的 `media_assets`。`flashnote_drafts.attachmentSignature` 保存本次提交附件顺序的签名，新增或移除附件同时递增草稿版本。附件本体复用 `LocalMediaStore`，保存到 `filesDir/media/originals`，不保留临时 URI 作为正式数据。

正式保存时，事项写入、附件转移和草稿清理处于一个事务；编辑先复制附件关联，保存才替换正式关联，取消只删除草稿关联。清理查询同时保护日程、闪念及其草稿引用，文件删除队列可跨重启恢复。文件复制或关联失败不会留下可见的半条附件。

## 数据库升级

`MIGRATION_8_9` 仅新增两张表及对应索引，原有业务表、字段、索引和媒体文件不变。保留 1 到 8 的迁移并注册完整升级链，不提供清库重建回退。schema 9 和当前 schema 10 均保留在 `app/schemas/`。0.19.0 注册完整的 1→10 升级链，9→10 只新增签名字段、两张关联表及索引；实际 schema 8 和 9 的升级均由真实 SQLite 测试验证。

`FlashnoteStorageTest` 在开发机使用真实 SQLite 验证：

1. 新库为空，事项、状态、完整正文及新建／编辑草稿在关闭重开后保留。
2. 未完成和已完成均最旧在前，同一创建时间按 ID 稳定排序，不受修改或完成时间影响。
3. 重复事项 ID、草稿 key 或编辑目标不能覆盖原内容，也不会意外删除原编辑草稿。
4. 编辑草稿必须关联存在的事项；删除事项只清理相关编辑草稿。
5. 关联写入失败时整个事务回滚，不留下半条正式记录。
6. 根据真实 schema 8 构造旧数据库，写入日程、周期、发生记录、附件、会话、草稿、提醒、加密凭据字节、封面队列和铃声设置；升级后逐表比较旧数据及表／索引定义，检查附件文件字节与外键一致性，并验证新表可读写。

## 手机验收

1. 覆盖安装后正常打开应用，进入日程并浏览已有日期：原卡片和完成状态应保留，详情能够打开，无数据库升级报错或闪退。
2. 打开原日程的图片和文件附件，再进入原会话：图片、附件和聊天记录仍可查看；检查原草稿及“模型与 API”中的配置状态仍保留。
3. 打开“设置 → 通知提醒”：原铃声名称及配置保持；选择一项已有日程完成后恢复，再关闭重开应用，状态和列表正常。
4. 进入闪念并保存一条测试文字，再关闭重开：真实卡片和创建时间应保留，状态筛选、详情及输入样式正常，不出现旧示例。

新表读写及迁移一致性由自动化测试验证，本轮手机验收重点是覆盖升级后的既有数据和相关回归。

## 代码位置

- [事项与草稿实体](../../app/src/main/java/com/txl/mebot/data/local/FlashnoteEntities.kt)
- [闪念 DAO](../../app/src/main/java/com/txl/mebot/data/local/FlashnoteDao.kt)
- [主库及升级迁移](../../app/src/main/java/com/txl/mebot/data/local/MebotDatabase.kt)
- [存储与迁移测试](../../app/src/test/java/com/txl/mebot/data/FlashnoteStorageTest.kt)
