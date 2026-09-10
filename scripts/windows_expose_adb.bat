@echo off
REM ==========================================================
REM  在 Windows 端以管理员身份运行此脚本一次。
REM  作用：把 Windows 5037 端口（雷电自带 adb server）暴露给 WSL
REM        以便 WSL 通过 ADB_SERVER_SOCKET 复用，而不是另起 adb。
REM ==========================================================

echo === 配置 portproxy ===
netsh interface portproxy delete v4tov4 listenport=5037 listenaddress=0.0.0.0 >nul 2>&1
netsh interface portproxy add v4tov4 listenport=5037 listenaddress=0.0.0.0 connectport=5037 connectaddress=127.0.0.1

echo === 添加防火墙入站规则 ===
netsh advfirewall firewall delete rule name="WSL ADB 5037" >nul 2>&1
netsh advfirewall firewall add rule name="WSL ADB 5037" dir=in action=allow protocol=TCP localport=5037

echo === 启动雷电 adb server（如果未启动） ===
REM 修改下行为你的雷电安装路径
set "LD_ADB=D:\leidian\LDPlayer9\adb.exe"
if exist "%LD_ADB%" (
    "%LD_ADB%" start-server
    "%LD_ADB%" devices
) else (
    echo [警告] 未找到 %LD_ADB% ，请改成你的雷电安装目录
    echo        然后再次双击此脚本
)

echo.
echo 完成。WSL 现在可通过以下方式连接：
echo     export ADB_SERVER_SOCKET=tcp:172.24.160.1:5037
echo     adb devices
pause
