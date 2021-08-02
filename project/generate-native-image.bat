@call "C:\Program Files (x86)\Microsoft Visual Studio\2017\Enterprise\VC\Auxiliary\Build\vcvars64.bat"
if %errorlevel% neq 0 exit /b %errorlevel%
echo on
"C:\hostedtoolcache\windows\cs\2.0.13\x64\cs.exe" java --env --jvm-index "https://github.com/coursier/jvm-index/raw/master/index.json" --jvm graalvm-java11:21.2.0
if %errorlevel% neq 0 exit /b %errorlevel%
call "D:\a\metabrowse\metabrowse\null\Coursier\cache\jvm\graalvm-java11@21.2.0\bin\gu" install native-image
if %errorlevel% neq 0 exit /b %errorlevel%
call sbt ++%SCALA_VERSION% "show server-cli/nativeImageManifest"
if %errorlevel% neq 0 exit /b %errorlevel%
call "D:\a\metabrowse\metabrowse\null\Coursier\cache\jvm\graalvm-java11@21.2.0\bin\native-image.cmd" -cp metabrowse-server-cli\target\native-image-internal\manifest.jar metabrowse.server.cli.MetabrowseServerCli server-cli
if %errorlevel% neq 0 exit /b %errorlevel%
mkdir artifacts\
copy server-cli.exe artifacts\metabrowse-scala-%SCALA_VERSION%-x86_64-pc-win32.exe
if %errorlevel% neq 0 exit /b %errorlevel%
