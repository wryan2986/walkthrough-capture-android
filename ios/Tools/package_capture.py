"""Create a local artifact from the named capture XCTest's own attachments."""
from __future__ import annotations

import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
from typing import Any
import uuid
import zipfile
import zlib

TEST = "AutomaticCaptureTests/testAutomaticCapture()"
SWIFT_EXPORT = Path(__file__).resolve().with_name("ExportSilentVideo.swift")
XCT_SUFFIX = r"(?:_\d+_[A-Za-z0-9-]+)?"

class PackagingError(RuntimeError):
    """A result bundle does not unambiguously contain the expected media."""

def _run(args: list) -> str:
    proc = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode:
        detail = (proc.stderr or proc.stdout).strip().splitlines()
        raise PackagingError(f"{Path(str(args[0])).name} failed: " + "\n".join(detail[-5:]))
    return proc.stdout

def _one(items: list, regex: str, *, optional=False):
    chosen=[item for item in items if re.fullmatch(regex, item["suggestedHumanReadableName"],flags=re.IGNORECASE)]
    if len(chosen)==1:
        return chosen[0]
    if optional and not chosen:
        return None
    raise PackagingError(f"Expected one attachment matching {regex}; found {len(chosen)}.")

def _path(directory:Path, item:dict) -> Path:
    name=item["exportedFileName"]
    if not isinstance(name,str) or Path(name).name!=name:
        raise PackagingError("The attachment manifest contains an invalid filename.")
    file=directory/name
    if not file.is_file():
        raise PackagingError("An expected attachment file is missing.")
    return file

def _json(directory:Path,item:dict):
    return json.loads(_path(directory,item).read_text(encoding="utf-8"))

def _epoch(value:Any, label:str) -> float:
    if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value):
        raise PackagingError(f"The {label} timestamp is unavailable.")
    return float(value)

def _marker(directory:Path,item:dict)->float:
    value=_epoch(_json(directory,item).get("time"),"trajectory marker")
    logged=_epoch(item.get("timestamp"),"attachment")
    if abs(logged-value)>1:
        raise PackagingError("The device and recording clocks disagree. Check automatic date and time, then retry.")
    return value

def _png_size(file:Path)->tuple[int,int]:
    with file.open("rb") as inp:
        if inp.read(16)!=b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
            raise PackagingError("A screenshot attachment has an invalid PNG header.")
        ihdr=inp.read(13)
        if len(ihdr)!=13 or inp.read(4)!=struct.pack(">I",zlib.crc32(b"IHDR"+ihdr)):
            raise PackagingError("A screenshot attachment has a damaged PNG header.")
        width,height=struct.unpack(">II",ihdr[:8])
        if not width or not height:
            raise PackagingError("A screenshot has no visible pixels.")
    return width,height

def _screens(directory:Path, items:list, stage:Path, report:dict)->bool:
    pattern=re.compile(r"capture-(\d+)"+XCT_SUFFIX+r"\.png",flags=re.IGNORECASE)
    numbered={}
    for item in items:
        match=pattern.fullmatch(item["suggestedHumanReadableName"])
        if match:
            index=int(match[1])
            if index in numbered: raise PackagingError(f"Duplicate screenshot index {index}.")
            numbered[index]=_path(directory,item)
    count=report.get("screenshots")
    if type(count) is not int or sorted(numbered)!=list(range(1,len(numbered)+1)) or count!=len(numbered):
        raise PackagingError("The screenshot sequence does not match the run report.")
    if not count:
        if report["status"]=="complete": raise PackagingError("The completed screenshot run has no PNGs.")
        return False
    sizes=set()
    digits=max(4,len(str(count)))
    with zipfile.ZipFile(stage,"x",allowZip64=True) as archive:
        for number,file in sorted(numbered.items()):
            sizes.add(_png_size(file))
            info=zipfile.ZipInfo(f"screenshots/{number:0{digits}d}.png")
            info.file_size=file.stat().st_size
            with archive.open(info,"w") as member,file.open("rb") as src:
                shutil.copyfileobj(src,member,1024*1024)
        report["screenSizes"]=[{"width":w,"height":h} for w,h in sorted(sizes)]
        archive.writestr(zipfile.ZipInfo("result.json"),json.dumps(report,sort_keys=True,indent=2)+"\n")
    with zipfile.ZipFile(stage) as archive:
        if archive.testzip(): raise PackagingError("A screenshot ZIP entry failed its checksum.")
    return True

