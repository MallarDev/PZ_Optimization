# PZ Optimization uninstaller (Windows PowerShell), a release asset beside install.ps1:
#   irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.ps1 | iex
# Runs the latest install.ps1 with -Uninstall: finds the game, waits for it to close, removes every installed file and
# puts the launcher settings back. The game folder also has Uninstall-PZ-Optimization.cmd to double-click.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
& ([scriptblock]::Create((irm 'https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1'))) -Uninstall
