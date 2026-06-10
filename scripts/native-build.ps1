Import-Module "C:\Program Files\Microsoft Visual Studio\18\Insiders\Common7\Tools\Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath "C:\Program Files\Microsoft Visual Studio\18\Insiders" -SkipAutomaticLocation -DevCmdArguments '-arch=x64'
cd d:\gitcode\wuwei\wuwei-core
D:\codesoft\gradle-9.3.1\bin\gradle.bat nativeCompile --no-daemon
