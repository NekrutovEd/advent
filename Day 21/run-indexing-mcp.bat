@echo off
echo Starting Document Indexing MCP Server...
cd /d "%~dp0"
call gradlew.bat runIndexingMcpServer
