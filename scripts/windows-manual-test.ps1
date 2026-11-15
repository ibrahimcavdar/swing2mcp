<#
.SYNOPSIS
  Helpers for the manual checks of docs/manual-testing.md that need a person at the screen.

.DESCRIPTION
  ctrlc       Starts demo-app with "mvn swing2mcp:run"; press Ctrl+C and the script reports whether the
              application closed too.
  screenshot  Opens demo-app on Java 8 (if found) and on the JDK of the MCP server, logs in and saves
              screenshots, so they can be compared with the windows at the current display scale.
  ai          Prepares demo-app for an AI client, puts the first test prompt on the clipboard and starts
              the application.

  Tools are taken from PATH, or from %USERPROFILE%\tools (apache-maven-*, jdk8*, python-*).
  The file is saved as UTF-8 with BOM because Windows PowerShell 5.1 reads it as ANSI otherwise.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\windows-manual-test.ps1 ctrlc
#>
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('ctrlc', 'screenshot', 'ai')]
    [string] $Test,
    # JDK 8 to run the application with; searched in the usual places when omitted.
    [string] $Java8Home
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$demo = Join-Path $repo 'demo-app'

function Fail([string] $message) {
    Write-Host $message -ForegroundColor Red
    exit 1
}

function Find-Maven {
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($env:MAVEN_HOME -and (Test-Path "$env:MAVEN_HOME\bin\mvn.cmd")) { return "$env:MAVEN_HOME\bin\mvn.cmd" }
    $found = Get-ChildItem "$HOME\tools\apache-maven-*\bin\mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($found) { return $found.FullName }
    Fail 'Maven not found. Add it to PATH or unpack it under %USERPROFILE%\tools.'
}

function Find-Java8 {
    if ($Java8Home) {
        if (-not (Test-Path "$Java8Home\bin\java.exe")) { Fail "$Java8Home\bin\java.exe does not exist." }
        return $Java8Home
    }
    $patterns = "$HOME\tools\jdk8*", "$HOME\tools\jdk1.8*", 'C:\Program Files\Java\jdk1.8*',
        'C:\Program Files\Eclipse Adoptium\jdk-8*', 'C:\Program Files\Amazon Corretto\jdk1.8*', 'C:\Program Files\Zulu\zulu-8*'
    foreach ($pattern in $patterns) {
        $found = Get-ChildItem $pattern -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } | Select-Object -Last 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Find-Python {
    # The "python" of a fresh Windows is a Microsoft Store stub that only prints a message.
    foreach ($name in 'python.exe', 'py.exe') {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notlike '*\WindowsApps\*') {
            try {
                & $cmd.Source --version *> $null
                if ($LASTEXITCODE -eq 0) { return $cmd.Source }
            } catch {
                # Windows PowerShell turns any stderr output of a native command into an error here.
            }
        }
    }
    $found = Get-ChildItem "$HOME\tools\python-*\python.exe" -ErrorAction SilentlyContinue | Select-Object -Last 1
    if ($found) { return $found.FullName }
    Fail 'Python 3 not found. Add it to PATH or unpack it under %USERPROFILE%\tools.'
}

# Runs swing2mcp:setup in demo-app and returns the server command it registered (java.exe, jar).
function Initialize-Demo([string] $mvn) {
    if (-not (Test-Path "$demo\target\demo-app.jar")) {
        Fail 'demo-app is not built. First run in the repository root: mvn install'
    }
    Push-Location $demo
    try {
        & $mvn -q -B swing2mcp:setup | Out-Host
        if ($LASTEXITCODE -ne 0) { Fail 'mvn swing2mcp:setup failed (output above).' }
    } finally {
        Pop-Location
    }
    $server = (Get-Content "$demo\.mcp.json" -Raw | ConvertFrom-Json).mcpServers.swing
    return @{ Java = $server.command; Jar = $server.args[1] }
}

function Get-AgentApps {
    @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like '*swing2mcp-agent*' -and $_.CommandLine -like '*com.acme.legacy.DemoApp*' })
}

