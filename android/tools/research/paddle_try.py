"""Run PaddleOCR models (via RapidOCR / ONNX Runtime) on OCR input images; prints text per config.
Usage: python paddle_try.py <out_dir> <image> [<image> ...]"""
import sys, os, time
import cv2
from rapidocr import RapidOCR
from rapidocr.utils.typings import LangRec, OCRVersion, ModelType, LangDet

CONFIGS = {
    'v5_latin_mobile': {"Det.ocr_version": OCRVersion.PPOCRV5, "Det.model_type": ModelType.SERVER,
                        "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.lang_type": LangRec.LATIN, "Rec.model_type": ModelType.MOBILE},
    'v5_latin_mobiledet': {"Det.ocr_version": OCRVersion.PPOCRV5, "Det.model_type": ModelType.MOBILE,
                        "Rec.ocr_version": OCRVersion.PPOCRV5, "Rec.lang_type": LangRec.LATIN, "Rec.model_type": ModelType.MOBILE},
    'v6_multi_small': {"Det.ocr_version": OCRVersion.PPOCRV6, "Det.model_type": ModelType.MEDIUM,
                       "Rec.ocr_version": OCRVersion.PPOCRV6, "Rec.model_type": ModelType.SMALL},
    'v6_multi_medium': {"Det.ocr_version": OCRVersion.PPOCRV6, "Det.model_type": ModelType.MEDIUM,
                        "Rec.ocr_version": OCRVersion.PPOCRV6, "Rec.model_type": ModelType.MEDIUM},
}

out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
names = sys.argv[2].split(',') if sys.argv[2] != 'all' else list(CONFIGS)
for name in names:
    params = dict(CONFIGS[name]); params["Global.use_cls"] = False; params["Global.max_side_len"] = 4000
    try:
        engine = RapidOCR(params=params)
    except Exception as e:
        print(name, 'FAILED', e); continue
    for f in sys.argv[3:]:
        img = cv2.imread(f)
        t = time.time()
        res = engine(img)
        dt = time.time() - t
        lines = list(res.txts) if res.txts is not None else []
        base = os.path.splitext(os.path.basename(f))[0]
        open(os.path.join(out_dir, f'{base}.{name}.txt'), 'w', encoding='utf-8').write('\n'.join(lines))
        print(name, base, f'{dt:.1f}s', len(lines), 'lines')
