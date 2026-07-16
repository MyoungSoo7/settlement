FROM python:3.11-slim

# Non-root runtime user.
RUN groupadd --system app && useradd --system --gid app --home /app app

WORKDIR /app

# Install deps first for layer caching.
COPY requirements.txt ./
RUN pip install --no-cache-dir --upgrade pip \
    && pip install --no-cache-dir -r requirements.txt

# App code + bundled demo data.
COPY src/ ./src/
COPY data/ ./data/

ENV PYTHONPATH=/app/src \
    PORT=8122 \
    HOST=0.0.0.0 \
    LOG_LEVEL=INFO \
    PYTHONUNBUFFERED=1

USER app

EXPOSE 8122

CMD ["python", "-m", "forecast_service.main"]
