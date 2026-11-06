#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVAC_VERSION="$(javac -version 2>&1 || true)"
if [[ ! "$JAVAC_VERSION" =~ javac[[:space:]]+([0-9]+) ]] || (( BASH_REMATCH[1] < 19 )); then
  if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 19)"
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
    printf '使用本机 JDK 19：%s\n' "$JAVA_HOME"
  else
    printf '需要 JDK 19 或更高版本，请设置 JAVA_HOME 后重试。\n' >&2
    exit 1
  fi
fi

MAVEN="${MVN:-mvn}"
if ! command -v "$MAVEN" >/dev/null 2>&1; then
  printf '找不到 Maven，请安装 Maven 或用 MVN 指定 mvn 可执行文件的完整路径。\n' >&2
  exit 1
fi

MAVEN_OPTIONS=()
if [[ "${SKILL_DEMO_OFFLINE:-0}" == "1" ]]; then
  MAVEN_OPTIONS+=(-o)
fi

"$MAVEN" ${MAVEN_OPTIONS[@]+"${MAVEN_OPTIONS[@]}"} -pl ai-rec-odin-demo-main -am clean compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath \
  -DincludeScope=runtime \
  -DincludeGroupIds=org.springframework,org.springframework.boot,org.springframework.ai,org.springframework.retry,com.fasterxml.jackson.core,com.fasterxml.jackson.datatype,com.fasterxml.jackson.module,com.fasterxml.jackson.dataformat,org.apache.tomcat.embed,org.apache.logging.log4j,org.slf4j,io.micrometer,jakarta.annotation,io.projectreactor,org.reactivestreams,com.github.victools,com.fasterxml,org.antlr,org.yaml,com.knuddels \
  -DexcludeArtifactIds=slf4j-reload4j \
  -Dmdep.outputFile=target/skill-demo.classpath

CLASSPATH="$ROOT/ai-rec-odin-demo-main/target/classes:$ROOT/ai-rec-odin-demo-app/target/classes:$ROOT/ai-rec-odin-demo-web/target/classes"
CLASSPATH="$CLASSPATH:$(cat ai-rec-odin-demo-main/target/skill-demo.classpath)"
printf '\n启动对话与 Skill 工作空间：\n  http://127.0.0.1:8080/skills.html\n（可通过 --server.port 修改端口）\n'
exec java -Dfile.encoding=UTF-8 -cp "$CLASSPATH" \
  com.jd.diviner.ai.rec.odin.demo.AiRecOdinDemoApplication "$@"
