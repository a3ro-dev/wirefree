<#
.SYNOPSIS
  Installs VB-CABLE and Hi-Fi Cable (free, signed, from vb-audio.com), names their endpoints for wirefree
  and sets them to 48 kHz.

.DESCRIPTION
  Windows PowerShell 5.1, no modules. Self-elevates. Nothing from VB-Audio is bundled: each pack is
  downloaded from vb-audio.com over HTTPS, checked against a pinned SHA-256 and an Authenticode signature,
  and only then run.

  Endpoint names set (matched by the driver's original names, so re-runs are safe):
    Hi-Fi Cable Input   -> android-speaker
    Hi-Fi Cable Output  -> wirefree speaker link
    CABLE Output        -> android-mic
    CABLE Input         -> wirefree mic link

  Exit code: 0 done, 3010 done but a reboot is needed, 2 download/integrity check failed, 1 other failure.

.PARAMETER DryRun       Print what would happen; change nothing; no admin needed.
.PARAMETER SkipInstall  Don't download or install drivers; only rename the endpoints and set the format.
.PARAMETER Elevated     Internal: set when this script relaunched itself through UAC (keeps the window open at the end).
#>
[CmdletBinding()]
param([switch]$DryRun, [switch]$SkipInstall, [switch]$Elevated)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'audio-common.ps1')

# Pinned from the files vb-audio.com served on 2026-11-23. If VB-Audio republishes a pack the hash stops
# matching and the script refuses to run it: update the hash after checking the new pack.
$SignerPattern = 'Vincent Burel|BUREL VINCENT|VB-Audio'   # VB-CABLE's exe subject is "BUREL VINCENT Entrepreneur individuel"
$VendorPage    = 'https://vb-audio.com/Cable/'
$Packs = @(
  @{ Name = 'VB-CABLE'; Keys = 'vb-render', 'vb-capture'
     Url = 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'
     Sha256 = 'B950E39F01AF1D04EA623C8F6D8EB9B6EA5C477C637295FABF20631C85116BFB'
     # The pack has no ARM64 exe: its x64 setup also carries the ARM64 driver.
     Exe = 'VBCABLE_Setup_x64.exe'; SilentArgs = @('-i', '-h')
     Steps = '' }
  @{ Name = 'Hi-Fi Cable'; Keys = 'hifi-render', 'hifi-capture'
     Url = 'https://download.vb-audio.com/Download_CABLE/HiFiCableAsioBridgeSetup_v1007.zip'
     Sha256 = '3ECF204BFD8579D36BB918F9856EB1EADDD49C75146F5E8A8F59DCB8375AE89A'
     # No silent mode (a plain GUI installer with one Install button), so it is launched normally.
     Exe = 'HiFiCableAsioBridgeSetup.exe'; SilentArgs = $null
     Steps = 'In the VB-Audio window: click the Install button, wait for "Installation Complete and Successful", then close it. If it asks you to restart first, restart and run this setup again.' }
)

function Say($m)  { Write-Host $m }
function Warn($m) { Write-Host "WARNING: $m" -ForegroundColor Yellow; $script:Warnings += $m }

function Stop-OnBadPack($pack, $why) {
  Write-Host "ABORTED: $($pack.Name) was NOT installed. $why" -ForegroundColor Red
  Write-Host "Nothing was run. Download and install it yourself from $VendorPage" -ForegroundColor Red
  try { Start-Process $VendorPage } catch { }
  throw [Exception]'integrity'
}

