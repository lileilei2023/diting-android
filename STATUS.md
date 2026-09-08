# 谛听 DiTing · 项目现状（供后续 session 接手）

最后更新：2026-09-08 凌晨。本文记录**代码之外**才知道的事：拓扑、契约、决策、已验证的行为、已知缺口。
代码结构看 `BUILDING.md`，页面设计看 `project/谛听 DiTing Demo.dc.html`（验收标准）。

## 1. 产品与拓扑

- **产品**：MR20 蓝牙录音卡 + Android App「谛听」+ 大脑后端。硬件只会 BLE（和一个只有 AP 模式的 Wi-Fi），手机永远是桥。
- **数据流**：录音卡 →(BLE 拉文件)→ App →(HTTP 上传 MP3)→ 大脑转写/抽取 →(HTTP 回报)→ App 入库 → 页面。大脑主动说话走 WS `/channel/pendant-<sn>`，App 收到后发通知 + 让卡震动。
- **三个代码库**：
  - `ai_core/diting-android`（本仓库，分支 `feat/android-diting`）— App。
  - `ai_core/neuroagi-core-v2` — 大脑**裸内核**，缺 echo 扩展，**不是**线上跑的版本。
  - `external_project/echo/`（前身 Echo）：`echo-android` 旧桥接 App；`neuroagi-core-v2` 带 `integrations/echo_*_ext.py` 的完整版，**线上跑的是这个**。
- **线上大脑**：`http://47.115.135.22:8000`，`ssh root@47.115.135.22`，代码 `/root/project/echo/neuroagi-core-v2`，systemd `echo-brain.service`，env `/root/project/echo/echo.env`（`BRAIN_AUTH=token`，运维 token 在 `BRAIN_TOKENS`）。远端不是 git 仓库，部署靠 `echo/deploy.sh` rsync。SSH 长命令（>30s）会断，要拆短。
- **服务器已打的补丁**：`brain/store.py put_capability` 的 ON CONFLICT 原本不更新 `key_hash`，导致设备重复注册后 WS 永远 403；已加 `key_hash=coalesce(excluded.key_hash, capability.key_hash)`，三处（服务器、echo 副本、ai_core 副本）一致。
- **测试手机**：Samsung S23U，USB 串号 `R5CWA1YQ6MD`（Wi-Fi adb 会因手机加入录音卡热点而断，用 USB）。包名 `com.diting.app.debug`。旧 Echo App `com.mrsnail.pendant` 也在手机上，联调前要 `am force-stop` 它，否则它占着 BLE。
- **这块卡**：BT MAC `50:c0:f0:43:7d:68`，大脑侧设备 id `pendant-50c0f0437d68`，广播名 `YLF20_f0437d68`（不是 MR20，广播里没有服务 UUID，扫描不能按 UUID 过滤）。SK 绑定密钥 `pmsykEeb8o7V5HGD`（旧 Echo 写进卡的，新 App 必须复用，否则 `SK&ERR`）。
- **大脑账户**：手机号+密码登录，绑定码 `4d448a43` 把账号挂到租户 `echo`。App 端 `BrainStore`（EncryptedSharedPreferences）存 token / sn / deviceKey。

## 2. 已拍板的决策

- **Wi-Fi 同步放弃**（用户 2026-09-07）：三星上 `WifiNetworkSpecifier` 一旦批准过热点就必现 `Network not present in config manager`，设备回的 AP 密码 `pmsykEeb` 也被系统拒绝。代码保留（`device/wifi/`），入口撤掉，`DeviceService` 强制 `preferWifi=false`。蓝牙约 90 KB/s。
- **页面逐元素对齐 Demo HTML**（用户会拿 Demo 截图对照）。共享组件在 `ui/components/Common.kt`：`BackCircle` `UnderlineTabs` `RailSheet` `SheetCard` `GroupCard/GroupRow` `MintAction/WhiteAction` `BottomActionBar` `AiOrb` `MintLink` `MonoMeta` `Eyebrow` `SegmentedPills`；`FilterChipPill`（SessionScreens，internal）；`PillSwitch`（TeachScreens）。不要用 Material 默认 TabRow/Button/Switch/FilterChip。
- **所有按钮要有反馈**（Toast）；未接的功能保留入口并禁用/说明，不能消失。
- **相邻录音拼成一场再理解**（用户 2026-09-08）：见 §4 Episode。
- **无意义录音不自动删**，只进清理卡由用户勾选；30 秒内且没听到说话的误触例外，自动丢。

## 3. 大脑契约（echo 版 server.py + integrations/console_api.py）

