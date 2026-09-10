# SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
# SPDX-License-Identifier: GPL-3.0-only
[CmdletBinding()]
param(
    [string]$NdkRoot,
    # Regenerate only this pair, e.g. -Shader halation_scatter.comp. Omit to
    # regenerate all five, which is almost never what a single change wants.
    [string]$Shader
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($NdkRoot)) {
    if ([string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        throw 'Pass -NdkRoot or set ANDROID_HOME.'
    }
    $NdkRoot = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358'
}

$glslc = Join-Path $NdkRoot 'shader-tools\windows-x86_64\glslc.exe'
if (-not (Test-Path -LiteralPath $glslc -PathType Leaf)) {
    throw "Pinned NDK glslc not found: $glslc"
}

$pairs = @(
    @('filming.comp', 'filming_spv.inc'),
    @('printing.comp', 'printing_spv.inc'),
    @('scan_spectral_chain.comp', 'scan_spectral_chain_spv.inc'),
    @('halation_scatter.comp', 'halation_scatter_spv.inc'),
    @('grain.comp', 'grain_spv.inc')
)

# Regenerating everything rewrites shaders you did not touch: glslc from a
# different NDK emits different bytes for identical source, so a run for one
# shader silently churns the other three and detaches them from the device
# evidence pinned to those exact binaries (pointwise_spirv.sha256 records which
# NDK produced which). Pass -Shader to regenerate one pair, which is what a
# change to a single shader wants.
if (-not [string]::IsNullOrWhiteSpace($Shader)) {
    $pairs = @($pairs | Where-Object { $_[0] -eq $Shader -or $_[0] -eq "$Shader.comp" })
    if ($pairs.Count -eq 0) {
        throw "No shader pair matches -Shader '$Shader'."
    }
}

foreach ($pair in $pairs) {
    $source = Join-Path $PSScriptRoot $pair[0]
    $output = Join-Path $PSScriptRoot $pair[1]
    & $glslc -fshader-stage=compute --target-env=vulkan1.1 -mfmt=c `
        -o $output $source
    if ($LASTEXITCODE -ne 0) {
        throw "glslc failed for $($pair[0]) with exit code $LASTEXITCODE"
    }
}

foreach ($pair in $pairs) {
    foreach ($name in $pair) {
        $path = Join-Path $PSScriptRoot $name
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
        Write-Output "$hash  $name"
    }
}