# Download, verify, run. Returns $true when the installer was run.
function Install-Pack($pack) {
  $work = Join-Path ([IO.Path]::GetTempPath()) ('wirefree-' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $work | Out-Null
  try {
    $zip = Join-Path $work 'pack.zip'
    Say "  downloading $($pack.Url)"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    try { Invoke-WebRequest -Uri $pack.Url -OutFile $zip -UseBasicParsing } catch { Stop-OnBadPack $pack "Download failed: $($_.Exception.Message)" }
    $h = (Get-FileHash $zip -Algorithm SHA256).Hash
    if ($h -ne $pack.Sha256) { Stop-OnBadPack $pack "The download's SHA-256 ($h) does not match the pinned value ($($pack.Sha256)). VB-Audio may have updated the pack, or the download was tampered with." }
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $work 'x')
    $exe = Get-ChildItem (Join-Path $work 'x') -Recurse -File -Filter $pack.Exe | Select-Object -First 1
    if (-not $exe) { Stop-OnBadPack $pack "$($pack.Exe) is not in the download." }
    $sig = Get-AuthenticodeSignature $exe.FullName
    if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch $SignerPattern) {
      Stop-OnBadPack $pack "$($pack.Exe) signature is '$($sig.Status)', signer '$($sig.SignerCertificate.Subject)'; expected a valid VB-Audio / Vincent Burel signature."
    }
    Say "  verified: SHA-256 ok, signed by $($sig.SignerCertificate.Subject.Split(',')[0])"
    if ($pack.SilentArgs) {
      Say "  installing silently ($($pack.Exe) $($pack.SilentArgs -join ' '))..."
      $p = Start-Process $exe.FullName -ArgumentList $pack.SilentArgs -WorkingDirectory $exe.DirectoryName -WindowStyle Hidden -PassThru
      if (-not $p.WaitForExit(240000)) { try { $p.Kill() } catch { }; throw "$($pack.Name) setup did not finish within 4 minutes." }
      Say "  setup exited with code $($p.ExitCode)"
    } else {
      Write-Host "  >> $($pack.Steps)" -ForegroundColor Cyan
      Start-Process $exe.FullName -WorkingDirectory $exe.DirectoryName -Wait | Out-Null
      # The installer writes this Uninstall entry when it finishes; without it (and without endpoints) the user closed it early.
      $u = 'Microsoft\Windows\CurrentVersion\Uninstall\VB:ASIOBridge {17359A74-1236-5467}'
      if (-not ((Test-Path -LiteralPath "HKLM:\SOFTWARE\$u") -or (Test-Path -LiteralPath "HKLM:\SOFTWARE\WOW6432Node\$u") -or (Test-PackPresent $pack (Get-Endpoints)))) {
        Warn "$($pack.Name) setup was closed without installing. Run this setup again and click Install."
        return $false
      }
    }
    return $true
  } finally { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
}

function Get-Endpoints { ,@([Wirefree.Audio]::List()) }

function Test-PackPresent($pack, $all) {
  foreach ($k in $pack.Keys) { if (-not (Find-WfEndpoint $all ($WfEndpoints | Where-Object { $_.Key -eq $k }))) { return $false } }
  $true
}

