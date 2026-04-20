param(
    [Parameter(Mandatory = $false)]
    [string]$CasesPath = ".\cases.csv",

    [Parameter(Mandatory = $false)]
    [string]$BaseUrl = "http://localhost:8080",

    [Parameter(Mandatory = $false)]
    [string]$OutputDir = ".",

    [Parameter(Mandatory = $false)]
    [string]$AuthToken = "",

    [Parameter(Mandatory = $false)]
    [bool]$SkipTlsCheck = $true,

    [Parameter(Mandatory = $false)]
    [int]$TimeoutSec = 60,

    [Parameter(Mandatory = $false)]
    [int]$PauseMs = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Initialize-Tls {
    param([bool]$SkipCertCheck)

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    if ($SkipCertCheck) {
        [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
    }
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percent
    )

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return 0
    }

    $sorted = $Values | Sort-Object
    if ($sorted.Count -eq 1) {
        return [math]::Round($sorted[0], 2)
    }

    $rank = ($Percent / 100.0) * ($sorted.Count - 1)
    $low = [math]::Floor($rank)
    $high = [math]::Ceiling($rank)

    if ($low -eq $high) {
        return [math]::Round($sorted[$low], 2)
    }

    $weight = $rank - $low
    $value = $sorted[$low] + ($sorted[$high] - $sorted[$low]) * $weight
    return [math]::Round($value, 2)
}

function Get-TopReasons {
    param(
        [object[]]$Rows,
        [int]$TopN = 3
    )

    $reasons = $Rows |
        Where-Object { -not $_.hard_pass -and $_.fail_reason -and $_.fail_reason.Trim().Length -gt 0 } |
        Group-Object -Property fail_reason |
        Sort-Object -Property Count -Descending |
        Select-Object -First $TopN

    if ($null -eq $reasons -or $reasons.Count -eq 0) {
        return "none"
    }

    return ($reasons | ForEach-Object { "{0}({1})" -f $_.Name, $_.Count }) -join ", "
}

function ConvertTo-OrderedPoiIds {
    param([object[]]$RouteItems)

    if ($null -eq $RouteItems -or $RouteItems.Count -eq 0) {
        return @()
    }

    return $RouteItems |
        Sort-Object -Property visitOrder |
        ForEach-Object {
            if ($null -eq $_.poiId) { "" } else { [string]$_.poiId }
        } |
        Where-Object { $_ -and $_.Trim().Length -gt 0 }
}

function Test-HardPass {
    param([object[]]$RouteItems)

    if ($null -eq $RouteItems -or $RouteItems.Count -eq 0) {
        return @{ pass = $false; reason = "empty_route_items" }
    }

    $orders = @{}
    foreach ($item in $RouteItems) {
        if ($null -eq $item.visitOrder -or [int]$item.visitOrder -le 0) {
            return @{ pass = $false; reason = "invalid_visit_order" }
        }
        if ($orders.ContainsKey([int]$item.visitOrder)) {
            return @{ pass = $false; reason = "duplicated_visit_order" }
        }
        $orders[[int]$item.visitOrder] = $true

        if ($null -eq $item.poiId -or ([string]$item.poiId).Trim().Length -eq 0) {
            return @{ pass = $false; reason = "invalid_poi_id" }
        }

        if ($null -eq $item.recommendedDuration -or [int]$item.recommendedDuration -le 0) {
            return @{ pass = $false; reason = "invalid_recommended_duration" }
        }

        if ($item.visitTime -and ([string]$item.visitTime).Trim().Length -gt 0) {
            try {
                [DateTime]::Parse(([string]$item.visitTime)) | Out-Null
            }
            catch {
                return @{ pass = $false; reason = "invalid_visit_time" }
            }
        }
    }

    return @{ pass = $true; reason = "" }
}

if (-not (Test-Path -Path $CasesPath)) {
    throw "cases.csv not found: $CasesPath"
}