| 用途 | 调用 | 备注 |
|---|---|---|
| 登录/注册 | `POST /auth/login` `/auth/register`(adopt_code) `GET /auth/me` | `BrainApi` |
| 设备登记 | `POST /capabilities/register` → device key | `mintDeviceKey(sn)`；登录和 AuthFailed 时自动重登记 |
| 实时通道 | `WS /channel/pendant-<sn>?token&key` | `PendantChannel`，403 三次后 AuthFailed |
| 上传转写 | `POST /upload {kind:audio, audio_base64, format, filename, at, store_raw, extract}` | 返回 `transcript[]`("说话人N：文本")、`summary`、`title`、`facts[[s,p,o]]`、`items[{owner,task,due_days}]`。`due_days` 相对 `at`（录音时间）。`raw=true` 不偏置不入库（校准用） |
| 问答 | `POST /ask {question}` | 对话页非「帮我…」问题 |
| 待办提议 | `GET /pending`、`POST /confirm/{id}?approve=` | 洞察「待确认」 |
| 记忆 | `GET /memories?since&limit&slim=1` | 已封装未在页面用 |
| 能力总线 | `POST /invoke {name, action, args}` | `correct.apply {ops:[{type:hotword,words}]}` 推热词（服务端存 setting/hotwords → ASR system 提示）；`calibrate` `hotwords/script/diff`（口音校准）；`calibrate.voiceprint` 返回不可用 |

ASR：服务端 `_transcribe_wav_b64` 调 qwen 音频模型，单次 ~5 分钟上限，服务器出口 ~190 Kbps，`urlopen` 120s 超时 → App 端 `Mp3Chunker` 按帧边界切 1.15 MB 一片，逐片上传，片报告落盘可续传（`BrainStore.chunkProgress`）。**没有说话人分离**：所有行都是「说话人1」；`echo_diarize_ext` 只对挂件实时帧按响度二分，批量上传用不上。

## 4. App 数据链路（现状）

- **同步**：`DeviceService`（前台服务）→ `Mr20DeviceManager.syncNewRecordings`：录音中拒绝拉文件（a1 通道共用），新→旧，>5 MB 默认跳过（「同步全部录音」才拉），登记为 session（`deviceFilePath` 去重），然后 enqueue `BrainUploadWorker`。
- **导入**：`SessionRepository.importLocalRecording/scanImports`。设备页「导入录音文件」（SAF 多选）；或 adb `run-as` 把 mp3 放进 `files/recordings/`，App 启动（`BrainBridge.start`）和 Worker 启动都会扫描登记为 `import/<name>`，时间取文件名 `yyyy-MM-dd HH-mm-ss`。`.partNN.mp3` 是上传切片，已排除并清理。
- **上传**：`BrainUploadWorker`（前台、Mutex 单实例、一批 20 条后自己 APPEND 下一批、LINEAR 30s 退避）。启动时先 `pushHotwords`。`import/` 的会话不受「只上传配对之后」限制。40 MB 上限。锁屏冻结靠前台通知 + `svc power stayon usb` + deviceidle 白名单（测试机已设）。
- **入库**：`SessionRepository.applyBrainReport`：turns 按字数均摊时间成 segments（**时间戳是估的**），`StoredSummary(points=facts 文本, todos, oneLine=summary)`，标题用大脑的；`feedMemory` 把三元组喂进图谱、人名/产品名写成 DISCOVERED 热词候选；<30 s 无语音的直接删。
- **Episode 拼接**：`EpisodeMerger.run()`（BrainBridge 启动 + Worker 每批结束）：同设备、间隔 <10 分钟的已转写录音拼成一场：MP3 字节拼接成 `episode_<id>.mp3`，转写按前段时长平移，碎片用 `sessions.mergedIntoId` 隐藏（保留做同步去重与引用）。有理解模型时 episode **不存拼接摘要**，`summaryJson` 留空 → `sessions.summarize()` 用本机理解模型整段重读（带真实引用时间戳）；离线失败下次再试（`episodesWithoutSummary`）。
- **理解模型**：`AiClientFactory.understanding()`——用户已配「自带 Key · 豆包 · 火山方舟 ep-…」；`SessionRepository.summarize` 之后也 `feedMemory`。ASR 层未配（走大脑）。
- **记忆图谱**：`MemoryRepository.ingestBrainFacts`：`主体 · 关系 · 客体` → PERSON/TOPIC/COMMITMENT/DECISION/RISK；每场会话每节点只计一次；跨会话第 2 次出现生成 `InsightEntity(kind=RECURRING)`；节点时间用录音时间。图谱为空时 `backfillMemoryIfEmpty` 从已有 summary 重建（「全部遗忘」后重启即重建）。
- **今日**：当天没录音时锚定最近有录音的一天并注明。**复盘**：自然周/月，锚定最近录音，‹ › 翻页。**记录**：按天分组，「日期」跳转，长按删除，清理卡（无语音 + `StoredSummary.isLowValue()`）。
- **洞察**：纪要=本地 insights；待确认=本地 insights + 大脑 `/pending`；定位张力=summary 里关系为 观点/要求/顾虑 的三元组按人分组。
- **任务**：`帮我…` → 任务闸门①；确认后本机 Agent（理解模型）生成工件 → 闸门② → 采用（对外目的地要二次确认，但目的地都未连接，只标记已采用）。
- **对话**：`/ask` + 来源列表；未登录提示。
- **说话人**：转写页点标签 → 「这是我」/写名字（`SpeakerDao.setIdentity`，仅本场）。
- **首次引导 = 口音校准**：选领域 → `calibrate.hotwords` 术语 → `calibrate.script` 朗读稿 → `MediaRecorder` 录 m4a → `/upload raw=true` → `calibrate.diff` 存别名+热词。未登录大脑时退化为只朗读固定三句。
- **播放**：`playback/SessionPlayer.kt`（ExoPlayer），详情页波形可点选进度，「回到原声」在本页 seek。

