# ActivationAgent — 基于指纹的 Java 字节码插桩 Agent

不写死类名/方法名，靠 **混淆免疫的语义指纹** 在运行时定位并改写授权验证逻辑。
针对 MyBatisCodeHelper-Pro 的混淆变体，8 条规则命中 26 个类，覆盖本地+联网两条验证链及绑定解绑全场景。

## 核心思想

混淆器能改类名/方法名/字段名，但改不掉这三类语义契约：
1. **Gson `@SerializedName`** 的值（序列化兼容性逼着保留）
2. **Kotlin `@Metadata`** 里编码的 getter 语义名（反射/序列化需要）
3. **业务字符串常量**（URL / 路径 / 用户可见文案）

Agent 的 `ClassFileTransformer` 在每个类加载时扫描这三类特征，按规则集做多级匹配，
命中后用 ASM 改写字节码（空实现校验方法 / 让 boolean getter 恒返回 true）。

## 文件结构

- `ClassFingerprint.java` — 某个类的指纹数据结构
- `FingerprintScanner.java` — ASM 扫描器，提取 @SerializedName / @Metadata / 常量池字符串
- `MatchRule.java` — 规则定义（serial/string/stringContains/getter 多条件 AND）
- `FingerprintTransformer.java` — ClassFileTransformer，匹配+改写
- `ActivationAgent.java` — premain 入口 + 默认规则集
- `OfflineScanner.java` — 离线扫描器（不开 IDE，直接对 JAR 跑规则，验证匹配准确性）
- `AgentLogger.java` — 不依赖日志框架的轻量日志
- `BUILD.sh` — 构建 fat jar 脚本（优先使用 IDEA JBR/Maven）

## 默认规则集

| 规则 | 锚点 | 动作 | 命中类 |
|------|------|------|--------|
| neutralize-online-validator | 含 `mybatispaidinfo/` 子串 + `okhttp3/OkHttpClient` 整串 | NEUTRALIZE_ALL | 2 个联网端点类（k/a 每日校验 + ak/b/a 激活/解锁/解绑） |
| neutralize-bind-unbind | 含 `unbind` 子串 + `paidKey` 整串 | LOG_ONLY | 1 个激活/解绑对话框（仅定位，保留配置/激活 UI） |
| neutralize-online-resp-gate | 含 `response is not success` 整串 | NEUTRALIZE_ALL | 1 个联网响应拉闸类（ak/c，失败时 setValid(false)） |
| neutralize-validator-core | 含 `feimao` + `keyNotExist` 整串 | NEUTRALIZE | 9 个本地校验核心类 |
| neutralize-piracy-popup | 含 `brucege.com/pay/view` + `购买正版` 子串 | NEUTRALIZE | 9 个弹窗内部类 |
| neutralize-janetfilter-probe | 含 `com/bruce/User` + `field not exist` 整串 | NEUTRALIZE | 1 个 janetfilter 反破解探针（l/e） |
| force-valid-true | Kotlin @Metadata 含 `getValid` | FORCE_TRUE | Profile（功能门禁闸门） |
| detect-validate-result | @SerializedName 含 `validTo`+`paidKey` | LOG_ONLY | ValidateNewResult / ValidateNewResultData |

8 条规则共命中 **26 个类**，其中 23 个类实际改写、3 个类仅记录；覆盖本地校验网 + **联网验证 + 绑定解绑** + 状态闸门 + 验证结果 DTO + janetfilter 反破解探针，本地与联网两条验证链均已短路。

**NEUTRALIZE_ALL 动作**：清空类内【所有】void 方法体 + 引用返回方法改为 `return null`（含带参方法），不受"只改第一个"限制。针对联网层——`ak/b/a`、`ak/c` 等类的联网方法都是带参的（如 `a(String)Z`、`a(ak/c/a)ak/d/a`），普通 NEUTRALIZE 只改无参 `()V` 改不到它们。

`neutralize-janetfilter-probe` 针对 `com.ccnode.codegenerator.l.e`——该类用 ASM 在运行时动态生成 `com/bruce/User`，其 `show()` 反射读 `com.janetfilter.plugins.power.ArgsFilter` 的 `l2cached` 字段探测 ja-netfilter 激活框架。`Lcom/janetfilter/...;` 类型引用被指纹过滤规则排除，改用动态类名 `com/bruce/User` + 探针异常文案 `field not exist` 作为锚点（两者均唯一且通过噪声过滤）。运行时该类 `byte[] a()` 探针方法被 `ReturnNullVisitor` 改为 `return null`，调用方 `al/a/a.a()` 收到 null 后 `byArray.length` 抛 NPE 被外层 try-catch 吞掉，探测链短路。

## 构建

```bash
bash BUILD.sh
# 生成 target/java-reverse-agent-shaded.jar（含 ASM + gson）
```

## 使用

**离线验证规则匹配**（不开 IDE）：
```bash
java -cp target/java-reverse-agent-shaded.jar com.reverse.agent.OfflineScanner ~/path/to/target.jar
```

APPLY 会在目标 JAR 同目录生成 `<target.jar>.patched.jar`，不会覆盖原文件。

**挂载到 IntelliJ**：编辑 `~/Library/Application Support/JetBrains/IntelliJIdea2023.x/idea.vmoptions`，加一行：
```
-javaagent:/Users/xiaomingzhang/IdeaProjects/java-reverse-agent/target/java-reverse-agent-shaded.jar
```

**dry-run 模式**（只扫描记录、不改字节码，用于先验证）：
```
-javaagent:.../java-reverse-agent-shaded.jar -Dagent.dryRun=true
```

启动 IDE后在日志里会看到 `📷 [HIT]` 和 `🔧 [PATCH]` 行，确认规则生效。
