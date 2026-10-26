
from pathlib import Path

import ollama
from capture_image import load_image


MODEL_NAME = "qwen2.5vl:3b"


def describe_image(image_path):
    """Generate a scene description using the local vision model."""
    path = Path(image_path).expanduser()

    if not path.is_file():
        print(f"\nImage not found: {path}")
        return

    try:
        # Validate that the image can be opened.
        image = load_image(str(path))

        print("\nImage loaded successfully!")
        print(f"Dimensions: {image.width} x {image.height} pixels")
        print("\nAnalyzing image with Qwen2.5-VL...")
        print("Please wait; the first run may take a little longer.")

        response = ollama.chat(
            model=MODEL_NAME,
            messages=[
                {
                    "role": "user",
                    "content": (
                        "Describe this image for a visually impaired person. "
                        "Explain the overall scene, important objects, their "
                        "relative positions, people and their visible activities. "
                        "Mention relevant environmental details. Be clear and "
                        "concise. Do not guess details that are not visible."
                    ),
                    "images": [str(path.resolve())],
                }
            ],
           options={"num_ctx": 16384},
        )

        description = response["message"]["content"]

        print("\n" + "=" * 50)
        print("VISION ASSIST - SCENE DESCRIPTION")
        print("=" * 50)
        print(description)
        print("=" * 50)

    except ollama.ResponseError as error:
        print(f"\nOllama model error: {error}")
        print("Check that the model is downloaded and Ollama is running.")

    except (FileNotFoundError, OSError) as error:
        print(f"\nCould not load image: {error}")

    except Exception as error:
        print(f"\nUnexpected error: {error}")


def main():
    print("=" * 50)
    print("              VISION ASSIST")
    print("       Local AI Scene Description")
    print("=" * 50)

    while True:
        image_path = input(
            "\nEnter a photo's full path (or type 'exit' to quit): "
        ).strip().strip('"')

        if image_path.lower() == "exit":
            print("Vision Assist closed.")
            break

        if not image_path:
            print("Please enter an image path.")
            continue

        describe_image(image_path)


if __name__ == "__main__":
    main()
