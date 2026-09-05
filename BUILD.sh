#!/usr/bin/env bash
# Build the shaded agent jar with the local IDEA JDK/Maven when available.
set -e
cd "$(dirname "$0")"
idea_jdk="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
idea_maven="/Applications/IntelliJ IDEA.app/Contents/plugins/maven-plugin/lib/maven3/bin/mvn"

if [[ -x "$idea_jdk/bin/java" ]]; then
  export JAVA_HOME="$idea_jdk"
  export PATH="$idea_jdk/bin:$PATH"
fi

if [[ -x "$idea_maven" ]]; then
  mvn_cmd=("$idea_maven")
elif command -v mvn >/dev/null 2>&1; then
  mvn_cmd=(mvn)
else
  echo "未找到 Maven；请配置 IDEA Maven 或将 mvn 加入 PATH" >&2
  exit 1
fi
"${mvn_cmd[@]}" -q -DskipTests clean package
shaded_jar="$(find target -maxdepth 1 -type f -name '*-shaded.jar' -print -quit)"
[[ -n "$shaded_jar" ]] || { echo "未找到 shade 产物" >&2; exit 1; }
echo "产物: $(pwd)/$shaded_jar"
echo "挂载: -javaagent:$(pwd)/$shaded_jar"
echo "扫描: java -cp $shaded_jar com.reverse.agent.OfflineScanner <jar> [apply]"
