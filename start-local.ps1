$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$backend = Join-Path $repo 'interview-agent-java'
$frontend = Join-Path $repo 'interview-agent-web'
$data = Join-Path $backend 'data\local'
New-Item -ItemType Directory -Force -Path $data | Out-Null

# Keep Jetty's temporary files in a writable local path.
$safeTemp = Join-Path $data 'tmp'
New-Item -ItemType Directory -Force -Path $safeTemp | Out-Null
$env:TEMP = $safeTemp
$env:TMP = $safeTemp

# spring-dotenv reads this key from interview-agent-java/.env when the app starts.
$apiKey = $env:DASHSCOPE_API_KEY
if (-not $apiKey) {
    $envFile = Join-Path $backend '.env'
    if (Test-Path $envFile) {
        $line = Get-Content $envFile | Where-Object { $_ -match '^\s*DASHSCOPE_API_KEY\s*=' } | Select-Object -First 1
        if ($line) { $apiKey = ($line -split '=', 2)[1].Trim().Trim('"', "'") }
    }
}
if (-not $apiKey -or $apiKey -eq 'sk-your-api-key') {
    throw '请先在 interview-agent-java/.env 设置 DASHSCOPE_API_KEY，或设置同名环境变量。'
}
Remove-Variable apiKey -ErrorAction SilentlyContinue

$secretFile = Join-Path $data 'jwt-secret.txt'
if (-not (Test-Path $secretFile)) {
    $secretBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($secretBytes)
    [System.IO.File]::WriteAllText($secretFile,
        (-join ($secretBytes | ForEach-Object { $_.ToString('x2') })))
}
$env:JWT_SECRET = [System.IO.File]::ReadAllText($secretFile).Trim()

$localJdk21 = Join-Path $env:USERPROFILE '.jdks\microsoft-21.0.13\bin\java.exe'
$javaExe = if (Test-Path $localJdk21) {
    $localJdk21
} elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $bundled = Get-ChildItem (Join-Path $repo '..\work\jdk21') -Filter java.exe -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundled) { $bundled.FullName } else { (Get-Command java.exe -ErrorAction Stop).Source }
}
$nodeExe = (Get-Command node.exe -ErrorAction Stop).Source
$jar = Join-Path $backend 'target\interview-agent-1.0.0.jar'
if (-not (Test-Path $jar)) { throw "未找到后端 Jar：$jar。请先用 Java 21 执行 mvn clean package。" }
$vite = Join-Path $frontend 'node_modules\vite\bin\vite.js'
if (-not (Test-Path $vite)) { throw '未安装前端依赖。请先在 interview-agent-web 中执行 npm install。' }

$backendPort = Get-NetTCPConnection -LocalPort 9090 -State Listen -ErrorAction SilentlyContinue
if ($backendPort) { throw '9090 端口已有后端进程。请先停止旧进程，再以 API Key 配置启动。' }
$process = Start-Process -FilePath $javaExe -WorkingDirectory $backend -WindowStyle Hidden -PassThru `
    -ArgumentList @('-jar', $jar, '--spring.profiles.active=local', '--server.address=127.0.0.1') `
    -RedirectStandardOutput (Join-Path $data 'backend.stdout.log') `
    -RedirectStandardError (Join-Path $data 'backend.stderr.log')
[System.IO.File]::WriteAllText((Join-Path $data 'backend.pid'), [string]$process.Id)

function Find-FrontendUrl {
    foreach ($candidate in @('http://127.0.0.1:5173/', 'http://[::1]:5173/')) {
        try {
            $response = Invoke-WebRequest $candidate -TimeoutSec 2 -UseBasicParsing
            if ($response.StatusCode -eq 200) { return $candidate }
        } catch { }
    }
    return $null
}

$frontendUrl = Find-FrontendUrl
if (-not $frontendUrl) {
    $process = Start-Process -FilePath $nodeExe -WorkingDirectory $frontend -WindowStyle Hidden -PassThru `
        -ArgumentList @($vite, '--host', '127.0.0.1', '--strictPort') `
        -RedirectStandardOutput (Join-Path $data 'frontend.stdout.log') `
        -RedirectStandardError (Join-Path $data 'frontend.stderr.log')
    [System.IO.File]::WriteAllText((Join-Path $data 'frontend.pid'), [string]$process.Id)
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        Start-Sleep -Seconds 1
        $frontendUrl = Find-FrontendUrl
        if ($frontendUrl) { break }
        if ($process.HasExited) { break }
    }
}

$ready = $false
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    try {
        $response = Invoke-WebRequest 'http://127.0.0.1:9090/health' -TimeoutSec 2 -UseBasicParsing
        if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 1 }
}
if (-not $ready) { throw "后端启动失败，请查看 $data\backend.stderr.log。" }
if (-not $frontendUrl) { throw "前端未就绪，请查看 $data\frontend.stderr.log。" }

Write-Host "已启动： $frontendUrl"
Write-Host "后端：   http://127.0.0.1:9090/health"
Write-Host '模型：   DashScope（API Key）'
