# ClipForge

A local Windows desktop app for creating clips from a YouTube URL and generating styled subtitles.

## Current features
- Paste a YouTube URL.
- Enter start/end times (HH:MM:SS, MM:SS, or seconds).
- Download only the requested section with yt-dlp.
- Open the resulting clip.
- Transcribe locally with OpenAI Whisper (`base` model).
- Generate an `.srt` subtitle file.
- Choose font, size, text color, outline color, position, and a simple pop-in effect.
- Burn subtitles into the video with FFmpeg.
- No paid API is used.

## Important
Only download/use videos you have permission to download or reuse. The app does not include third-party watermark removal.

## Requirements already installed
- Java 17
- JavaFX 21 SDK at `C:\Java\javafx-sdk-21.0.4`
- FFmpeg
- yt-dlp
- Python 3.10 + `openai-whisper`
- Whisper `base` model

## First run
1. Open a NEW PowerShell window so PATH changes are visible.
2. In this folder run:
   `gradlew.bat run`
3. If `gradlew.bat` is not present, run:
   `gradle run` if Gradle is installed, or use the generated IDE project.

The app also lets you browse for `ffmpeg.exe`, `yt-dlp.exe`, and `python.exe` if PATH discovery fails.
