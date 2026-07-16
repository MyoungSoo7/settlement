# settlement-anomaly-service --- Python/FastAPI anomaly scoring
FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PORT=8121 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Install deps first for better layer caching.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

# Copy source.
COPY src ./src
COPY pyproject.toml ./
COPY data ./data

# Make the package importable.
ENV PYTHONPATH=/app/src

# Non-root user.
RUN useradd --create-home --uid 10001 appuser \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8121

# Fit-on-startup happens in the app lifespan; /score works immediately.
ENTRYPOINT ["uvicorn", "anomaly_service.api.app:app", "--host", "0.0.0.0", "--port", "8121"]
