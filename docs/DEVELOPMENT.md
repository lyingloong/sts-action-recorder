# 开发指南

安装和运行配置见 [使用指南](USER_GUIDE.md)，实机覆盖检查见 [采集验收](COLLECTION_AUDIT.md)。

## 仓库结构

```text
src/main/java/actionrecorder/
  ActionRecorder.java                   BaseMod 入口、更新/渲染订阅、存档字段
  ActionRecorderConfig.java             游戏内显示设置
  ui/                                   动作提示和可选动作提示框
  runtime/ActionRecorderRuntime.java     对局身份、事务、后台 JSONL/TCP 输出
  runtime/CommunicationStateBridge.java  调用 CommunicationMod 发布接口
  runtime/CommandLedger.java             可选脚本指令关联、前状态校验和执行回执
  runtime/DecisionState.java             独立执行校验投影，不改变完整录制状态
  runtime/QueuedDecisionTracker.java     排队动作身份与执行生命周期
  runtime/RuntimeStateFields.java        卡牌/玩家原始字段投影、缓存反射与 null 约定
  runtime/GridCardPreviews.java          GRID 完整确认卡、游戏 UI/独立副本升级预览
  runtime/MatchGameState.java            翻牌公开棋盘、固定位置与已揭示记忆
  patches/CommunicationStatePatches.java 状态 ID、钥匙、卡牌/选牌字段补充
  patches/CommunicationCommandPatches.java 官方指令回执、接口出牌/药水动作采集
  patches/DecisionPatches.java           细粒度游戏动作入口
  patches/RawInputPatches.java           键鼠调试输入
  patches/ControllerInputPatches.java    手柄调试输入
src/main/resources/ModTheSpire.json      Mod 元数据
src/test/java/actionrecorder/            补丁和队列 smoke checks
tools/                                  原始日志审计、离线数据集提取
tests/                                  Python 工具测试
docs/                                   使用、协议、数据集和验收文档
```

## 构建

需要 Java 8、Maven 3.x，以及本机游戏、BaseMod、ModTheSpire jar。
本机路径通过构建参数传入；以下均为占位路径：

```powershell
$env:JAVA_HOME = "C:/path/to/jdk8"
$steamApps = "C:/path/to/Steam/steamapps"
mvn clean package "-DSteam.path=$steamApps"
```

pom.xml 从以下相对位置读取依赖：

```text
<Steam.path>/common/SlayTheSpire/desktop-1.0.jar
<Steam.path>/workshop/content/646570/1605833019/BaseMod.jar
<Steam.path>/workshop/content/646570/1605060445/ModTheSpire.jar
```

构建产物为 target/action-recorder.jar。package 阶段默认将 jar 复制到游戏 mods 目录。
仅构建检查、暂不安装时运行：

```powershell
mvn clean package "-DSteam.path=$steamApps" "-Dactionrecorder.install.skip=true"
```

这个选项只跳过最后的安装复制，仍运行 Java smoke 和字节码校验。
不要用 `maven.antrun.skip` 代替它；后者会把验证任务也一起跳过。

更换游戏或依赖版本后，需要重新检查补丁签名、启动日志和实际操作。

## 验证命令

Python 工具使用任意 Python 3.10+，不需要其他项目的环境：

```powershell
python -m unittest discover -s tests -v
python tools/audit_trace.py "C:/path/to/game/data/actionrecorder/run-example.jsonl"
python tools/extract_dataset.py --help
```

Java smoke checks 是独立 main 程序，不是 JUnit 测试。
Maven 的 test 阶段会自动运行参数绑定、插桩编译、队列、运行时字段、存档/奖励身份、快照发布、
DecisionStateSmoke、CampfireOptionsSmoke、GridCardPreviewsSmoke、RuntimeDescriptionsSmoke 和 CommandLedgerSmoke。
安装了 CommunicationMod 时，还会通过实际 DataReader 测试 Windows CRLF 报文、检查真实 serializer，
并运行 CommunicationBytecodeSmoke。
JVM 校验失败就阻止后续 package/安装。未安装这个可选依赖时明确打印 SKIP，不阻止独立 Mod 构建；
手动运行下面不带 `--if-installed` 的 serializer/字节码命令则不会跳过。
构建后在本仓库根目录设置 classpath：

```powershell
$gameJar = "$steamApps/common/SlayTheSpire/desktop-1.0.jar"
$baseModJar = "$steamApps/workshop/content/646570/1605833019/BaseMod.jar"
$mtsJar = "$steamApps/workshop/content/646570/1605060445/ModTheSpire.jar"
$communicationJar = "$steamApps/workshop/content/646570/2131373661/CommunicationMod.jar"
$patchClasspath = "target/classes;target/test-classes;$gameJar;$baseModJar;$mtsJar;$communicationJar"
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.PatchBindingSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.PatchInstrumentationSmoke
& "$env:JAVA_HOME/bin/java.exe" -Xverify:all -cp $patchClasspath actionrecorder.CommunicationBytecodeSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.QueuedDecisionTrackerSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.RuntimeStateFieldsSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.RuntimeDescriptionsSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.runtime.GridCardPreviewsSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.CommandLedgerSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.DecisionStateSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.CampfireOptionsSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.CommunicationSerializationSmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.JournalIdentitySmoke
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.runtime.CommunicationSnapshotSmoke
```

