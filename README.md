# Neko VoidWarehouse Search（虚空仓库客户端增强）

纯客户端 Fabric mod，给「虚空仓库」的界面（一个 6 行原版大箱子）加两件事：

1. **搜索框**：支持**中文 / 拼音全拼 / 拼音首字母 / 注册名**搜索筛选物品。服务端 GUI 一个字节不变，纯原版玩家零感知。
2. **Ctrl + Alt + 左键：把这一格的物品取出到背包放不下为止**（0.2.0 新增）。常规模式给**纯散货**（一个盒子都不装），潜影盒模式给**满盒**，都是「能装多少装多少」——装多少由服务端按你的真实背包现算，只取仓库里现有的（不走 9 合 1 兑换）。
3. **两个功能各有独立开关 + 可自定义热键**（0.2.0）。开关落 `config/neko-voidwarehouse-search.json`，热键走原版「选项 → 控制 → 按键绑定 → 虚空仓库」（默认 `H` 切搜索框、`J` 切取满手势），随时可改，冲突检测与重置也由原版代劳。

> 🔴 原版容器的点击协议里**没有修饰键**（`PICKUP / QUICK_MOVE / SWAP / CLONE / THROW / QUICK_CRAFT / PICKUP_ALL` 一个都带不了 Ctrl / Alt），所以这个手势只能在客户端识别，再走专用通道 `carpet_neko_addition:fill_request` 告诉服务端「点的是这一格、意图是填满」。**没装本 mod（或低于 0.2.0）的玩家，这个手势完全不存在**：点击就是原版的「取 1 个」，没有残迹也没有报错。

