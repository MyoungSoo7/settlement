FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=8120

WORKDIR /app

# Install deps first for layer caching.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# App source + bundled sample data.
COPY src/ ./src/
COPY data/ ./data/
COPY pyproject.toml README.md ./

ENV PYTHONPATH=/app/src

# Non-root runtime user.
RUN useradd --create-home --uid 10001 appuser \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8120

# uvicorn entrypoint (honours $PORT via the module main()).
CMD ["python", "-m", "screening_backtest"]