def package(result_bundle:Path, output_dir:Path, mode:str)->tuple[Path|None,dict]:
    """Return the new artifact and XCTest report. An empty partial run returns (None, report)."""
    aliases={"screenshots":"conversation","discovery":"discover"}
    mode=aliases.get(mode,mode)
    if mode not in ("discover","conversation","trajectory"):
        raise PackagingError("Unknown capture mode.")
    result_bundle=Path(result_bundle).resolve()
    output_dir=Path(output_dir).resolve()
    output_dir.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".capture-",dir=output_dir) as temp:
        work=Path(temp)
        directory=work/"attachments"
        _run(["xcrun","xcresulttool","export","attachments","--test-id",TEST,
              "--path",result_bundle,"--output-path",directory])
        manifest=json.loads((directory/"manifest.json").read_text(encoding="utf-8"))
        groups=[g for g in manifest if g.get("testIdentifier")==TEST]
        if len(groups)!=1:
            raise PackagingError("Expected a single capture test. Run one destination and one capture at a time.")
        items=groups[0]["attachments"]
        report_attachment=_one(items,r"result"+XCT_SUFFIX+r"\.json")
        report=_json(directory,report_attachment)
        if not isinstance(report,dict) or report.get("mode")!=mode or report.get("status") not in ("complete","partial"):
            raise PackagingError("The result report does not match the requested capture.")
        if mode=="discover":
            item=_one(items,r"discovery"+XCT_SUFFIX+r"\.json",optional=report["status"]=="partial")
            if item is None or report["status"]!="complete":
                return None,report
            rows=_json(directory,item)
            if not isinstance(rows,list) or not all(
                isinstance(row,dict) and all(isinstance(row.get(k),str) and row[k] for k in ("id","title","timestamp"))
                for row in rows
            ) or len({row["id"] for row in rows})!=len(rows):
                raise PackagingError("Discovery did not produce a usable activity list.")
            stage=work/"trajectories.json"
            stage.write_text(json.dumps(rows,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
            name="trajectories.json"
        else:
            stamp=dt.datetime.fromtimestamp(_epoch(report_attachment["timestamp"],"result"),dt.timezone.utc).strftime("%Y-%m-%d %H-%M-%S")
            suffix=" partial" if report["status"]=="partial" else ""
            # Multiple captures can end in the same second. Preserve all earlier outputs.
            unique=uuid.uuid4().hex[:8]
            if mode=="conversation":
                stage=work/"capture.zip"
                if not _screens(directory,items,stage,report):return None,report
                name=f"Screenshots {stamp}{suffix} {unique}.zip"
            else:
                start=_one(items,r"video-start"+XCT_SUFFIX+r"\.json",optional=report["status"]=="partial")
                end=_one(items,r"video-end"+XCT_SUFFIX+r"\.json",optional=report["status"]=="partial")
                if start is None or end is None:return None,report
                recording=_one(items,r"Screen Recording.*\.(?:mp4|mov)")
                origin=_epoch(recording.get("timestamp"),"screen recording")
                first,last=_marker(directory,start)-origin,_marker(directory,end)-origin
                stage=work/"capture.mp4"
                metadata=_run(["xcrun","--sdk","macosx","swift",SWIFT_EXPORT,_path(directory,recording),stage,str(first),str(last)])
                report["video"]=json.loads(metadata)
                name=f"Trajectory {stamp}{suffix} {unique}.mp4"
        artifact=output_dir/name
        os.replace(stage,artifact)
        return artifact,report
