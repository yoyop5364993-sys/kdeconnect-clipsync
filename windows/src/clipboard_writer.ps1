param(
    [string]$Type,
    [string]$Content,
    [string]$Base64File
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

try {
    if ($Type -eq "text") {
        [System.Windows.Forms.Clipboard]::SetText($Content)
        Write-Host "SUCCESS"
    } elseif ($Type -eq "image") {
        $rawB64 = Get-Content -Path $Base64File -Raw
        $bytes = [Convert]::FromBase64String($rawB64)
        $ms = New-Object System.IO.MemoryStream($bytes, 0, $bytes.Length)
        $img = [System.Drawing.Image]::FromStream($ms)
        [System.Windows.Forms.Clipboard]::SetImage($img)
        $img.Dispose()
        $ms.Dispose()
        Write-Host "SUCCESS"
    }
} catch {
    Write-Error $_
}
