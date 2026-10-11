$ErrorActionPreference = "Stop"

$dbHost = "localhost"
$dbPort = 3306
$dbName = "study_tour"
$dbUser = "root"
$dbPass = "123456"
$amapKey = $env:AMAP_KEY

if ([string]::IsNullOrWhiteSpace($amapKey)) {
    throw "AMAP_KEY is empty."
}

function Escape-Sql([string]$s) {
    if ($null -eq $s) { return "" }
    return $s.Replace("'", "''")
}

function To-Int($v) {
    if ($null -eq $v) { return -1 }
    $n = 0
    if ([int]::TryParse([string]$v, [ref]$n)) { return $n }
    return -1
}

$tagsRaw = & mysql -h $dbHost -P $dbPort -u $dbUser "-p$dbPass" -D $dbName -N -B -e "SELECT name FROM tags WHERE id BETWEEN 1 AND 15 ORDER BY id;"
if ($LASTEXITCODE -ne 0) { throw "Failed to query tags." }
$tags = $tagsRaw | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($tags.Count -ne 15) { throw "Expected 15 tags, got $($tags.Count)." }

$attrRaw = & mysql -h $dbHost -P $dbPort -u $dbUser "-p$dbPass" -D $dbName -N -B -e "SELECT poi_id,name,location,citycode,adcode FROM attractions WHERE adcode LIKE '3201%' AND location IS NOT NULL AND location <> '' ORDER BY poi_id;"
if ($LASTEXITCODE -ne 0) { throw "Failed to query attractions." }

$attractions = @()
foreach ($line in $attrRaw) {
    $parts = $line -split "`t"
    if ($parts.Count -lt 5) { continue }
    $poiId = $parts[0]
    $name = $parts[1]
    $location = $parts[2]
    $citycode = $parts[3]
    $adcode = $parts[4]
    if ([string]::IsNullOrWhiteSpace($poiId) -or [string]::IsNullOrWhiteSpace($location)) { continue }
    $attractions += [PSCustomObject]@{
        poi_id = $poiId
        name = $name
        location = $location
        citycode = $citycode
        adcode = $adcode
    }
}

if ($attractions.Count -lt 10) {
    throw "Not enough Nanjing attractions. found=$($attractions.Count)"
}

$insertRows = @()
$usedPairs = New-Object System.Collections.Generic.HashSet[string]