PatchBindingSmoke 使用 ModTheSpire 参数绑定逻辑检查 prefix/postfix，包括历史错误签名的
失败回归。PatchInstrumentationSmoke 对实际安装 jar 编译 ExprEditor 替换代码。
CommunicationBytecodeSmoke 将 CommunicationMod 的 instrument 补丁应用到实际 jar，
生成完整 class 文件并通过隔离加载器交给 JVM 加载和校验（不初始化游戏）。
补丁能编译不等于字节码能通过校验；这一步覆盖接口药水记录曾出现的 VerifyError。
CommandLedgerSmoke 检查命令关联、重复/过期状态拒绝、原始命令兼容性、LF/CRLF 外层报文，
以及载荷内部换行和非法 Base64 仍被拒绝。实际 DataReader 的回归测试包含 START 和 STATE，
避免仅测试不带换行的内存字符串而遗漏 Windows 脚本接口行为。
DecisionStateSmoke 验证非手牌的展示数值重置可通过，但原始 JSON 保持完整；卡牌身份/顺序、
升级/费用、玩家/敌人/界面变化以及未知字段仍被拒绝。静态描述、自定义动态变量不被忽略。
CommandLedgerSmoke 还验证结构化拒绝码，确保执行器异常或已入队事务不会错误标为 not_executed。
CampfireOptionsSmoke 检查营火继承字段、当前语言说明、未知模组选项、原始明细独立快照，
以及复用 CommunicationMod 名称后的数量、索引和等长错序校验。源字段不可用时保留 null，
不会构造营火按钮或执行其 useOption。
对已录制的 controller 消息还可回放校验投影：

```powershell
& "$env:JAVA_HOME/bin/java.exe" -cp $patchClasspath actionrecorder.DecisionStateSmoke --replay /path/to/messages.jsonl 26 28
```

末尾两个参数是 `message_index`；程序要求两份原始观察确实不同、但校验投影相同。
这用于验证非手牌临时数值重置的误拒绝案例，不代表任何不同状态都应通过校验。

QueuedDecisionTrackerSmoke 检查对象身份、执行/取消和重置。RuntimeStateFieldsSmoke 用
合成对象检查运行时标记、玩家计数/姿态、翻牌位置与公开信息边界。这些检查不代替游戏启动和
[实际采集验收](COLLECTION_AUDIT.md)。其他系统使用相应 classpath 分隔符和 Java 路径。

CommunicationSerializationSmoke 使用实际 CommunicationMod 的 shaded Gson 执行 serializer
补丁，验证中立姿态名称、隐藏牌面和未知字段的 null 穿过序列化与 JSON 树保存边界。
队列测试覆盖自动连锁、下一项人类操作和结束回合等待；字段测试包含单卡预览/多选确认。
描述测试检查牌组中尚未初始化的数值回退到游戏对象的基础值、战斗内零值不被覆盖，
以及未知数值保持不可用和游戏文本格式的清理。
GridCardPreviewsSmoke 验证候选只升级独立副本、原牌和现成 UI 预览不变、模组候选明确未知、
确认卡字段完整、非升级场景无预览、列表身份错位拒绝，以及重复 STATE 不受副本随机 UUID 影响。
原版候选识别使用游戏 ClassLoader 和原始 class 资源所在 JAR，结果按 Class 缓存；
不依赖 ProtectionDomain/CodeSource，因为 ModTheSpire 重定义补丁类时不保留这些来源信息。
回归检查同时覆盖普通 classpath 与 null-domain 类加载环境。未知模组卡、非游戏 JAR 资源、
无法确认的类资源以及带 CardModifier 的卡牌仍保守拒绝合成预览。
这不是模组代码沙箱：其他模组仍可能补丁修改原版卡；因此独立副本、对象身份检查和异常回退仍需保留。
能量图标检查覆盖中文、各色能量、数字/重复图标和普通文字不被替换。

## 采集实现

动作入口调用 beginDecision()，请求 CommunicationMod 原有发布器生成完整状态；
状态补丁给实际 JSON 编号并保存 state_published，action_begin 引用该结果。
游戏接受动作后记录 action_accepted，未接受则记录 action_rejected。

主动采集发布附带 recorder_snapshot_only=true 和边界类型；自然 CM 发布为 false。
脚本执行端必须忽略只读快照的 ready_for_command，不将其作为命令完成通知；
记录消费者仍保存它们。CommunicationSnapshotSmoke 验证三种边界、嵌套调用及异常后复原。

