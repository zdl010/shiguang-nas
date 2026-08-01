#!/usr/bin/env bash
#
# 把拾光 NAS 打成一个解压即用的绿色包（jpackage --type app-image）。
#
# 为什么是 app-image 而不是 .dmg / .deb：需求第 6 条要求"一个包双击部署"。
# app-image 产出的是自带 JRE 的目录，用户解压后双击启动器即可，
# 不需要预装 Java，也不需要安装器的管理员权限。
#
# 用法：
#   ./packaging/jpackage.sh                 # 完整构建
#   SKIP_FRONTEND=1 ./packaging/jpackage.sh # 跳过前端（前端没改动时省 10 秒）
#
# ffmpeg 不需要单独准备：它由 org.bytedeco:ffmpeg 依赖提供，已经在 jar 里了。
#
# 注意：jpackage 不能交叉编译。macOS 上只能打 macOS 包，
# 三平台产物靠 .github/workflows/release.yml 的 matrix 分别构建。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD="$ROOT/packaging/build"
INPUT="$BUILD/input"
DEST="$BUILD/image"

APP_NAME="ShiguangNAS"
# pom.xml 里第一个 <version> 是 spring-boot parent 的，第二个才是本项目的
PROJECT_VERSION="$(sed -n 's|.*<version>\(.*\)</version>.*|\1|p' "$ROOT/pom.xml" | sed -n 2p)"
JAR_NAME="shiguang-nas.jar"

# macOS 的 CFBundleShortVersionString 要求主版本号 ≥ 1，jpackage 会直接拒掉 0.x。
# 这里只替换**包的版本号**，应用自己报的版本（/api/system/info）仍是 pom 里的真值。
APP_VERSION="${APP_VERSION:-$PROJECT_VERSION}"
if [[ "$(uname -s)" == "Darwin" && "$APP_VERSION" == 0.* ]]; then
  APP_VERSION="1.0.0"
  # 变量一律写成 ${VAR}：macOS 自带的 bash 3.2 在非 UTF-8 locale 下
  # 会把紧跟其后的全角标点当成变量名的一部分，报 unbound variable
  echo "注意：macOS 不接受主版本号为 0 的包版本，包版本记为 ${APP_VERSION}，"
  echo "      应用内显示的版本仍是 ${PROJECT_VERSION}。发正式版时把 pom 升到 1.x，"
  echo "      或用 APP_VERSION=1.2.3 ./packaging/jpackage.sh 覆盖。"
fi

# Maven 位置：优先 $MVN，其次 PATH 上的 mvn，最后仓库自带的 wrapper
MVN="${MVN:-}"
if [[ -z "$MVN" ]]; then
  if command -v mvn > /dev/null 2>&1; then
    MVN=mvn
  elif [[ -x "$ROOT/mvnw" ]]; then
    MVN="$ROOT/mvnw"
  else
    echo "错误：找不到 Maven。装一个，或用 MVN=/path/to/mvn 指定。" >&2
    exit 1
  fi
fi

echo "==> 打包 $APP_NAME $APP_VERSION ($(uname -s)/$(uname -m))"

# ── 1. 前端 ────────────────────────────────────────────────────────────
# 必须在 mvn package 之前跑：产物落在 src/main/resources/static/，要被打进 jar
if [[ "${SKIP_FRONTEND:-}" != "1" ]]; then
  echo "==> 构建前端"
  ( cd "$ROOT/frontend" && npm ci --silent && npm run build )
else
  echo "==> 跳过前端构建（SKIP_FRONTEND=1）"
  if [[ ! -f "$ROOT/src/main/resources/static/index.html" ]]; then
    echo "错误：static/index.html 不存在，不能跳过前端构建。" >&2
    exit 1
  fi
fi

# ── 2. 后端 ────────────────────────────────────────────────────────────
echo "==> 构建后端 jar"
"$MVN" -q -B clean package

