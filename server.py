
from pathlib import Path
from uuid import uuid4
import io
import threading
import socket
import atexit
import asyncio

import ollama
from PIL import Image
from fastapi import FastAPI, File, UploadFile, HTTPException
from starlette.concurrency import run_in_threadpool

from vision_pipeline import run_pipeline

try:
    from zeroconf import ServiceInfo, Zeroconf
except ImportError:
    ServiceInfo = None
    Zeroconf = None


app = FastAPI(title="Vision Assist Server")

UPLOAD_FOLDER = Path("uploads")
UPLOAD_FOLDER.mkdir(exist_ok=True)

analysis_lock = threading.Lock()

SERVICE_TYPE = "_visionassist._tcp.local."
SERVICE_NAME = "Vision Assist._visionassist._tcp.local."
SERVER_PORT = 8000

_zeroconf = None
_service_info = None


def start_service_advertisement():
    global _zeroconf, _service_info

    if Zeroconf is None:
        print("mDNS disabled: install zeroconf using pip install zeroconf.")
        return

    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        try:
            probe.connect(("8.8.8.8", 80))
            address = probe.getsockname()[0]
        finally:
            probe.close()

        _service_info = ServiceInfo(
            SERVICE_TYPE,
            SERVICE_NAME,
            addresses=[socket.inet_aton(address)],
            port=SERVER_PORT,
            properties={
                b"app": b"Vision Assist",
                b"protocol": b"http"
            },
            server="visionassist.local."
        )

        _zeroconf = Zeroconf()
        _zeroconf.register_service(_service_info)

        print(
            f"mDNS service advertised successfully at "
            f"http://{address}:{SERVER_PORT}"
        )

    except Exception as error:
        print(
            "WARNING: mDNS advertisement failed: "
            f"{type(error).__name__}: {repr(error)}"
        )

        if _zeroconf is not None:
            try:
                _zeroconf.close()
            except Exception:
                pass

        _zeroconf = None
        _service_info = None


def stop_service_advertisement():
    global _zeroconf, _service_info

    if _zeroconf is not None:
        try:
            if _service_info is not None:
                _zeroconf.unregister_service(_service_info)
        except Exception as error:
            print(f"mDNS shutdown warning: {error!r}")
        finally:
            _zeroconf.close()

    _zeroconf = None
    _service_info = None


atexit.register(stop_service_advertisement)


@app.on_event("startup")
async def on_startup():
    await asyncio.to_thread(start_service_advertisement)


@app.on_event("shutdown")
async def on_shutdown():
    await asyncio.to_thread(stop_service_advertisement)


@app.get("/")
def home():
    return {
        "status": "online",
        "application": "Vision Assist",
        "message": "Local AI server is ready!"
    }


@app.get("/health")
def health():
    return {
        "status": "healthy",
        "processing": analysis_lock.locked()
    }


@app.post("/upload")
async def upload_image(file: UploadFile = File(...)):

    if not analysis_lock.acquire(blocking=False):
        raise HTTPException(
            status_code=409,
            detail="Vision Assist is already analyzing an image. Please wait."
        )

    try:
        if not file.content_type or not file.content_type.startswith("image/"):
            raise HTTPException(
                status_code=400,
                detail="Please upload an image."
            )

        image_data = await file.read()

        if not image_data:
            raise HTTPException(
                status_code=400,
                detail="Uploaded image is empty."
            )

        try:
            image = Image.open(io.BytesIO(image_data))
            image.load()

            width, height = image.size

            filename = f"{uuid4().hex}.jpg"
            image_path = UPLOAD_FOLDER / filename

            image.convert("RGB").save(
                image_path,
                format="JPEG"
            )

        except Exception:
            raise HTTPException(
                status_code=400,
                detail="Invalid image file."
            )

        print("\n" + "=" * 50)
        print("VISION ASSIST - IMAGE RECEIVED")
        print("=" * 50)
        print(f"Filename: {filename}")
        print(f"Dimensions: {width} x {height}")
        print("\nStarting AI analysis...")

        narration = await run_in_threadpool(
            run_pipeline,
            image_path
        )

        if not narration or not narration.strip():
            raise HTTPException(
                status_code=500,
                detail="AI returned an empty description."
            )

        print("\nAI analysis completed successfully!")

        return {
            "status": "success",
            "message": "Image analyzed successfully!",
            "filename": filename,
            "width": width,
            "height": height,
            "description": narration
        }

    except HTTPException:
        raise

    except ollama.ResponseError as error:
        print(f"Ollama error: {error}")

        raise HTTPException(
            status_code=500,
            detail=f"AI model error: {error}"
        )

    except Exception as error:
        print(f"Analysis error: {type(error).__name__}: {error!r}")

        raise HTTPException(
            status_code=500,
            detail=f"Could not analyze the image: {error}"
        )

    finally:
        analysis_lock.release()
