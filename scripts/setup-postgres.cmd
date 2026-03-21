@echo off
setlocal

if "%PGUSER%"=="" (
  echo PGUSER is not set. Example: set PGUSER=postgres
  exit /b 1
)
if "%PGPASSWORD%"=="" (
  echo PGPASSWORD is not set. Example: set PGPASSWORD=your_password
  exit /b 1
)

set "PSQL_EXE="
for /f "delims=" %%P in ('where psql 2^>nul') do set "PSQL_EXE=%%P"
if "%PSQL_EXE%"=="" (
  for /f "delims=" %%P in ('where /r "C:\Program Files\PostgreSQL" psql.exe 2^>nul') do set "PSQL_EXE=%%P"
)
if "%PSQL_EXE%"=="" (
  for /f "delims=" %%P in ('where /r "C:\Program Files (x86)\PostgreSQL" psql.exe 2^>nul') do set "PSQL_EXE=%%P"
)
if "%PSQL_EXE%"=="" (
  for /d %%D in ("C:\Program Files\PostgreSQL\*") do (
    if exist "%%D\bin\psql.exe" set "PSQL_EXE=%%D\bin\psql.exe"
  )
)
if "%PSQL_EXE%"=="" (
  for /d %%D in ("C:\Program Files (x86)\PostgreSQL\*") do (
    if exist "%%D\bin\psql.exe" set "PSQL_EXE=%%D\bin\psql.exe"
  )
)
if "%PSQL_EXE%"=="" (
  echo psql.exe not found. Install PostgreSQL or add its bin folder to PATH.
  exit /b 1
)

echo Using: %PSQL_EXE%

"%PSQL_EXE%" -h localhost -d postgres -v ON_ERROR_STOP=1 -f "%~dp0..\db\create_db.sql"
"%PSQL_EXE%" -h localhost -d copytrading -v ON_ERROR_STOP=1 -f "%~dp0..\db\schema.sql"
"%PSQL_EXE%" -h localhost -d copytrading -v ON_ERROR_STOP=1 -f "%~dp0..\db\seed_admin.sql"

echo Done.
endlocal