## 5. 已验证（真机）

- 配对（含手填密钥）、BLE 同步、分片上传、40 分钟录音转写（55 turns）、大脑 `say` → 通知+卡震动、锁屏后台上传。
- 27 条 7 月录音（`~/Desktop/RECORD`，1–28 分钟有说话的）已导入并转写；5 个 episode 拼接并被豆包整段重读（32 分钟那场：6 要点 / 5 决策 / 11 待办）。
- 全部 22 个 Demo 页面对齐；键盘避让；热词推送到大脑（日志 `pushed N hotwords`）；朗读稿由大脑生成。**朗读→diff 那一步需要真人读，未验证。**

## 6. 已知缺口 / 占位

- **声纹 / 说话人分离**：服务器 4 核无 GPU 不跑 CAM++；批量上传无响度信息。目前手动标。要做需要服务端接说话人嵌入模型或换带 diarization 的 ASR。
- **Segment 时间戳是按字数估的**（大脑不返回逐句时间）。
- **实时字幕 / 同传 / 中英对照**：未接（录音页写明批处理）。
- **Skill 列表、目的地连接（OAuth）、订阅方案文案、MCP**：静态占位。
- **报告导出**：系统分享文本，没有长图。
- **导出全部记忆**：未实现（Toast）。
- 卡上还有 49 条 >5 MB 的录音（1.24 GB）没拉，「同步全部录音」可拉，请给卡充电。
- 两小时整段（28 MB）的 25 条 7 月录音没导入（转写太慢）。
- 手机 Wi-Fi「客厅」的保存配置曾丢失，需要用户手动重连。

## 7. 常用操作

```bash
cd /Users/lileilei/workspace/ai_core/diting-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug --console=plain -q          # 构建
adb -s R5CWA1YQ6MD install -r app/build/outputs/apk/debug/app-debug.apk
adb -s R5CWA1YQ6MD shell monkey -p com.diting.app.debug -c android.intent.category.LAUNCHER 1
adb -s R5CWA1YQ6MD logcat | grep -E "BrainApi|BrainUpload|EpisodeMerger|BleGatt|FATAL"
# 看数据库（debug 包）
adb -s R5CWA1YQ6MD exec-out run-as com.diting.app.debug cat databases/diting.db > /tmp/d.db
adb -s R5CWA1YQ6MD exec-out run-as com.diting.app.debug cat databases/diting.db-wal > /tmp/d.db-wal
# 导入录音（放进目录即可，启动后自动登记）
adb -s R5CWA1YQ6MD push some.mp3 /data/local/tmp/ && adb -s R5CWA1YQ6MD shell 'run-as com.diting.app.debug cp /data/local/tmp/some.mp3 files/recordings/'
```

UI 自动化：`scripts/adb_tap.py "文本"`（uiautomator dump 找文本并点击，精确匹配优先）。签名不同要先卸载再装。Room 版本 3（`mergedIntoId`），迁移在 `DitingDatabase`。

## 8. 建议的下一步

1. 说话人：服务端加 diarization（或换 paraformer 带 speaker），App 端已有 `Speaker.isOwner/displayName` 承接。
2. 让大脑返回逐句时间戳，替换按字数估算。
3. 记忆页接 `/memories` 做真实冷热分层；「导出全部记忆」。
4. 目的地（日历/飞书/邮件）真正连接；Skill 注册表落库。
5. 提交本轮改动（37 个文件 + 新增 `brain/` `playback/` `EpisodeMerger.kt` `BrainScreens.kt`，均未 commit）。
