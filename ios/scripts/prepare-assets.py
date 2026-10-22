"""Package the existing fictional portraits and Android icon; create a synthetic test clip."""
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
REPO = ROOT.parent
resources = ROOT / "ANON/Resources"
resources.mkdir(parents=True, exist_ok=True)
image = Image.open(REPO / "app/src/main/assets/faces.webp").convert("RGB")
image.save(resources / "faces.jpg", quality=90, optimize=True)

# Same vector paths and colors as Android's ic_anon.xml, rasterized for Apple's icon catalog.
icon = Image.new("RGB", (2048, 2048), "#10120F")
draw = ImageDraw.Draw(icon)
scale = 2048 / 108
points = [(26,76),(44,30),(64,30),(82,76),(66,76),(63,66),(45,66),(42,76)]
draw.polygon([(round(x*scale),round(y*scale)) for x,y in points], fill="#D9FC76")
draw.polygon([(round(x*scale),round(y*scale)) for x,y in [(49,54),(59,54),(54,38)]], fill="#10120F")
draw.rectangle([round(20*scale),round(45*scale),round(88*scale),round(55*scale)], fill="#10120F")
icon.resize((1024,1024), Image.Resampling.LANCZOS).save(resources / "Assets.xcassets/AppIcon.appiconset/AppIcon.png")

fixtures = ROOT / "ANONTests/Fixtures"
fixtures.mkdir(parents=True, exist_ok=True)
temporary = REPO / "build/ios-fixture-landscape.mp4"
temporary.parent.mkdir(exist_ok=True)
subprocess.run(["ffmpeg", "-y", "-v", "error", "-loop", "1", "-framerate", "30", "-i", str(resources / "faces.jpg"),
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "0.6",
    "-vf", "scale=480:480,pad=640:480:80:0:color=0x173a2d,drawbox=x=0:y=0:w=60:h=90:color=0xf54242:t=fill",
    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", "-c:a", "aac", str(temporary)], check=True)
subprocess.run(["ffmpeg", "-y", "-v", "error", "-display_rotation:v:0", "90", "-i", str(temporary), "-c", "copy",
    "-metadata", "title=PRIVATE_TEST_TITLE",
    "-metadata", "artist=PRIVATE_TEST_AUTHOR", "-metadata", "location=+48.8566+002.3522/",
    "-metadata", "creation_time=2001-02-03T04:05:06Z", str(fixtures / "faces-portrait.mp4")], check=True)
print("Packaged fictional portraits, icon, and synthetic 18-frame portrait/audio fixture.")
