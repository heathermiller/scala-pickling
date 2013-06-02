set SCRIPT_DIR=%~dp0
java -Xmx2G -Xss1M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256m -jar "%SCRIPT_DIR%sbt-launch-0.12.2.jar" %*
