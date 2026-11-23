<#
.SYNOPSIS
  Gives the four VB-Audio endpoints their original names back. Does nothing else:
  the drivers themselves are removed with VB-Audio's own uninstaller.

.DESCRIPTION
  android-speaker -> Hi-Fi Cable Input, wirefree speaker link -> Hi-Fi Cable Output,
  android-mic -> CABLE Output, wirefree mic link -> CABLE Input.
  Exit code: 0 ok (also when nothing needed restoring), 1 failure.

.PARAMETER DryRun    Print what would happen; change nothing; no admin needed.
.PARAMETER Elevated  Internal: set when this script relaunched itself through UAC.
#>
[CmdletBinding()]
param([switch]$DryRun, [switch]$Elevated)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audio-common.ps1')

if (-not $DryRun -and (-not (Test-WfAdmin) -or (-not [Environment]::Is64BitProcess -and [Environment]::Is64BitOperatingSystem))) {
  exit (Restart-WfElevated $PSCommandPath @())
}

$code = 0
try {
  $all = [Wirefree.Audio]::List()
  foreach ($spec in $WfEndpoints) {
    $ep = Find-WfEndpoint $all $spec
    if (-not $ep) { Write-Host "'$($spec.New)': not found, skipping."; continue }
    $full = Get-WfFullName $spec.Orig $ep.Adapter
    if ($ep.Desc -eq $spec.Orig -and $ep.Name -eq $full) { Write-Host "'$($spec.Orig)': already original."; continue }
    if ($DryRun) { Write-Host "[dry run] would rename '$($ep.Desc)' -> '$full'"; continue }
    [Wirefree.Audio]::SetName($ep.Id, $spec.Orig, $full)
    Write-Host "'$($ep.Desc)' -> '$full'"
  }
  if (-not $DryRun) {
    $bad = @($WfEndpoints | Where-Object { $e = Find-WfEndpoint ([Wirefree.Audio]::List()) $_; $e -and $e.Desc -ne $_.Orig })
    if ($bad.Count -gt 0) { Write-Host 'Names did not show up yet; restarting the Windows Audio service...'; Restart-Service Audiosrv -Force }
  }
} catch { Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red; $code = 1 }
if ($Elevated) { Read-Host 'Press Enter to close' | Out-Null }
exit $code