![Side](https://img.shields.io/badge/side-%E7%BA%AF%E5%AE%A2%E6%88%B7%E7%AB%AF-informational) ![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.x-2ea043)

## 工作方式

```
打开仓库界面 ──search_hello──▶ 服务端确认是虚空仓库
             ◀─search_catalog─ 仓库物品 id 目录（≈30 KB）
输入 ─▶ 本地匹配（中文名 / 全拼 / 首字母 / 注册名，jpinyin 建索引）
     ──search_apply──▶ 服务端按 Item 身份过滤出页（空列表 = 恢复完整列表）
```

- 匹配在客户端做（只有客户端有中文语言文件），服务端只做集合求交；
- 过滤是**会话级**状态：关掉界面即消失，不写存档；页码回第一页；「可兑」虚拟条目同样参与搜索；
- 服务端限流：hello 100ms / apply 50ms，目录上限 4096 种，未知 id 静默忽略。

### Ctrl + Alt + 左键（0.2.0）

```
按住 Ctrl+Alt 左键点仓库格 ──fill_request{slotId}──▶ 服务端
                                                      ├─ 校验：菜单必须是虚空仓库（否则静默丢）
                                                      ├─ 校验：slotId 必须是内容格（导航行/背包格不认）
                                                      ├─ 校验：这一格要解析得出物品
                                                      ├─ 限流 50ms
                                                      └─ fill()：常规 = 纯散货填满背包 / 盒模式 = 满盒
◀──────────────────── 聊天提示「已取出 N 个 <物品>」─────────────────────
```

- **只发 `slotId`**：物品是什么、能装多少，全由服务端从自己的数据和你的真实背包现算，客户端报的数字一概不作数；
- 客户端**只有真的把包发出去**才 cancel 掉原版点击；对端是老服务端（没这条通道）时原样放行，退回「取 1 个」；
- 「能装多少」= 三个上限取最小：背包放得下多少 / 仓库里有多少 / 盒模式下材料够做几盒。**一个都装不下时**会发一句提示，不会静默失败；
- 盒模式下潜影壳 / 箱子也能装盒，但材料账会先把「马上要装走的那批」刨掉（库存刚好只够一盒容量时一盒都不给，宽裕时照给满盒）。

## 安装

mod id `neko_voidwarehouse_search`，构件名 `neko-voidwarehouse-search`。五个 Minecraft 版本各一个 jar：

| 客户端版本 | jar | 构建管线 |
| --- | --- | --- |
| 26.3 / 26.2 / 26.1.2 / 26.1.1 | `neko-voidwarehouse-search-mc<ver>-0.2.0.jar` | fabric-loom（非 remap，Java 25） |
| 1.21.11 | `neko-voidwarehouse-search-mc1.21.11-0.2.0.jar` | fabric-loom-remap + officialMojangMappings（Java 21） |

| 位置 | 需要什么 |
| --- | --- |
| 客户端 | 本 mod（按版本选 jar）+ Fabric API（需搭配服务端 `carpet-Neko-Addition` 1.0.16+） |
| 服务端 | `carpet-Neko-Addition` 1.0.16+（对应版本 jar；本 mod 不是它的硬依赖，没有它时搜索框自动隐藏） |
| 纯原版客户端 | 什么都不用装，看不到任何变化 |

## 使用

- 打开虚空仓库界面，点击界面上方的搜索框；
- 输入 `铁锭` / `tieding` / `td` / `iron_ingot` 均可命中；清空输入框 = 恢复完整列表；
- **Ctrl + Alt + 左键**点仓库里的物品格：取出到背包放不下为止（常规模式纯散货、潜影盒模式满盒）；
- 🔴 聚焦搜索框时**所有按键都会被吞掉**（ESC = 退出搜索框，再按一次关界面）——否则数字键 `1-9` 会触发原版「把快捷栏整组存进仓库」的 SWAP 语义。Ctrl+Alt+左键 命中槽位时会自动让出焦点（原版点击被 cancel，它的「点框外失焦」不会执行，所以这里补上）。

### 开关与快捷键（0.2.0）

两个功能各自独立，互不影响；关掉一个不会影响另一个。

| 配置项 | 默认 | 关掉之后 |
| --- | --- | --- |
| `searchBox` | `true` | 界面里**不再创建**搜索框（不占地方、不吃按键、不发 `search_apply`），Ctrl+Alt+左键 照常可用 |
| `fillGesture` | `true` | Ctrl+Alt+左键 退回原版「取 1 个」，搜索框照常可用 |

| 热键 | 默认键 | 说明 |
| --- | --- | --- |
| `key.neko_voidwarehouse_search.toggle_search` | `H` | 切搜索框开关；**正在开着界面时立刻生效**（连带清空输入、撤掉服务端过滤） |
| `key.neko_voidwarehouse_search.toggle_fill` | `J` | 切取满手势开关；立刻生效，下一次左键就走新状态 |

- 配置文件：`config/neko-voidwarehouse-search.json`（首次启动自动生成，带 `_readme` 注释字段）；
- **生效时机**：热键切换 → 立刻 + 当场写回文件；手改 json → **退出重进一次世界**（进服时重读）；重开界面也会重读；
- 两个开关**都关**时客户端不发 `search_hello`，连那 ≈30 KB 的目录都不拉——只想安静用原版仓库的玩家可以这样彻底关掉；
- 热键去**原版按键绑定界面**改（分类「虚空仓库」）。不借用 malilib：MiniHUD 那套热键表是 malilib 提供的，本 mod 不引依赖，走原版 UI 语义等价，且能白拿冲突检测、按键冲突提示与「重置为默认」；
- 热键**不分界面**都在生效（切完会打一行动作栏提示）。不在仓库界面时按也没关系，回到界面直接是新状态；
- ⚠️ 一个例外：**两个开关都关着时打开界面**，界面里根本不会创建搜索框；此时按 `H` 把搜索框打开，需要**关掉界面再开一次**才会出现（框是在界面初始化时建的）。只要打开界面时有任意一个开关是开的，之后来回切都立刻生效。

## 构建

- JDK 25、Gradle 9.7.0（wrapper 已固定）。多子工程结构：`common/`（纯逻辑 SearchIndex + 单测）+ `modern/`（26.x 四版本）+ `versions/1.21.11`：
  ```bat
  .\gradlew.bat build dist
  .\gradlew.bat :common:test          # SearchIndex 单测（11 条）
  .\gradlew.bat :modern:26.3:test     # 开关配置 JSON 单测（13 条）
  .\gradlew.bat :versions:1.21.11:dist
  ```
- 🔴 **逐版本构建**（与 carpet 同款铁律）：全量 `build` 在 26.x 与 1.21.11 混跑时可能撞 TinyRemapper；按子工程逐个跑最稳。
- 产物统一复制到工作区 `deliver/neko-voidwarehouse-search/`。
- 🔴 **与服务端 jar 成对更新**：`neko/voidwarehouse/search/` 三个 payload 类 + `neko/voidwarehouse/fill/FillRequestPayload` 与 carpet-Neko-Addition 里同名类**必须逐字节一致**（同 FQCN 双份、Fabric knot 只加载一份），改一边务必同步改另一边。payload 源文件按 carpet 26.3 基准 md5 校验：hello `ee8150d8` / catalog `fc27b613` / apply `856c374a` / fill `ca737c87`（五版本 + 服务端六份逐字节一致）。
- 1.21.11 走 loom-remap 管线：mixin 注解名编译期硬 remap 成 intermediary（无 refmap 文件，与 carpet-Neko-Addition 1.21.11 同机制）；`dist` 任务必须依赖 `remapJar` 而不是 `jar`（jar 产出的是 dev jar）。

## 版本差异（仅四处）

| 差异点 | 26.x | 1.21.11 |
| --- | --- | --- |
| 网络注册 | `serverboundPlay()` / `clientboundPlay()` | `playC2S()` / `playS2C()` |
| 渲染注入 | `extractRenderState(GuiGraphicsExtractor,...)` TAIL 后手动 `box.extractRenderState(...)` | `render(GuiGraphics,int,int,float)` TAIL 后手动 `box.render(...)` |
| 按键注册 | `KeyMappingHelper.registerKeyMapping`（`fabric-key-mapping-api-v1`） | `KeyBindingHelper.registerKeyBinding`（`fabric-key-binding-api-v1`） |
| 动作栏消息 | `player.sendSystemMessage(Component)` | `player.displayClientMessage(Component, true)` |

两代容器屏都不渲染 renderables，搜索框都必须手动驱动。第 3、4 项集中在唯一一个分叉文件 `SearchPlatform.java` 里，其余源码五版本逐字节相同（换行统一 CRLF）。

> 🔴 26.3 有两处**只此一家**的编码，写死另一版本的值会静默失效：`InputConstants.Type` 常量改名为 `KEYBOARD`（1.21.11 叫 `KEYSYM`）⇒ 一律用 `KeyMapping(name, 键码, category)` 三参构造绕开；`Minecraft#screen` 在 26.1.x 是公开字段、26.2+ 挪到 `Gui#screen()` ⇒ 干脆不碰它，改由 mixin 实现 `SearchScreenAccess` 把宿主屏存下来。

## 版本兼容

| 服务端 \ 客户端 | 装了本 mod | 没装 / 老版本 |
| --- | --- | --- |
| carpet-Neko-Addition 1.0.16+（对应版本） | 搜索框可用 | 无任何影响 |
| 其它版本 / 没装 carpet | 搜索框自动隐藏（`canSend` 判定） | 无任何影响 |

## 许可证

MIT。拼音库 [jpinyin](https://github.com/stuxuhai/jpinyin)（Apache-2.0）以 Jar-in-Jar 打包。
