# Neko Client

[![许可证](https://img.shields.io/badge/license-MIT-6B7280?style=flat-square)](LICENSE) [![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11%20%7C%2026.x-2ea043?style=flat-square&logo=minecraft&logoColor=white)](https://github.com/YoshinoShizuka/neko-client) [![侧别](https://img.shields.io/badge/side-%E7%BA%AF%E5%AE%A2%E6%88%B7%E7%AB%AF-2563EB?style=flat-square)](https://github.com/YoshinoShizuka/neko-client)

面向 Fabric 的客户端增强，把「虚空仓库」那面 6 行原版大箱子变成能搜索、能整格取货、能记配方、能投影填充的工作台。纯客户端实现，服务端 GUI 一个字节都不改，纯原版客户端零感知。

## 功能

| 功能 | 说明 |
| --- | --- |
| 搜索框 | 中文名 / 拼音全拼 / 首字母 / 注册名筛选；匹配在本地做，服务端只做集合求交。 |
| 取满手势 | 按住组合键点一格，取出到背包放不下为止；常规给散货、潜影盒模式给满盒，容量由服务端现算。 |
| 配方本 | 图标 + 连续滚动 + 搜索（含模糊拼音），按匹配度置顶；逐条删除（点两次确认，全服共用）。 |
| 投影容器填充 | 装了 Litematica 时右键投影里的容器，按投影内容逐格填充；缺料自动向仓库取货，余料自动退回。 |
| 容器填充物合计 | 一张表：整张原理图所有容器要什么 × 背包 + 仓库还缺多少。入口在材料列表工具栏。 |
| 按键打开仓库 | 自己绑一个热键，游戏内按一下服务端就开界面；已在界面时不重置分页。 |
| 快速丢弃 | 仓库界面里 `Q` 丢 1 个、`Ctrl+Q` 丢一组（原版语义）、`Shift+Q` 丢整格（≤4096 件）。 |
| 聊天静音 | 一键隐藏虚空仓库发来的全部聊天提示，只影响本机显示。 |

## 依赖

<table>
  <thead>
    <tr><th>名称</th><th>类型</th><th>链接</th><th>说明</th></tr>
  </thead>
  <tbody>
    <tr><td nowrap>Fabric API</td><td nowrap>必需</td><td nowrap><a href="https://modrinth.com/mod/fabric-api">Modrinth</a></td><td>Fabric 模组运行所需的 API。</td></tr>
    <tr><td nowrap>MaLiLib</td><td nowrap>必需</td><td nowrap><a href="https://modrinth.com/mod/malilib">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/malilib">GitHub</a></td><td>配置界面与热键体系；装过 MiniHUD / Litematica 的玩家已有。</td></tr>
    <tr><td nowrap>Litematica</td><td nowrap>可选</td><td nowrap><a href="https://modrinth.com/mod/litematica">Modrinth</a>&nbsp;|&nbsp;<a href="https://github.com/maruohon/litematica">GitHub</a></td><td>启用投影容器填充与容器填充物合计。</td></tr>
    <tr><td nowrap>carpet-Neko-Addition</td><td nowrap>可选</td><td nowrap><a href="https://github.com/YoshinoShizuka/Neko-Carpet-Addition">GitHub</a></td><td>服务端规则与通道来源；没装时搜索框自动隐藏，其余功能自行关闭。</td></tr>
  </tbody>
</table>

## 版本支持

| 游戏版本 | jar | 构建管线 |
| --- | --- | --- |
| 26.3 / 26.2 / 26.1.2 / 26.1.1 | `neko-client-mc<ver>-0.5.9.jar` | fabric-loom（非 remap，Java 25） |
| 1.21.11 | `neko-client-mc1.21.11-0.5.9.jar` | fabric-loom-remap + officialMojangMappings（Java 21） |

服务端需 `carpet-Neko-Addition` 1.0.16+（按键打开仓库与快速丢弃需 **1.0.17+**，快速丢弃要第 29 次原地替换的 jar）并打开 `voidWarehouse`。

## 热键

| 名称 | 默认键 | 说明 |
| --- | --- | --- |
| 搜索框 / `searchBox` | 未绑定 | 切搜索框，界面开着时立刻生效 |
| 取满手势 / `fillGesture` | 未绑定 | 切取满开关 |
| 静音聊天 / `silenceChat` | 未绑定 | 切聊天静音 |
| 取满组合键 / `fillCombo` | `LEFT_CONTROL,LEFT_ALT,BUTTON_2` | 手势本身：按住它再点格子；清空 = 关掉手势 |
| 打开配置界面 / `openGui` | 未绑定 | 打开 malilib 配置界面 |
| 打开配方本 / `openRecipes` | 未绑定 | 随地可用，不必开着仓库 |
| 容器填充物合计 / `openContainerMaterials` | 未绑定 | 备用入口；主入口是材料列表工具栏的「容器材料」 |
| 打开虚空仓库 / `openWarehouse` | 未绑定 | 非界面状态按一下，服务端开界面 |

- 快速丢弃的 `Shift+Q` 是**固定键**，不在配置里；`Q` / `Ctrl+Q` 走原版，不接管。
- 热键默认留空是为了不与其他模组撞键；配置落 `config/neko-client.json`，手改后重进一次世界生效。
- 绑定入口：Mod Menu 或任意 malilib 模组的配置界面 → 选「Neko Client」。

## 安装

1. 装 Fabric Loader + Fabric API + **MaLiLib**（同版本）。
2. 按游戏版本把对应 jar 放进 `mods/`。
3. 需要搜索 / 取满 / 配方本 / 快速丢弃时，服务端装 `carpet-Neko-Addition` 并打开相关规则。

## 构建

JDK 25、Gradle 9.7.0（wrapper 已固定）。多子工程：`common/`（纯逻辑）、`modern/`（26.x 四版本）、`versions/1.21.11`。

```bat
.\gradlew.bat :modern:26.3:test
.\gradlew.bat :common:test
.\gradlew.bat :modern:26.3:build
.\gradlew.bat :versions:1.21.11:build
```

- 🔴 **逐版本构建**：全量 `build` 在 26.x 与 1.21.11 混跑可能撞 TinyRemapper。
- 🔴 **与服务端成对更新**：`neko/voidwarehouse/` 下的 payload 类与 carpet 同名类必须逐字节一致（同 FQCN 只加载一份）。
- 交付前跑单测 **189 条（26.3）+ 11 条（common）** 与 jar 级字节码断言 **2967 条**。

## 兼容性

| 服务端 \ 客户端 | 装了本 mod | 没装 |
| --- | --- | --- |
| carpet-Neko-Addition 1.0.17+ | 全部功能可用（按规则开关） | 无任何影响 |
| carpet-Neko-Addition 1.0.16 | 搜索 / 取货可用；开门与快速丢弃提示「服务端不支持」 | 无任何影响 |
| 其它版本 / 没装 carpet | 搜索框自动隐藏 | 无任何影响 |

客户端没装 MaLiLib 时模组不会加载（硬依赖）。

## 许可证

[MIT](LICENSE)。拼音库 [jpinyin](https://github.com/stuxuhai/jpinyin)（Apache-2.0）以 Jar-in-Jar 打包。
