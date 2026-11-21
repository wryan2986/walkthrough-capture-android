"""Check a saved capture locally, including an optional fixed privacy rectangle."""
import argparse
import io
import json
import math
from pathlib import Path
import re
import subprocess
import zipfile


def bounds(spec, width, height):
    if spec is None:
        return None
    x1, y1, x2, y2 = map(float, spec.split(","))
    if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
        raise ValueError("Use normalized left,top,right,bottom coordinates between 0 and 1.")
    return math.floor(x1 * width), math.floor(y1 * height), math.ceil(x2 * width), math.ceil(y2 * height)


def check_zip(path, region):
    from PIL import Image
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError("A ZIP entry failed its checksum.")
        if archive.namelist().count("status.txt") != 1:
            raise ValueError("Expected exactly one capture status report.")
        status = archive.read("status.txt")
        if not status.startswith(b"Complete"):
            raise ValueError("The capture is marked incomplete.")
        manual = b"Mode: Manual screenshots" in status.splitlines()
        numbered = []
        for name in archive.namelist():
            if name == "status.txt":
                continue
            match = re.fullmatch(r"(?:screenshots/)?(\d+)\.png", name)
            if not match:
                raise ValueError("Unexpected archive member.")
            numbered.append((int(match[1]), name))
        numbered.sort()
        if not numbered or [i for i, name in numbered] != list(range(1, len(numbered) + 1)):
            raise ValueError("Missing or duplicated numbered screenshot.")
        sizes = []
        for index, name in numbered:
            with Image.open(io.BytesIO(archive.read(name))) as image:
                image.load()
                if image.format != "PNG" or (not manual and sizes and image.size not in sizes):
                    raise ValueError(f"Unexpected format or size at screenshot {index}.")
                if image.size not in sizes:
                    sizes.append(image.size)
                box = bounds(region, *image.size)
                if box:
                    channels = image.crop(box).convert("RGBA").getextrema()
                    if channels != ((0, 0), (0, 0), (0, 0), (255, 255)):
                        raise ValueError(f"Non-black or transparent mask pixel at screenshot {index}.")
        dimensions = ", ".join(f"{width} x {height}" for width, height in sizes)
        return f"{len(numbered)} full-size PNGs at {dimensions}"


def check_video(path, region, ffmpeg, ffprobe):
    tracks = json.loads(subprocess.check_output([
        ffprobe, "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height",
        "-of", "json", str(path)
    ]))["streams"]
    if len(tracks) != 1 or tracks[0]["codec_type"] != "video" or tracks[0]["codec_name"] != "h264":
        raise ValueError("Expected exactly one H.264 video track and zero audio tracks.")
    width, height = tracks[0]["width"], tracks[0]["height"]
    box = bounds(region, width, height)
    command = [ffmpeg, "-nostdin", "-v", "error", "-xerror", "-i", str(path), "-map", "0:v:0"]
    if box is None:
        subprocess.run(command + ["-f", "null", "-"], check=True)
        return f"Silent H.264 at {width} x {height}, decoded to the end"
    # Decode RGB before cropping so a mask may start on any pixel, including an odd column.
    x1, y1, x2, y2 = box
    w, h = x2 - x1, y2 - y1
    command += ["-vf", f"format=rgb24,crop={w}:{h}:{x1}:{y1}", "-fps_mode", "passthrough",
                "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    frames = 0
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        while True:
            frame = process.stdout.read(w * h * 3)
            if not frame:
                break
            frames += 1
            if len(frame) != w * h * 3 or max(frame) > 32:
                process.kill()
                raise ValueError(f"Incomplete frame or bright mask pixel at frame {frames}.")
        if process.wait() or not frames:
            raise ValueError("Video decode failed or returned zero frames.")
    if box != (0, 0, width, height):
        # A broken renderer can satisfy a mask check by returning a completely blank movie.
        preview = [ffmpeg, "-nostdin", "-v", "error", "-xerror", "-i", str(path), "-map", "0:v:0",
                   "-vf", "fps=2,scale=36:64:flags=area,format=gray", "-f", "rawvideo", "-"]
        with subprocess.Popen(preview, stdout=subprocess.PIPE) as process:
            varied = False
            while True:
                image = process.stdout.read(36 * 64)
                if not image:
                    break
                varied |= max(image) - min(image) > 18
            if process.wait() or not varied:
                raise ValueError("No visible contrast was found in the movie. Review the unmasked area.")
    return f"{frames} silent H.264 frames at {width} x {height} with the fixed mask covered"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file", type=Path)
    parser.add_argument("--rect", help="Check a fixed rectangle, e.g. 0,0,1,0.10 for the top 10%%.")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    args = parser.parse_args()
    if args.file.suffix.lower() == ".zip":
        result = check_zip(args.file, args.rect)
    elif args.file.suffix.lower() == ".mp4":
        result = check_video(args.file, args.rect, args.ffmpeg, args.ffprobe)
    else:
        parser.error("Choose a conversation ZIP or a trajectory MP4.")
    print(result)
