@echo off
echo === ClipForge environment check ===
java -version
echo.
where ffmpeg
where ffprobe
where yt-dlp
where python
where deno
echo.
python -c "import whisper; print('Whisper:', whisper.__file__)"
echo.
echo If all commands above return paths/versions, the environment is ready.
pause
