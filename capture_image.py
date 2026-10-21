
from pathlib import Path
from PIL import Image


def load_image(image_path):
    """Load an image and return it with its dimensions."""
    path = Path(image_path)

    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {path}")

    with Image.open(path) as image:
        image.load()
        return image.copy()


if __name__ == "__main__":
    image_path = input("Enter the full path to an image: ").strip().strip('"')

    try:
        image = load_image(image_path)
        print("Image loaded successfully!")
        print(f"Format: {image.format or 'Unknown'}")
        print(f"Dimensions: {image.width} x {image.height}")
        print(f"Colour mode: {image.mode}")
    except (FileNotFoundError, OSError) as error:
        print(f"Could not load image: {error}")
