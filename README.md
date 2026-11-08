# Better Bundle / 更好的收纳袋

一个面向 **Minecraft 26.3 / Fabric** 的收纳袋增强模组：在背包旁提供按收纳袋分组的面板，并支持**拾取自动存入**、**物品匹配规则**和**手动放入限制**。

> 本仓库是基于 [zeroowo24/BetterBundleHUDMod](https://github.com/zeroowo24/BetterBundleHUDMod) 的**继续维护版本**。
> 原项目使用 **CC0-1.0** 许可证。本版本也是 **CC0-1.0**，并在其基础上做了大量重写和功能扩展。
> 本版本与原作者没有隶属关系，也不代表原作者认可这些修改。

---

## 功能

- **按收纳袋分组的面板**
  - 左侧列出背包里所有收纳袋，选中后只显示该收纳袋内容
  - 原版风格槽位，跟随资源包对 `inventory.png` 的修改
  - 搜索框支持中文、拼音和物品 ID
  - 鼠标滚轮切换收纳袋 / 滚动内容
- **拾取自动存入**
  - 根据收纳袋自定义名称里的正则匹配物品
  - 默认只存入“刚捡到的数量”，不吞背包里原有的同种物品
  - 可配置为整组存入
- **手动放入限制**
  - 默认只允许把匹配规则的物品放进收纳袋
  - 对“物品 → 收纳袋”和“收纳袋 → 物品格”两个方向都生效
  - 收纳袋套收纳袋也会按规则检查
- **接近箱子的操作方式**
  - 左键取整组 / 右键取一半
  - Shift+左键整组放进背包
  - 手持物品左键放入全部 / 右键放入一个
- **游戏内配置界面**
  - Mod Menu 中点击配置按钮
  - 或按 `F9`（可在按键设置里改键）
- **配置热重载**
  - 直接编辑 `config/better-bundle.json`，保存后约 1 秒生效

---

## 环境要求

| 项目 | 要求 |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | >= 0.19 |
| Fabric API | 0.161.0+26.3（或更新的 26.3 版本） |
| Java | 25 |
| Mod Menu | 可选，安装后可在 Mod Menu 里直接打开配置 |

---

## 安装

1. 安装 Fabric Loader 和 Fabric API
2. 把 `better-bundle-<版本>+26.3.jar` 放进 `mods/`
3. 可选：安装 Mod Menu
4. 启动游戏

---

## 收纳袋命名规则

收纳袋的**自定义名称**格式：

```
显示名<分隔符><正则>
```

默认分隔符是 `_`，例如：

```
矿物_^(绿宝石|钻石|红石)(矿石)?$
```

- 默认只按**显示名称**匹配（`matchItemId=false`、`matchDisplayName=true`）
- 如果正则是按物品 ID 写的，例如：

  ```
  矿物_^minecraft:(diamond|emerald|redstone)(_ore)?$
  ```

  需要把 `matchItemId` 改成 `true`，或者同时打开 ID 和显示名匹配。

没有有效规则的收纳袋按原版行为处理，不会限制手动放入。

---

## 使用说明

### 面板

- 打开背包后，GUI 右侧的按钮可以显示/隐藏面板
- 左侧图标是收纳袋，点击切换
- 搜索框只过滤当前选中的收纳袋
- 物品悬停显示提示

### 取出物品

| 操作 | 效果 |
|---|---|
| 左键物品 | 取整组到光标 |
| 右键物品 | 取一半到光标（需要空格子拆分） |
| Shift+左键物品 | 整组移到背包 |

### 放入物品

| 操作 | 效果 |
|---|---|
| 左键收纳袋图标 / 面板 | 放入全部 |
| 右键收纳袋图标 / 面板 | 只放入一个（需要两个空格子） |
| 空格+左键背包物品 | 批量放入当前/匹配的收纳袋 |

### 两种取出模式

| 模式 | 说明 |
|---|---|
| `SELECT` | 使用原版选中包，速度快，适用于大多数服务器 |
| `ROTATE` | 不使用选中包，用普通点击把目标物品轮换到第 0 位再取出；较慢，需要足够空格子 |

如果某个服务器上原版“选中后取出”也只能取第一个物品，说明服务端没有正确处理选中包，请使用 `ROTATE`。

---

## 配置

配置文件位置：

- 正式游戏：`.minecraft/config/better-bundle.json`
- 开发环境：`run/config/better-bundle.json`

默认值（1.3.20）：

```json
{
  "configVersion": 7,

  "autoDeposit": true,
  "autoDepositInInventory": false,
  "autoDepositInMenus": true,

  "serverSelectDelayTicks": 8,
  "serverStepDelayTicks": 4,
  "extractionMode": "SELECT",

  "depositDelayTicks": 4,
  "maxDepositsPerTick": 1,
  "depositMode": "PICKED_UP_ONLY",
  "maxSplitItems": 16,
  "splitOverflow": "SKIP",
  "splitClickDelayTicks": 1,

  "allowUnmatchedManualInsert": false,
  "nameSeparator": "_",
  "matchItemId": false,
  "matchDisplayName": true,

  "debug": false,
  "debugPackets": false
}
```

| 配置 | 默认 | 说明 |
|---|---|---|
| `autoDeposit` | `true` | 拾取自动放入总开关 |
| `autoDepositInInventory` | `false` | 背包/容器打开时是否继续自动放入 |
| `autoDepositInMenus` | `true` | Esc 菜单/聊天等非容器界面打开时是否继续自动放入 |
| `serverSelectDelayTicks` | `8` | 联机时“选中包 → 抽取点击”的等待 tick |
| `serverStepDelayTicks` | `4` | 联机时面板多步操作间隔 |
| `extractionMode` | `"SELECT"` | `SELECT` / `ROTATE` |
| `depositDelayTicks` | `4` | 每次存入后的等待 |
| `maxDepositsPerTick` | `1` | 每 tick 最多处理几个存入任务 |
| `depositMode` | `"PICKED_UP_ONLY"` | `PICKED_UP_ONLY` / `WHOLE_STACK` |
| `maxSplitItems` | `16` | 拆分模式单次最多拆多少 |
| `splitOverflow` | `"SKIP"` | 无法拆分时 `SKIP` / `WHOLE_STACK` |
| `splitClickDelayTicks` | `1` | 拆分点击间隔 |
| `allowUnmatchedManualInsert` | `false` | 是否允许手动放入不匹配物品 |
| `nameSeparator` | `"_"` | 名称分隔符 |
| `matchItemId` | `false` | 是否用正则匹配物品 ID |
| `matchDisplayName` | `true` | 是否用正则匹配显示名称 |
| `debug` | `false` | 动作栏调试消息 |
| `debugPackets` | `false` | 把收发数据包写入 `latest.log` |

---

## 从源码构建

需要 **JDK 25**。请通过 `JAVA_HOME` 或你自己的 `org.gradle.java.home` 指定，不要把本机路径提交到仓库。

```bash
git clone https://github.com/doorAllOpen/BetterBundle.git
cd BetterBundle
./gradlew build
```

产物：

```
build/libs/better-bundle-<version>+26.3.jar
```

Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-25"
.\gradlew.bat build
```

---

## 许可证与致谢

- 原项目：[zeroowo24/BetterBundleHUDMod](https://github.com/zeroowo24/BetterBundleHUDMod)
- 原作者：**zeroowo24**
- 原项目许可证：**CC0-1.0 Universal**
- 本继续维护版本同样以 **CC0-1.0** 发布，详见 [LICENSE](LICENSE)

本仓库包含大量重写、修复和新功能。原作者未参与本版本的维护；如原项目有更新，欢迎对比合并。

---

## 问题反馈

请在 GitHub Issues 中反馈，并附上：

- 游戏版本 / Fabric Loader / Fabric API 版本
- 是否安装了 Mod Menu
- `config/better-bundle.json` 内容
- `logs/latest.log` 中相关报错
- 服务器类型（单机、Fabric、Paper、Folia 等）以及是否能稳定复现