if [[ ! -f "$ROOT/target/$JAR_NAME" ]]; then
  echo "错误：target/$JAR_NAME 不存在。" >&2
  exit 1
fi

# ── 3. 组装 jpackage 输入目录 ──────────────────────────────────────────
rm -rf "$BUILD"
mkdir -p "$INPUT" "$DEST"
cp "$ROOT/target/$JAR_NAME" "$INPUT/"

JAVA_OPTIONS=(
  --java-options "-Dfile.encoding=UTF-8"
  # 让应用知道自己是打包运行的：据此决定开托盘、自动开浏览器等桌面行为
  --java-options "-Dshiguang.packaged=true"
  # 家用机内存差异很大，按比例给堆比写死数值稳妥
  --java-options "-XX:MaxRAMPercentage=50"
  # SQLite 是单文件库，崩溃时的堆转储对排查几乎没用，反而会把照片元数据写到磁盘上
  --java-options "-XX:-HeapDumpOnOutOfMemoryError"
)

# ── 4. jpackage ────────────────────────────────────────────────────────
# 运行时模块要显式列全。默认的 jdeps 推导对 Spring 这种重反射的应用不可靠，
# 漏掉一个模块的表现是运行时才 NoClassDefFoundError，很难排查。
MODULES="java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.localedata,jdk.charsets,jdk.accessibility"

ICON_ARGS=()
case "$(uname -s)" in
  Darwin) [[ -f "$ROOT/packaging/icon.icns" ]] && ICON_ARGS=(--icon "$ROOT/packaging/icon.icns") ;;
  Linux)  [[ -f "$ROOT/packaging/icon.png"  ]] && ICON_ARGS=(--icon "$ROOT/packaging/icon.png")  ;;
esac

echo "==> 执行 jpackage"
jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --vendor "拾光" \
  --description "拾光 NAS · 局域网私有媒体库" \
  --input "$INPUT" \
  --main-jar "$JAR_NAME" \
  --dest "$DEST" \
  --add-modules "$MODULES" \
  --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=zip-6" \
  "${JAVA_OPTIONS[@]}" \
  ${ICON_ARGS[@]+"${ICON_ARGS[@]}"}
  # ICON_ARGS 用 ${arr[@]+...} 展开：macOS 自带的是 bash 3.2，
  # 在 set -u 下直接写 "${ICON_ARGS[@]}" 展开空数组会报 unbound variable

# ── 5. 压成分发包 ──────────────────────────────────────────────────────
OS_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"
# Linux 的 uname -m 报 aarch64，macOS 报 arm64，是同一种架构的两个叫法。
# 统一成 arm64，免得发布页上出现 darwin-arm64 和 linux-aarch64 两种写法。
ARCH_TAG="$(uname -m)"
[[ "$ARCH_TAG" == "aarch64" ]] && ARCH_TAG="arm64"
BASENAME="$APP_NAME-$PROJECT_VERSION-$OS_TAG-$ARCH_TAG"

cd "$DEST"
if [[ "$OS_TAG" == "darwin" ]]; then
  # -y 保留符号链接，JRE 里有一堆；用 -r 会把包撑大且破坏签名
  ditto -c -k --sequesterRsrc --keepParent "$APP_NAME.app" "$ROOT/packaging/build/$BASENAME.zip"
else
  tar -czf "$ROOT/packaging/build/$BASENAME.tar.gz" "$APP_NAME"
fi

echo
echo "==> 完成"
ls -lh "$ROOT/packaging/build/$BASENAME."* 2>/dev/null || true
cat <<EOF

产物：packaging/build/$BASENAME.*

macOS 提示：未签名 / 未公证的 .app 双击会被 Gatekeeper 拦下，
提示"无法打开，因为无法验证开发者"。自用可右键 → 打开，或
  xattr -dr com.apple.quarantine ShiguangNAS.app
正式分发需要 99 美元/年的开发者账号做签名和公证（见 PROGRESS.md M5）。
EOF
