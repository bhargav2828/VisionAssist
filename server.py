
from pathlib import Path
from uuid import uuid4
import io
import threading

import ollama
from PIL import Image
from fastapi import FastAPI, File, UploadFile, HTTPException
from starlette.concurrency import run_in_threadpool

from vision_pipeline import run_pipeline


app = FastAPI(title="Vision Assist Server")

UPLOAD_FOLDER = Path("uploads")
UPLOAD_FOLDER.mkdir(exist_ok=True)

# Allow only one image analysis at a time.
analysis_lock = threading.Lock()


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

    # Reject duplicate requests immediately.
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
        print(f"Analysis error: {error}")

        raise HTTPException(
            status_code=500,
            detail=f"Could not analyze the image: {error}"
        )

    finally:
        analysis_lock.release()