游戏线程构造事件并入队。写盘线程追加并 flush 本地文件，再把副本交给独立 TCP 线程。
状态转换仍在游戏线程执行；PostUpdate 的生命周期/队列检查不做每帧完整状态序列化。

出牌和结束回合采用提交、执行两个边界。队列项按对象身份关联到 transaction_id，
处理队首前获取执行状态；实际 useCard/结束回合分支证明 executed，队列丢弃记录
skipped/cancelled。执行和结算边界的公开字段见 [事件格式](EVENT_SCHEMA.md)。

run_id 和角色/进阶/种子指纹存入 BaseMod savefield。继续同一存档追加原文件；
跨幕暂时清空地牢字段不会结束对局，真实终局由 VictoryScreen/DeathScreen 入口记录。
0.1.5 的 SaveLoadPatches 在 loadPlayerSave 入口建立加载屏障，等待 BaseMod onLoadRaw
和游戏初始化完成后恢复保存的 RunIdentity；不会根据临时 act=0/seed=null 生成新身份。
RunIdentity 保存文件名和 checkpoint，RewardIdentity 用对象身份而非内容分配奖励 UUID。
新开同种子的另一局仍获得新 run_id。JournalIdentitySmoke 覆盖这些身份规则和加载就绪条件；
仍需实机验证保存退出、重启继续及 post-combat 存档恢复。

## 扩展动作

1. 用 javap 检查目标游戏 jar，定位动作入口和真正接受操作的分支。
2. 在对应 patch 中，于状态修改之前 beginDecision()；接受后 recordAction(...)，
   未接受时 discardDecision()。必须保留游戏原返回值和原行为。
3. 将实体 ID、UUID、下标、目标及选择集合保存为结构化参数。
4. 保持细粒度：入口、选牌、确认和返回分别记录；自动效果不是新的玩家操作。
5. 更新 [事件格式](EVENT_SCHEMA.md)；增加必要的状态补充时更新
   [状态契约](STATE_SCHEMA.md) 和 JSON Schema。
6. 构建、执行相关 smoke checks、检查启动，并测试有/无 TCP 接收端的实际操作。

几个容易漏采或重复的入口：

- 人类 playCard 直接加入 cardQueue；仅监听 addCardQueueItem 会漏掉人类并混入自动播放。
- 结束回合输入走 EndTurnButton.disable(true)，与引擎 endTurn 不是同一边界。
- closeCurrentScreen 也用于自动切屏，返回/取消应监听实际按钮接受分支。
- 升级预览也会调用 upgrade()，不能全局把每次 upgrade 当成营火操作。
- 药水栏包含 PotionSlot 占位，列表长度不能判断是否满；购买药水只在 obtainPotion 成功时记录。
- 信使会补货，商品对象应在购买前保存，避免购买后读取到下一件商品。
- 宝箱 keyRequirement() 可先修改 isOpen，观察应在其修改前捕获；Boss 宝箱按实际类型分类。
- 原状态不可用时保持 null，不能回退到缓存的另一个场景。

提示框只读取内存快照，渲染中不做磁盘或网络 I/O。显示裁剪不影响日志内容。

## 补丁兼容性与常见错误

旧版 @SpirePatch 返回值绑定不仅取决于参数名。boolean 返回值的 postfix 可采用：

```java
public static boolean postfix(boolean __result, Target __instance) {
    // 记录所需信息。
    return __result;
}
```

将 __result 放在不支持的位置、声明为 void 或改变返回值，可能导致
“Illegal patch parameter: Cannot determine name” 或改变游戏行为。
因此 prefix/postfix 绑定和 ExprEditor 编译都需要检查。

Javassist 的替换代码编译器不能依赖 Java 泛型隐式插入类型转换。
例如从 `ArrayList<AbstractPotion>` 调用 `get()` 时，必须显式写
`(AbstractPotion) player.potions.get(index)`；仅声明左侧变量为 AbstractPotion 不足以保证
生成 `checkcast`，可能编译通过但游戏启动时报 `VerifyError: Bad type on operand stack`。
修改此类补丁后必须运行 JVM 字节码校验测试，不能通过关闭校验绕过错误。

依赖更新后按实际 jar 检查签名、运行 smoke checks、查看 ModTheSpire 启动日志，并进行
常见场景采集验收。开发文档不把某次测试数量视为长期覆盖承诺。

## 数据工具维护

audit_trace.py 做只读结构诊断；extract_dataset.py 负责对局筛选、明确引用关联和通用导出。
保持完整原始字段，冲突明确报错，输出目录不覆盖。提取工具不生成模型提示词或训练标签。
修改输出格式时更新 [数据集提取](DATASET_EXTRACTION.md)，并检查保存/继续、去重、
未知字段、缺失引用、胜负筛选及取消动作的保留。
