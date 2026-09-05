#!/usr/bin/env bash
# 构建 fat agent jar：clean package 后取 target/java-reverse-agent-shaded.jar
set -e
cd "$(dirname "$0")"
idea_jdk="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
idea_maven="/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn"

# 当前项目使用 IDEA 配置的 JDK 25。若本机安装了 IDEA，优先使用它的
# JBR/Maven，避免 PATH 中较旧的系统 JDK/Maven 把构建切回错误环境。
if [[ -x "$idea_jdk/bin/java" ]]; then
  export JAVA_HOME="$idea_jdk"
  export PATH="$idea_jdk/bin:$PATH"
fi

if [[ -x "$idea_maven" ]]; then
  mvn_cmd=("$idea_maven")
elif command -v mvn >/dev/null 2>&1; then
  mvn_cmd=(mvn)
else
  echo "❌ 未找到 Maven（请在 IDEA 中配置 Maven，或将 mvn 加入 PATH）" >&2
  exit 1
fi
"${mvn_cmd[@]}" -q -DskipTests clean package
shaded_jar="$(find target -maxdepth 1 -type f -name 'java-reverse-agent-*-shaded.jar' \
  ! -name 'java-reverse-agent-shaded.jar' -print -quit)"
if [[ -z "$shaded_jar" ]]; then
  echo "❌ 未找到 shade 产物" >&2
  exit 1
fi
if [[ "$shaded_jar" != "target/java-reverse-agent-shaded.jar" ]]; then
  cp "$shaded_jar" target/java-reverse-agent-shaded.jar
fi
echo "✅ 产物: $(pwd)/target/java-reverse-agent-shaded.jar"
echo "   挂载: -javaagent:$(pwd)/target/java-reverse-agent-shaded.jar"
echo "   离线扫描: java -cp target/java-reverse-agent-shaded.jar com.reverse.agent.OfflineScanner <jar>"
echo "   更新Jar: java -cp target/java-reverse-agent-shaded.jar com.reverse.agent.OfflineScanner <jar> apply"
