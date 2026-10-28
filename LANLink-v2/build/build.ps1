# LANLink build script (Windows PowerShell 5.1)
# Usage: powershell -ExecutionPolicy Bypass -File build\build.ps1
#
# Flow: compile -> self test -> jar -> jpackage (build\stage2) -> (package.py assembles the zip)
param(
    [switch]$SkipTests,
    [string]$Jdk = "E:\develop\jdk\jdk-17",
    [string]$AppVersion = "2.1.0"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root "build"
$classes = Join-Path $build "classes"
$testClasses = Join-Path $build "test-classes"
$inputDir = Join-Path $build "input2"
$stage = Join-Path $build "stage2"
$appOut = Join-Path $stage "LANLink"

function Step($msg) { Write-Output ("[STEP] " + $msg) }

Step "clean"
foreach ($d in @($classes, $testClasses, $inputDir, $stage)) {
    if (Test-Path $d) { Remove-Item -Recurse -Force $d -ErrorAction SilentlyContinue }
}
New-Item -ItemType Directory -Force -Path $classes, $testClasses, $inputDir | Out-Null

Step "deps: zxing"
$py0 = "C:\Users\lenovo\.workbuddy\binaries\python\versions\3.13.12\python.exe"
$zx = Join-Path $root "lib\zxing-core-3.5.3.jar"
if (Test-Path $py0) {
    & $py0 (Join-Path $PSScriptRoot "fetch-zxing.py") | Out-Null
}
if (-not (Test-Path $zx)) { throw "lib/zxing-core-3.5.3.jar missing (二维码依赖)" }

# --- 编译主程序（参数文件用 ANSI 编码 + 正斜杠，绕开 javac 对中文路径的编码问题）---
Step "compile main"
$mainList = Join-Path $build "sources-main.txt"
(Get-ChildItem -Path (Join-Path $root "src\main\java") -Recurse -Filter *.java |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }) | Set-Content -Encoding Default $mainList
& (Join-Path $Jdk "bin\javac.exe") -encoding UTF-8 -cp $zx -d $classes ("@" + $mainList)
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# --- 编译测试 ---
Step "compile tests"
$testList = Join-Path $build "sources-test.txt"
(Get-ChildItem -Path (Join-Path $root "src\test\java") -Recurse -Filter *.java |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }) | Set-Content -Encoding Default $testList
& (Join-Path $Jdk "bin\javac.exe") -encoding UTF-8 -cp "$classes;$zx" -d $testClasses ("@" + $testList)
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

# --- 静态检查：声明却未赋值的 UI 字段（javac 查不出，运行即崩）---
Step "static check: unassigned UI fields"
$py = "C:\Users\lenovo\.workbuddy\binaries\python\versions\3.13.12\python.exe"
if (Test-Path $py) {
    & $py (Join-Path $PSScriptRoot "check-null-fields.py") (Join-Path $root "src\main\java\lanlink\ui\AppFrame.java")
    if ($LASTEXITCODE -ne 0) { throw "static check failed: unassigned UI field" }
} else {
    Write-Output "  (python not found, skip static check)"
}

# --- 自检 ---
if (-not $SkipTests) {
    Step "run self tests"
    & (Join-Path $Jdk "bin\java.exe") "-Dfile.encoding=UTF-8" -cp "$classes;$testClasses;$zx" lanlink.Tests
    if ($LASTEXITCODE -ne 0) { throw "self tests failed" }

    # 界面冒烟测试：无头构建整个界面并遍历组件树。
    # 曾出现 2116 项逻辑断言全过、程序却因一个未赋值控件启动即崩的情况 ——
    # 因为逻辑自检从未构造过界面。这一项就是那道防线。
    Step "run UI smoke test"
    & (Join-Path $Jdk "bin\java.exe") "-Djava.awt.headless=true" "-Dfile.encoding=UTF-8" -cp "$classes;$testClasses;$zx" lanlink.UiSmokeTest
    if ($LASTEXITCODE -ne 0) { throw "UI smoke test failed" }

    # 真实数据夹具测试：用实际抓取到的内核输出验证成员列表解析。
    # 关键：夹具来自「强制中继」模式（等价于异地对称 NAT）的真实 peer JSON，
    # 而非编造的输入 —— 编造的输入曾让解析缺陷长期未被发现。
    Step "run fixture test"
    Push-Location $root
    & (Join-Path $Jdk "bin\java.exe") "-Dfile.encoding=UTF-8" -cp "$classes;$testClasses;$zx" lanlink.FixtureTest
    Pop-Location
    if ($LASTEXITCODE -ne 0) { throw "fixture test failed" }

    # 中继端到端测试：起两个真实内核，禁用 P2P 与打洞（模拟异地对称 NAT），
    # 验证双方能经社区公共节点互相发现 —— 这正是用户反馈出问题的场景。
    Step "run relay e2e test"
    Push-Location $root
    & (Join-Path $Jdk "bin\java.exe") "-Dfile.encoding=UTF-8" "-Dlanlink.home=$root" -cp "$classes;$testClasses;$zx" lanlink.RelayE2ETest
    Pop-Location
    if ($LASTEXITCODE -ne 0) { throw "relay e2e test failed" }

    # 界面布局测试：窗口不得超出屏幕可用区域（标题栏与关闭按钮须可见），
    # 内容超高时必须可滚动。此前窗口写死 820x720，在 1366x768 屏幕上
    # 关闭按钮会跑到屏幕外，且建房页底部按钮被裁掉。
    Step "run layout test"
    & (Join-Path $Jdk "bin\java.exe") "-Djava.awt.headless=true" "-Dfile.encoding=UTF-8" -cp "$classes;$testClasses;$zx" lanlink.LayoutProbe
    if ($LASTEXITCODE -ne 0) { throw "layout test failed" }

    # 二维码回环：生成后再解码，必须与原文完全一致。
    # 只断言「图片非空」会漏掉静区缺失、尺寸算错这类「能生成但扫不出来」的缺陷。
    Step "run qr round trip"
    & (Join-Path $Jdk "bin\java.exe") "-Dfile.encoding=UTF-8" -cp "$classes;$testClasses;$zx" lanlink.QrRoundTrip
    if ($LASTEXITCODE -ne 0) { throw "qr round trip failed" }
}