for ($i = 0; $i -lt $tags.Count; $i++) {
    $tag = $tags[$i]
    for ($k = 0; $k -lt 3; $k++) {
        $fromIdx = ($i * 3 + $k * 2) % $attractions.Count
        $toIdx = ($fromIdx + 1 + (($i + $k) % 3)) % $attractions.Count
        if ($toIdx -eq $fromIdx) { $toIdx = ($toIdx + 1) % $attractions.Count }

        $from = $attractions[$fromIdx]
        $to = $attractions[$toIdx]
        $pairKey = "$($from.poi_id)->$($to.poi_id)"
        if ($usedPairs.Contains($pairKey)) {
            $toIdx = ($toIdx + 3) % $attractions.Count
            if ($toIdx -eq $fromIdx) { $toIdx = ($toIdx + 1) % $attractions.Count }
            $to = $attractions[$toIdx]
            $pairKey = "$($from.poi_id)->$($to.poi_id)"
        }
        [void]$usedPairs.Add($pairKey)

        $endpoint = "https://restapi.amap.com/v5/direction/transit/integrated"
        $params = @{
            key = $amapKey
            origin = $from.location
            destination = $to.location
            originpoi = $from.poi_id
            destinationpoi = $to.poi_id
            city1 = $from.citycode
            city2 = $to.citycode
        }
        $query = ($params.GetEnumerator() | ForEach-Object { "{0}={1}" -f $_.Key, [System.Uri]::EscapeDataString([string]$_.Value) }) -join "&"
        $url = "$endpoint`?$query"

        $resp = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 25
        $routeDistance = To-Int $resp.route.distance
        $transits = @($resp.route.transits)
        $transitCount = $transits.Count
        $bestTransitDistance = -1
        $bestWalkingDistance = -1
        $nightFlag = ""
        $lineNames = @()

        if ($transitCount -gt 0) {
            $best = $transits[0]
            $bestTransitDistance = To-Int $best.distance
            $bestWalkingDistance = To-Int $best.walking_distance
            $nightFlag = [string]$best.nightflag
            if ($best.segments) {
                foreach ($seg in $best.segments) {
                    if ($seg.bus -and $seg.bus.buslines) {
                        foreach ($line in $seg.bus.buslines) {
                            $n = [string]$line.name
                            if (-not [string]::IsNullOrWhiteSpace($n)) { $lineNames += $n }
                        }
                    }
                }
            }
        }

        $lineNames = $lineNames | Select-Object -Unique
        $hasMetro = $false
        foreach ($ln in $lineNames) {
            if ($ln -like "*地铁*") { $hasMetro = $true; break }
        }
        $commuteMode = if ($transitCount -le 0) {
            "No stable public transit result, taxi or chartered bus is preferred"
        } elseif ($hasMetro -and $lineNames.Count -gt 0) {
            "Metro-first with short bus transfer"
        } elseif ($lineNames.Count -gt 0) {
            "Bus-first route"
        } else {
            "Public transit available, route detail should be confirmed before departure"
        }
        $walkConstraint = if ($bestWalkingDistance -ge 2500) {
            "Long walking section, reserve extra transfer time"
        } elseif ($bestWalkingDistance -ge 0) {
            "Walking effort is moderate"
        } else {
            "Walking distance is uncertain, keep schedule buffer"
        }
        $nightConstraint = if ($nightFlag -eq "1") {
            "Night-service flag detected, run this segment in daytime when possible"
        } else {
            "Daytime travel is recommended, verify opening hours"
        }

        $notes = "Commute summary: $commuteMode. Constraints: $walkConstraint; $nightConstraint."
        if ($notes.Length -gt 980) {
            $notes = $notes.Substring(0, 980)
        }

        $insertRows += [PSCustomObject]@{
            tag = $tag
            notes = $notes
            region_adcode = "320100"
            from_poi_id = $from.poi_id
            from_poi_name = $from.name
            to_poi_id = $to.poi_id
            to_poi_name = $to.name
        }

        Start-Sleep -Milliseconds 120
    }
}

$sqlLines = @()
$sqlLines += "START TRANSACTION;"
$sqlLines += "DELETE FROM reference_pairs WHERE region_adcode = '320100';"
foreach ($r in $insertRows) {
    $sqlLines += "INSERT INTO reference_pairs(tag,notes,region_adcode,from_poi_id,from_poi_name,to_poi_id,to_poi_name,created_at) VALUES ('$(Escape-Sql $r.tag)','$(Escape-Sql $r.notes)','$(Escape-Sql $r.region_adcode)','$(Escape-Sql $r.from_poi_id)','$(Escape-Sql $r.from_poi_name)','$(Escape-Sql $r.to_poi_id)','$(Escape-Sql $r.to_poi_name)',NOW());"
}
$sqlLines += "COMMIT;"
$sqlLines += "SELECT tag, COUNT(*) AS pair_cnt FROM reference_pairs WHERE region_adcode='320100' GROUP BY tag ORDER BY tag;"

$tmpSql = Join-Path $PSScriptRoot "seed_reference_pairs_from_amap.generated.sql"
$sqlLines | Set-Content -Path $tmpSql -Encoding UTF8

& mysql --default-character-set=utf8mb4 -h $dbHost -P $dbPort -u $dbUser "-p$dbPass" -D $dbName -e "source $tmpSql"
if ($LASTEXITCODE -ne 0) { throw "Failed to import generated SQL." }

Write-Host "Seeded rows: $($insertRows.Count)"
