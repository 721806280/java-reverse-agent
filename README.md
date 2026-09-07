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
| `src/main/java/com/reverse/agent/OfflineScanner.java` | 离线扫描报告与补丁 JAR 生成 |
| `src/main/java/com/reverse/decompiler/JarDecompiler.java` | 基于 CFR 的 JAR/class 反编译工具 |

## 默认规则

当前默认规则集共 7 条：

| 规则 | 锚点 | 动作 |
|---|---|---|
| `neutralize-online-validator` | `mybatispaidinfo/`、`okhttp3/OkHttpClient`、指定方法形状 | 全量短路 |
| `neutralize-online-resp-gate` | `response is not success`、指定方法形状 | 返回成功对象 |
| `deserialize-validate-json` | `gson catch exception, the json string is` | Gson 反序列化 |
| `neutralize-validator-core` | 同一 `()V` 方法内包含 `feimao`、`keyNotExist` 和内嵌公钥 | 清空方法体 |
| `neutralize-piracy-popup` | `brucege.com/pay/view`、`购买正版` | 清空方法体 |
| `neutralize-janetfilter-probe` | `com/bruce/User`、`field not exist` | 清空方法体 |
| `force-valid-true` | Kotlin metadata 中的 `getValid` | 强制返回 `true` |

动作含义：

- `NEUTRALIZE`：清空首个匹配形状的无参方法体。
- `NEUTRALIZE_ALL`：清空规则范围内所有匹配方法；void 直接返回，引用返回 `null`，primitive 返回默认值。
- `RETURN_SUCCESS`：让匹配方法返回一个成功对象。
- `DESERIALIZE_JSON`：把 String 参数反序列化为方法声明的引用返回类型。
- `FORCE_TRUE`：让匹配的 boolean getter 恒返回 `true`。

命中数量随目标 JAR 版本和混淆结果变化，以 `OfflineScanner` 报告为准。

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

输出文件为 `/path/to/target-patched.jar`，不会覆盖原 JAR。退出码：`0` 表示有命中，`2` 表示无命中，`3` 表示存在改写失败。

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

不传参数时会探测默认目录下最新的 `instrumented-MyBatisCodeHelper-Pro*.jar`，并输出到 `.decompiled`。
