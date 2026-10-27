# Windows 11 Native Clipboard Watcher for Text & Images
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$signature = @"
[DllImport("user32.dll")]
public static extern uint GetClipboardSequenceNumber();
"@
$User32 = Add-Type -MemberDefinition $signature -Name "User32Clipboard" -Namespace "Win32" -PassThru

$lastSeq = [Win32.User32Clipboard]::GetClipboardSequenceNumber()

Write-Host "CLIPBOARD_WATCHER_READY"
[Console]::Out.Flush()

while ($true) {
    Start-Sleep -Milliseconds 250
    $curSeq = [Win32.User32Clipboard]::GetClipboardSequenceNumber()
    if ($curSeq -ne $lastSeq) {
        $lastSeq = $curSeq
        
        # Check clipboard content in STA mode
        try {
            if ([System.Windows.Forms.Clipboard]::ContainsImage()) {
                $img = [System.Windows.Forms.Clipboard]::GetImage()
                if ($img -ne $null) {
                    $ms = New-Object System.IO.MemoryStream
                    $img.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
                    $bytes = $ms.ToArray()
                    $ms.Close()
                    $img.Dispose()
                    $b64 = [Convert]::ToBase64String($bytes)
                    $json = @{
                        type = "image"
                        mime = "image/png"
                        data = $b64
                    } | ConvertTo-Json -Compress
                    Write-Host "CLIPBOARD_EVENT:$json"
                    [Console]::Out.Flush()
                }
            } elseif ([System.Windows.Forms.Clipboard]::ContainsText()) {
                $text = [System.Windows.Forms.Clipboard]::GetText()
                if (![string]::IsNullOrEmpty($text)) {
                    $json = @{
                        type = "text"
                        content = $text
                    } | ConvertTo-Json -Compress
                    Write-Host "CLIPBOARD_EVENT:$json"
                    [Console]::Out.Flush()
                }
            }
        } catch {
            # Clipboard might be locked by another process temporarily
        }
    }
}
