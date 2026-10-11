param(
    [string]$InputFile = "src/main/resources/content/attractions_merged_20260721_100255.json",
    [int]$TopN = 50,
    [bool]$DryRun = $false
)

$ErrorActionPreference = "Stop"

$execArgs = "--inputFile=$InputFile --topN=$TopN --markMissingStale=true --dryRun=$($DryRun.ToString().ToLower())"

mvn -DskipTests exec:java `
    "-Dexec.mainClass=com.viyangle.study_tour.utils.AmapAttractionJsonDbSync" `
    "-Dexec.args=$execArgs"
