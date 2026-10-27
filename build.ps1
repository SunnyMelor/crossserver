# 重新构建 CrossServer 插件：编译 src 下的源码并打包到 ..\plugins\CrossServer-<version>.jar
# 用法：先关闭 Velocity，再在 PowerShell 里执行 .\build.ps1
$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$velocityRoot = Split-Path (Split-Path $root -Parent) -Parent
$velocityJar = Join-Path $velocityRoot 'velocity-4.3.0-44.jar'

$version = '2.0.0'
$manifest = Get-Content (Join-Path $root 'velocity-plugin.json') -Raw | ConvertFrom-Json
if ($manifest.version) { $version = $manifest.version }
$pluginJar = Join-Path $velocityRoot "plugins\CrossServer-$version.jar"

$jdkHome = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
if (-not (Test-Path $jdkHome)) {
    $found = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $found) { throw "找不到 JDK，请修改脚本里的 jdkHome" }
    $jdkHome = $found.FullName
}

$javac = Join-Path $jdkHome 'bin\javac.exe'
$jarTool = Join-Path $jdkHome 'bin\jar.exe'
$build = Join-Path $root 'build'

if (-not (Test-Path $velocityJar)) { throw "找不到 $velocityJar" }

if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build | Out-Null

$sources = Get-ChildItem -Recurse (Join-Path $root 'src') -Filter *.java | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 --release 21 -cp $velocityJar -d $build $sources
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

Copy-Item (Join-Path $root 'velocity-plugin.json') (Join-Path $build 'velocity-plugin.json')

if (Test-Path $pluginJar) {
    try {
        Remove-Item $pluginJar -Force
    } catch {
        throw "无法覆盖 $pluginJar，请先关闭 Velocity 再重新构建"
    }
}
& $jarTool --create --file $pluginJar -C $build .
if ($LASTEXITCODE -ne 0) { throw "打包失败" }

Write-Host "已生成 $pluginJar"
Write-Host "重启 Velocity 后生效"
