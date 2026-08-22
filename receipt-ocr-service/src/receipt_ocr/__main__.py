"""``python -m receipt_ocr`` 진입점."""

from .eval.cli import main

if __name__ == "__main__":
    raise SystemExit(main())
