[CmdletBinding()]
param(
    [string]$Database = "study_tour",
    [string]$MySqlCommand = "mysql",
    [string]$SqlFile = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$applicationConfig = Join-Path $repoRoot "src\main\resources\application.yaml"
if (-not $SqlFile) {
    $SqlFile = Join-Path $PSScriptRoot "seed_nanjing_projects.sql"
}
$resolvedSqlFile = (Resolve-Path -LiteralPath $SqlFile).Path

if (-not (Test-Path -LiteralPath $applicationConfig)) {
    throw "未找到数据库配置：$applicationConfig"
}

$configLines = Get-Content -Encoding UTF8 -LiteralPath $applicationConfig
$datasourceStart = [Array]::FindIndex(
    $configLines,
    [Predicate[string]] { param($line) $line -match '^\s*datasource:\s*$' }
)
if ($datasourceStart -lt 0) {
    throw "application.yaml 中缺少 spring.datasource 配置"
}

$datasourceLines = $configLines[($datasourceStart + 1)..([Math]::Min($datasourceStart + 8, $configLines.Count - 1))]
$usernameLine = $datasourceLines | Where-Object { $_ -match '^\s*username:\s*' } | Select-Object -First 1
$passwordLine = $datasourceLines | Where-Object { $_ -match '^\s*password:\s*' } | Select-Object -First 1

$databaseUser = if ($env:STUDY_TOUR_DB_USER) {
    $env:STUDY_TOUR_DB_USER
} elseif ($usernameLine) {
    (($usernameLine -split ':', 2)[1].Trim()).Trim('"').Trim("'")
} else {
    "root"
}
$databasePassword = if ($env:STUDY_TOUR_DB_PASSWORD) {
    $env:STUDY_TOUR_DB_PASSWORD
} elseif ($passwordLine) {
    (($passwordLine -split ':', 2)[1].Trim()).Trim('"').Trim("'")
} else {
    ""
}

if (-not (Get-Command $MySqlCommand -ErrorAction SilentlyContinue)) {
    throw "未找到 MySQL 客户端命令：$MySqlCommand"
}

$sourcePath = $resolvedSqlFile.Replace('\', '/')
$previousPassword = $env:MYSQL_PWD
try {
    $env:MYSQL_PWD = $databasePassword
    & $MySqlCommand `
        --default-character-set=utf8mb4 `
        "--user=$databaseUser" `
        "--database=$Database" `
        "--execute=source $sourcePath"
    if ($LASTEXITCODE -ne 0) {
        throw "数据填充失败，mysql 退出码：$LASTEXITCODE"
    }
} finally {
    if ($null -eq $previousPassword) {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    } else {
        $env:MYSQL_PWD = $previousPassword
    }
}
