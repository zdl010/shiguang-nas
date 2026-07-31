# 把拾光 NAS 打成 Windows 绿色包（jpackage --type app-image）。
# 对应 Unix 侧的 jpackage.sh，设计理由见那个文件的头注释。
#
#   powershell -ExecutionPolicy Bypass -File packaging\jpackage.ps1
#   $env:SKIP_FRONTEND=1; powershell ... # 跳过前端构建
#
# ffmpeg 不需要单独准备：它由 org.bytedeco:ffmpeg 依赖提供，已经在 jar 里了。

$ErrorActionPreference = 'Stop'

$root  = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$build = Join-Path $root 'packaging\build'
$input_ = Join-Path $build 'input'
$dest  = Join-Path $build 'image'

$appName = 'ShiguangNAS'
$jarName = 'shiguang-nas.jar'

# 从 pom.xml 里取版本号：第二个 <version> 是本项目的，第一个是 spring-boot parent 的
$appVersion = (Select-String -Path (Join-Path $root 'pom.xml') -Pattern '<version>(.*?)</version>' -AllMatches |
    ForEach-Object { $_.Matches } | Select-Object -Skip 1 -First 1).Groups[1].Value

Write-Host "==> 打包 $appName $appVersion (windows/$env:PROCESSOR_ARCHITECTURE)"

$mvn = if ($env:MVN) { $env:MVN } else { 'mvn' }

# ── 1. 前端 ────────────────────────────────────────────────────────────
# 必须在 mvn package 之前跑：产物落在 src\main\resources\static\，要被打进 jar
if ($env:SKIP_FRONTEND -ne '1') {
    Write-Host '==> 构建前端'
    Push-Location (Join-Path $root 'frontend')
    try {
        & npm ci --silent
        if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }
        & npm run build
        if ($LASTEXITCODE -ne 0) { throw 'npm run build 失败' }
    }
    finally { Pop-Location }
}
else {
    Write-Host '==> 跳过前端构建（SKIP_FRONTEND=1）'
    if (-not (Test-Path (Join-Path $root 'src\main\resources\static\index.html'))) {
        throw 'static\index.html 不存在，不能跳过前端构建。'
    }
}

# ── 2. 后端 ────────────────────────────────────────────────────────────
Write-Host '==> 构建后端 jar'
Push-Location $root
try {
    & $mvn -q -B clean package
    if ($LASTEXITCODE -ne 0) { throw 'mvn package 失败' }
}
finally { Pop-Location }

$jarPath = Join-Path $root "target\$jarName"
if (-not (Test-Path $jarPath)) { throw "target\$jarName 不存在。" }

# ── 3. 组装 jpackage 输入目录 ──────────────────────────────────────────
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $input_, $dest | Out-Null
Copy-Item $jarPath $input_

$javaOptions = @(
    '--java-options', '-Dfile.encoding=UTF-8'
    '--java-options', '-Dshiguang.packaged=true'
    '--java-options', '-XX:MaxRAMPercentage=50'
    '--java-options', '-XX:-HeapDumpOnOutOfMemoryError'
)

# ── 4. jpackage ────────────────────────────────────────────────────────
# 模块要显式列全：jdeps 推导对 Spring 这种重反射的应用不可靠，
# 漏模块的表现是运行时才 NoClassDefFoundError，很难排查。
$modules = 'java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.localedata,jdk.charsets,jdk.accessibility'

$iconArgs = @()
$icon = Join-Path $root 'packaging\icon.ico'
if (Test-Path $icon) { $iconArgs = @('--icon', $icon) }

Write-Host '==> 执行 jpackage'
$jpackageArgs = @(
    '--type', 'app-image'
    '--name', $appName
    '--app-version', $appVersion
    '--vendor', '拾光'
    '--description', '拾光 NAS · 局域网私有媒体库'
    '--input', $input_
    '--main-jar', $jarName
    '--dest', $dest
    '--add-modules', $modules
    '--jlink-options', '--strip-debug --no-header-files --no-man-pages --compress=zip-6'
    # 保留控制台窗口：局域网访问地址和错误信息都打在这里，
    # 出问题时用户能直接把窗口内容截图发过来。
    '--win-console'
) + $javaOptions + $iconArgs

& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw 'jpackage 失败' }

# ── 5. 压成分发包 ──────────────────────────────────────────────────────
$arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x86_64' }
$baseName = "$appName-$appVersion-windows-$arch"
$zipPath = Join-Path $build "$baseName.zip"

Write-Host '==> 压缩'
Compress-Archive -Path (Join-Path $dest $appName) -DestinationPath $zipPath -Force

Write-Host ''
Write-Host '==> 完成'
Get-Item $zipPath | Format-List Name, Length

Write-Host @'

首次启动提示（务必写进用户文档）：
  · Windows 防火墙会弹窗，必须勾选"专用网络"，否则手机访问不到。
  · 首次启动直接在浏览器里注册管理员，第一个注册的账号即管理员。
  · 未签名的 exe 会触发 SmartScreen，需要点"更多信息 → 仍要运行"。
'@