# --- 图标 ---
Step "make icon"
$ico = Join-Path $build "icon.ico"
if (Test-Path $py) {
    & $py (Join-Path $PSScriptRoot "make-icon.py") | Out-Null
} else {
    Write-Output "  (python not found, skip icon)"
}

# --- 打包 jar（切到 build 目录用相对路径，规避中文路径问题）---
Step "build jar"
Push-Location $build
$zxDir = Join-Path $build "zxing-unpacked"
if (Test-Path $zxDir) { Remove-Item -Recurse -Force $zxDir -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Force -Path $zxDir | Out-Null
if (Test-Path $py0) {
    & $py0 (Join-Path $PSScriptRoot "unpack-jar.py") $zx $zxDir | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "unpack zxing failed" }
} else { throw "python required to unpack zxing" }
& (Join-Path $Jdk "bin\jar.exe") --create --file "input2\LANLink.jar" --main-class lanlink.Main -C classes . -C $zxDir .
$jarRc = $LASTEXITCODE
Pop-Location
if ($jarRc -ne 0) { throw "jar failed" }

# --- 拷内核 ---
Step "copy engine"
$engineSrc = Join-Path $root "engine"
$engineDst = Join-Path $inputDir "engine"
New-Item -ItemType Directory -Force -Path $engineDst | Out-Null
if (Test-Path $engineSrc) {
    Copy-Item -Path (Join-Path $engineSrc "*") -Destination $engineDst -Recurse -Force -ErrorAction SilentlyContinue
    $coreExe = Join-Path $engineDst "easytier-core.exe"
    if (Test-Path $coreExe) {
        $mb = [math]::Round((Get-Item $coreExe).Length / 1MB, 1)
        Write-Output ("  core = " + $mb + " MB")
    } else {
        throw "easytier-core.exe missing in engine dir"
    }
} else {
    throw "engine dir missing"
}

# --- jpackage ---
Step "jpackage"
$jpArgs = @(
    "--type", "app-image",
    "--name", "LANLink",
    "--app-version", $AppVersion,
    "--input", "input2",
    "--dest", "stage2",
    "--main-jar", "LANLink.jar",
    "--main-class", "lanlink.Main",
    "--vendor", "LANLink",
    "--description", "LANLink LAN Party Helper",
    "--java-options", "-Xmx256m",
    "--java-options", "-Dfile.encoding=UTF-8"
)
if (Test-Path $ico) { $jpArgs += @("--icon", "icon.ico") }
Push-Location $build
& (Join-Path $Jdk "bin\jpackage.exe") @jpArgs
$jpRc = $LASTEXITCODE
Pop-Location
if ($jpRc -ne 0) { throw "jpackage failed" }

if (-not (Test-Path (Join-Path $appOut "LANLink.exe"))) { throw "LANLink.exe not produced" }
$total = (Get-ChildItem -Recurse $appOut | Measure-Object -Property Length -Sum).Sum
Write-Output ("[DONE] app-image = " + $appOut + "  (" + [math]::Round($total / 1MB, 1) + " MB)")
Write-Output "[NEXT] python build\package.py build\stage2\LANLink"