if (-not (Test-Path -Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Initialize-Tls -SkipCertCheck:$SkipTlsCheck

$headers = @{}
if ($AuthToken -and $AuthToken.Trim().Length -gt 0) {
    $headers["Authorization"] = "Bearer $AuthToken"
}

$cases = Import-Csv -Path $CasesPath
if ($null -eq $cases -or $cases.Count -eq 0) {
    throw "cases.csv is empty"
}

$runId = Get-Date -Format "yyyyMMdd_HHmmss"
$resultPath = Join-Path $OutputDir "result_$runId.csv"
$summaryPath = Join-Path $OutputDir "summary_$runId.txt"

$results = New-Object System.Collections.Generic.List[object]
$lastPoiByMemory = @{}

foreach ($case in $cases) {
    $caseId = [string]$case.case_id
    $memoryId = [string]$case.memory_id
    $message = [string]$case.message
    $expectChangeRaw = [string]$case.expect_change
    $expectChange = ($expectChangeRaw -eq "1" -or $expectChangeRaw.ToLowerInvariant() -eq "true")

    $timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
    $requestOk = $false
    $hardPass = $false
    $failReason = ""
    $routeId = ""
    $latencyMs = 0
    $poiCount = 0
    $finalPoiIds = @()
    $changeEffective = "NA"

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if (-not $memoryId -or $memoryId.Trim().Length -eq 0) {
            throw "memory_id is empty"
        }
        if (-not $message -or $message.Trim().Length -eq 0) {
            throw "message is empty"
        }

        $encodedMessage = [System.Uri]::EscapeDataString($message)
        $generateUrl = "$BaseUrl/routes/ai/${memoryId}?message=${encodedMessage}"
        $genResp = Invoke-RestMethod -Method Post -Uri $generateUrl -Headers $headers -TimeoutSec $TimeoutSec

        if ($null -eq $genResp -or $null -eq $genResp.code -or [int]$genResp.code -ne 1) {
            $apiMsg = if ($null -eq $genResp) { "null_response" } else { [string]$genResp.msg }
            throw "generate_failed:$apiMsg"
        }

        $routeId = [string]$genResp.data
        if (-not $routeId -or $routeId.Trim().Length -eq 0) {
            throw "empty_route_id"
        }

        $detailUrl = "$BaseUrl/routes/$routeId"
        $detailResp = Invoke-RestMethod -Method Get -Uri $detailUrl -Headers $headers -TimeoutSec $TimeoutSec

        if ($null -eq $detailResp -or $null -eq $detailResp.code -or [int]$detailResp.code -ne 1) {
            $apiMsg = if ($null -eq $detailResp) { "null_response" } else { [string]$detailResp.msg }
            throw "detail_failed:$apiMsg"
        }

        $routeItems = @($detailResp.data)
        $hardCheck = Test-HardPass -RouteItems $routeItems
        $hardPass = [bool]$hardCheck.pass
        if (-not $hardPass) {
            $failReason = [string]$hardCheck.reason
        }

        $finalPoiIds = ConvertTo-OrderedPoiIds -RouteItems $routeItems
        $poiCount = $finalPoiIds.Count

        if ($expectChange) {
            if ($lastPoiByMemory.ContainsKey($memoryId)) {
                $previous = [string]$lastPoiByMemory[$memoryId]
                $current = ($finalPoiIds -join "|")
                $changeEffective = if ($previous -ne $current) { "1" } else { "0" }
            }
            else {
                $changeEffective = "NA"
            }
        }

        $lastPoiByMemory[$memoryId] = ($finalPoiIds -join "|")
        $requestOk = $true

        if ($requestOk -and -not $hardPass -and (-not $failReason -or $failReason.Trim().Length -eq 0)) {
            $failReason = "hard_check_failed"
        }
    }
    catch {
        $requestOk = $false
        $hardPass = $false
        $failReason = $_.Exception.Message
    }
    finally {
        $sw.Stop()
        $latencyMs = [int]$sw.ElapsedMilliseconds
    }

    $results.Add([pscustomobject]@{
            timestamp        = $timestamp
            case_id          = $caseId
            memory_id        = $memoryId
            message          = $message
            expect_change    = if ($expectChange) { 1 } else { 0 }
            request_ok       = if ($requestOk) { 1 } else { 0 }
            hard_pass        = if ($hardPass) { 1 } else { 0 }
            change_effective = $changeEffective
            route_id         = $routeId
            poi_count        = $poiCount
            final_poi_ids    = ($finalPoiIds -join "|")
            latency_ms       = $latencyMs
            fail_reason      = $failReason
        })

    if ($PauseMs -gt 0) {
        Start-Sleep -Milliseconds $PauseMs
    }
}

$results | Export-Csv -Path $resultPath -NoTypeInformation -Encoding UTF8

$total = $results.Count
$hardPassCount = @($results | Where-Object { $_.hard_pass -eq 1 }).Count
$hardPassRate = if ($total -eq 0) { 0 } else { [math]::Round(100.0 * $hardPassCount / $total, 2) }

$changeRows = @($results | Where-Object { $_.expect_change -eq 1 -and $_.change_effective -ne "NA" })
$changeTotal = $changeRows.Count
$changeOk = @($changeRows | Where-Object { $_.change_effective -eq "1" }).Count
$changeRate = if ($changeTotal -eq 0) { 0 } else { [math]::Round(100.0 * $changeOk / $changeTotal, 2) }

$latencies = @($results | ForEach-Object { [double]$_.latency_ms })
$p95Latency = Get-Percentile -Values $latencies -Percent 95
$avgPoi = if ($total -eq 0) { 0 } else { [math]::Round((($results | Measure-Object -Property poi_count -Average).Average), 2) }

$topReasons = Get-TopReasons -Rows $results -TopN 3

$summary = @(
    "run_id: $runId",
    "cases_path: $CasesPath",
    "base_url: $BaseUrl",
    "total_cases: $total",
    "hard_pass_rate_pct: $hardPassRate",
    "change_effective_rate_pct: $changeRate",
    "p95_latency_ms: $p95Latency",
    "avg_poi_count: $avgPoi",
    "top_fail_reasons: $topReasons",
    "result_csv: $resultPath"
)

$summary | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host "Done"
Write-Host "result: $resultPath"
Write-Host "summary: $summaryPath"
