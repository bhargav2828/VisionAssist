
from pathlib import Path
from time import perf_counter
import io

import ollama
from PIL import Image

from object_detection import (
    detect_objects,
    group_detections,
    format_detection_summary
)


# --------------------------------------------------
# CONFIGURATION
# --------------------------------------------------

QWEN_MODEL = "qwen2.5vl:3b"

MAX_IMAGE_SIZE = 1600
JPEG_QUALITY = 92


# --------------------------------------------------
# 1. PREPARE IMAGE
# --------------------------------------------------

def prepare_image(image_path):

    with Image.open(image_path) as source:
        image = source.convert("RGB")
        original_size = image.size

        image.thumbnail(
            (MAX_IMAGE_SIZE, MAX_IMAGE_SIZE),
            Image.Resampling.LANCZOS
        )

        buffer = io.BytesIO()

        image.save(
            buffer,
            format="JPEG",
            quality=JPEG_QUALITY
        )

        return (
            buffer.getvalue(),
            original_size,
            image.size
        )


# --------------------------------------------------
# 2. GENERATE CONFIDENCE-FOCUSED DESCRIPTION
# --------------------------------------------------

def describe_image(image_bytes, detections):

    detection_summary = format_detection_summary(detections)

    if not detection_summary:
        detection_summary = (
            "YOLO did not detect any supported objects."
        )

    prompt = f"""
You are Vision Assist, an accessibility assistant
designed to help blind and visually impaired people
understand their surroundings.

YOUR MOST IMPORTANT RULE:
Describe what you can actually see.
Never invent details to make the description sound complete.

YOLO OBJECT DETECTIONS:
{detection_summary}

YOLO detections are supporting evidence only.
They may contain incorrect or incomplete results.
Inspect the attached image independently.

STEP 1 - UNDERSTAND THE SCENE:

Identify the general type of scene only when it is clear.
For example, a room, kitchen, street, shop or office.

If the exact scene is uncertain, describe the visible setting
without guessing its purpose.

STEP 2 - IDENTIFY OBJECTS:

Describe the most important clearly visible objects.

For each object:

- Identify it only when its appearance supports that identity.
- Mention its colour, shape or material when useful.
- Explain its approximate position.
- Mention nearby objects when the relationship is visible.

If you cannot confidently identify an object:

- Describe its visible appearance instead.
- You may say "an object that looks like..."
- Do not guess that it is a person, bed, chair, table,
  bag or another specific object without sufficient evidence.

STEP 3 - AVOID FALSE INFORMATION:

Never invent:

- People or body parts.
- Animals.
- Furniture.
- Activities or movements.
- Object relationships.
- Text, numbers or labels.
- Room purposes or people's intentions.

Do not interpret a dark shape as a person.
Do not interpret a yellow object as a blanket.
Do not interpret every horizontal surface as a table or bed.

Only describe these things when they are visibly identifiable.

STEP 4 - POSITIONS:

Use simple spatial descriptions:

- Left side.
- Right side.
- Centre.
- Foreground.
- Background.
- Beside, behind or in front of another object.

Only state relationships that are visually supported.

STEP 5 - COUNTING:

Count objects only when they can be distinguished clearly.

- Avoid guessing counts of partially hidden objects.
- Do not count the same object more than once.
- Do not blindly copy YOLO counts.
- If the count is uncertain, do not give an exact number.

STEP 6 - CONFIDENCE:

Separate clearly visible facts from uncertain interpretations.

If an object's identity is unclear, describe its appearance.
If an important detail is unclear, say that it is unclear.
It is better to omit an uncertain detail than invent one.

Do not repeatedly say "maybe" for objects that are obvious.

STEP 7 - SPOKEN DESCRIPTION:

Use natural, simple English.

Follow this priority:

1. General scene.
2. Main objects.
3. Their positions and relationships.
4. Important secondary details.

Make the description informative but concise.
Do not add details just to make it longer.
Do not repeat the same information.

Never provide navigation or obstacle-avoidance advice.
Never claim an area or route is safe.

Return only the final spoken description.
"""

    response = ollama.chat(
        model=QWEN_MODEL,
        messages=[
            {
                "role": "user",
                "content": prompt,
                "images": [image_bytes]
            }
        ],
        options={
            "num_ctx": 4096,
            "num_predict": 250,
            "temperature": 0.1
        }
    )

    narration = response["message"]["content"].strip()

    if not narration:
        raise RuntimeError(
            "Qwen returned an empty description."
        )

    return narration


# --------------------------------------------------
# 3. COMPLETE PIPELINE
# --------------------------------------------------

def run_pipeline(image_path):

    path = Path(image_path).expanduser()

    if not path.is_file():
        raise FileNotFoundError(
            f"Image not found: {path}"
        )

    print("\n" + "=" * 55)
    print("VISION ASSIST - AI ANALYSIS")
    print("=" * 55)

    total_start = perf_counter()

    # STEP 1: IMAGE PREPARATION

    print("\nSTEP 1: Preparing image...")

    image_bytes, original_size, resized_size = prepare_image(path)

    print(f"Original image: {original_size}")
    print(f"AI image: {resized_size}")

    # STEP 2: YOLO OBJECT DETECTION

    print("\nSTEP 2: Running YOLO object detection...")

    yolo_start = perf_counter()

    detections = detect_objects(path)
    grouped = group_detections(detections)

    yolo_time = perf_counter() - yolo_start

    print(f"YOLO completed in {yolo_time:.1f} seconds.")
    print(f"Supporting detections: {len(detections)}")

    for item in grouped:
        print(
            f"  {item['label']}: {item['count']} "
            f"(confidence: {item['average_confidence']:.3f})"
        )

    # STEP 3: QWEN VISUAL UNDERSTANDING

    print("\nSTEP 3: Running Qwen visual understanding...")

    qwen_start = perf_counter()

    narration = describe_image(
        image_bytes,
        detections
    )

    qwen_time = perf_counter() - qwen_start

    total_time = perf_counter() - total_start

    # FINAL DESCRIPTION

    print("\n" + "=" * 55)
    print("VISION ASSIST - FINAL DESCRIPTION")
    print("=" * 55)

    print(narration)

    print("\n" + "=" * 55)
    print(f"YOLO time: {yolo_time:.1f} seconds")
    print(f"Qwen time: {qwen_time:.1f} seconds")
    print(f"Total time: {total_time:.1f} seconds")
    print("=" * 55)

    return narration


# --------------------------------------------------
# 4. STANDALONE TEST
# --------------------------------------------------

def main():

    print("VISION ASSIST - MULTIMODAL AI")

    while True:

        try:
            image_path = input(
                "\nEnter image path or type 'exit': "
            ).strip().strip('"')

            if image_path.lower() == "exit":
                print("Vision Assist closed.")
                break

            if not image_path:
                continue

            try:
                run_pipeline(image_path)

            except ollama.ResponseError as error:
                print(f"Ollama error: {error}")

            except Exception as error:
                print(f"Analysis error: {error}")

        except (KeyboardInterrupt, EOFError):
            print("\nVision Assist stopped.")
            break


if __name__ == "__main__":
    main()
