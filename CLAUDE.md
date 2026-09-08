# 给编码代理的入口

**先读 `STATUS.md`**：拓扑、线上大脑契约、已拍板决策、已验证行为、已知缺口、下一步。它比 `README.md`（设计交接包的说明）和 `BUILDING.md`（模块与构建）更接近现状。

## 工作方式

- 页面必须逐元素对齐 `project/谛听 DiTing Demo.dc.html`。动手前用脚本把对应 `data-screen-label="…"` 段落 dump 出来看，别凭印象。复用 `app/src/main/kotlin/com/diting/app/ui/components/Common.kt` 里的组件。
- 用户会在真机上验收。改完就构建、安装、截图确认（命令见 `STATUS.md` §7）。测试机 `adb -s R5CWA1YQ6MD`，包名 `com.diting.app.debug`。
- 按钮必须有反馈（Toast）；没接的功能保留入口并禁用/说明。
- 不要用 Wi-Fi 同步路径（已放弃，代码保留）。
- 大脑以 `external_project/echo/neuroagi-core-v2` 的 echo 版为准（`integrations/console_api.py`、`echo_*_ext.py`），`ai_core/neuroagi-core-v2` 只是裸内核。
- 数据尽量真实：不要写死演示数据；缺数据就显示诚实的空态并说明原因。
- 每次会话结束前把新的决策/契约/缺口补进 `STATUS.md`。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug --console=plain -q
./gradlew :core:protocol:test :core:domain:test :core:ai:test   # 纯 JVM 测试
```
