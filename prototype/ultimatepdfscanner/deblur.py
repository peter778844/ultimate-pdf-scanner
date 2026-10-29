"""Deblurring of slightly out-of-focus pages.

The blur is estimated from the text edges: a step edge of contrast A blurred by a Gaussian
of width sigma has a maximum gradient of A / (sigma * sqrt(2 pi)). If the page is blurry,
Richardson-Lucy deconvolution with that Gaussian sharpens it again. Motion blur is not
modelled; such photos should be retaken.
"""
import cv2
import numpy as np

from .enhance import flatten_illumination, levels, to_gray

BLUR_SIGMA = 1.4  # pages with an estimated blur above this (in pixels) are deconvolved
ITERATIONS = 15


def estimate_blur(image: np.ndarray) -> float:
    """Estimated Gaussian blur sigma in pixels (sharp phone photos: ~0.7-1.2)."""
    gray = flatten_illumination(to_gray(image)).astype(np.float32)
    black, white = levels(gray.astype(np.uint8))
    contrast = white - black
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3) / 8.0
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3) / 8.0
    grad = np.hypot(gx, gy)
    g = float(np.percentile(grad, 99.5))
    if g <= 0 or contrast <= 0:
        return 0.0
    return contrast / (g * np.sqrt(2 * np.pi))


def richardson_lucy(plane: np.ndarray, sigma: float, iterations: int = ITERATIONS) -> np.ndarray:
    """Richardson-Lucy deconvolution for a Gaussian PSF (symmetric, so no flipped kernel)."""
    img = plane.astype(np.float32) + 1.0
    est = img.copy()
    for _ in range(iterations):
        conv = cv2.GaussianBlur(est, (0, 0), sigma)
        est *= cv2.GaussianBlur(img / np.maximum(conv, 1e-3), (0, 0), sigma)
    return np.clip(est - 1.0, 0, 255).astype(np.uint8)


def deblur(image: np.ndarray, threshold: float = BLUR_SIGMA) -> tuple[np.ndarray, float]:
    """Returns (image, estimated sigma); the image is only changed if it is blurry."""
    sigma = estimate_blur(image)
    if sigma <= threshold:
        return image, sigma
    psf = 0.8 * sigma  # slightly under-estimate: an over-sized PSF causes ringing
    if image.ndim == 2:
        return richardson_lucy(image, psf), sigma
    return cv2.merge([richardson_lucy(p, psf) for p in cv2.split(image)]), sigma
