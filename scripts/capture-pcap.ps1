# Captures the REST call on loopback plus the Diameter CER/CEA, DWR/DWA and CCR/CCA
# exchanges into transaction_flow.pcap (classic pcap format, as the challenge asks).
#
# Prerequisites: Wireshark with Npcap installed with "Support loopback traffic" ticked.
# Run from an elevated PowerShell if capture fails with a permission error.
#
# Usage:
#   .\scripts\capture-pcap.ps1                 # capture for 60 seconds
#   .\scripts\capture-pcap.ps1 -Seconds 120
# While it runs, execute .\scripts\send-sample-requests.ps1 in another window.
param(
    [int]$Seconds = 60,
    [string]$Output = "transaction_flow.pcap",
    [string]$Tshark = "C:\Program Files\Wireshark\tshark.exe"
)

if (-not (Test-Path $Tshark)) {
    Write-Error "tshark not found at $Tshark. Install Wireshark (with Npcap loopback support) or pass -Tshark."
    exit 1
}

Write-Host "Available interfaces:"
& $Tshark -D

# Npcap exposes loopback as "\Device\NPF_Loopback"; the friendly name is "Adapter for loopback traffic capture".
$iface = "\Device\NPF_Loopback"
$filter = "tcp port 8080 or tcp port 3868"

Write-Host ""
Write-Host "Capturing on $iface with filter '$filter' for $Seconds s -> $Output"
& $Tshark -i $iface -f $filter -a "duration:$Seconds" -F pcap -w $Output

Write-Host ""
Write-Host "Summary of captured Diameter and HTTP frames:"
& $Tshark -r $Output -Y "diameter || http" -T fields -e frame.number -e ip.src -e tcp.srcport -e ip.dst -e tcp.dstport -e _ws.col.Protocol -e _ws.col.Info
