# Java Reverse Agent

基于语义指纹的 Java 字节码插桩 Agent 与离线扫描器。它不写死目标类名、方法名或字段名，而是通过业务字符串、序列化契约、Kotlin metadata 和方法形状定位代码，适配混淆后包名/类名每次变化的目标 JAR。

> 仅用于 Java 字节码、混淆与授权机制的学习研究。请遵守目标软件许可协议和当地法律。

## 工作原理

1. `FingerprintScanner` 扫描字节码，提取混淆器无法稳定修改的语义特征：
   - 常量池和 `LDC` 中的业务字符串
   - Gson `@SerializedName` 值
   - Kotlin `@Metadata` 中的 getter 语义名
   - 方法参数数量与返回类型组成的形状
2. `MatchRule` 用多个锚点做交集匹配，并可把字符串锚点限定到同一个方法内。
3. `FingerprintTransformer` 在类加载时执行规则；`OfflineScanner` 可先离线验证，再生成补丁副本。

方法形状只比较参数数量和返回类型，例如 `args=0,return=void`，不包含混淆后的包名或类名。

## 项目结构

| 文件 | 职责 |
|---|---|
| `src/main/java/com/reverse/agent/ActivationAgent.java` | Java Agent 入口与默认规则集 |
| `src/main/java/com/reverse/agent/FingerprintScanner.java` | 提取类和方法级语义指纹 |
| `src/main/java/com/reverse/agent/MatchRule.java` | 定义锚点、方法形状过滤器和动作 |
| `src/main/java/com/reverse/agent/FingerprintTransformer.java` | 运行时匹配与字节码改写 |
| `src/main/java/com/reverse/agent/BytecodeVisitors.java` | 方法体替换实现 |
| `src/main/java/com/reverse/agent/OfflineScanner.java` | 离线扫描报告、补丁 JAR 生成与命中量级自检 |
| `src/main/java/com/reverse/agent/BytecodeVerifier.java` | 逐方法数据流校验，捕获会触发 VerifyError 的非法字节码 |
| `src/main/java/com/reverse/decompiler/JarDecompiler.java` | 基于 CFR 的 JAR/class 反编译工具 |

## 默认规则

授权体系最终收敛到少数几个**混淆稳定**的语义闸门，因此默认规则集只保留 3 条，直接改裁决源头，而不是去逐个短路散落的验证过程方法（后者锚在业务明文串上，目标重新混淆就失效，维护成本高）。

| 规则 | 语义闸门 | 锚点 | 动作 |
|---|---|---|---|
| `force-license-verdict` | Gate A 裁决 DTO | `@SerializedName` 同时含 `valid`+`validTo`+`paidKey` | 强制授权值 |
| `force-profile-valid` | Gate B 全局状态 | Kotlin metadata 中 `getValid`+`getUseFreeVersion`+`getAlreadyTrail` 共存 | 强制授权值 |
| `deserialize-validate-json` | RSA 旁路 | `gson catch exception, the json string is` | Gson 反序列化 |

三条规则的分工：

- **Gate A（主力）**：所有验证路径最终都把服务器/本地数据反序列化成裁决 DTO，再读 `getValid()`/`getValidTo()`。`@SerializedName` 是服务器通信契约，混淆改不动。强制 `getValid()` 返回 `true`、`getValidTo()` 返回远期时间戳，从根上让裁决恒为“已授权”。这一条同时解决了「杀单开关」——复验读到的 `valid` 永远为真、`validTo` 永远未过期，本地密钥文件因此不会被删除。
- **Gate B**：Kotlin 全局授权态 `Profile`，`getValid` 等 getter 直接被 UI/功能判断读取，强制为“已授权”。锚点是 Kotlin `@Metadata`，同样混淆免疫。
- **RSA 旁路**：粘贴的明文激活 JSON 不是密文，正常路径 `Base64+RSA` 解密必抛异常，激活在读到 Gate A 之前就崩溃。这条规则把解密方法改成直接 `gson` 反序列化，让明文 JSON 能被解析成裁决 DTO。这是 Gate A/B 无法替代的一环，锚点是异常日志串（相对稳定），标注为过渡锚点。

动作含义：

- `FORCE_GETTER_VALUE`：按无参 getter 的返回形状强制“已授权”值——`()Z`→`true`、`()Ljava/lang/Boolean;`→`Boolean.TRUE`、`()J`/`()Ljava/lang/Long;`→远期时间戳。设了 getter 名单时只改名单内的；否则靠类级序列化契约锁定类，只认返回形状唯一的 Boolean/Long getter（String getter 如 paidKey/userMac 不动）。
- `DESERIALIZE_JSON`：把 String 参数反序列化为方法声明的引用返回类型。
- 另有 `NEUTRALIZE` / `NEUTRALIZE_ALL` / `RETURN_SUCCESS` / `FORCE_TRUE` 等动作保留在 `MatchRule.Action` 中，供自定义规则使用。

命中数量随目标 JAR 版本和混淆结果变化，以 `OfflineScanner` 报告为准。报告尾部会对每条规则做**命中量级自检**：实际命中低于期望即输出「锚点疑似失效」告警，其中 Gate A/B 属核心闸门，未命中会额外醒目提示——目标一旦重新混淆导致锚点失配，第一时间可见，而不必等插件崩溃。

## 构建

需要 JDK 16+ 和 Maven。`BUILD.sh` 会优先使用本机 IntelliJ IDEA 自带的 JBR 和 Maven：

```bash
./BUILD.sh
```

产物：

```text
target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar
```

## 离线扫描

只扫描并输出报告：

```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.agent.OfflineScanner /path/to/target.jar
```

生成补丁副本：

```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.agent.OfflineScanner /path/to/target.jar apply
```

输出文件为 `/path/to/target-patched.jar`，不会覆盖原 JAR。apply 完成后会自动对补丁 JAR 做字节码合法性校验。退出码：`0` 表示有命中，`2` 表示无命中，`3` 表示存在改写失败，`4` 表示补丁字节码校验不通过（会触发 VerifyError）。

## 字节码校验

对任意 JAR 逐方法做数据流校验，捕获会在类加载期触发 `VerifyError` 的非法字节码（`apply` 已内置调用，也可单独运行）：

```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.agent.BytecodeVerifier /path/to/target-patched.jar
```

用 ASM `BasicVerifier`，不加载被引用类型，因此不会因目标 JAR 的 IntelliJ/Kotlin 依赖缺失而产生假阳性。退出码：`0` 全部合法，`4` 存在非法方法。

## 挂载 Agent

先建议用 `OfflineScanner` 做 dry-run 验证。确认后编辑 IntelliJ 的 `idea.vmoptions`：

```text
-javaagent:/absolute/path/to/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar
```

只扫描不改写：

```text
-javaagent:/absolute/path/to/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar
-Dagent.dryRun=true
```

禁用规则：

```text
-Dagent.rulesEnabled=false
```

启动后可在日志中查看 `📷 [HIT]`、`🔧 [PATCH]` 和命中汇总。

## 反编译

```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.decompiler.JarDecompiler /path/to/input.jar ./decompiled
```

省略输出目录时，默认输出到当前目录下的 `.decompiled`。
