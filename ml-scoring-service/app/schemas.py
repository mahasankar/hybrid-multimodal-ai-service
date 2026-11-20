from pydantic import BaseModel, Field

class ExpenseClaimRequest(BaseModel):
    claim_amount: float = Field(..., gt=0, description="Total claim amount in USD")
    amount_to_role_avg_ratio: float = Field(..., gt=0, description="Ratio of claim to role baseline average")
    employee_tenure_months: int = Field(..., ge=0, description="Employee tenure in months")
    claims_last_30_days: int = Field(..., ge=0, description="Number of claims submitted in last 30 days")
    is_weekend_submission: int = Field(..., ge=0, le=1, description="1 if weekend, 0 otherwise")
    is_high_risk_category: int = Field(..., ge=0, le=1, description="1 if high-risk category, 0 otherwise")
    receipt_text_confidence: float = Field(..., ge=0.0, le=1.0, description="OCR text confidence score")

class ScoreResponse(BaseModel):
    fraud_probability: float
    risk_level: str
    model_version: str