# Sends the traffic the PCAP deliverable must show: ten successful charges followed by
# the three failure paths (peer rejects, peer silent, bad payload).
# Usage: .\scripts\send-sample-requests.ps1 [-BaseUrl http://localhost:8080]
param(
    [string]$BaseUrl = "http://localhost:8080"
)

$endpoint = "$BaseUrl/api/v1/charge"

function Send-Charge([string]$msisdn, [string]$label) {
    $body = @{ msisdn = $msisdn; requestType = "INITIAL"; requestNumber = 0; requestedOctets = 1048576 } | ConvertTo-Json -Compress
    try {
        $resp = Invoke-WebRequest -Uri $endpoint -Method Post -ContentType "application/json" -Body $body -UseBasicParsing
        Write-Host ("{0,-28} HTTP {1}  {2}" -f $label, $resp.StatusCode, $resp.Content)
    } catch {
        $r = $_.Exception.Response
        if ($r -ne $null) {
            $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
            $content = $reader.ReadToEnd()
            Write-Host ("{0,-28} HTTP {1}  {2}" -f $label, [int]$r.StatusCode, $content)
        } else {
            Write-Host ("{0,-28} FAILED {1}" -f $label, $_.Exception.Message)
        }
    }
}

Write-Host "Health:" (Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing).Content
Write-Host ""

for ($i = 1; $i -le 10; $i++) {
    $msisdn = "9198765432{0:D2}" -f $i
    Send-Charge $msisdn "CCR #$i ($msisdn)"
}

Write-Host ""
Send-Charge "919876540000" "user unknown -> 422/5030"
Send-Charge "919876549999" "credit limit -> 422/4012"
Send-Charge "919876545555" "blackhole   -> 504"

Write-Host ""
Write-Host "Bad payload -> 400"
try {
    Invoke-WebRequest -Uri $endpoint -Method Post -ContentType "application/json" -Body '{"msisdn":"abc"}' -UseBasicParsing | Out-Null
} catch {
    $r = $_.Exception.Response
    $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
    Write-Host ("{0,-28} HTTP {1}  {2}" -f "validation", [int]$r.StatusCode, $reader.ReadToEnd())
}
