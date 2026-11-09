@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Premiere fois : preparation de l'outil ^(1 a 2 minutes, Internet necessaire^)...
  py -3 -m venv .venv || goto :erreur
  ".venv\Scripts\python.exe" -m pip install --quiet numpy sounddevice || goto :erreur
)
:menu
echo.
echo  Mesure du son de la 308 (micro du PC)
echo  1. Courbe de reponse (egaliseur)
echo  2. Alignement gauche / droite (clics)
echo  3. Volume maximum sans saturation
echo  4. Liste des micros
echo  0. Quitter
set /p choix=Ton choix :
if "%choix%"=="1" ".venv\Scripts\python.exe" mesure_son.py courbe
if "%choix%"=="2" ".venv\Scripts\python.exe" mesure_son.py clics
if "%choix%"=="3" (
  set /p dep=Cran de depart choisi sur l'autoradio :
  call ".venv\Scripts\python.exe" mesure_son.py saturation --depart %%dep%%
)
if "%choix%"=="4" ".venv\Scripts\python.exe" mesure_son.py micros
if "%choix%"=="0" exit /b
goto :menu
:erreur
echo Echec de la preparation. Verifie la connexion Internet et relance.
pause
