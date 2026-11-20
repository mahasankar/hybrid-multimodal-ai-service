FROM python:3.10-slim

WORKDIR /app

# Install dependencies first (optimizes Docker layer caching)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application and serialized model
COPY app/ ./app/
COPY models/ ./models/

EXPOSE 8000

# Run Uvicorn production server
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]