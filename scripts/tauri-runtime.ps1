param([string]$Runtime = 'resources/runtime')
$ErrorActionPreference = 'Stop'
# Java 17's native launcher uses ANSI Windows paths. Opt this bundled launcher
# into UTF-8 so a Chinese install path also works on an English Windows system.
# Preserve its existing elevation/compatibility manifest. No system locale changes.
$kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10/bin'
$mt = Get-ChildItem "$kits/*/x64/mt.exe" | Sort-Object FullName -Descending | Select-Object -First 1
if (!$mt) { throw 'Windows SDK mt.exe is required to prepare the Tauri runtime.' }
foreach ($name in @('java.exe', 'javaw.exe')) {
  $file = (Resolve-Path (Join-Path $Runtime "bin/$name")).Path
  $manifest = Join-Path ([IO.Path]::GetTempPath()) ('coverage-java-' + [guid]::NewGuid() + '.manifest')
  try {
    & $mt.FullName '-nologo' "-inputresource:$file;#1" "-out:$manifest"
    if ($LASTEXITCODE -ne 0) { throw "Cannot read $name manifest" }
    $xml = New-Object System.Xml.XmlDocument
    $xml.Load($manifest)
    $ns = 'urn:schemas-microsoft-com:asm.v3'
    $application = $xml.DocumentElement.SelectSingleNode("*[local-name()='application' and namespace-uri()='$ns']")
    if (!$application) {
      $application = $xml.CreateElement('application', $ns)
      [void]$xml.DocumentElement.AppendChild($application)
    }
    $settings = $application.SelectSingleNode("*[local-name()='windowsSettings']")
    if (!$settings) {
      $settings = $xml.CreateElement('windowsSettings', $ns)
      [void]$application.AppendChild($settings)
    }
    $codePage = $settings.SelectSingleNode("*[local-name()='activeCodePage']")
    if (!$codePage) {
      $codePage = $xml.CreateElement('activeCodePage', 'http://schemas.microsoft.com/SMI/2019/WindowsSettings')
      [void]$settings.AppendChild($codePage)
    }
    $codePage.InnerText = 'UTF-8'
    $xml.Save($manifest)
    & $mt.FullName '-nologo' '-manifest' $manifest "-outputresource:$file;#1"
    if ($LASTEXITCODE -ne 0) { throw "Cannot update $name manifest" }
  } finally {
    Remove-Item $manifest -ErrorAction SilentlyContinue
  }
}
