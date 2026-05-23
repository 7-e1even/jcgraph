@echo off
rem jcgraph launcher. Prefers a bundled JRE next to this script (self-contained),
rem falls back to whatever `java` is on PATH. Put this folder on PATH, then run
rem e.g.  jcgraph index path\to\proj
setlocal
set "JCG_DIR=%~dp0"
if exist "%JCG_DIR%jre\bin\java.exe" (
  "%JCG_DIR%jre\bin\java.exe" -jar "%JCG_DIR%target\jcgraph.jar" %*
) else (
  java -jar "%JCG_DIR%target\jcgraph.jar" %*
)
endlocal