function Invoke-Main {
  $script:Warnings = @()
  $installed = @(); $renamed = @(); $alreadyNamed = @(); $reboot = $false; $failed = $false
  if (-not [Environment]::Is64BitOperatingSystem) { throw 'wirefree needs 64-bit Windows.' }
  if ($DryRun) { Say '[dry run] nothing will be downloaded, installed or changed.' }

  # --- 1. drivers ---
  $all = Get-Endpoints
  foreach ($pack in $Packs) {
    if (Test-PackPresent $pack $all) { Say "$($pack.Name): already installed, skipping."; continue }
    if ($SkipInstall) { Say "$($pack.Name): not installed (-SkipInstall: not installing)."; continue }
    Say "$($pack.Name): not installed."
    if ($DryRun) {
      $how = if ($pack.SilentArgs) { "run $($pack.Exe) $($pack.SilentArgs -join ' ') silently" } else { "launch $($pack.Exe) normally; you click Install" }
      Say "  [dry run] would download $($pack.Url)"
      Say "  [dry run] would require SHA-256 $($pack.Sha256) and a valid signature from /$SignerPattern/, then $how"
      continue
    }
    if (Install-Pack $pack) { $installed += $pack.Name; $reboot = $true }
  }

  # Drivers need a moment to publish their endpoints.
  if ($installed.Count -gt 0) {
    for ($i = 0; $i -lt 10; $i++) {
      $all = Get-Endpoints
      if (-not ($Packs | Where-Object { $installed -contains $_.Name -and -not (Test-PackPresent $_ $all) })) { break }
      Start-Sleep -Seconds 2
    }
  } else { $all = Get-Endpoints }

  # --- 2. names ---
  $found = @()
  foreach ($spec in $WfEndpoints) {
    $ep = Find-WfEndpoint $all $spec
    if (-not $ep) { continue }
    $found += $ep.Id
    if ($ep.Desc -eq $spec.New -and $ep.Name -eq $spec.New) { $alreadyNamed += $spec.New; continue }
    if ($DryRun) { Say "[dry run] would rename $($spec.Flow) '$($spec.Orig)' -> '$($spec.New)'"; continue }
    try { [Wirefree.Audio]::SetName($ep.Id, $spec.New, $spec.New) } catch { Warn "Could not rename '$($ep.Desc)': $($_.Exception.Message)"; $failed = $true; continue }
    $renamed += "$($spec.Orig) -> $($spec.New)"
  }
  $missing = @($WfEndpoints | Where-Object { -not (Find-WfEndpoint $all $_) })
  if ($missing.Count -gt 0) {
    if ($DryRun) { Say "[dry run] endpoints not present yet: $(($missing | ForEach-Object { $_.Orig }) -join ', ')" }
    elseif ($reboot) { Warn "Endpoints not visible yet ($(($missing | ForEach-Object { $_.Orig }) -join ', ')). Restart Windows, then run this setup again (it will only rename and set formats)." }
    else { Warn "Endpoints not found ($(($missing | ForEach-Object { $_.Orig }) -join ', ')). Run this setup without -SkipInstall."; $failed = $true }
  }

  # Did the names stick? If not, the audio service may be holding the old ones.
  if (-not $DryRun -and $renamed.Count -gt 0) {
    $bad = { @($WfEndpoints | ForEach-Object { $e = Find-WfEndpoint (Get-Endpoints) $_; if ($e -and ($e.Desc -ne $_.New -or $e.Name -ne $_.New)) { $_.New } }) }
    if ((& $bad).Count -gt 0) {
      Say 'Names did not show up yet; restarting the Windows Audio service...'
      Restart-Service Audiosrv -Force; Start-Sleep -Seconds 3
      $still = & $bad
      if ($still.Count -gt 0) { Warn "These names did not stick: $($still -join ', '). Sound settings may still show the VB names."; $failed = $true }
    }
  }

  # --- 3. 48 kHz ---
  $fmtDone = 0; $fmtBad = @()
  foreach ($spec in $WfEndpoints) {
    $ep = Find-WfEndpoint (Get-Endpoints) $spec
    if (-not $ep) { continue }
    if ($ep.Hz -eq 48000 -and $ep.Channels -eq 2) { $fmtDone++; continue }
    if ($DryRun) { Say "[dry run] would set '$($spec.New)' to 48000 Hz, 16-bit, stereo (now $($ep.Hz) Hz, $($ep.Bits)-bit, $($ep.Channels) ch)"; continue }
    try { [Wirefree.Audio]::SetFormat($ep.Id, 48000, 16, 2) } catch { $fmtBad += $spec.New; continue }
    $after = Find-WfEndpoint (Get-Endpoints) $spec
    if ($after.Hz -eq 48000) { $fmtDone++ } else { $fmtBad += $spec.New }
  }
  if ($fmtBad.Count -gt 0) {
    Warn "Could not set 48000 Hz on: $($fmtBad -join ', '). Set it by hand: Sound settings > device > Advanced > Default format > 48000 Hz. Hi-Fi Cable needs the same rate on both sides."
  }

  # --- summary ---
  Say ''
  Say '=== wirefree audio setup ==='
  if ($DryRun) { Say 'Dry run: nothing was changed.' }
  Say ('Installed this run : ' + $(if ($installed) { $installed -join ', ' } else { 'nothing' }))
  Say ('Renamed            : ' + $(if ($renamed) { $renamed -join '; ' } else { 'nothing' }) + $(if ($alreadyNamed) { "   (already named: $($alreadyNamed -join ', '))" } else { '' }))
  Say "48 kHz stereo      : $fmtDone of $($found.Count) endpoints"
  if ($reboot) { Write-Host 'RESTART WINDOWS to finish installing the audio drivers.' -ForegroundColor Cyan }
  if ($script:Warnings.Count -gt 0) { Say "$($script:Warnings.Count) warning(s), see above." }
  if ($failed) { return 1 }
  if ($reboot) { return 3010 }
  return 0
}

$code = 1
if (-not $DryRun -and (-not (Test-WfAdmin) -or (-not [Environment]::Is64BitProcess -and [Environment]::Is64BitOperatingSystem))) {
  $code = Restart-WfElevated $PSCommandPath $(if ($SkipInstall) { '-SkipInstall' })
} else {
  try { $code = Invoke-Main }
  catch {
    if ($_.Exception.Message -eq 'integrity') { $code = 2 }
    else { Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red; $code = 1 }
  }
  if ($Elevated) { Read-Host 'Press Enter to close' | Out-Null }
}
exit $code