# Runs MCP tool calls through scripts\mcp-steps.py and returns its output lines.
function Invoke-Steps($server, [object[]] $steps, [string] $imageDir) {
    $python = Find-Python
    $work = Join-Path $env:TEMP 'swing2mcp-manual'
    New-Item -ItemType Directory -Force $work | Out-Null
    $lines = [string[]]($steps | ForEach-Object { ConvertTo-Json $_ -Compress -Depth 5 })
    [IO.File]::WriteAllLines("$work\steps.jsonl", $lines, (New-Object Text.UTF8Encoding $false))
    $arguments = "`"$repo\scripts\mcp-steps.py`" --java `"$($server.Java)`" --jar `"$($server.Jar)`""
    if ($imageDir) { $arguments += " --save-images `"$imageDir`"" }
    # Not -Wait: it also waits for the processes launch_app starts, which keep running.
    $process = Start-Process -FilePath $python -ArgumentList $arguments -NoNewWindow -PassThru `
        -RedirectStandardInput "$work\steps.jsonl" -RedirectStandardOutput "$work\out.txt" -RedirectStandardError "$work\err.txt"
    $null = $process.Handle # keeps ExitCode readable after exit
    $process.WaitForExit()
    $output = @(Get-Content "$work\out.txt" -Encoding UTF8)
    if ($process.ExitCode -ne 0) {
        $output + @(Get-Content "$work\err.txt" -Encoding UTF8) | Write-Host
        Fail 'MCP steps failed (output above).'
    }
    return $output
}

$mvn = Find-Maven
$java8 = Find-Java8
$runArgs = @('-B', 'swing2mcp:run')
if ($java8) {
    $runArgs += "-Dswing2mcp.javaHome=$java8"
    $runJava = "Java 8 ($java8)"
} else {
    $runJava = 'the Java of Maven (JDK 8 not found; you can pass one with -Java8Home)'
}

switch ($Test) {
    'ctrlc' {
        if ((Get-AgentApps).Count -gt 0) {
            Fail "A demo-app is already open (pid $((Get-AgentApps).ProcessId -join ', ')). Close it first."
        }
        Initialize-Demo $mvn | Out-Null
        Write-Host ''
        Write-Host "Starting the application with $runJava." -ForegroundColor Cyan
        Write-Host 'When the window opens (log in if you like), click THIS terminal and press Ctrl+C.' -ForegroundColor Cyan
        Write-Host 'If you are asked "Terminate batch job (Y/N)?", answer Y.' -ForegroundColor Cyan
        Write-Host ''
        Push-Location $demo
        try {
            & $mvn @runArgs
        } finally {
            Pop-Location
            # The application gets the Ctrl+C itself and Maven destroys it on exit; give both a moment.
            $deadline = (Get-Date).AddSeconds(10)
            while ((Get-AgentApps).Count -gt 0 -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
            $left = Get-AgentApps
            Write-Host ''
            if ($left.Count -eq 0) {
                Write-Host 'PASSED: the application closed together with Maven.' -ForegroundColor Green
            } else {
                Write-Host "FAILED: the application is still open (pid $($left.ProcessId -join ', '))." -ForegroundColor Red
                Write-Host "To close it: Stop-Process -Id $($left.ProcessId -join ',')"
            }
        }
    }

    'screenshot' {
        $server = Initialize-Demo $mvn
        $dpi = (Get-ItemProperty 'HKCU:\Control Panel\Desktop\WindowMetrics' -ErrorAction SilentlyContinue).AppliedDPI
        if ($dpi) { Write-Host "Main display scale: $([math]::Round($dpi * 100 / 96))% (a change shows up here after signing out and in again)" -ForegroundColor Cyan }

        $javas = @()
        if ($java8) { $javas += @{ Name = 'java8'; Exe = "$java8\bin\java.exe" } } else { Write-Host 'JDK 8 not found, only the JDK of the server will be tried.' }
        $javas += @{ Name = 'mcp-jdk'; Exe = $server.Java }

        $shots = Join-Path $demo 'target\manual-test'
        Remove-Item $shots -Recurse -Force -ErrorAction SilentlyContinue
        $pids = @()
        foreach ($java in $javas) {
            $steps = @(
                @{ tool = 'launch_app'; args = @{ command = "`"$($java.Exe)`" -jar target\demo-app.jar"; cwd = $demo } },
                @{ tool = 'wait_for'; args = @{ text = 'Log In'; timeoutMs = 30000 }; grep = '^$' },
                @{ tool = 'type_text'; args = @{ ref = '$ref:textfield "User name"'; text = 'admin' }; grep = '^$' },
                @{ tool = 'type_text'; args = @{ ref = '$ref:passwordfield'; text = 'admin' }; grep = '^$' },
                @{ tool = 'click'; args = @{ ref = '$ref:button "Log In"' }; grep = 'frame' },
                @{ tool = 'screenshot' }
            )
            $output = Invoke-Steps $server $steps (Join-Path $shots $java.Name)
            $version = '?'
            foreach ($line in $output) {
                if ($line -match 'Launched pid (\d+) \((.*?)\)') { $pids += [int]$Matches[1]; $version = $Matches[2] }
            }
            $png = Get-ChildItem (Join-Path $shots $java.Name) -Filter *.png | Select-Object -First 1
            Add-Type -AssemblyName System.Drawing
            $image = [Drawing.Image]::FromFile($png.FullName)
            Write-Host "$($java.Name): $version, screenshot $($image.Width)x$($image.Height) px -> $($png.FullName)"
            $image.Dispose()
        }
        Invoke-Item $shots
        Write-Host ''
        Write-Host 'Compare the images in the opened folder with the windows on the screen:' -ForegroundColor Cyan
        Write-Host '  - is the text readable or blurry?  - is part of the window cut off?  - are the Turkish letters correct?' -ForegroundColor Cyan
        Read-Host 'Press Enter when done; the applications will be closed' | Out-Null
        Invoke-Steps $server @($pids | ForEach-Object { @{ tool = 'stop_app'; args = @{ pid = $_ } } }) $null | Write-Host
    }

    'ai' {
        Initialize-Demo $mvn | Out-Null
        $prompt = 'connect to the swing application, log in with admin/admin, add a customer named Ali Veli from İzmir, ' +
            'check that it shows up in the table and take a screenshot.'
        Set-Clipboard -Value $prompt
        Write-Host ''
        Write-Host '1. Open your AI tool in this folder (approve the "swing" MCP server on first start):' -ForegroundColor Cyan
        Write-Host "     $demo"
        Write-Host '2. The first prompt is on the clipboard, paste it:' -ForegroundColor Cyan
        Write-Host "     $prompt"
        Write-Host '3. Other prompts: docs/manual-testing.md, "Hot reload".' -ForegroundColor Cyan
        Write-Host "Starting the application now with $runJava; press Ctrl+C in this terminal when done." -ForegroundColor Cyan
        Write-Host ''
        Push-Location $demo
        try {
            & $mvn @runArgs
        } finally {
            Pop-Location
            if ((Get-Command git -ErrorAction SilentlyContinue) -and (git -C $repo status --porcelain -- demo-app)) {
                Write-Host 'The demo-app sources changed. To restore them, run in the repository root: git checkout -- demo-app' -ForegroundColor Yellow
            }
        }
    }
}
