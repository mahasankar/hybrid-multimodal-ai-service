from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from app.schemas import ExpenseClaimRequest, ScoreResponse
from app.model import FraudModelService

ml_service = None

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Load model into memory once when FastAPI boots up (< 10ms)
    global ml_service
    try:
        ml_service = FraudModelService()
    except Exception as e:
        print(f"[CRITICAL] Failed to initialize ML model service: {e}")
        raise e
    yield
    # Cleanup on shutdown
    ml_service = None

app = FastAPI(
    title="Expense Fraud Scoring Service",
    description="Production microservice for real-time XGBoost risk inference",
    version="1.0.0",
    lifespan=lifespan
)

@app.get("/health", tags=["System"])
def health_check():
    """Kubernetes/Docker health check probe endpoint."""
    return {"status": "healthy", "model_loaded": ml_service is not None and ml_service.model is not None}

@app.post("/score", response_model=ScoreResponse, tags=["Inference"])
def score_expense_claim(payload: ExpenseClaimRequest):
    """Takes expense feature attributes and returns deterministic fraud risk probability."""
    if not ml_service or not ml_service.model:
        raise HTTPException(status_code=503, detail="Model service not initialized")
    
    try:
        data_dict = payload.model_dump()
        raw_probability = ml_service.predict(data_dict)
        
        # Business risk categorization thresholding
        risk_level = "LOW"
        if raw_probability >= 0.50:
            risk_level = "HIGH"
        elif raw_probability >= 0.20:
            risk_level = "MEDIUM"

        return ScoreResponse(
            fraud_probability=round(raw_probability, 4),
            risk_level=risk_level,
            model_version="1.0.0"
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Inference error: {str(e)}")