#!/bin/sh

if [ -f /home/admin/default_vm.sh ]; then
  source /home/admin/default_vm.sh
fi

# start.sh所在路径
SHDIR=$(cd "$(dirname "$0")"; pwd)
# 发布包路径
BASEDIR=$(cd $SHDIR/..; pwd)
cd $BASEDIR
[[  $APP_NAME ]] || {
    export APP_NAME="ai-rec-odin-demo-main"
}

# 应用启动日志路径一般建议用/export/log/
LOGDIR=/export/Logs/$APP_NAME
LOGFILE="$LOGDIR/${APP_NAME}_startup.log"
# 应用jar包和conf文件所在路径 最终也要包含在应用进程里，是获取进程的依据
CLASSPATH="$BASEDIR/conf/:$BASEDIR/lib/*"
# MAIN_MODULE根据不同应用自行配置
MAIN_MODULE="com.jd.diviner.ai.rec.odin.demo.AiRecOdinDemoApplication"

echo current path:$BASEDIR

# 创建应用日志目录
if [ ! -d "$LOGDIR" ] ;then
    mkdir "$LOGDIR"
    if [ $? -ne 0 ] ;then
        echo "Cannot create $LOGDIR" >&2
        exit 1
    fi
fi

# 获取进程信息 用的是 java + $CLASSPATH 路径 防止被服务器上其他进程干扰
function get_pid
{
    pgrep -lf "java .* $CLASSPATH"
}

[[ -z $(get_pid) ]] || {
    echo "ERROR:  $APP_NAME already running" >&2
    exit 1
}

echo "Starting $APP_NAME ...."


# pfinder
PFINDER_URI="http://pfinder-master.jd.com/access/script"
if test "$JDOS_ENV" == "test" ;then
  PFINDER_URI="http://test.pfinder-master.jd.com/access/script"
  echo "pfinder: $PFINDER_URI"
fi
curl -s $PFINDER_URI -o /tmp/pfinder.sh ; source /tmp/pfinder.sh || :

# 获取 Java 主版本号
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {split($2, v, "."); print v[1]}')

echo "Current Java version is: ${JAVA_VERSION}"

# 根据 Java 版本添加参数
if [[ "$JAVA_VERSION" -ge 17 ]]; then
# 追加 --add-opens 参数到 JAVA_OPTS
JAVA_OPTS="${JAVA_OPTS} --add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
--add-opens java.base/sun.util.calendar=ALL-UNNAMED \
--add-opens java.base/java.math=ALL-UNNAMED \
--add-opens java.base/sun.security.action=ALL-UNNAMED \
--add-opens java.base/java.time=ALL-UNNAMED \
--add-exports=java.base/sun.net.util=ALL-UNNAMED"
echo "Added JVM options for Java 17+"
fi

echo "Final JVM options: ${JAVA_OPTS}"

echo "Basedir is: ${BASEDIR}"
echo "MAIN_MODULE is: ${MAIN_MODULE}"
echo "JAVA_TOOL_OPTIONS is: ${JAVA_TOOL_OPTIONS}"
echo "LOGFILE is: ${LOGFILE}"

# 无需配置java路径 会自动使用应用在上线全局配置里选的java版本
setsid java ${PFINDER_AGENT:-} $JAVA_OPTS  -classpath "$CLASSPATH" -Dbasedir="$BASEDIR" -Dfile.encoding="UTF-8" ${MAIN_MODULE} > $LOGFILE 2>&1 &

# 如果应用启动较慢 根据实际情况考虑sleep时间
sleep 10

# 判断应用是否启动成功
[[ -n $(get_pid) ]] || {
    echo "ERROR: $APP_NAME failed to start" >&2
    exit 1
}

echo "$APP_NAME is up runnig :)"