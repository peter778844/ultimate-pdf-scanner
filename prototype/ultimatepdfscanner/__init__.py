"""Local document-scanning pipeline: detect -> warp -> enhance -> OCR -> export.

Every stage is a plain function on numpy images so it can be ported 1:1 to
Android (OpenCV Android SDK + an on-device OCR engine).
"""
from .types import Page, Word
from .pipeline import PipelineConfig, process_image, process_images, process_photo

__all__ = ["Page", "Word", "PipelineConfig", "process_image", "process_images", "process_photo"]
