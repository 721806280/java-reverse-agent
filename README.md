# ActivationAgent — 基于指纹的 Java 字节码插桩 Agent

不写死目标类名/方法名，靠 **混淆免疫的语义指纹** 在运行时定位并改写授权验证逻辑。
规则锚点只使用序列化契约、Kotlin metadata、业务字符串和方法形状，适配混淆包名/类名每次变化的目标 JAR。

## 核心思想

混淆器能改类名/方法名/字段名，但改不掉这三类语义契约：
1. **Gson `@SerializedName`** 的值（序列化兼容性逼着保留）
2. **Kotlin `@Metadata`** 里编码的 getter 语义名（反射/序列化需要）
3. **业务字符串常量**（URL / 路径 / 用户可见文案）

Agent 的 `ClassFileTransformer` 在每个类加载时扫描这三类特征，按规则集做多级匹配，
命中后用 ASM 改写字节码（空实现校验方法 / 让 boolean getter 恒返回 true）。

## 结构

- `ActivationAgent` — agent 入口和默认规则集
- `FingerprintScanner` — 提取序列化契约、Kotlin metadata 和业务字符串
- `MatchRule` — 语义锚点和方法形状规则
- `FingerprintTransformer` — 运行时匹配和改写
- `OfflineScanner` — 离线验证或生成补丁副本
- `BytecodeVisitors` — 方法体替换逻辑

## 默认规则集

| 规则 | 锚点 | 动作 | 目标 |
|------|------|------|--------|
| neutralize-online-validator | 含 `mybatispaidinfo/` 子串 + `okhttp3/OkHttpClient` 整串 | NEUTRALIZE_ALL | 联网端点类 |
| neutralize-bind-unbind | 含 `unbind` 子串 + `paidKey` 整串 | LOG_ONLY | 激活/解绑对话框 |
| neutralize-online-resp-gate | 含 `response is not success` 整串 | RETURN_SUCCESS | 联网响应闸 |
| neutralize-validator-core | 含 `feimao` + `keyNotExist` 整串 | NEUTRALIZE | 本地校验核心类 |
| neutralize-piracy-popup | 含 `brucege.com/pay/view` + `购买正版` 子串 | NEUTRALIZE | 弹窗内部类 |
| neutralize-janetfilter-probe | 含 `com/bruce/User` + `field not exist` 整串 | NEUTRALIZE | 反破解探针 |
| force-valid-true | Kotlin @Metadata 含 `getValid` | FORCE_TRUE | 功能门禁闸门 |
| detect-validate-result | @SerializedName 含 `validTo`+`paidKey` | LOG_ONLY | 验证结果 DTO |

命中数量随目标 JAR 版本和混淆结果变化，以 `OfflineScanner` 报告为准。规则覆盖本地校验网、联网验证、绑定解绑、状态闸门、验证结果 DTO 和反破解探针。

**NEUTRALIZE_ALL 动作**：清空规则范围内所有方法体：void 直接返回，引用返回 `null`，boolean 返回 `true`，其它 primitive 返回默认值。方法形状只比较参数数量和返回类型，不包含混淆后的包名/类名。

`neutralize-janetfilter-probe` 使用运行时动态类名 `com/bruce/User` 和探针异常文案 `field not exist` 作为锚点。命中后无参引用返回方法被替换为 `return null`，探测链由调用方已有的异常处理短路。

## 构建

```bash
bash BUILD.sh
```

产物按版本命名为：

```text
target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar
```

## 反编译

```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.decompiler.JarDecompiler <jar-or-class-path> [输出目录]
```

## 使用

**离线验证规则匹配**（不开 IDE）：
```bash
java -cp target/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar \
  com.reverse.agent.OfflineScanner ~/path/to/target.jar
```

APPLY 会在目标 JAR 同目录生成 `<target.jar>.patched.jar`，不会覆盖原文件。

**挂载到 IntelliJ**：编辑 `idea.vmoptions`，加一行：
```
-javaagent:/absolute/path/to/java-reverse-agent-0.0.1-SNAPSHOT-shaded.jar
```

**dry-run 模式**（只扫描记录、不改字节码，用于先验证）：
```
-javaagent:.../java-reverse-agent-shaded.jar -Dagent.dryRun=true
```

启动 IDE后在日志里会看到 `📷 [HIT]` 和 `🔧 [PATCH]` 行，确认规则生效。
